package com.meapet.mobile.legacy.steps

import android.util.Log
import com.meapet.mobile.legacy.LegacyMigrationContext
import com.meapet.mobile.legacy.LegacyMigrationStep
import java.io.File

/**
 * 旧版下载的 native 库残留清理（1.7.x → 1.8.0 升级路径）。
 *
 * **预计移除版本：1.11.0**
 * **被引用点：`LegacyMigration.STEPS`**
 *
 * 旧版曾把 `libonnxruntime.so` 下载到 `tts_model/lib/<abi>/`（约 20MB）；现 native 库随 sherpa AAR
 * 打包进 APK，不再需要下载，检测到 `lib/` 目录即整目录删除。当前已在 IO 线程，无需再包 CoroutineScope。
 */
internal class LegacyTtsNativeLibCleanup : LegacyMigrationStep {

    override val id: String = "tts-native-lib-cleanup"

    private companion object {
        private const val TAG = "LegacyTtsNativeLibCleanup"
    }

    override suspend fun migrate(context: LegacyMigrationContext) {
        val libDir = File(context.ttsModelDir, "lib")
        if (!libDir.exists()) return

        context.ttsLegacyDetected = true
        Log.i(TAG, "检测到旧版 native 库目录，清理残留")
        runCatching { libDir.deleteRecursively() }
            .onFailure { Log.w(TAG, "清理旧版 native 库目录失败", it) }
    }
}
