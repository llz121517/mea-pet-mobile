package com.meapet.mobile.tts.model

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 旧版本 TTS 兼容清理（1.7.x → 1.8.0 升级路径）。
 *
 * 独立成文件的目的：这是**一次性兼容代码**，等老版本用户基本升级完之后，
 * 直接删掉本文件 + [TtsModelManager.init] 里对它的两行调用即可，不需要在主逻辑里翻找。
 *
 * 负责两件事：
 * 1. 旧版 4 模块 ONNX（enc_p/dp/flow/dec，~73MB）→ 整目录删除，按「未下载」引导重新下载 sherpa 模型包；
 * 2. 旧版曾把 libonnxruntime.so 下载到 `tts_model/lib/<abi>/`（~20MB）→ 异步清理残留
 *    （现 native 库随 sherpa AAR 打包进 APK，不再需要下载）。
 */
internal object TtsLegacyMigration {
    private const val TAG = "TtsLegacyMigration"

    /** 旧版 4 模块模型文件名。 */
    private val LEGACY_MODEL_FILES = listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx")

    /** 是否存在旧版模型文件。 */
    fun hasLegacyModel(modelDir: File): Boolean =
        LEGACY_MODEL_FILES.any { File(modelDir, it).exists() }

    /** 升级迁移：检测到旧版模型即整目录清空（同步、幂等）。 */
    fun migrate(modelDir: File) {
        if (!hasLegacyModel(modelDir)) return
        Log.i(TAG, "检测到旧版 4 模块模型，清理后需重新下载 sherpa 模型包")
        runCatching { modelDir.deleteRecursively() }
            .onFailure { Log.w(TAG, "清理旧版模型失败", it) }
    }

    /** 异步清掉旧版下载的 native 库目录（不阻塞启动）。 */
    fun cleanLegacyNativeLibDir(modelDir: File) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { File(modelDir, "lib").deleteRecursively() }
        }
    }
}
