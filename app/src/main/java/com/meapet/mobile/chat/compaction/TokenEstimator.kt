package com.meapet.mobile.chat.compaction

import com.meapet.mobile.chat.ChatMessage

/**
 * 本地 token 估算器。
 *
 * ## 为什么需要
 * 压缩触发、窗口预算都需要知道「这轮要发多少 token」，但请求发出前拿不到服务端的权威值。
 * 这里用字符类别加权做保守估算，再用每次响应 `usage.prompt_tokens` 校准。
 *
 * ## 估算系数（偏保守，宁可高估）
 * - CJK 字符：1.0 token/字（中文通常 1 字 ≈ 1~2 token，取低值由安全余量兜底）
 * - 拉丁字母/数字：0.25 token/字符
 * - 其他（标点、空白、emoji 等）：0.5 token/字符
 * - 每条消息另有 4 token 结构开销
 *
 * ## 校准
 * 每轮成功响应后传入 (本地估算, 真实 prompt_tokens)，用 EMA 维护 `真实/估算` 比值并夹在
 * 0.5~2.0 之间。之后估算统一乘该比值。响应缺 usage 时退化为纯本地估算。
 */
class TokenEstimator(
    /** EMA 平滑系数：0.3 表示新观测占三成，避免单次异常把比值带偏。 */
    private val smoothing: Double = 0.3
) {
    /** 真实 / 本地估算 的当前校准比值（1.0 = 未校准）。 */
    @Volatile
    var ratio: Double = 1.0
        private set

    /** 估算单条消息的原始 token（未乘校准系数）。 */
    fun rawTokens(message: ChatMessage): Int = 4 + rawTokens(message.content)

    /** 估算一段文本的原始 token（未乘校准系数）。 */
    fun rawTokens(text: String): Int {
        var cjk = 0
        var latin = 0
        var other = 0
        for (ch in text) {
            when {
                isCjk(ch) -> cjk++
                ch.isLetterOrDigit() || ch == '_' -> latin++
                else -> other++
            }
        }
        return (cjk * 1.0 + latin * TOKEN_PER_LATIN_CHAR + other * 0.5).toInt()
    }

    /** 估算一批消息的 token（已乘校准系数）。 */
    fun estimate(messages: List<ChatMessage>): Int {
        val raw = messages.sumOf { rawTokens(it) }
        return (raw * ratio).toInt()
    }

    /** 估算单段文本的 token（已乘校准系数）。 */
    fun estimate(text: String): Int = (rawTokens(text) * ratio).toInt()

    /**
     * 用服务端真实值校准。
     *
     * @param localEstimate 本次请求前算出的本地估算（未乘校准系数的原始值）
     * @param promptTokens 服务端返回的 `usage.prompt_tokens`
     */
    fun calibrate(localEstimate: Int, promptTokens: Int) {
        if (localEstimate <= 0 || promptTokens <= 0) return
        val observed = (promptTokens.toDouble() / localEstimate).coerceIn(MIN_RATIO, MAX_RATIO)
        ratio = (ratio * (1 - smoothing) + observed * smoothing).coerceIn(MIN_RATIO, MAX_RATIO)
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||   // CJK 统一表意文字
            (code in 0x3400..0x4DBF) ||      // 扩展 A
            (code in 0x3040..0x30FF) ||      // 日文假名
            (code in 0xAC00..0xD7AF) ||      // 韩文
            (code in 0xF900..0xFAFF)         // 兼容表意文字
    }

    private companion object {
        const val TOKEN_PER_LATIN_CHAR = 0.25
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 2.0
    }
}
