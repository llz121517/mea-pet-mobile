package com.meapet.mobile.tts.engine

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.meapet.mobile.tts.model.TtsModelManager
import java.io.File

/** 音高随机性 / 时长随机性：最终方案定稿值（桌面 spike 试听确认）。 */
internal const val TTS_NOISE_SCALE = 0.55f
internal const val TTS_NOISE_SCALE_W = 0.7f
internal const val TTS_NUM_THREADS = 2

/** 数字归一化 FST 顺序固定：phone 必须最先（否则电话号码会被读成中文数词）。 */
internal val RULE_FST_FILES = listOf("fsts/phone.fst", "fsts/date.fst", "fsts/number.fst")

/**
 * 构造 sherpa 配置。关键点：
 * - 不传 AssetManager（file 模式），路径必须是设备上的绝对路径；
 * - 走文本前端（不预分词），metadata 里 jieba=1 会让 CharacterLexicon 做最长匹配；
 * - ruleFsts 用逗号分隔的绝对路径。
 */
internal fun buildSherpaTtsConfig(modelDir: File): OfflineTtsConfig {
    val vits = OfflineTtsVitsModelConfig(
        model = File(modelDir, "model.onnx").absolutePath,
        lexicon = File(modelDir, "lexicon.txt").absolutePath,
        tokens = File(modelDir, "tokens.txt").absolutePath,
        noiseScale = TTS_NOISE_SCALE,
        noiseScaleW = TTS_NOISE_SCALE_W,
        lengthScale = 1.0f,
    )
    return OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = vits,
            numThreads = TTS_NUM_THREADS,
            debug = false,
            provider = "cpu",
        ),
        ruleFsts = RULE_FST_FILES.joinToString(",") { File(modelDir, it).absolutePath },
        maxNumSentences = 1,
    )
}

/**
 * sherpa-onnx 离线 TTS 引擎（逐句流式）。
 *
 * - 首次合成才创建 [OfflineTts]（加载 75MB 模型 + 8.9MB lexicon，耗时数百 ms~数秒，务必在 IO 线程）；
 * - [OfflineTts.generateWithConfigAndCallback] 是阻塞原生调用，**非线程安全**：并发合成由上层
 *   [com.meapet.mobile.tts.TtsManager] 的 Mutex 串行化；回调对每句完成即触发（`maxNumSentences=1`
 *   时逐句串行），回调返回 0 会让 sherpa 中止后续句子——打断时能省掉剩余推理；
 *   回调对象**必须用 Java 适配器 [TtsStreamCallback]**（JNI 的 methodID 签名 Kotlin lambda 生成不出来）；
 * - [release] 幂等，删除模型前由设置页调用。
 */
class SherpaTtsEngine(private val modelManager: TtsModelManager) : TtsEngine {

    companion object {
        private const val TAG = "SherpaTtsEngine"
    }

    @Volatile
    private var engine: OfflineTts? = null

    @Synchronized
    private fun requireEngine(): OfflineTts {
        engine?.let { return it }
        val startedAt = SystemClock.elapsedRealtime()
        val config = buildSherpaTtsConfig(modelManager.modelDir())
        Log.i(TAG, "创建 OfflineTts：model=${config.model.vits.model}")
        return OfflineTts(config = config).also {
            engine = it
            Log.i(
                TAG,
                "OfflineTts 就绪：sampleRate=${it.sampleRate()} speakers=${it.numSpeakers()} " +
                    "load=${SystemClock.elapsedRealtime() - startedAt}ms",
            )
        }
    }

    override fun synthesizeStreaming(
        text: String,
        speed: Float,
        onChunk: (GeneratedAudio) -> Boolean,
    ): Boolean {
        val tts = requireEngine()
        val startedAt = SystemClock.elapsedRealtime()
        var chunkCount = 0
        var aborted = false
        // sherpa 回调约定：返回 1 继续、返回 0 中止后续合成（打断/抢占时用）。
        // **必须传 Java 适配器**：JNI 按 `invoke([F)Ljava/lang/Integer;` 取回调方法，
        // Kotlin 2.x indy lambda 脱糖后只有擦除版 invoke(Object)，直接传 lambda 会 CheckJNI SIGABRT
        // （真机 2026-11-15 崩溃，详见 TtsStreamCallback 注释）。
        val callback = TtsStreamCallback { samples ->
            chunkCount++
            onChunk(GeneratedAudio(samples, tts.sampleRate())).also { keepGoing ->
                if (!keepGoing) aborted = true
            }
        }
        val whole = tts.generateWithConfigAndCallback(
            text,
            GenerationConfig(speed = speed, sid = 0),
            callback,
        )
        Log.d(
            TAG,
            "流式合成${if (aborted) "中止" else "完成"}：文字=${text.length} speed=$speed " +
                "分块=$chunkCount 样本=${whole.samples.size} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        return !aborted
    }

    @Synchronized
    override fun release() {
        val current = engine ?: return
        engine = null
        runCatching { current.release() }.onFailure { Log.w(TAG, "释放 OfflineTts 异常", it) }
        Log.i(TAG, "OfflineTts 已释放")
    }
}
