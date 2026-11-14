package com.meapet.mobile.legacy

/**
 * 单个迁移步骤接口（长期迁移框架，见 [LegacyMigration]）。
 *
 * 具体实现是一次性代码：见 `steps/` 下各文件头部的「预计移除版本 + 被引用点」。
 */
internal interface LegacyMigrationStep {
    /** 步骤标识，仅用于日志排查。 */
    val id: String

    /** 执行迁移；实现必须幂等、可重复调用。 */
    suspend fun migrate(context: LegacyMigrationContext)
}
