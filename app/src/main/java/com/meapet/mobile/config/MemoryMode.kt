package com.meapet.mobile.config

/**
 * 记忆检索模式（用户设置项，见 `SettingsManager.getMemoryMode`）。
 *
 * 记忆的**存储与摘要**由「启用记忆」开关控制；本枚举只决定模型如何**检索/写入**记忆：
 * - [AUTO]：优先工具调用（function calling）。端点明确不支持 tools 时，本会话自动降级为
 *   [TEXT]（不打断聊天，也不反复用必然失败的请求去试）；
 * - [TOOLS]：只用工具调用。端点不支持时同样会降级（否则完全无法聊天），但会在日志中明确记录；
 * - [TEXT]：把检索结果按关键词注入上下文，写操作走文本围栏协议（1.8 及更早的行为）；
 * - [OFF]：不向模型注入任何记忆上下文，也不提供工具（记忆仍然存储，仅本轮不用）。
 *
 * 默认 [AUTO]：能力探测与降级自动化，用户通常无需关心。
 */
enum class MemoryMode {
    AUTO,
    TOOLS,
    TEXT,
    OFF;

    /** 该模式是否允许发起工具调用请求。 */
    val usesTools: Boolean get() = this == AUTO || this == TOOLS

    /** 该模式下端点不支持 tools 时是否允许自动降级为文本协议（[TOOLS] 也降级，只是日志更明确）。 */
    val allowsTextFallback: Boolean get() = this != OFF

    companion object {
        val DEFAULT = AUTO

        /** 宽容解析：大小写不敏感，未知值退化为 [DEFAULT]（不因脏数据让聊天不可用）。 */
        fun parse(raw: String?): MemoryMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}
