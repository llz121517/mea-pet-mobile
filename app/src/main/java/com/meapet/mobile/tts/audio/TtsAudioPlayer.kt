package com.meapet.mobile.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

/**
 * TTS 音频播放器：AudioTrack 播放单声道 PCM16，采样率由合成结果给出（当前模型 22050Hz）。
 *
 * **流式播放**：引擎逐句回调波形，[startStreaming] 建 track 并起专用写线程，[writeStreaming]
 * 把每句 PCM 追加进队列（回调线程只入队、不阻塞合成），[finishStreaming] 入队结束标记。
 *
 * **真机平台的坑（2026-11-15 小米 22041216UC / Android 14 实测，两条都必须踩过）**：
 * 1. **先写后播**：空 buffer 就 `play()` 的 track 会一帧不渲染（无声）；写线程先写入首批数据再 `play()`。
 * 2. **短片段必须补满 buffer**：总帧数 < track buffer 帧数时（如 ≤2s 的短句），即使 playState=PLAYING、
 *    underrun=0，AudioFlinger 也一帧都不消费（播放头恒 0、marker 永不到）。结束标记前用**静音补齐到
 *    buffer 容量**逼它开始渲染；marker 仍打在真实音频末尾，补齐的静音听不见。
 *    修复前所有 ≤2s 的回复/试听都是静音，靠轮询超时兜底收尾。
 *
 * ## 线程模型（修复并发写同一 AudioTrack 的竞态）
 * - **每段新建一个 AudioTrack**（[obtainTrack] 不复用）：被 stop 打断过的 track 状态不可信，
 *   复用是"两线程同写一个 track"的根源；每段新建彻底消除该竞态。
 * - **start/stop 串行化（playLock）**："停旧段 → 取新号 → 启新线程"整体在 [playLock] 内
 *   原子完成，杜绝两个段并发时互相 pause/flush、或"上一段残留兜底"release 掉他段新 track
 *   的竞态（偶发无声的两个来源）。stop 在锁上排队，最长等一次 join 超时。
 * - 每段音频带一个单调递增的**代际令牌（generation）**：[stop] 会使旧令牌失效，
 *   专用线程在 write 前后比对令牌，过期段直接丢弃，不会写出；[writeStreaming] 过期返回 false，
 *   上层据此中止原生合成。
 * - [stop]/[release] 先 **pause/flush** 解除写线程在 native `write()` 上的阻塞（Java interrupt
 *   对 native 阻塞无效），再 `interrupt()` + `join()` 等待其退出；join 超时则释放 track 强制重建。
 *
 * ## 播放完成判断（真实进度，非 playState 估算）
 * MODE_STREAM 下 `playState==PLAYING` 只表示"处于播放态"，缓冲写完后仍一直 PLAYING，
 * 不能用来判断播完。这里在最后一块 PCM 入 track 后用
 * [AudioTrack.setPlaybackPositionUpdateListener] 注册**末尾帧位置标记**，硬件播放头推进到该帧
 * 才回调 [onPlaybackComplete]——这是真实进度。播放/创建失败路径同样会回调，保证上层 isPlaying
 * 状态能复位。
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

        /**
         * 流式播放的 AudioTrack 缓冲目标（秒）：吸收"合成慢于实时"的抖动，
         * 避免句间空洞；越长越抗抖动、占内存也越多（22050Hz 单声道 2 秒 ≈ 88KB）。
         * 注意：短于该时长的片段要补静音到满才会被平台真正播放（见类注释），所以别随意放大。
         */
        private const val STREAM_BUFFER_SECONDS = 2

        /** 队列结束哨兵：空 PCM 数组（真实分块不会为空，入队前已过滤）。 */
        private val END_SENTINEL = ShortArray(0)

        /** 播放完成兜底轮询间隔（毫秒）。 */
        private const val COMPLETION_POLL_MS = 100L
    }

    /** 播放完成回调（硬件播放头到达末尾帧、或播放失败时触发，主线程）。 */
    @Volatile
    var onPlaybackComplete: (() -> Unit)? = null

    /** 是否正在播放（有未完成片段）。供互斥判断。 */
    @Volatile
    var isPlaying: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 段调度锁：start/stop/release 的"停旧 + 启新"序列在此锁内串行，杜绝跨段冲写/误释放。 */
    private val playLock = Any()

    /** 当前 AudioTrack，仅在专用写线程上读写；stop/release 在主调线程经锁同步访问。 */
    private val trackLock = Any()
    private var track: AudioTrack? = null

    /** 专用写线程（每段一个，写完即退）。 */
    @Volatile
    private var playThread: Thread? = null

    /** 代际令牌：每次 start 递增，stop 后置失效，写线程据此丢弃过期段。 */
    private val generationLock = Any()
    private var generation = 0

    /** 当前流式段的 PCM 队列；stop 时置空，避免迟到分块写进旧段。 */
    @Volatile
    private var streamQueue: LinkedBlockingQueue<ShortArray>? = null

    /** 已完成通知的代际，保证 marker 与兜底轮询不会重复回调。 */
    @Volatile
    private var notifiedGen = -1

    /**
     * 开始一段流式播放（首个分块到达时调用）：先停旧段、建新的专用写线程等待 PCM。
     *
     * @return 本段代际令牌，后续 [writeStreaming] / [finishStreaming] 必须带同一令牌
     */
    fun startStreaming(sampleRate: Int): Int {
        val queue = LinkedBlockingQueue<ShortArray>()
        synchronized(playLock) {
            stopInternal()
            val gen = synchronized(generationLock) { ++generation }
            streamQueue = queue
            isPlaying = true
            playThread = Thread({ writeStreamingLoop(queue, gen, sampleRate) }, "TtsAudioPlayer").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "startStreaming: 已提交写线程 gen=$gen sr=$sampleRate")
            return gen
        }
    }

    /**
     * 追加一块 PCM（fp32 已转 PCM16）。回调线程只入队，不阻塞合成。
     *
     * @return false = 该段已失效（被 stop / 新段取代），调用方应中止后续合成
     */
    fun writeStreaming(gen: Int, pcm: ShortArray): Boolean {
        if (!isCurrent(gen)) return false
        if (pcm.isEmpty()) return true
        val queue = streamQueue ?: return false
        queue.offer(pcm)
        return isCurrent(gen)
    }

    /** 通知本段 PCM 已全部入队；播放头走到末尾帧后才回调 [onPlaybackComplete]。 */
    fun finishStreaming(gen: Int) {
        if (!isCurrent(gen)) return
        val queue = streamQueue ?: return
        queue.offer(END_SENTINEL)
        Log.d(TAG, "finishStreaming gen=$gen: 已入队结束标记")
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
     * **调用约束**：仅在持有 [playLock] 时调用（start/stop/release 内部）。
     */
    private fun stopInternal() {
        synchronized(generationLock) { generation++ }   // 旧段令牌失效
        streamQueue = null
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

    /**
     * 专用写线程：阻塞取队列 → 写 AudioTrack（缓冲满时 write 自然形成背压）。
     * 收到 [END_SENTINEL] 后注册末尾帧标记，线程退出，由回调负责播放完成通知。
     */
    private fun writeStreamingLoop(
        queue: LinkedBlockingQueue<ShortArray>,
        gen: Int,
        sampleRate: Int,
    ) {
        try {
            if (!isCurrent(gen)) {
                Log.d(TAG, "writeStreamingLoop gen=$gen: 已过期，丢弃")
                return
            }
            val t = obtainTrack(sampleRate)
            Log.d(
                TAG,
                "writeStreamingLoop gen=$gen: track 已创建 sr=$sampleRate " +
                    "session=${t.audioSessionId} buffer=${t.bufferSizeInFrames} 帧",
            )
            var total = 0
            var sawEnd = false
            var playing = false
            while (isCurrent(gen)) {
                val chunk = queue.take()
                if (chunk === END_SENTINEL) {
                    sawEnd = true
                    break
                }
                if (!isCurrent(gen)) break
                var offset = 0
                while (offset < chunk.size) {
                    if (!isCurrent(gen)) {
                        Log.d(TAG, "writeStreamingLoop gen=$gen: 段已失效，停止写入（正常 stop）")
                        return
                    }
                    // 首块：先非阻塞写入把数据塞进 buffer，再 play()——避免"空 buffer 就 start"。
                    // 真机 2026-11-15 实测：空缓冲 start 的单块短音频（0.7~1s）AudioFlinger 一帧都不消费
                    // （AudioTrackShared front=0，无声，marker/轮询都等不到），换成先写后启播。
                    val written = if (playing) {
                        t.write(chunk, offset, chunk.size - offset)
                    } else {
                        t.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    when {
                        written > 0 -> {
                            offset += written
                            total += written
                            if (!playing) {
                                t.play()
                                playing = true
                                Log.d(
                                    TAG,
                                    "writeStreamingLoop gen=$gen: 已写入首批 $total 帧，play() 启动" +
                                        "（playState=${t.playState}）",
                                )
                            }
                        }
                        written == 0 -> {
                            if (!playing) {
                                // 非阻塞写把 buffer 写满了：先启动播放腾空间
                                t.play()
                                playing = true
                                Log.d(TAG, "writeStreamingLoop gen=$gen: buffer 已满，先 play() 再续写")
                            } else {
                                Thread.sleep(5)
                            }
                        }
                        else -> {
                            Log.e(TAG, "writeStreamingLoop gen=$gen: write 返回错误码 $written")
                            notifyComplete(gen)
                            return
                        }
                    }
                }
                Log.d(TAG, "writeStreamingLoop gen=$gen: 写入 ${chunk.size} 样本（累计 $total, playing=$playing）")
            }
            if (!isCurrent(gen) || !sawEnd) {
                Log.d(TAG, "writeStreamingLoop gen=$gen: 未收到结束标记即退出（正常 stop）")
                return
            }
            if (total == 0) {
                Log.w(TAG, "writeStreamingLoop gen=$gen: 没有可播放样本")
                notifyComplete(gen)
                return
            }
            if (!playing) {
                // 理论不可达（有样本就已在首块后 play）；兜底保证一定处于播放态
                t.play()
            }
            // 短片段兜底（真机 2026-11-15 实测）：总帧数不足以填满 track buffer 时，AudioFlinger
            // 会一直挂着这条流式 track —— playState=PLAYING、underrun=0、播放头恒 0、一帧不渲染（无声），
            // marker/轮询都等不到。用静音补齐到 buffer 容量逼它开始渲染；marker 仍打在真实音频末尾。
            val bufferFrames = t.bufferSizeInFrames
            if (total < bufferFrames) {
                val pad = ShortArray(bufferFrames - total)
                var padOffset = 0
                while (padOffset < pad.size && isCurrent(gen)) {
                    val w = t.write(pad, padOffset, pad.size - padOffset)
                    if (w <= 0) break
                    padOffset += w
                }
                Log.d(
                    TAG,
                    "writeStreamingLoop gen=$gen: 短片段（$total < buffer $bufferFrames 帧），" +
                        "补静音 ${pad.size} 帧 → 已写 $padOffset",
                )
            }
            registerPlaybackEnd(t, gen, total, sampleRate)
            Log.d(TAG, "writeStreamingLoop gen=$gen: 全部写入共 $total 帧，等待播放头到末尾")
        } catch (e: InterruptedException) {
            // 被 stop/release 打断：正常路径，不回调完成
            Log.d(TAG, "writeStreamingLoop gen=$gen: 被 InterruptedException 打断（正常 stop）")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "流式播放失败 gen=$gen", e)
            notifyComplete(gen)
        }
    }

    /**
     * 注册末尾帧位置标记：硬件播放头到达 [totalFrames] 才算真实播完（marker 为主，周期回调与
     * 轮询兜底；极短音频可能在 marker 注册前就已播过头，必须有兜底，否则 isPlaying 会卡住）。
     */
    private fun registerPlaybackEnd(t: AudioTrack, gen: Int, totalFrames: Int, sampleRate: Int) {
        t.setPositionNotificationPeriod(totalFrames)
        t.notificationMarkerPosition = totalFrames
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) {
                Log.d(TAG, "流式段 gen=$gen: marker 到达（真实播完）")
                notifyComplete(gen)
            }

            override fun onPeriodicNotification(track: AudioTrack?) {
                // 已通知过（marker 先到）就不再重复处理/刷日志
                if (notifiedGen == gen) return
                // 周期回调：核对播放头是否已到末尾（兜底）。
                val head = track?.playbackHeadPosition ?: 0
                if (head >= totalFrames) {
                    Log.d(TAG, "流式段 gen=$gen: 周期回调 head=$head>=total=$totalFrames，判完成")
                    notifyComplete(gen)
                }
            }
        }, mainHandler)
        armCompletionPoll(t, gen, totalFrames, sampleRate)
    }

    /**
     * 轮询兜底：每 100ms 核对播放头；超过「音频时长 + 1s」仍未完成则强制收尾，避免状态卡死。
     * 前几次轮询额外记录 playState / underrunCount，便于区分"没播"与"播了但回调丢失"。
     */
    private fun armCompletionPoll(t: AudioTrack, gen: Int, totalFrames: Int, sampleRate: Int) {
        val deadlineMs = totalFrames * 1000L / maxOf(1, sampleRate) + 1000L
        var polls = 0
        fun poll(elapsedMs: Long) {
            if (!isCurrent(gen) || notifiedGen == gen) return
            val head = runCatching { t.playbackHeadPosition }.getOrDefault(0)
            if (polls < 3) {
                val state = runCatching { t.playState }.getOrDefault(-1)
                val underrun = runCatching { t.underrunCount }.getOrDefault(-1)
                Log.d(
                    TAG,
                    "流式段 gen=$gen 轮询#$polls: head=$head/$totalFrames " +
                        "playState=$state underrun=$underrun",
                )
            }
            polls++
            when {
                head >= totalFrames -> {
                    Log.d(TAG, "流式段 gen=$gen: 轮询确认播完 head=$head>=total=$totalFrames")
                    notifyComplete(gen)
                }
                elapsedMs >= deadlineMs -> {
                    Log.w(TAG, "流式段 gen=$gen: 轮询超时（head=$head / total=$totalFrames），强制收尾")
                    notifyComplete(gen)
                }
                else -> mainHandler.postDelayed({ poll(elapsedMs + COMPLETION_POLL_MS) }, COMPLETION_POLL_MS)
            }
        }
        mainHandler.postDelayed({ poll(0) }, COMPLETION_POLL_MS)
    }

    /** 标记播放结束并回调（主线程）。仅当段仍有效且尚未通知过时调用（marker / 兜底轮询只生效一次）。 */
    private fun notifyComplete(gen: Int) {
        if (!isCurrent(gen)) return
        if (notifiedGen == gen) return
        notifiedGen = gen
        isPlaying = false
        mainHandler.post { onPlaybackComplete?.invoke() }
    }

    /**
     * 获取一个「待写入」的 AudioTrack（**不在这里 play**）。
     *
     * **不复用旧 track**：被 stop 打断过的 track 可能已被旧写线程并发操作过、状态不可信；
     * 每段新建一个干净 track 能彻底杜绝"两线程同写一个 track"的竞态。
     * 缓冲取 [STREAM_BUFFER_SECONDS] 秒，吸收合成慢于实时的抖动。
     *
     * **启动时机交给写线程**：真机实测"空 buffer 就 start()"的单块短音频会被 AudioFlinger 一帧不渲染
     * （无声、marker 等不到回调），所以写线程先写入首批数据再 [AudioTrack.play]。
     *
     * 调用前提：playLock 已串行化段调度，正常路径下旧写线程已退出；
     * 兜底 release 仅兜底 join 超时等异常残留。
     */
    private fun obtainTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, STREAM_BUFFER_SECONDS * sampleRate * 2)
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
