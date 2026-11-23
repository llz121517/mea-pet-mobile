package com.meapet.mobile.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * SAF 薄封装：暂存目录、打开读写流、显示名、进页清扫。
 *
 * SAF（Storage Access Framework）走 `CreateDocument` / `OpenDocument`，不需要存储权限。
 * 导出直接写用户选中的 `content://` 流；导入先拷到 `cacheDir/backup` 再按中央目录读
 * （zip4j 的 `ZipFile` 需要随机访问，不能直接用 SAF 流）。
 */
class BackupService(private val context: Context) {

    /** 导入暂存目录（`cacheDir/backup`）；每次访问确保存在。 */
    val stagingDir: File
        get() = File(context.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }

    /**
     * 把用户选中的包拷进暂存目录，返回暂存文件。
     *
     * 复制而不是让 zip4j 直接读 SAF 流：`ZipFile` 必须随机访问中央目录。
     *
     * @throws IOException 打不开或读取失败
     */
    fun stageForRead(uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选备份文件")
        return stageCopy(input, "import_${System.currentTimeMillis()}.zip")
    }

    /**
     * 打开导出目标流（SAF CreateDocument 返回的 uri）。
     *
     * 用 `"wt"` 模式：目标文件已存在时截断重写，避免旧包内容残留在尾部。
     *
     * @throws IOException 打不开目标文件
     */
    fun openWriteSink(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, WRITE_MODE) ?: throw IOException("无法打开导出目标文件")

    /** 读取 SAF 显示名；取不到返回 null（调用方用默认名兜底）。 */
    fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (_: Exception) {
        // 查询不到（无权限 / 非 Openable 的 uri）时由调用方回退默认文件名
        null
    }

    /**
     * 进页清扫：删掉上次异常退出（或旧版本）残留的暂存文件。
     *
     * 暂存目录只放「读包前拷进来的副本」，全部可删；目录本身保留。
     */
    fun sweepStaleStaging() {
        sweepStagingDir(stagingDir)
    }

    // ── 内部工具（供暂存拷贝复用）────────────────────

    /** 把 [input] 全量拷进暂存目录中名为 [fileName] 的文件，返回该文件。 */
    internal fun stageCopy(input: InputStream, fileName: String): File {
        val target = File(stagingDir, fileName)
        input.use { source ->
            target.outputStream().use { out -> source.copyTo(out) }
        }
        return target
    }

    companion object {
        /** 暂存子目录名（`cacheDir/backup`）。 */
        const val STAGING_DIR_NAME = "backup"

        /** 导出流打开模式：截断重写。 */
        private const val WRITE_MODE = "wt"

        /** 默认文件名里的 UTC 紧凑时间戳，形如 `20261122T153000Z`。 */
        private val UTC_COMPACT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** 当前 UTC 紧凑时间戳（文件名用）。 */
        fun utcCompactNow(): String = UTC_COMPACT.format(Instant.now())
    }
}

/**
 * 删掉 [dir] 下的全部残留内容（文件与子目录），保留 [dir] 本身。
 *
 * 独立成纯函数以便 JVM 单测直接验证清扫语义（不依赖 Android Context）。
 */
internal fun sweepStagingDir(dir: File) {
    dir.listFiles()?.forEach { it.deleteRecursively() }
}
