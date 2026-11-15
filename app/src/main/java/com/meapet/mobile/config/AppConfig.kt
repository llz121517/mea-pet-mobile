package com.meapet.mobile.config

/**
 * 全局应用配置。
 *
 * 所有子系统依赖此配置决定行为，不各自定义硬编码默认值。
 * 修改此文件即可变更应用全局行为，无需逐个翻查各模块。
 *
 * @property defaultApiUrl 默认 API 基础 URL
 * @property defaultModel 默认模型名
 * @property maxHistoryMessages 单次请求送的最大历史消息数（滑动窗口）
 * @property historyTrimBatch 历史超限时一次性裁掉的条数。逐条裁剪会让每轮请求的
 *   消息前缀都不一样，服务端的自动 prefix cache 全程无法命中；批量裁一次，
 *   中间这些轮的前缀完全相同
 * @property memorySummaryModel 用于记忆总结的模型名（null = 使用 defaultModel）
 * @property maxMemoryItems 记忆库最多保留条目数
 * @property maxContextMemories 注入上下文时最多取几条记忆
 * @property maxPersonaFacts 每轮注入 system prompt 的事实/特质条数上限。这两类永不自动
 *   淘汰又全量注入，不封顶的话用久了会无限撑大每次请求（只影响注入，不影响存储与展示）
 * @property minSummaryItems 触发摘要所需的最少短期记忆条数（不足则跳过本次，攒着下次再合）
 * @property memoryOpsEchoTurns 组装请求时，最近多少条助手消息要把记忆协议块贴回正文当格式范例
 * @property enableMemory 是否启用记忆系统
 * @property enableAutoSummary 是否自动摘要对话为长期记忆
 * @property appVersion 应用版本名
 * @property ttsModelBaseUrl TTS 模型下载基础地址（BuildConfig 注入）
 */
data class AppConfig(
    val defaultApiUrl: String = "https://api.deepseek.com",
    val defaultModel: String = "deepseek-v4-flash",
    val maxHistoryMessages: Int = 35,
    val historyTrimBatch: Int = 8,
    val memorySummaryModel: String? = null,
    val maxMemoryItems: Int = 500,
    val maxContextMemories: Int = 5,
    val maxPersonaFacts: Int = 30,
    val minSummaryItems: Int = 3,
    val memoryOpsEchoTurns: Int = 3,
    val enableMemory: Boolean = true,
    val enableAutoSummary: Boolean = true,
    val appVersion: String = "1.0.0",
    /**
     * TTS 模型包（6 个文件）的下载基础地址，尾部不带文件名；远端以平铺文件名上传
     * （`model.onnx` / `tokens.txt` / `lexicon.txt` / `phone.fst` / `date.fst` / `number.fst`）。
     * 经 BuildConfig 从 local.properties 注入；空 = 未配置，下载入口提示。
     */
    val ttsModelBaseUrl: String = ""
) {
    companion object {
        /** 合理的生产默认值。各模块可通过 AppContainer 的 config 属性访问。 */
        val DEFAULT = AppConfig()

        /**
         * 从 BuildConfig 注入的地址构建配置（在 AppContainer 初始化时调用）。
         * local.properties 缺失时对应字段为空字符串，下载入口据此提示未配置。
         */
        fun fromBuildConfig(
            ttsModelBaseUrl: String,
            appVersion: String
        ): AppConfig = DEFAULT.copy(
            ttsModelBaseUrl = ttsModelBaseUrl,
            appVersion = appVersion
        )
    }
}
