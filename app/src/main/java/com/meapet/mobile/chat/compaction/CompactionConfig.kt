package com.meapet.mobile.chat.compaction

/**
 * 上下文压缩相关的固定阈值。
 *
 * 全部为**文件级常量**（不放进设置页）：这些是产品决策而非用户可调项，
 * 改动任一数值都会影响已发布版本的上下文行为，应视为版本事件。
 */
object CompactionConfig {
    /**
     * 手动压缩提醒阈值（token）。
     *
     * 上下文过长有边际效应：稀释注意力、增加延迟与费用，缓存也更容易被压缩/裁剪打断。
     * 6.5 万 token 是一个很自然的人工介入点——到这个量级时通常已经聊了很久，
     * 用户自己也能感知到「该整理一下了」。
     *
     * 仅作**提醒**：不自动压缩，是否整理由用户决定。
     */
    const val COMPACT_HINT_TOKENS: Int = 65536

    /**
     * 自动压缩的硬触发阈值（占有效预算的比例）。
     *
     * **自动压缩只做兜底**：仅在上下文逼近窗口上限、再不处理请求会被服务端拒绝时才触发，
     * 且不弹任何提示。日常整理走用户手动触发。
     */
    const val HARD_TRIGGER_RATIO: Double = 0.90

    /**
     * 压缩后希望回落到有效预算的比例（目标值，非硬性保证）。
     */
    const val TARGET_RATIO: Double = 0.35

    /**
     * 小窗口保护：有效预算低于该值时不做 LLM 摘要，只做按轮对齐的硬截断。
     *
     * 固定开销（人设 prompt、工具说明）已占大头，摘要自身也要花 token，得不偿失。
     */
    const val SMALL_WINDOW_TOKENS: Int = 6000

    /** 压缩后至少保留的最近轮数（原文逐字发送，保证近期对话细节不丢）。 */
    const val MIN_KEEP_TURNS: Int = 4

    /** 摘要请求的 `max_tokens` 上限。 */
    const val SUMMARY_MAX_TOKENS: Int = 1200

    /** 摘要请求温度：偏低，保证摘要稳定、少发挥。 */
    const val SUMMARY_TEMPERATURE: Double = 0.3
}
