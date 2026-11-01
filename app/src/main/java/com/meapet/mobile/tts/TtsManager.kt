package com.meapet.mobile.tts

import android.util.Log
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.engine.TtsEngine
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import com.meapet.mobile.tts.text.TtsTextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * TTS 门面：开关判断 + 归一化 + 整段合成播放。
 *
 * 主界面与悬浮窗两路 LLM 回复都汇聚到这里：[speak] 先查对应开关与模型就绪，
 * 满足则「归一化 → sherpa 合成 → 播放」。新回复到达时取消上一段（旧话不念完）。
 *
 * ## 并发
 * - [speakSeq]：native 合成无法被 `Job.cancel()` 打断，合成完成后要校验自己仍是最新，
 *   否则旧消息的协程会在合成完成后盖掉新消息的播放（表现为"最新回复无声"）。
 * - [synthMutex]：sherpa 的 `generateWithConfig` 非线程安全，且取消挡不住在途 native 调用，
 *   用互斥锁把在途合成串行化；拿到锁后先复查序号与模型状态（非 Ready 视为模型目录正被替换/删除），
 *   过期或模型未就绪直接放弃，不浪费一次推理、也不会在删除/替换期间重建 OfflineTts。
 *   [releaseEngine] 用**同一把锁**，保证释放不会与在途合成并发（否则是 native use-after-free）。
 */
class TtsManager(
    private val settingsManager: SettingsManager,
    private val modelManager: TtsModelManager,
    private val engine: TtsEngine,
    private val normalizer: TtsTextNormalizer,
    private val player: TtsAudioPlayer,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "TtsManager"

        /** 单次合成的最大字符数：超长回复合成占用内存/时间线性增长，超出后截断朗读（不影响文字显示）。 */
        private const val MAX_SYNTH_CHARS = 200

        /** 设置里的 lengthScale 合法范围（与设置页滑杆一致）。 */
        private const val LENGTH_SCALE_MIN = 0.5f
        private const val LENGTH_SCALE_MAX = 2.0f

        /**
         * TTS 开始播放时的回调（互斥：停掉未完的触摸语音）。
         * 由 app 容器注入，避免 tts 包反向依赖 live2d。
         */
        @Volatile
        var onPlaybackStart: (() -> Unit)? = null
    }

    /** 发声来源。 */
    enum class Source { MAIN, OVERLAY }

    /** 是否正在播放（驱动喇叭图标变色）。合成阶段为 false，真正出声才置 true。 */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 本次会话（内存）语音是否被临时关闭；不持久化，重启 App 即恢复。 */
    private val _sessionMuted = MutableStateFlow(false)
    val sessionMuted: StateFlow<Boolean> = _sessionMuted.asStateFlow()

    init {
        // 真实播放完成（硬件播放头到末尾帧）→ 解除播放中标记
        player.onPlaybackComplete = { _isPlaying.value = false }
    }

    /** 切换本次会话的语音开关（快捷静音按钮用）。关闭时同时停止当前朗读。 */
    fun toggleSessionMute() {
        val muted = !_sessionMuted.value
        _sessionMuted.value = muted
        if (muted) stop()
    }

    @Volatile
    private var currentJob: Job? = null

    /** 在途合成互斥（sherpa generate 非线程安全，且协作式取消挡不住 native 调用）。 */
    private val synthMutex = Mutex()

    /** speak 序号（原子递增）：合成完成后、播放前校验自己仍是最新。 */
    private val speakSeq = AtomicInteger(0)

    /**
     * 朗读一条助手回复。开关关闭 / 会话静音 / 模型未就绪时静默跳过（不影响文字显示）。
     *
     * @param message 助手消息（content 已剥协议块）
     * @param source 触发来源（决定用哪个开关）
     */
    fun speak(message: ChatMessage, source: Source) {
        speakInternal(text = message.content, checkEnabled = { isEnabledFor(source) }, tag = "speak($source)")
    }

    /**
     * 设置页「试听」用：跳过来源开关（该入口本身就是用户主动操作），仍尊重会话静音与模型就绪。
     */
    fun speakPreview(text: String) {
        speakInternal(text = text, checkEnabled = { true }, tag = "speakPreview")
    }

    private fun speakInternal(text: String, checkEnabled: () -> Boolean, tag: String) {
        Log.d(TAG, "$tag 进入，content='${text.trim().take(30)}'")
        if (!checkEnabled()) {
            Log.d(TAG, "$tag: 开关关闭，跳过朗读")
            return
        }
        if (_sessionMuted.value) {
            Log.d(TAG, "本次会话已静音，跳过朗读")
            return
        }
        if (modelManager.state.value != TtsModelState.Ready) {
            Log.d(TAG, "模型未就绪（state=${modelManager.state.value}），跳过朗读")
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            Log.d(TAG, "$tag: content 空白，跳过")
            return
        }
        val clipped = if (trimmed.length > MAX_SYNTH_CHARS) {
            Log.w(TAG, "文本超长（${trimmed.length} 字），截断为前 $MAX_SYNTH_CHARS 字朗读")
            trimmed.substring(0, MAX_SYNTH_CHARS)
        } else {
            trimmed
        }

        // lengthScale 越大读得越慢；sherpa 用 speed = 1 / lengthScale
        val lengthScale = settingsManager.getTtsLengthScale().toFloat()
            .coerceIn(LENGTH_SCALE_MIN, LENGTH_SCALE_MAX)
        val speed = 1.0f / lengthScale

        // 抢占最新发言权（见类注释 speakSeq）
        val mySeq = speakSeq.incrementAndGet()

        // 打断上一段：取消合成 + 停止播放；同时复位播放中标记（新段合成期间不亮）
        currentJob?.cancel()
        player.stop()
        _isPlaying.value = false

        // 合成放 IO 线程池：首次合成要加载 75MB 模型 + 8.9MB lexicon（IO 密集），
        // ONNX 推理走 native 计算，都不应挤占 Dispatchers.Default（它还承载其他协程）。
        currentJob = scope.launch(Dispatchers.IO) {
            try {
                val normalized = normalizer.normalize(clipped)
                if (normalized.isBlank()) {
                    Log.w(TAG, "$tag: 归一化后为空，静默跳过")
                    if (mySeq == speakSeq.get()) _isPlaying.value = false
                    return@launch
                }
                Log.d(TAG, "$tag: 开始合成 '${normalized.take(20)}' (${normalized.length} 字, speed=$speed)")

                // 串行化在途合成；拿到锁后复查序号与模型状态：
                // 序号过期 → 不再浪费一次推理；模型已非 Ready → 目录可能正被替换/删除
                // （SettingsViewModel 会先 markMutating 再 releaseEngine），此时重建 OfflineTts
                // 会加载到半套/已删文件，直接放弃。
                val audio = synthMutex.withLock {
                    if (mySeq != speakSeq.get()) return@withLock null
                    if (modelManager.state.value != TtsModelState.Ready) return@withLock null
                    engine.synthesize(normalized, speed)
                } ?: run {
                    Log.d(TAG, "$tag: 合成前已被更新的 speak/stop 取代或模型已非就绪（seq=$mySeq），放弃")
                    if (mySeq == speakSeq.get()) _isPlaying.value = false
                    return@launch
                }

                // 合成期间可能有更新的 speak/stop（cancel 对 native 推理无效）：过期则丢弃
                if (mySeq != speakSeq.get()) {
                    Log.w(TAG, "$tag: 合成完成但已有更新的 speak/stop（seq=$mySeq → ${speakSeq.get()}），丢弃本段")
                    return@launch
                }
                if (audio.samples.isEmpty()) {
                    Log.w(TAG, "$tag: 合成为空数组，静默跳过（无声！）")
                    _isPlaying.value = false
                    return@launch
                }
                // 真正开始播放前才亮（合成 / 排队阶段喇叭保持熄灭）
                _isPlaying.value = true
                onPlaybackStart?.invoke()   // 互斥：停掉未完的触摸语音
                player.play(audio.samples, audio.sampleRate)   // 播完经 onPlaybackComplete 解除 isPlaying
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "$tag: 合成被取消（上一段被打断）")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "合成失败: $clipped", e)
                if (mySeq == speakSeq.get()) _isPlaying.value = false
            }
        }
    }

    /** 停止当前朗读并取消待合成任务。 */
    fun stop() {
        Log.d(TAG, "stop() 调用（取消合成 + 停播放）")
        speakSeq.incrementAndGet()   // 让在途合成完成后自行丢弃（cancel 挡不住 native 推理）
        currentJob?.cancel()
        currentJob = null
        player.stop()
        _isPlaying.value = false
    }

    /** 释放播放器（整个 App 退出时调用）；推理引擎由 [releaseEngine] 单独释放。 */
    fun release() {
        stop()
        player.release()
    }

    /**
     * 删除/导入模型前调用：停止播放/合成并释放底层 OfflineTts（75MB 模型 + 8.9MB lexicon 的 native 内存）。
     * 之后若重新下载模型，引擎会在下次合成时按新模型懒加载重建。
     *
     * **必须是 suspend**：sherpa 的 generateWithConfig 是阻塞 native 调用，stop() 取消不掉；
     * 这里用同一把 [synthMutex] 等完在途合成再释放，避免 release 与 synthesize 并发访问已释放的 native 对象。
     * 调用点（SettingsViewModel 的 importTtsModelZip / deleteTtsModel）已在 viewModelScope.launch 内，无需改。
     */
    suspend fun releaseEngine() {
        stop()
        synthMutex.withLock { engine.release() }
    }

    /** 对应来源的语音开关是否开启。 */
    private fun isEnabledFor(source: Source): Boolean = when (source) {
        Source.MAIN -> settingsManager.isTtsMainEnabled()
        Source.OVERLAY -> settingsManager.isTtsOverlayEnabled()
    }
}
