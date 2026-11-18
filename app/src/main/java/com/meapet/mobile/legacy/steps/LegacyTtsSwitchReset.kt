package com.meapet.mobile.legacy.steps

import android.util.Log
import com.meapet.mobile.legacy.LegacyMigrationContext
import com.meapet.mobile.legacy.LegacyMigrationStep

/**
 * 旧版 TTS 残留命中后关闭两个语音开关（1.7.x → 1.8.0 升级路径）。
 *
 * **预计移除版本：1.11.0**
 * **被引用点：`LegacyMigration.STEPS`**（必须排在模型 / native 库清理之后，依赖前两步置位
 * [LegacyMigrationContext.ttsLegacyDetected]）
 *
 * 旧模型已被删除、需要重新下载，若沿用旧开关值会出现「开关开着但模型未就绪」的误导状态，
 * 因此主界面与悬浮窗两个开关一起置 false，由用户重新准备模型后手动开启。
 */
internal class LegacyTtsSwitchReset : LegacyMigrationStep {

    override val id: String = "tts-switch-reset"

    private companion object {
        private const val TAG = "LegacyTtsSwitchReset"
    }

    override suspend fun migrate(context: LegacyMigrationContext) {
        if (!context.ttsLegacyDetected) return

        context.settingsManager.setTtsMainEnabled(false)
        context.settingsManager.setTtsOverlayEnabled(false)
        Log.i(TAG, "旧版 TTS 残留已清理，主界面与悬浮窗语音开关已关闭")
    }
}
