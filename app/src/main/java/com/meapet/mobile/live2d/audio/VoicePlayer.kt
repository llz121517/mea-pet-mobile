package com.meapet.mobile.live2d.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * 触摸语音播放器。
 * 从 assets/voice/ 加载并播放 Opus 音频（.ogg，48kHz 单声道 48kbps）。
 *
 * 为什么是 Opus：原 .wav 是 32kHz 单声道无损，21 个文件共 2.7MB，占 assets 体积大头；
 * 同一批语音转 Opus 后共 225KB（−92%），听感无明显损失。重新编码见
 * `tools/encode-voice-assets.ps1`。
 *
 * 这里用 [android.content.res.AssetManager.openFd] 直接读 fd：`.ogg` 在 aapt 默认
 * 不压缩扩展名列表里（本项目另在 build.gradle.kts 的 androidResources.noCompress 显式声明），
 * 压缩存储的 asset 不能 openFd，会抛 FileNotFoundException。
 *
 * 由 GL 线程调用。[play] 使用 [MediaPlayer.prepareAsync] 异步准备，
 * 避免在 GL 线程上同步阻塞（同步 prepare 会拖住 GL 帧，进而经
 * GLSurfaceView.onPause 阻塞主线程导致 ANR）。
 *
 * 线程安全：[player] 的所有读写都经 [lock] 同步，可在 GL 线程（触摸触发）
 * 与协程线程（TTS 互斥 stopTouchVoices）间安全调用。
 *
 * @param voiceDir 语音子目录，如 "voice/upper"、"voice/lower_left"、"voice/lower_right"
 */
class VoicePlayer(
    private val context: Context,
    private val voiceDir: String
) {
    private val lock = Any()
    private var player: MediaPlayer? = null

    companion object {
        private const val TAG = "VoicePlayer"
        private const val VOICE_EXT = ".ogg"
    }

    /** 列出该子目录下所有 .ogg 文件。 */
    fun listVoices(): List<String> {
        return try {
            context.assets.list(voiceDir)
                ?.filter { it.endsWith(VOICE_EXT, ignoreCase = true) }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun play(filename: String) {
        // fd 必须及时关闭：setDataSource 读取完成后 fd 不再被持有，开着会随播放次数累积泄漏
        try {
            context.assets.openFd("$voiceDir/$filename").use { afd ->
                try {
                    // 先停掉上一个（含正在异步准备的）
                    stop()
                    val mp = MediaPlayer().apply {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        setOnCompletionListener { stop() }
                        setOnPreparedListener { p ->
                            // 准备完成才起播；若期间已被 stop（p 已不在锁内）则忽略
                            synchronized(lock) {
                                if (player === p) {
                                    try { p.start() } catch (e: Exception) { Log.w(TAG, "start 失败", e) }
                                }
                            }
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.w(TAG, "MediaPlayer error: $what / $extra")
                            stop()
                            true
                        }
                    }
                    synchronized(lock) { player = mp }
                    // 异步准备，立即返回不阻塞调用线程
                    mp.prepareAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to play $filename", e)
                }
            }
        } catch (e: Exception) {
            // 例如 asset 被压缩存储时 openFd 抛 FileNotFoundException
            Log.e(TAG, "Failed to open $voiceDir/$filename", e)
        }
    }

    fun stop() {
        synchronized(lock) {
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }
}
