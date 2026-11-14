package com.meapet.mobile.legacy

import android.content.Context
import android.util.Log
import com.meapet.mobile.legacy.steps.LegacyTtsModelCleanup
import com.meapet.mobile.legacy.steps.LegacyTtsNativeLibCleanup
import com.meapet.mobile.legacy.steps.LegacyTtsSwitchReset
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.model.TtsModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 旧版兼容迁移总入口（一次性代码）。
 *
 * ## 生命周期
 * - **预计移除版本：1.9.0**（确认老用户升级覆盖率后，整包 `com.meapet.mobile.legacy` 可直接删除）
 * - **被引用点：`AppContainer.warmUp()`**（App 启动时异步执行；除此之外不应有任何调用方）
 * - 每个迁移操作独立文件，见 [steps] 包；新增迁移只需实现 [LegacyMigrationStep] 并加入 [STEPS]
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
    )

    suspend fun run(context: Context, settingsManager: SettingsManager, ttsModelManager: TtsModelManager) {
        withContext(Dispatchers.IO) {
            val ctx = LegacyMigrationContext(ttsModelManager.modelDir(), settingsManager)
            for (step in STEPS) {
                runCatching { step.migrate(ctx) }
                    .onFailure { Log.w(TAG, "旧版迁移步骤失败：${step.id}", it) }
            }
            ttsModelManager.refreshState()
            if (ctx.ttsLegacyDetected) Log.i(TAG, "旧版 TTS 残留已清理，两个语音开关已关闭，需重新下载模型")
        }
    }
}
