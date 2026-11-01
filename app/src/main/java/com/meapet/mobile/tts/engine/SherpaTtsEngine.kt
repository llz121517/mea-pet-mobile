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
 * sherpa-onnx 离线 TTS 引擎。
 *
 * - 首次 [synthesize] 才创建 [OfflineTts]（加载 75MB 模型 + 8.9MB lexicon，耗时数百 ms~数秒，务必在 IO 线程）；
 * - [OfflineTts.generateWithConfig] 是阻塞原生调用，**非线程安全**：并发合成由上层
 *   [com.meapet.mobile.tts.TtsManager] 的 Mutex 串行化；
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

    override fun synthesize(text: String, speed: Float): GeneratedAudio {
        val tts = requireEngine()
        val audio = tts.generateWithConfig(text, GenerationConfig(speed = speed, sid = 0))
        Log.d(TAG, "合成完成：文字=${text.length} speed=$speed 样本=${audio.samples.size} sr=${audio.sampleRate}")
        return audio
    }

    @Synchronized
    override fun release() {
        val current = engine ?: return
        engine = null
        runCatching { current.release() }.onFailure { Log.w(TAG, "释放 OfflineTts 异常", it) }
        Log.i(TAG, "OfflineTts 已释放")
    }
}
