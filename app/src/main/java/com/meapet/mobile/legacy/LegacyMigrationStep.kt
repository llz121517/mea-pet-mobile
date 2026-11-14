package com.meapet.mobile.legacy

/** 单个旧版迁移步骤（一次性兼容代码，见 [LegacyMigration]）。 */
internal interface LegacyMigrationStep {
    /** 步骤标识，仅用于日志排查。 */
    val id: String

    /** 执行迁移；实现必须幂等、可重复调用。 */
    suspend fun migrate(context: LegacyMigrationContext)
}
