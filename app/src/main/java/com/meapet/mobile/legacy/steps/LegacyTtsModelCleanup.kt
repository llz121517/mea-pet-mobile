package com.meapet.mobile.legacy.steps

import android.util.Log
import com.meapet.mobile.legacy.LegacyMigrationContext
import com.meapet.mobile.legacy.LegacyMigrationStep
import java.io.File

/**
 * 旧版 4 模块 ONNX 模型清理（1.7.x → 1.8.0 升级路径）。
 *
 * **预计移除版本：1.11.0**
 * **被引用点：`LegacyMigration.STEPS`**
 *
 * 旧版模型 `enc_p/dp/flow/dec.onnx`（约 73MB）与新 sherpa 6 文件包不兼容，检测到任一即**整目录删除**
 * `tts_model/`（破坏性设计：混合目录一并删除，由用户重新下载或本地 zip 导入）。
 */
internal class LegacyTtsModelCleanup : LegacyMigrationStep {

    override val id: String = "tts-model-cleanup"

    private companion object {
        private const val TAG = "LegacyTtsModelCleanup"

        /** 旧版 4 模块模型文件名。 */
        private val LEGACY_MODEL_FILES = listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx")
    }

    override suspend fun migrate(context: LegacyMigrationContext) {
        val legacyFound = LEGACY_MODEL_FILES.any { File(context.ttsModelDir, it).exists() }
        if (!legacyFound) return

        context.ttsLegacyDetected = true
        Log.i(TAG, "检测到旧版 4 模块模型，清理后需重新下载 sherpa 模型包")
        runCatching { context.ttsModelDir.deleteRecursively() }
            .onFailure { Log.w(TAG, "清理旧版模型失败", it) }
    }
}
