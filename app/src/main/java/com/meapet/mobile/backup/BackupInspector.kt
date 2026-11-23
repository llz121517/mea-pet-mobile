package com.meapet.mobile.backup

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

/** 流式读写缓冲区大小（[BackupInspector] 与读取器共用）。 */
private const val BUFFER_SIZE = 8 * 1024

/** 包内一个类别的元信息（只暴露给 UI 展示，不暴露 zip 句柄）。 */
data class BackupContentEntry(
    /** 包内路径。 */
    val path: String,
    /** manifest 登记的未压缩字节数。 */
    val bytes: Long,
)

/**
 * 已校验的待导入包（只含元信息，不含口令）。
 *
 * 口令不落状态：zip 句柄由 [BackupInspector.open] 返回的 [BackupContentReader] 私有持有，
 * ViewModel 只在自身字段里保存它，UI 状态只放本数据类。
 */
data class BackupContents(
    val manifest: BackupManifest,
    val entries: Map<BackupCategory, BackupContentEntry>,
) {
    /** 包内实际存在的类别。 */
    val categories: Set<BackupCategory> get() = entries.keys
}

/**
 * 读取已打开备份包内容的只读接口。
 *
 * 实现持有已解密的 zip 句柄与口令，**不得**放进 UI 状态；用完后 [close]。
 */
interface BackupContentReader : Closeable {
    val contents: BackupContents

    /** 解出文本条目内容；超过 [maxBytes] 抛 [ManifestRejectException]。 */
    fun extractText(
        category: BackupCategory,
        maxBytes: Long = BackupInspector.MAX_TEXT_BYTES,
    ): String

    /** 把二进制条目（壁纸）流式解到 [target]。 */
    fun extractToFile(category: BackupCategory, target: File)
}

/**
 * 读包、两级版本校验、体积闸。
 *
 * 校验顺序（任一失败整包拒绝，统一抛 [ManifestRejectException]）：
 * 1. 能打开（口令错 / 文件损坏 → 「口令错误或文件已损坏」）
 * 2. 有 `manifest.json` 且可解析、`format` 受支持
 * 3. 每个 zip 条目：路径能归入已知类别、manifest 有登记、`schema` 受支持
 * 4. 一个类别只允许出现一次
 */
object BackupInspector {

    /** 文本条目解压后上限（防解压炸弹）；二进制条目流式写盘不设上限。 */
    const val MAX_TEXT_BYTES: Long = 64L * 1024 * 1024

    /** manifest 本身允许的最大字节数（防止畸形包把 manifest 做成解压炸弹）。 */
    const val MAX_MANIFEST_BYTES: Long = 1024L * 1024

    /** 口令错误与文件损坏共用同一文案：不区分、不泄露包内信息。 */
    private const val DAMAGED_MESSAGE = "口令错误或文件已损坏"

    /**
     * 打开并校验备份包。
     *
     * @param stageFile 已暂存到 cacheDir 的包文件（zip4j 需要随机访问）
     * @param password 口令
     * @throws ManifestRejectException 口令错误、结构不完整或版本不支持
     */
    fun open(stageFile: File, password: CharArray): BackupContentReader {
        // ZipFile 持有口令供后续 getInputStream 解密；口令不复制到任何 UI 状态/日志。
        val zipFile = ZipFile(stageFile, password)
        try {
            return validateAndOpen(zipFile)
        } catch (t: Throwable) {
            // 校验失败也要释放 zip4j 的句柄（ZipFile 内部可能持有 executor）
            runCatching { zipFile.close() }
            throw t
        }
    }

    // ── 校验 ──────────────────────────────────────────

    private fun validateAndOpen(zipFile: ZipFile): BackupContentReader {
        // 1) 能打开：非 zip / 中央目录损坏
        if (!zipFile.isValidZipFile()) throw damaged()

        val headers = try {
            zipFile.fileHeaders
        } catch (e: ZipException) {
            throw damaged()
        }

        // 2) 有且只有一个 manifest.json，先于业务条目读取
        val manifestHeader = headers.singleOrNull { it.fileName == BackupFormat.MANIFEST_ENTRY }
            ?: throw structureIncomplete()
        val manifestText = try {
            String(readEntryBytes(zipFile, manifestHeader, MAX_MANIFEST_BYTES), Charsets.UTF_8)
        } catch (e: ZipException) {
            // 口令错误在这里浮现（zip4j 在解第一条加密条目时校验口令）
            throw damaged()
        }
        val manifest = BackupFormat.decode(manifestText)

        // manifest 自身不能重复登记同一路径（否则「登记数 == 包内条目数」的对账会失真）
        val registeredByPath = LinkedHashMap<String, ManifestEntry>()
        manifest.entries.forEach { entry ->
            if (registeredByPath.put(entry.path, entry) != null) throw structureIncomplete()
        }

        // 3)/4) 逐 zip 条目校验：已知路径 → 有登记且 name/path 一致 → schema 受支持 → 类别唯一
        val dataHeaders = headers.filter { it.fileName != BackupFormat.MANIFEST_ENTRY }
        val entriesByCategory = LinkedHashMap<BackupCategory, BackupContentEntry>()
        val headerByCategory = LinkedHashMap<BackupCategory, FileHeader>()
        val seenPaths = HashSet<String>()
        dataHeaders.forEach { header ->
            val path = header.fileName
            // 目录条目、重名条目一律按结构不完整拒绝（导出方只写文件条目）
            if (header.isDirectory || !seenPaths.add(path)) throw structureIncomplete()
            val category = BackupCategory.fromPath(path) ?: throw structureIncomplete()
            val registered = registeredByPath[path] ?: throw structureIncomplete()
            if (registered.name != category.entryName) throw structureIncomplete()
            if (!BackupFormat.entrySupported(registered)) {
                throw ManifestRejectException(BackupFormat.unsupportedEntryMessage(registered))
            }
            if (entriesByCategory.containsKey(category)) throw structureIncomplete()
            entriesByCategory[category] = BackupContentEntry(path = path, bytes = registered.bytes)
            headerByCategory[category] = header
        }

        // manifest 里登记、但包里没有的条目：结构不完整
        if (registeredByPath.size != dataHeaders.size) throw structureIncomplete()

        return ZipBackupContentReader(
            zipFile = zipFile,
            contents = BackupContents(manifest = manifest, entries = entriesByCategory),
            headerByCategory = headerByCategory,
        )
    }

    /** 边读边计数，超过 [limit] 即拒绝（防解压炸弹）。 */
    private fun readEntryBytes(zipFile: ZipFile, header: FileHeader, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        zipFile.getInputStream(header).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw structureIncomplete()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun damaged(): ManifestRejectException = ManifestRejectException(DAMAGED_MESSAGE)

    private fun structureIncomplete(): ManifestRejectException =
        ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
}

/**
 * 持有已校验 zip 句柄的读取器。
 *
 * 口令由 zip4j 的 [ZipFile] 自己持有（解密条目必需），本类不额外保存口令副本；
 * 也不打印任何条目内容或口令。
 */
private class ZipBackupContentReader(
    private val zipFile: ZipFile,
    override val contents: BackupContents,
    private val headerByCategory: Map<BackupCategory, FileHeader>,
) : BackupContentReader {

    private var closed = false

    override fun extractText(category: BackupCategory, maxBytes: Long): String {
        val header = headerFor(category)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        try {
            zipFile.getInputStream(header).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw tooLarge(maxBytes)
                    output.write(buffer, 0, read)
                }
            }
        } catch (e: ZipException) {
            // 损坏条目（CRC/MAC 校验失败）与口令错误对外统一
            throw damaged()
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    override fun extractToFile(category: BackupCategory, target: File) {
        val header = headerFor(category)
        target.parentFile?.mkdirs()
        try {
            // 流式写盘：二进制条目（壁纸）不设内存上限；outputStream() 会覆盖已有文件
            zipFile.getInputStream(header).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: ZipException) {
            throw damaged()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        zipFile.close()
    }

    private fun headerFor(category: BackupCategory): FileHeader =
        headerByCategory[category] ?: throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)

    private fun damaged(): ManifestRejectException =
        ManifestRejectException("口令错误或文件已损坏")

    /** 用实际传入的 [maxBytes] 生成文案；默认值 64 MiB 时与 PRD 文案完全一致。 */
    private fun tooLarge(maxBytes: Long): ManifestRejectException {
        val mebibytes = maxBytes / (1024 * 1024)
        val limitText = if (mebibytes > 0) "$mebibytes MiB" else "$maxBytes 字节"
        return ManifestRejectException("备份中的文本条目过大（超过 $limitText）")
    }
}
