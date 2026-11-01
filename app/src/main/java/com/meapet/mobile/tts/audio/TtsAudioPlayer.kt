package com.meapet.mobile.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * TTS 音频播放器：AudioTrack 播放单声道 PCM16，采样率由合成结果给出（当前模型 22050Hz）。
 *
 * 整段一次播放：[play] 提交到**专用守护线程**写完即播；[stop] 打断。线程安全。
 *
 * ## 线程模型（修复并发写同一 AudioTrack 的竞态）
 * - **每段新建一个 AudioTrack**（[obtainTrack] 不复用）：被 stop 打断过的 track 状态不可信，
 *   复用是"两线程同写一个 track"的根源；每段新建彻底消除该竞态。
 * - **play/stop 串行化（playLock）**："停旧段 → 取新号 → 启新线程"整体在 [playLock] 内
 *   原子完成，杜绝两个 play 并发时互相 pause/flush、或"上一段残留兜底"release 掉
 *   他段新 track 的竞态（偶发无声的两个来源）。stop 在锁上排队，最长等一次 join 超时。
 * - 每段音频带一个单调递增的**代际令牌（generation）**：[stop] 会使旧令牌失效，
 *   专用线程在 write 前后比对令牌，过期段直接丢弃，不会写出。
 * - [stop]/[release] 先 **pause/flush** 解除写线程在 native `write()` 上的阻塞（Java interrupt
 *   对 native 阻塞无效），再 `interrupt()` + `join()` 等待其退出；join 超时则释放 track 强制重建。
 *
 * ## 播放完成判断（真实进度，非 playState 估算）
 * MODE_STREAM 下 `playState==PLAYING` 只表示"处于播放态"，缓冲写完后仍一直 PLAYING，
 * 不能用来判断播完。这里用 [AudioTrack.setPlaybackPositionUpdateListener] 注册
 * **末尾帧位置标记**，硬件播放头推进到该帧才回调 [onPlaybackComplete]——这是真实进度。
 * 播放/创建失败路径同样会回调 [onPlaybackComplete]，保证上层 isPlaying 状态能复位。
 *
 * ## 诊断日志约定
 * `write()` 的返回值**必须记录**：阻塞写被并发 pause/flush 提前解除时会**短写返回**
 * （剩余样本被丢弃、末尾 marker 永不到达），忽略返回值会让"无声"与正常播放
 * 在日志上不可区分。全写/短写/错误码三条路径各有独立日志。
 */
class TtsAudioPlayer {

    companion object {
        private const val TAG = "TtsAudioPlayer"

        /** stop/release 后等待写线程退出的最长毫秒数。 */
        private const val JOIN_TIMEOUT_MS = 500L
    }

    /** 播放完成回调（硬件播放头到达末尾帧、或播放失败时触发，主线程）。 */
    @Volatile
    var onPlaybackComplete: (() -> Unit)? = null

    /** 是否正在播放（有未完成片段）。供互斥判断。 */
    @Volatile
    var isPlaying: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 段调度锁：play/stop/release 的"停旧 + 启新"序列在此锁内串行，杜绝跨段冲写/误释放。 */
    private val playLock = Any()

    /** 当前 AudioTrack，仅在专用写线程上读写；stop/release 在主调线程经锁同步访问。 */
    private val trackLock = Any()
    private var track: AudioTrack? = null

    /** 专用写线程（每段一个，写完即退）。 */
    @Volatile
    private var playThread: Thread? = null

    /** 代际令牌：每次 play 递增，stop 后置失效，写线程据此丢弃过期段。 */
    private val generationLock = Any()
    private var generation = 0

    /**
     * fp32 波形 → int16 并播放。采样率由合成结果给出（当前模型 22050），不再写死。
     * 新段到达会先停止旧段。
     */
    fun play(audio: FloatArray, sampleRate: Int) {
        Log.d(
            TAG,
            "play: audio=${audio.size} 样本 sr=$sampleRate (~${audio.size / (sampleRate / 1000f) / 1000f}s)，" +
                "thread=${Thread.currentThread().name}",
        )
        // PCM 转换放锁外：与段调度无关，长段转换不应阻塞 stop（触摸语音互斥路径）
        val pcm = floatToPcm16(audio)
        synchronized(playLock) {
            // 先停旧段（含 join 等待其写线程退出），再发新段
            stopInternal()
            val gen = synchronized(generationLock) { ++generation }
            isPlaying = true
            playThread = Thread({ writePcm(pcm, gen, sampleRate) }, "TtsAudioPlayer").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "play: 已提交写线程 gen=$gen sr=$sampleRate")
        }
    }

    /** 停止当前播放。 */
    fun stop() {
        Log.d(TAG, "stop() 调用（thread=${Thread.currentThread().name}）")
        synchronized(playLock) { stopInternal() }
    }

    /** 释放资源：先停并等写线程退出，再释放 track。 */
    fun release() {
        Log.d(TAG, "release() 调用")
        synchronized(playLock) {
            stopInternal()
            synchronized(trackLock) {
                track?.let {
                    try {
                        it.stop(); it.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "release AudioTrack 异常", e)
                    }
                }
                track = null
            }
        }
    }

    // ── 内部 ──────────────────────────────────────────

    /**
     * 使当前段失效并停止播放：递增代际、清状态、先 pause/flush 解除 write 阻塞、
     * 再 interrupt + join 等待写线程退出。
     *
     * **顺序关键**：`AudioTrack.write()` 是 native 阻塞调用，Java 的 `interrupt()` 无法解除它。
     * 唯一的解除手段是从另一线程对同一 track 调 `pause()` / `flush()`（write 随即返回）。
     * 必须先停 track 再 join，否则 join 会超时、留下"仍卡在 write() 里的旧写线程"，
     * 与下一段的写线程并发操作同一 track（AudioTrack 非线程安全）→ 状态损坏 → 随机无声。
     *
     * **调用约束**：仅在持有 [playLock] 时调用（play/stop/release 内部）。
     */
    private fun stopInternal() {
        synchronized(generationLock) { generation++ }   // 旧段令牌失效
        isPlaying = false

        // 先 pause/flush：解除旧写线程在 native write() 上的阻塞，使其能快速返回并退出
        synchronized(trackLock) {
            track?.let {
                try {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                    it.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "stop 清理 AudioTrack 异常", e)
                }
            }
        }

        val t = playThread
        if (t != null && t.isAlive) {
            t.interrupt()
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // join 超时仍存活：写线程异常卡死（不应发生，pause/flush 后 write 应返回）。
            // 释放该 track 并置空，强制下一段新建，杜绝并发写同一 track。
            if (t.isAlive) {
                Log.w(TAG, "stopInternal: 写线程未在 ${JOIN_TIMEOUT_MS}ms 内退出，释放 track 供下次重建")
                synchronized(trackLock) {
                    track?.let {
                        try { it.stop(); it.release() } catch (_: Exception) {}
                    }
                    track = null
                }
            } else {
                Log.d(TAG, "stopInternal: 写线程已退出")
            }
        }
        playThread = null
    }

    /** 段是否仍有效（未被 stop / 被新段取代）。 */
    private fun isCurrent(gen: Int): Boolean =
        synchronized(generationLock) { gen == generation }

    private fun writePcm(pcm: ShortArray, gen: Int, sampleRate: Int) {
        try {
            if (!isCurrent(gen)) {
                Log.d(TAG, "writePcm gen=$gen: 已过期（被 stop/新段取代），丢弃")
                return
            }
            val t = obtainTrack(pcm.size, sampleRate)
            Log.d(TAG, "writePcm gen=$gen: track 已创建 sr=$sampleRate session=${t.audioSessionId} buffer=${t.bufferSizeInFrames} 帧")
            // 注册末尾帧标记：硬件播到最后一帧时回调（真实播放完成）
            t.setPositionNotificationPeriod(pcm.size)   // 周期回调（兜底）
            t.notificationMarkerPosition = pcm.size     // 末尾帧标记
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    Log.d(TAG, "writePcm gen=$gen: marker 到达（真实播完）")
                    if (isCurrent(gen)) notifyComplete()
                }
                override fun onPeriodicNotification(track: AudioTrack?) {
                    // 周期回调：核对播放头是否已到末尾（兜底）。
                    // totalFrames 用本段闭包捕获的 pcm.size（旧实现读共享字段，段间会串）
                    val head = track?.playbackHeadPosition ?: 0
                    if (pcm.size > 0 && head >= pcm.size && isCurrent(gen)) {
                        Log.d(TAG, "writePcm gen=$gen: 周期回调 head=$head>=total=${pcm.size}，判完成")
                        notifyComplete()
                    }
                }
            }, mainHandler)
            // write 全程可被 pause/flush（从 stop 线程）解除阻塞；写完逐段比对令牌
            Log.d(TAG, "writePcm gen=$gen: 开始 write ${pcm.size} 样本")
            val written = t.write(pcm, 0, pcm.size)
            when {
                written == pcm.size ->
                    Log.d(TAG, "writePcm gen=$gen: write 完成（全写 $written 样本），thread=${Thread.currentThread().name}")
                written >= 0 -> {
                    // 短写：被并发 pause/flush 提前解除（stop/新段）或底层异常，剩余样本
                    // 不会播放、末尾 marker（pcm.size）永不到达 → 按失败处理复位状态。
                    // 记录实际写入数，避免"无声"与正常播放日志不可区分。
                    Log.w(TAG, "writePcm gen=$gen: 短写！written=$written / size=${pcm.size}（剩余被丢弃，按失败完成）")
                    if (isCurrent(gen)) notifyComplete()
                }
                else -> {
                    Log.e(TAG, "writePcm gen=$gen: write 返回错误码 $written")
                    if (isCurrent(gen)) notifyComplete()
                }
            }
        } catch (e: InterruptedException) {
            // 被 stop/release 打断：正常路径，不回调完成
            Log.d(TAG, "writePcm gen=$gen: 被 InterruptedException 打断（正常 stop）")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "播放失败 gen=$gen", e)
            // 失败也回调完成，保证上层 isPlaying 状态复位（修复卡死）
            if (isCurrent(gen)) notifyComplete()
        }
    }

    /** 标记播放结束并回调（主线程）。仅当段仍有效时调用。 */
    private fun notifyComplete() {
        isPlaying = false
        mainHandler.post { onPlaybackComplete?.invoke() }
    }

    /**
     * 获取一个处于 PLAYING 状态的 AudioTrack。
     *
     * **不再复用旧 track**：被 stop 打断过的 track 可能已被旧写线程并发操作过、
     * 状态不可信；每段新建一个干净 track 能彻底杜绝"两线程同写一个 track"的竞态。
     * 建 track 成本（约几百 μs~ms）相比整段合成耗时可忽略。
     *
     * 调用前提：playLock 已串行化段调度，正常路径下旧写线程已退出；
     * 兜底 release 仅兜底 join 超时等异常残留。
     */
    private fun obtainTrack(minSamples: Int, sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, minSamples * 2)
        synchronized(trackLock) {
            // 上一段残留（正常路径 stopInternal 已清空；这里兜底再放一次）
            track?.let {
                try { it.stop(); it.release() } catch (_: Exception) {}
            }
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            newTrack.play()
            track = newTrack
            return newTrack
        }
    }
}

/**
 * fp32 波形（[-1,1]）→ PCM16。越界值裁剪到 [-1,1]，与参考实现 `audio * 32767` 一致；
 * 抽成顶层函数便于 JVM 单测（不触发 AudioTrack）。
 */
internal fun floatToPcm16(audio: FloatArray): ShortArray =
    ShortArray(audio.size) { i -> (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
