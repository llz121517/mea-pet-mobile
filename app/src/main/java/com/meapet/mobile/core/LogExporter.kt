package com.meapet.mobile.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.meapet.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 日志导出：抓取 logcat + 本进程 native crash tombstone，写入 cache，拉起系统分享菜单。
 *
 * 用户可经分享把 .log 发给开发者排查问题。
 *
 * ## 内容
 * - 设备 / 版本头信息；
 * - **tombstone**：本进程历史 native 崩溃（段错误等）的退出原因与堆栈，
 *   经 [ActivityManager.getHistoricalProcessExitReasons] 读取（API 30+；低版本跳过），
 *   堆栈为 debuggerd 的 protobuf 二进制，按原始字节写入（可用 android.os.tombstone 解码）；
 * - **logcat**：全量缓冲区的应用日志（不限定 PID——TTS 等服务可能跑在独立进程，
 *   且便于看到崩溃前的完整上下文）。
 *
 * ## 隐私
 * 项目日志不打对话正文；tombstone 仅含本包名进程的崩溃记录。文件经 FileProvider 临时授权分享。
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val LOG_DIR = "logs"

    /** 等 logcat 子进程退出的上限（秒）：正常瞬间返回，超时即强杀，不允许无上限阻塞。 */
    private const val LOGCAT_WAIT_TIMEOUT_SECONDS = 5L

    /**
     * 导出日志并拉起系统分享菜单。
     * @return 是否成功（失败时调用方可提示用户）
     */
    suspend fun exportAndShare(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = try {
            collect(context)
        } catch (e: Exception) {
            Log.e(TAG, "日志抓取失败", e)
            return@withContext false
        }
        withContext(Dispatchers.Main) { share(context, file) }
        true
    }

    /** 抓取日志 + tombstone，写入 cache/logs，返回文件。 */
    private fun collect(context: Context): File {
        val dir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        // 清理过期日志，避免堆积
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(5)?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "meapet-$stamp.log")

        // 二进制流直写：tombstone 是 protobuf 原始字节，经字符流会被 UTF-8 解码破坏
        file.outputStream().buffered().use { out ->
            out.writeText(header())
            out.newLine()
            writeTombstones(context, out)
            writeLogcat(out)
        }
        return file
    }

    private fun header(): String = buildString {
        appendLine("MeaPet 日志导出")
        appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("═".repeat(50))
    }

    /** 文本换行符（Android 即 '\n'）。 */
    private val LF = '\n'.code

    /** 以 UTF-8 写出文本；二进制内容（tombstone protobuf）必须走原始字节，见 [writeTombstoneBytes]。 */
    private fun OutputStream.writeText(s: String) = write(s.toByteArray(Charsets.UTF_8))

    /** 写一行 UTF-8 文本并换行。 */
    private fun OutputStream.writeLine(s: String) {
        writeText(s)
        write(LF)
    }

    private fun OutputStream.newLine() = write(LF)

    /** 写入本进程历史 native crash 的 tombstone（API 30+）。 */
    private fun writeTombstones(context: Context, out: OutputStream) {
        out.writeLine("■ Native Crash (tombstone)")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            out.writeLine("（系统版本低于 Android 11，不支持读取历史崩溃记录）")
            out.newLine()
            return
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // packageName=null+pid=0+maxNum：取本进程全部历史退出原因
            val reasons = am.getHistoricalProcessExitReasons(null, 0, 0)
            val crashes = reasons?.filter {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            } ?: emptyList()

            if (crashes.isEmpty()) {
                out.writeLine("（无 native 崩溃记录）")
            } else {
                crashes.forEach { info ->
                    out.writeLine("── 崩溃于 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))} ──")
                    out.writeLine("进程: ${info.processName}  重要性: ${info.importance}")
                    try {
                        info.traceInputStream?.use { trace ->
                            // tombstone 是 debuggerd 的 protobuf 二进制，必须按原始字节写出：
                            // 经字符流解码会把 ≥0x80 的字节替换成 U+FFFD（ef bf bd），
                            // 解出的 pid/tid 全是垃圾值，寄存器与地址全毁。
                            writeTombstoneBytes(trace, out)
                            out.newLine()
                        } ?: run { out.writeLine("（无堆栈详情）") }
                    } catch (e: Exception) {
                        out.writeLine("（堆栈读取失败: ${e.message}）")
                    }
                }
            }
        } catch (e: Exception) {
            out.writeLine("（tombstone 读取失败: ${e.message}）")
        }
        out.newLine()
        out.writeLine("═".repeat(50))
    }

    /** 写入 logcat 全量缓冲区（不限定 PID，覆盖服务进程与崩溃前上下文）。 */
    private fun writeLogcat(out: OutputStream) {
        out.writeLine("■ Logcat")
        try {
            // -d 立即返回（非阻塞）；-v threadtime 带时间戳与线程
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
                .redirectErrorStream(true).start()
            process.inputStream.use { it.copyTo(out) }
            // waitFor() 不带超时会把所在 Dispatchers.IO 线程占死，而且不响应协程取消：
            // logcat -d 正常会在输出读完后立刻退出，只有异常挂起时才需要强杀兜底。
            if (!process.waitFor(LOGCAT_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "logcat 子进程 ${LOGCAT_WAIT_TIMEOUT_SECONDS}s 未退出，强制结束")
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            out.writeLine("（logcat 抓取失败: ${e.message}）")
        }
    }

    /** 经 FileProvider 拉起系统分享菜单。 */
    private fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MeaPet 日志 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享日志给开发者").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

/**
 * 把 tombstone 的 protobuf 原始字节逐字节拷入 [out]。
 *
 * 独立成函数以便单测守住不变式：这些字节绝不可经字符流（Reader/Writer）中转，
 * 否则 ≥0x80 的字节会被替换为 U+FFFD（ef bf bd），protobuf 报废（见 [LogExporter.writeTombstones]）。
 */
internal fun writeTombstoneBytes(raw: InputStream, out: OutputStream) {
    raw.copyTo(out)
}
