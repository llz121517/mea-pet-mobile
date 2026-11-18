package com.meapet.mobile.chat.tools

import android.util.Log
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.model.ApiRequest
import com.meapet.mobile.client.model.ApiResponse
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import com.meapet.mobile.memory.tools.MemoryToolSchemas
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一次工具循环的结果。
 *
 * @property finalContent 最终助手回复正文
 * @property rounds 实际请求轮数（含最后一次出文本的请求）
 * @property toolCalls 实际执行的工具调用总数
 * @property firstPromptTokens 第一轮响应的 `usage.prompt_tokens`（用于校准本地估算，可能为 null）
 */
data class ToolLoopResult(
    val finalContent: String,
    val rounds: Int,
    val toolCalls: Int,
    val firstPromptTokens: Int? = null
)

/**
 * 工具调用循环（OpenAI 兼容 `tools` / `tool_calls`）。
 *
 * ## 流程
 * ```
 * 请求(带 tools)  →  有 tool_calls ?
 *   ├─ 否 → 返回 content（最终回复）
 *   └─ 是 → 原样回传 assistant 消息 → 逐个执行并回填 role=tool → 再请求
 * 直到拿到最终文本 / 达到 [maxRounds]
 * ```
 *
 * ## 关键约定
 * - **assistant 消息原样透传**（含 `tool_calls`、`reasoning_content` 等）：重新拼装会丢字段，
 *   思考模式端点会因此报错；
 * - **最后一轮 `tool_choice="none"`**，但 **`tools` 字段不变**：保证请求前缀与前面几轮一致，
 *   不至于破坏服务端 prefix cache；
 * - 单轮工具调用数超过 [maxCallsPerRound] 时，多余调用回填一条错误 tool 消息
 *   （而不是静默丢弃），保证 `tool_call_id` 一一对应，避免服务端 400；
 * - 工具执行错误一律作为 tool 消息返回给模型，不使整个 turn 失败。
 *
 * ## 本阶段范围
 * 工具执行结果（`role=tool`）与中间 assistant 消息**只在本次 turn 内存在**，
 * 不入历史（入历史与 `turn_id` 整轮回滚留到下一阶段）。
 */
class ChatToolLoop(
    private val clientProvider: () -> OpenAiCompatibleClient,
    private val executor: MemoryToolExecutor,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val maxCallsPerRound: Int = DEFAULT_MAX_CALLS_PER_ROUND
) {
    companion object {
        private const val TAG = "ChatToolLoop"

        /**
         * 最大请求轮数。
         *
         * 取 12 轮（较宽松）：
         * - 多步记忆操作（检索 → 删两条 → 再检索 → 回复）通常 5~6 轮；
         * - 后续还会加入 **web 搜索等工具**，一次回答可能需要「检索记忆 → 搜索 → 再搜索 → 汇总」，
         *   轮数上限太紧会把模型逼到最后一轮（`tool_choice=none`），反而让它把工具调用当文本吐出。
         *
         * 代价是异常情况下延迟会叠加（每轮一次请求），但正常对话极少逼近上限；
         * 每轮内部的调用数另有 [DEFAULT_MAX_CALLS_PER_ROUND] 限制。
         */
        const val DEFAULT_MAX_ROUNDS = 12

        /** 单轮最多执行多少个工具调用（模型偶尔会一口气返回一串）。 */
        const val DEFAULT_MAX_CALLS_PER_ROUND = 8
    }

    /**
     * 执行一次完整的工具循环。
     *
     * @param messages 初始消息（首条 system + 历史 + 并入 tail 的最后一条 user）
     * @throws IllegalStateException 响应无法解析 / 达到最大轮数仍只给工具调用 / 最终无文本
     */
    suspend fun run(
        model: String,
        messages: List<JsonObject>,
        temperature: Double,
        maxTokens: Int
    ): ToolLoopResult {
        val conversation = messages.toMutableList()
        var totalCalls = 0
        var firstPromptTokens: Int? = null

        for (round in 1..maxRounds) {
            val isLastRound = round == maxRounds
            val requestBody = ApiRequest.chatCompletion(
                model = model,
                messages = conversation,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false,
                tools = MemoryToolSchemas.ALL,
                toolChoice = if (isLastRound) "none" else "auto"
            )
            val rawResponse = clientProvider().chatCompletion(requestBody)
            if (firstPromptTokens == null) {
                firstPromptTokens = ApiResponse.usagePromptTokens(rawResponse)
            }
            val parsed = ApiResponse.chatCompletionMessage(rawResponse)
                ?: throw IllegalStateException("API 响应中没有有效的消息")

            if (parsed.toolCalls.isEmpty()) {
                val rawContent = parsed.content?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("API 响应中没有有效的回复内容")
                // 少数模型会在禁止调用工具的回合把内部标记当文本吐出来：这里清掉，交给调用方判断是否为空
                val content = ToolCallMarkup.strip(rawContent)
                if (content.isBlank()) {
                    Log.w(TAG, "Round $round: content was only tool-call markup, treated as empty")
                    return ToolLoopResult("", round, totalCalls, firstPromptTokens)
                }
                Log.d(TAG, "Tool loop finished in $round round(s), $totalCalls tool call(s)")
                return ToolLoopResult(content, round, totalCalls, firstPromptTokens)
            }

            if (isLastRound) {
                // 已经 tool_choice=none 还给工具调用：无法继续，如实失败（避免死循环）
                throw IllegalStateException("工具调用轮数达到上限，仍未给出最终回复")
            }

            // 原样回传 assistant 消息（含 tool_calls / reasoning_content 等全部字段）
            conversation += ApiRequest.assistantRawMessage(parsed.raw)

            var executed = 0
            for (call in parsed.toolCalls) {
                val content = if (executed >= maxCallsPerRound) {
                    tooManyCallsEnvelope()
                } else {
                    executed++
                    totalCalls++
                    executor.execute(call.name, call.arguments)
                }
                conversation += ApiRequest.toolMessage(call.id, content)
            }
            Log.d(TAG, "Round $round: executed $executed/${parsed.toolCalls.size} tool call(s)")
        }

        throw IllegalStateException("工具调用轮数达到上限，仍未给出最终回复")
    }

    private fun tooManyCallsEnvelope(): String = buildJsonObject {
        put("ok", false)
        put("error", "too_many_calls")
        put("message", "单轮工具调用超过上限，未执行；请减少调用数量后重试")
    }.toString()
}
