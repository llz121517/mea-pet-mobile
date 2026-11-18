package com.meapet.mobile.legacy.steps

import android.util.Log
import com.meapet.mobile.legacy.LegacyMigrationContext
import com.meapet.mobile.legacy.LegacyMigrationStep

/**
 * 把 1.8 及更早存在 DataStore 的对话轮数计数迁入 SQLite，并删除旧键（1.8.x → 1.9.0 升级路径）。
 *
 * **预计移除版本：1.10.0**
 * **被引用点：`LegacyMigration.STEPS`**
 *
 * 计数是记忆系统的运行状态而非用户设置，1.9.0 起改存记忆存储的 `meta` 表
 * （见 `MemoryStore.getExchangeCount`）。留一份在 DataStore 会混进「设置部分导出」，
 * 所以迁完后立即删除旧键，保证设置里不留运行状态。
 *
 * 幂等：迁完即删旧键，重复执行时读不到旧值直接返回。库中已有计数时不覆盖
 * （正常只会是新装/未用过记忆的情况才为 0）。
 */
internal class LegacyExchangeCountMigration : LegacyMigrationStep {

    override val id: String = "exchange-count-to-db"

    private companion object {
        private const val TAG = "LegacyExchangeCount"
    }

    override suspend fun migrate(context: LegacyMigrationContext) {
        val legacy = context.settingsManager.getLegacyExchangeCount() ?: return

        if (legacy > 0 && context.memoryStore.getExchangeCount() == 0) {
            context.memoryStore.setExchangeCount(legacy)
            Log.i(TAG, "旧版对话轮数计数已迁入数据库（$legacy）")
        }

        // 无论是否采用旧值都删除旧键：它已不再是设置项
        context.settingsManager.clearLegacyExchangeCount()
    }
}
