package com.meapet.mobile.chat.tools

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
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
 * @property firstPromptCacheHit 第一轮响应的 `usage.prompt_cache_hit_tokens`（观察期指标，可能为 null）
 * @property finalRaw 最终回合 assistant 的**原始 message JSON**（可能含 `reasoning_content` 等）。
 *   由 ChatService 存进 [ChatMessage.rawAssistantJson] 并原样透传给下一次请求。
 */
data class ToolLoopResult(
    val finalContent: String,
    val rounds: Int,
    val toolCalls: Int,
    val firstPromptTokens: Int? = null,
    val firstPromptCacheHit: Int? = null,
    val finalRaw: String? = null
)

/**
 * 工具调用循环（OpenAI 兼容 `tools` / `tool_calls`）。
 *
 * ## 流程
 * ```
 * 请求(带 tools, tool_choice=auto) → 有 tool_calls ?
 *   ├─ 否 → 返回 content（最终回复）
 *   └─ 是 → 工具轮次未用尽 ? 执行并回填 role=tool : 拦截并回填「已达上限」提示
 *          → 再请求，直到模型给出正文 / 超出总请求数上限
 * ```
 *
 * ## 关键约定
 * - **assistant 消息原样透传**（含 `tool_calls`、`reasoning_content` 等）：重新拼装会丢字段，
 *   思考模式端点会因此报错；
 * - **全程 `tool_choice="auto"`，绝不发 `"none"`**：把某一轮改成禁止调用工具，会逼模型
 *   **把工具调用标记当正文吐出来**（用户看到 `<｜｜DSML｜｜…>` 之类乱码）。所以轮次用尽时
 *   不改请求协议，而是在**工具执行端拦截**：调用不执行，只把一条「轮次已达上限，请直接用
 *   文字回答」的 tool 结果回填。模型看到所有工具都被拒绝，通常自己就会收尾输出正文；
 * - 拦截后仍可能再请求几轮（见 [MAX_TOOL_GRACE_ROUNDS]），只防「超级死犟」的模型无限要工具；
 *   超过总请求数上限才如实失败，由调用方回滚并提示重试；
 * - 单轮工具调用数超过 [maxCallsPerRound] 时，多余调用回填一条错误 tool 消息
 *   （而不是静默丢弃），保证 `tool_call_id` 一一对应，避免服务端 400；
 * - 工具执行错误一律作为 tool 消息返回给模型，不使整个 turn 失败。
 *
 * ## 入历史（由调用方决定）
 * 本类只通过回调把「需要进历史的记录」交出去，不直接改历史：
 * - 每轮带 tool_calls 的 assistant（含纯 tool_calls、正文为空）走 [onTranscript]；
 *   其中正文非空的还会走 [onVisibleFragment]，让 UI 在工具执行前就把这句话显示出来；
 * - 每个 tool 结果走 [onTranscript]；
 * - 最终正文通过 [ToolLoopResult.finalContent] 返回，由 ChatService 建最终消息。
 *
 * 同一 turn 的所有记录由 ChatService 收集后**一个事务**落库；失败/取消时整轮丢弃，
 * 因此不会留下「有 tool_calls 没结果」的半轮（服务端会 400）。所有记录共用 [run] 的
 * `turnId`，供整轮回滚/重试。
 */
/**
 * 工具调用轮次上限（**文件内常量**）。
 *
 * 抽成文件级常量而不放类里：工具返回消息里的轮次计数（本类拼接）与执行端的拦截
 * 必须用同一个数，两处各写一个字面量迟早漂移。
 *
 * 取 12 轮（较宽松）：
 * - 多步记忆操作（检索 → 删两条 → 再检索 → 回复）通常 5~6 轮；
 * - 后续还会加入 web 搜索等工具，一次回答可能需要「检索记忆 → 搜索 → 再搜索 → 汇总」，
 *   轮数上限太紧会让正常的多步任务提前失败。
 *
 * 超过本数后不再执行工具，只回填「已达上限」提示让模型收尾（见类注释），
 * 因此正常对话极少触达真正的失败路径。
 */
internal const val MAX_TOOL_ROUNDS: Int = 12

/** 单轮最多执行多少个工具调用（模型偶尔会一口气返回一串）。 */
internal const val MAX_TOOL_CALLS_PER_ROUND: Int = 8

/**
 * 工具轮次用尽后，还允许请求几轮（期间所有工具调用都被拦截）。
 *
 * 模型收到「轮次已达上限」的 tool 结果后通常下一轮就给正文，1 轮足够；
 * 留 2 轮余量只防个别模型在校正语气前又调一次工具。再多就说明模型真的死犟，
 * 继续请求只是烧时间和 token，不如如实失败让用户重试。
 */
internal const val MAX_TOOL_GRACE_ROUNDS: Int = 2

private const val TAG = "ChatToolLoop"

class ChatToolLoop(
    private val clientProvider: () -> OpenAiCompatibleClient,
    private val executor: MemoryToolExecutor,
    private val maxRounds: Int = MAX_TOOL_ROUNDS,
    private val maxCallsPerRound: Int = MAX_TOOL_CALLS_PER_ROUND
) {

    /**
     * 执行一次完整的工具循环。
     *
     * @param messages 初始消息（首条 system + 历史 + 并入 tail 的最后一条 user）
     * @param turnId 本轮 turn 的 id，写进所有交给 [onTranscript] 的消息
     * @param onVisibleFragment 某一轮**同时给了文本片段和工具调用**时，把该 assistant 记录回调出去。
     *   调用方据此立即显示一条助手消息（用户看到「连发多条」），而不是等整个循环结束。
     * @param onTranscript 需要进历史的每条记录（assistant 片段 + tool 结果），按发生顺序回调。
     * @param onTranscript 需要进历史的每条记录（assistant 片段 + tool 结果），按发生顺序回调。
     * @throws IllegalStateException 响应无法解析 / 达到最大轮数仍只给工具调用 / 最终无文本
     */
    suspend fun run(
        model: String,
        messages: List<JsonObject>,
        temperature: Double,
        maxTokens: Int,
        turnId: String,
        onVisibleFragment: (ChatMessage) -> Unit = {},
        onTranscript: (ChatMessage) -> Unit = {}
    ): ToolLoopResult {
        val conversation = messages.toMutableList()
        var totalCalls = 0
        var toolRoundsUsed = 0
        var round = 0
        var firstPromptTokens: Int? = null
        var firstPromptCacheHit: Int? = null

        // 总请求数 = 工具轮次 + 少量「拦截后收尾」轮。拦截轮只请求、不执行，
        // 正常情况下模型收到「已达上限」的 tool 结果就会给正文，所以只需 1~2 轮余量；
        // 上限只用于兜住真正死犟、无限要工具的模型（再多请求只是白烧时间和 token）。
        while (true) {
            if (round >= maxRounds + MAX_TOOL_GRACE_ROUNDS) {
                throw IllegalStateException("工具调用轮次已达上限，模型仍未给出最终回复")
            }
            round++
            // 每一轮都声明 tools + auto：绝不发 tool_choice="none"（见类注释：
            // 强制禁止调用会逼模型把工具调用当文本吐给用户）
            val requestBody = ApiRequest.chatCompletion(
                model = model,
                messages = conversation,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false,
                tools = MemoryToolSchemas.ALL,
                toolChoice = "auto"
            )
            val rawResponse = clientProvider().chatCompletion(requestBody)
            if (firstPromptTokens == null) {
                firstPromptTokens = ApiResponse.usagePromptTokens(rawResponse)
                firstPromptCacheHit = ApiResponse.usagePromptCacheHitTokens(rawResponse)
            }
            val parsed = ApiResponse.chatCompletionMessage(rawResponse)
                ?: throw IllegalStateException("API 响应中没有有效的消息")

            if (parsed.toolCalls.isEmpty()) {
                val content = parsed.content?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("API 响应中没有有效的回复内容")
                Log.d(TAG, "Tool loop finished in $round round(s), $totalCalls tool call(s)")
                return ToolLoopResult(
                    finalContent = content,
                    rounds = round,
                    toolCalls = totalCalls,
                    firstPromptTokens = firstPromptTokens,
                    firstPromptCacheHit = firstPromptCacheHit,
                    finalRaw = parsed.raw.toString()
                )
            }

            // 工具轮次用尽：在「工具执行端」拦截——调用不执行，只回填一条上限提示。
            // 请求协议保持不变（tools/auto），模型拿到的仍是合法 tool 结果，会自己收尾。
            val intercept = toolRoundsUsed >= maxRounds

            // 原样回传 assistant 消息（含 tool_calls / reasoning_content 等全部字段）
            conversation += ApiRequest.assistantRawMessage(parsed.raw)

            // 该轮同时给了文本片段：先交给历史（正文为空也要进历史），再立即上抛给 UI
            // 让用户看到「连发多条」，同时它也已随 assistant 原始消息进入本轮上下文
            val fragment = parsed.content.orEmpty()
            val assistantRecord = ChatMessage(
                role = ChatRole.assistant,
                content = fragment,
                turnId = turnId,
                rawAssistantJson = parsed.raw.toString()
            )
            onTranscript(assistantRecord)
            if (fragment.isNotBlank()) onVisibleFragment(assistantRecord)

            var executed = 0
            // 本轮的轮次号：拦截轮固定显示上限值（“已达上限”）
            val roundNo = if (intercept) maxRounds else toolRoundsUsed + 1
            for (call in parsed.toolCalls) {
                val content = when {
                    intercept -> toolRoundLimitEnvelope()
                    executed >= maxCallsPerRound -> tooManyCallsEnvelope()
                    else -> {
                        executed++
                        totalCalls++
                        executor.execute(call.name, call.arguments)
                    }
                }
                // 轮次计数拼进**发给模型的 tool 返回内容**：工具循环里最后一条消息是 tool，
                // 没有 user 可以挂 tail，模型只有从这里才能看到「已用 x/上限」。
                // 历史（transcript）保留原始结果，计数只属于本次请求，和 tail 一样不入历史。
                conversation += ApiRequest.toolMessage(call.id, withRoundCounter(content, roundNo))
                onTranscript(
                    ChatMessage(
                        role = ChatRole.tool,
                        content = content,
                        toolCallId = call.id,
                        toolName = call.name,
                        turnId = turnId
                    )
                )
            }
            if (intercept) {
                Log.w(
                    TAG,
                    "Round $round: tool rounds exhausted ($maxRounds), " +
                        "intercepted ${parsed.toolCalls.size} call(s)"
                )
            } else {
                toolRoundsUsed++
                Log.d(TAG, "Round $round: executed $executed/${parsed.toolCalls.size} tool call(s)")
            }
        }
    }

    private fun tooManyCallsEnvelope(): String = buildJsonObject {
        put("ok", false)
        put("error", "too_many_calls")
        put("message", "单轮工具调用超过上限，未执行；请减少调用数量后重试")
    }.toString()

    /**
     * 把工具轮次计数拼到 tool 返回内容末尾（格式 `x/上限`，如 `3/12`）。
     *
     * 只用于**发给模型的请求副本**：工具循环里最后一条消息是 tool，没有 user 可以挂 tail，
     * 模型只有从工具结果里才能看到「已用几轮 / 还有几轮」。历史（transcript）保存原始结果，
     * 计数和 tail 一样属于每轮动态内容、不写入历史。
     */
    private fun withRoundCounter(content: String, roundNo: Int): String {
        val remaining = (maxRounds - roundNo).coerceAtLeast(0)
        val hint = when {
            remaining == 0 -> "（已达上限，请直接回答）"
            remaining <= 2 -> "（接近上限，请尽快用文字回答）"
            else -> ""
        }
        return "$content\n\n【工具调用轮次】$roundNo/$maxRounds$hint"
    }

    /**
     * 工具轮次用尽后的拦截结果。
     *
     * 不能只是「静默不执行」（模型会以为工具没反应而重试），必须明确告诉它上限已到、
     * 让它别再调用，直接基于已有信息回答——这是模型自行收尾的关键信号。
     * 信封格式与执行器其它结果一致（ok/error/message），模型无需理解新协议。
     */
    private fun toolRoundLimitEnvelope(): String = buildJsonObject {
        put("ok", false)
        put("error", "tool_round_limit")
        put(
            "message",
            "工具调用轮次已达上限，本次调用未执行；请不要再调用任何工具，" +
                "直接基于上文已有信息用文字回答用户"
        )
    }.toString()
}
