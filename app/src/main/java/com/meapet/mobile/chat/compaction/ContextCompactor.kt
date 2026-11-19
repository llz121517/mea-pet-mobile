package com.meapet.mobile.chat.compaction

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.model.ApiRequest
import com.meapet.mobile.client.model.ApiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 上下文压缩器：把较早的对话轮次折叠成一段滚动摘要。
 *
 * ## 设计要点
 * - **切点按轮对齐**：只折叠完整的轮次（user → assistant），最近
 *   [CompactionConfig.MIN_KEEP_TURNS] 轮原文保留，避免把一轮拆开；
 * - **滚动摘要**：新摘要 = LLM(旧摘要 + 本次要折叠的消息)，只处理增量，
 *   成本与折叠段长度成正比；
 * - **纯文本输出**：摘要直接用模型回复正文，不要求 JSON（容错更好，也不需要 schema）；
 * - 摘要**并入首条 system 末尾**，不做独立 system/user 消息（兼容性最好，见 TailMode 调研）。
 *
 * ## 失败处理
 * 任何异常返回 null，由调用方退化为「只在发给模型的窗口内按轮丢弃」，不影响聊天主流程。
 */
class ContextCompactor(
    private val clientProvider: () -> OpenAiCompatibleClient,
    private val modelProvider: () -> String
) {
    companion object {
        private const val TAG = "ContextCompactor"

        /** 摘要请求的 system 提示：只要摘要正文，不要任何包装。 */
        private const val SYSTEM_PROMPT: String =
            "你是一个对话历史摘要助手。你只输出整理后的摘要正文，不加任何解释、前缀或标记。"

        /** 折进摘要时单条工具结果的截断长度（工具结果是机器 JSON，不需要全文）。 */
        private const val SUMMARY_TOOL_CONTENT_CHARS = 200
    }

    /**
     * 折叠 [messages] 中较早的部分，返回新摘要。
     *
     * @param messages 当前窗口内的非 system 消息（按时间正序）
     * @param previousSummary 已有摘要（空串 = 首次压缩）
     * @param foldCount 要折叠的消息条数（由 [foldCountFor] 计算，保证按轮对齐）
     * @return 新摘要正文；[foldCount] 为 0 或调用失败时返回 null
     */
    suspend fun compact(
        messages: List<ChatMessage>,
        previousSummary: String,
        foldCount: Int
    ): String? {
        if (foldCount <= 0 || foldCount > messages.size) return null
        val toFold = messages.take(foldCount)
        // 只记条数，不记内容（记忆/对话内容属隐私，禁止进日志）
        Log.i(TAG, "Compacting $foldCount message(s); previous summary ${previousSummary.length} chars")

        val body = buildSummaryPrompt(toFold, previousSummary)
        return withContext(Dispatchers.IO) {
            try {
                val request = ApiRequest.chatCompletion(
                    model = modelProvider(),
                    messages = listOf(
                        ApiRequest.textMessage("system", SYSTEM_PROMPT),
                        ApiRequest.textMessage("user", body)
                    ),
                    temperature = CompactionConfig.SUMMARY_TEMPERATURE,
                    maxTokens = CompactionConfig.SUMMARY_MAX_TOKENS
                )
                val raw = ApiResponse.chatCompletionContent(clientProvider().chatCompletion(request))
                raw?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Compaction request failed, caller should fall back to window trimming", e)
                null
            }
        }
    }

    private fun buildSummaryPrompt(toFold: List<ChatMessage>, previousSummary: String): String = buildString {
        appendLine("请把下面的对话整理成一段持续更新的「对话摘要」，供后续对话继续使用。")
        appendLine("要求：")
        appendLine("1. 第三人称、中文、中性语气，不要用角色口癖，不要加任何说明或标题；")
        appendLine("2. 保留用户信息与约定（尤其是日期、承诺、称呼）、进行中的话题、已得出的结论、双方关系与情绪状态；")
        appendLine("3. 合并旧摘要中仍然有效的信息，去掉已过期或已被纠正的内容；")
        appendLine("4. 控制在 400 字以内，只输出摘要正文。")
        appendLine()
        if (previousSummary.isNotBlank()) {
            appendLine("【已有摘要】")
            appendLine(previousSummary)
            appendLine()
        }
        appendLine("【需要并入摘要的对话】")
        toFold.forEach { msg ->
            val role = when (msg.role) {
                ChatRole.user -> "用户"
                ChatRole.assistant -> "助手"
                ChatRole.system -> "系统"
                ChatRole.tool -> "工具结果"
            }
            // 工具结果是机器生成的大段 JSON（单条可达上千字符），原样塞进摘要请求既贵又
            // 淹没人话：按概览所需截断，摘要只需要「查了什么、大致得到什么」
            val text = if (msg.role == ChatRole.tool && msg.content.length > SUMMARY_TOOL_CONTENT_CHARS) {
                msg.content.take(SUMMARY_TOOL_CONTENT_CHARS) + "…"
            } else {
                msg.content
            }
            appendLine("$role：$text")
        }
    }
}

/**
 * 计算本次可以折叠多少条消息：按轮对齐，并保证至少保留 [CompactionConfig.MIN_KEEP_TURNS] 轮原文。
 *
 * 规则：
 * - 每轮从一条 user 消息开始，到下一条 user 之前结束；
 * - 折叠条数必须落在某一轮结束处（不会把一轮拆成两半）；
 * - 剩余至少 [minKeepTurns] 轮；消息不足以折叠时返回 0。
 * - 给出 [targetTokens] 与 [estimator] 时，在保留轮数下限的范围内**尽量少折**：
 *   从最旧的轮开始逐轮增加折叠量，折叠到「剩余估算 ≤ 目标」为止（[CompactionConfig.TARGET_RATIO]
 *   的目标值，非硬性保证；折到上限仍不达标就按上限折）。少折可以保留更多原文细节，
 *   摘要也不会被一次性灌入过多内容。
 *
 * @return 可折叠的消息条数（0 表示无需/无法折叠）
 */
fun foldCountFor(
    messages: List<ChatMessage>,
    minKeepTurns: Int = CompactionConfig.MIN_KEEP_TURNS,
    targetTokens: Int? = null,
    estimator: TokenEstimator? = null
): Int {
    val turns = turnBoundaries(messages)
    if (turns.size <= minKeepTurns) return 0
    // 折叠到倒数第 minKeepTurns 轮之前
    val maxFoldTurnIndex = turns.size - minKeepTurns
    if (targetTokens != null && estimator != null) {
        for (index in 1..maxFoldTurnIndex) {
            val remaining = messages.subList(turns[index], messages.size)
            if (estimator.estimate(remaining) <= targetTokens) return turns[index]
        }
    }
    return turns[maxFoldTurnIndex]
}

/**
 * 返回每轮起始消息下标（每条 user 消息开启新一轮）。
 *
 * 例：`[user, assistant, user, assistant]` → `[0, 2]`。
 */
internal fun turnBoundaries(messages: List<ChatMessage>): List<Int> {
    val result = ArrayList<Int>()
    messages.forEachIndexed { index, msg ->
        if (msg.role == ChatRole.user) result.add(index)
    }
    return result
}

/**
 * 仅在发给模型的窗口内按轮丢弃最旧的轮次，直到估算 token 降到预算内。
 *
 * 这是压缩失败时的兜底（库里一条不删，UI 不受影响）。
 *
 * @return 需要跳过的消息条数（0 = 无需丢弃）
 */
fun dropCountToFit(
    messages: List<ChatMessage>,
    estimator: TokenEstimator,
    budget: Int
): Int {
    val turns = turnBoundaries(messages)
    if (turns.isEmpty()) return 0
    // 从最旧的轮开始逐轮丢弃，直到剩余部分（含保留的最近轮）不超预算
    for (i in turns.indices) {
        val remaining = messages.subList(turns[i], messages.size)
        if (estimator.estimate(remaining) <= budget) return turns[i]
    }
    // 连最后一轮都超预算：全部丢弃（极小窗口兜底）
    return messages.size
}
