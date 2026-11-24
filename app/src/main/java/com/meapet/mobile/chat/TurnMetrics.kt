package com.meapet.mobile.chat

import android.util.Log
import com.meapet.mobile.config.MemoryMode
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次 turn 的结局（观察指标用）。
 */
enum class TurnOutcome { SUCCESS, FAILURE, CANCELLED, TIMEOUT }

/**
 * 工具模式观察期指标（M4）：进程内计数，不落盘、不新增设置项。
 *
 * ## 为什么只放内存
 * 目标是回答「工具模式是否稳定、失败/取消/超时各自占比多少」这类**观察期**问题，
 * 不是长期统计。进程重启即归零足够，也避免把运行状态混进用户设置导出。
 *
 * ## 隐私
 * 只累加**计数与耗时**，日志字段仅模式/结果/轮数/调用数/耗时。
 * 绝不记录消息内容、工具参数、记忆内容或 API Key（见项目隐私铁律）。
 */
object TurnMetrics {

    private const val TAG = "TurnMetrics"

    private val turns = AtomicLong()
    private val toolTurns = AtomicLong()
    private val failedTurns = AtomicLong()
    private val cancelledTurns = AtomicLong()
    private val timedOutTurns = AtomicLong()
    private val totalToolCalls = AtomicLong()
    private val totalRounds = AtomicLong()
    private val totalDurationMs = AtomicLong()
    private val totalPromptTokens = AtomicLong()
    private val totalCacheHitTokens = AtomicLong()

    /**
     * 记录一次已结束的 turn（成功/失败/取消/超时都算一次）。
     *
     * @param mode 本轮解析出的记忆模式
     * @param toolMode 本轮是否实际走了工具循环
     * @param rounds 实际请求轮数（失败/取消时是按已产出片段估算的下限）
     * @param toolCalls 实际工具调用数（同上）
     * @param durationMs 本轮耗时（毫秒）
     * @param outcome 结局
     * @param promptTokens 服务端报告的首轮输入 token（缺 usage 时为 null）
     * @param cacheHitTokens 服务端报告的前缀缓存命中 token（缺字段时为 null）；
     *   与 promptTokens 一起用于观察工具模式对 prefix cache 的影响
     */
    fun record(
        mode: MemoryMode,
        toolMode: Boolean,
        rounds: Int,
        toolCalls: Int,
        durationMs: Long,
        outcome: TurnOutcome,
        promptTokens: Int? = null,
        cacheHitTokens: Int? = null
    ) {
        turns.incrementAndGet()
        if (toolMode) toolTurns.incrementAndGet()
        when (outcome) {
            TurnOutcome.SUCCESS -> Unit
            TurnOutcome.FAILURE -> failedTurns.incrementAndGet()
            TurnOutcome.CANCELLED -> cancelledTurns.incrementAndGet()
            TurnOutcome.TIMEOUT -> timedOutTurns.incrementAndGet()
        }
        totalToolCalls.addAndGet(toolCalls.coerceAtLeast(0).toLong())
        totalRounds.addAndGet(rounds.coerceAtLeast(0).toLong())
        totalDurationMs.addAndGet(durationMs.coerceAtLeast(0L))
        promptTokens?.let { totalPromptTokens.addAndGet(it.toLong()) }
        cacheHitTokens?.let { totalCacheHitTokens.addAndGet(it.toLong()) }
        // 只打一行、只含计数：观察期用 grep 统计，内容一律不进日志
        val cacheNote = if (promptTokens != null && cacheHitTokens != null) {
            " cacheHit=$cacheHitTokens/$promptTokens"
        } else {
            ""
        }
        Log.i(
            TAG,
            "turn outcome=${outcome.name} mode=${mode.name} tools=$toolMode " +
                "rounds=$rounds calls=$toolCalls durationMs=$durationMs$cacheNote | ${snapshot()}"
        )
    }

    /** 当前累计快照（单行，供日志/调试展示；不含任何对话内容）。 */
    fun snapshot(): String {
        val n = turns.get()
        val avgMs = if (n > 0) totalDurationMs.get() / n else 0L
        val prompt = totalPromptTokens.get()
        val hit = totalCacheHitTokens.get()
        val hitRatio = if (prompt > 0) "${hit * 100 / prompt}%" else "-"
        return "turns=$n toolTurns=${toolTurns.get()} failed=${failedTurns.get()} " +
            "cancelled=${cancelledTurns.get()} timeout=${timedOutTurns.get()} " +
            "toolCalls=${totalToolCalls.get()} rounds=${totalRounds.get()} avgMs=$avgMs " +
            "cacheHitRatio=$hitRatio"
    }
}
