package com.meapet.mobile.live2d

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.live2d.sdk.cubism.core.ICubismLogger
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Platform abstraction layer — file loading, timing, logging.
 */
object Live2dPal {
    private const val TAG = "Live2d"

    class PrintLogFunction : ICubismLogger {
        override fun print(message: String) {
            Log.d(TAG, message)
        }
    }

    class LoadFileFunction : ICubismLoadFileFunction {
        override fun load(path: String): ByteArray {
            return loadFileAsBytes(path)
        }
    }

    fun updateTime() {
        val now = System.nanoTime()
        deltaNanoTime = now - lastNanoTime
        lastNanoTime = now
    }

    // ── 文件字节缓存（异步预热）─────────────────────────
    // Live2D 模型（moc3 / 贴图 / 动作 / 物理等）启动时在 GL 线程同步加载，
    // asset 读取是最大耗时来源，会拖住 GL 线程 → 主线程 onPause 被阻塞数秒。
    // 这里缓存整目录字节，预热在 IO 线程完成，GL 线程只做解析 + GL 上传。

    /** 模型目录字节缓存：asset 相对路径 → 字节。 */
    private val byteCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** 预读缓存里的路径集合（当前预热的是哪批）。 */
    @Volatile
    private var cachedRoot: String? = null

    /** 异步预热模型目录全部文件（IO 线程）。幂等：同一目录只预热一次。 */
    fun prewarmDirectory(context: Context, dir: String) {
        if (cachedRoot == dir) return
        cachedRoot = dir
        CoroutineScope(Dispatchers.IO).launch {
            val ctx = context.applicationContext
            val assets = ctx.assets
            val startedAt = SystemClock.uptimeMillis()
            try {
                // 目录判定交给「能否列出子项」，不依赖 ROM 是否给目录名加尾斜杠
                val files = collectAssetFiles(dir) { path ->
                    try {
                        assets.list(path)?.toList() ?: emptyList()
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
                for (path in files) {
                    try {
                        val bytes = assets.open(path).use { input ->
                            input.readBytes()
                        }
                        byteCache[path] = bytes
                    } catch (e: Exception) {
                        Log.w(TAG, "预热读取失败: $path", e)
                    }
                }
                Log.i(
                    TAG,
                    "Live2D 模型目录预热完成：${files.size} 个文件，" +
                        "${SystemClock.uptimeMillis() - startedAt}ms（IO 线程）"
                )
            } catch (e: Exception) {
                Log.w(TAG, "预热模型目录失败", e)
                cachedRoot = null
            }
        }
    }

    /** 清除字节缓存（模型更新/重建时）。 */
    fun clearByteCache() {
        byteCache.clear()
        cachedRoot = null
    }

    fun loadFileAsBytes(path: String): ByteArray {
        // 命中预热缓存：免 asset 读取，GL 线程只做解析
        byteCache[path]?.let { return it }

        return try {
            // 用 application context（不随 Activity 销毁失效），Activity 不可用时回退
            val ctx = Live2dDelegate.getInstance().appContext
                ?: Live2dDelegate.getInstance().activity
                ?: return ByteArray(0)
            // readBytes() 循环读满：单次 read() 不保证填满，AAPT 压缩/特殊 ROM 下可能短读，
            // 导致 moc3/motion 数据静默截断、下游 Cubism 解析崩溃
            ctx.assets.open(path).use { it.readBytes() }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load file: $path", e)
            ByteArray(0)
        }
    }

    fun getDeltaTime(): Float {
        return (deltaNanoTime / 1_000_000_000.0).toFloat()
    }

    fun printLog(message: String) {
        Log.d(TAG, message)
    }

    private var lastNanoTime = System.nanoTime()
    private var deltaNanoTime = 0L
}
