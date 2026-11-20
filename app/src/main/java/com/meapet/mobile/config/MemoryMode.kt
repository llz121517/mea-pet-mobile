package com.meapet.mobile.config

/**
 * 记忆检索模式（用户设置项，见 `SettingsManager.getMemoryMode`）。
 *
 * 「启用记忆」控制记忆功能；本枚举决定如何读取记忆，以及是否开放工具读写：
 * - [AUTO]：优先工具调用（function calling）。端点明确不支持 tools 时，本会话自动降级为
 *   [TEXT] 的只读注入（不打断聊天，也不反复用必然失败的请求去试）；
 * - [TOOLS]：只用工具调用。端点不支持时同样会降级（否则完全无法聊天），但会在日志中明确记录；
 * - [TEXT]：只读模式，把关键词命中的已有记忆追加到本轮发送消息，不写入或触发记忆摘要；
 * - [OFF]：不向模型注入记忆，也不提供工具；已保存的记忆保留，本轮不写入或触发摘要。
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

    companion object {
        val DEFAULT = AUTO

        /** 宽容解析：大小写不敏感，未知值退化为 [DEFAULT]（不因脏数据让聊天不可用）。 */
        fun parse(raw: String?): MemoryMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}
