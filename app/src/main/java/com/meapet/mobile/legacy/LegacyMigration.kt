package com.meapet.mobile.legacy

import android.content.Context
import android.util.Log
import com.meapet.mobile.legacy.steps.LegacyExchangeCountMigration
import com.meapet.mobile.legacy.steps.LegacyTtsModelCleanup
import com.meapet.mobile.legacy.steps.LegacyTtsNativeLibCleanup
import com.meapet.mobile.legacy.steps.LegacyTtsSwitchReset
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.model.TtsModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 版本迁移总入口（**长期基础设施**，不是一次性代码）。
 *
 * ## 定位
 * - **框架长期保留**：今后每次出现不兼容变更（模型包格式、存储结构、设置项语义等），都在这里新增一个 step
 * - **只有 `steps/` 下的具体迁移步骤是一次性代码**：每个 step 头部注明「预计移除版本 + 被引用点」，到期单独删除
 * - 唯一外部入口：`AppContainer.warmUp()`（App 启动时异步执行；业务代码不得直接引用本包）
 * - 新增迁移：实现 [LegacyMigrationStep] → 加入 [STEPS] → 补单测，不修改业务模块
 * - 模块纪律见同目录 `README.md`；当前 step 的引用点与移除计划见 `docs/legacy-compat.md`
 *
 * ## 行为
 * - 在 IO 线程按 [STEPS] 顺序执行，单步失败只记日志、不阻塞启动、不影响其它步骤
 * - 全部结束后调用 [TtsModelManager.refreshState] 同步 UI 状态
 */
internal object LegacyMigration {
    private const val TAG = "LegacyMigration"

    private val STEPS: List<LegacyMigrationStep> = listOf(
        LegacyTtsModelCleanup(),
        LegacyTtsNativeLibCleanup(),
        LegacyTtsSwitchReset(),
        LegacyExchangeCountMigration(),
    )

    suspend fun run(
        context: Context,
        settingsManager: SettingsManager,
        ttsModelManager: TtsModelManager,
        memoryStore: MemoryStore
    ) {
        withContext(Dispatchers.IO) {
            val ctx = LegacyMigrationContext(ttsModelManager.modelDir(), settingsManager, memoryStore)
            for (step in STEPS) {
                runCatching { step.migrate(ctx) }
                    .onFailure { Log.w(TAG, "旧版迁移步骤失败：${step.id}", it) }
            }
            ttsModelManager.refreshState()
            if (ctx.ttsLegacyDetected) Log.i(TAG, "旧版 TTS 残留已清理，两个语音开关已关闭，需重新下载模型")
        }
    }
}
