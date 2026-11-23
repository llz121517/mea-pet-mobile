package com.meapet.mobile.backup

import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.OutputStream

/**
 * 把采集好的条目逐条写进 AES-256 加密 zip，最后写 manifest。
 *
 * 实现要点（zip4j）：只有 `io.outputstream.ZipOutputStream` 能往任意流写，
 * `ZipFile` 没有 OutputStream 构造器；文本用 DEFLATE、壁纸用 STORE（已压缩）。
 * 明文不落盘：文本在内存、图片直读源文件进加密流。
 */
object BackupExporter {

    /** manifest 在包内的固定路径。 */
    const val MANIFEST_ENTRY = BackupFormat.MANIFEST_ENTRY

    /** 默认文件名：`<UTC紧凑>_MeaPet-Android.zip`。 */
    fun defaultFileName(utcCompact: String): String = "${utcCompact}_MeaPet-Android.zip"

    /**
     * 写出整个包。
     *
     * 所有条目（含 manifest.json）都用 AES-256 加密；manifest 固定最后写出，
     * 便于读取方先定位中央目录再解 manifest。
     *
     * @param items 待写条目（顺序即包内顺序）
     * @param manifest 与之对应的 manifest（在条目之后收尾写出）
     * @param password 口令（调用方负责用后清零）
     * @param sink SAF 给的目标输出流；本函数会关闭它
     * @throws net.lingala.zip4j.exception.ZipException zip4j 写失败（口令为空、参数非法等）
     * @throws java.io.IOException 目标流写失败
     */
    fun export(
        items: List<BackupItem>,
        manifest: BackupManifest,
        password: CharArray,
        sink: OutputStream,
    ) {
        // 构造失败（如口令为空）时也要关闭 SAF 目标流，避免泄漏系统句柄
        val zip = try {
            ZipOutputStream(sink, password)
        } catch (t: Throwable) {
            runCatching { sink.close() }
            throw t
        }
        zip.use { out ->
            items.forEach { item -> writeItem(out, item) }
            // manifest 最后写；它同样走加密条目
            out.putNextEntry(parameters(MANIFEST_ENTRY, CompressionMethod.DEFLATE))
            out.write(BackupFormat.encode(manifest).toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }

    private fun writeItem(out: ZipOutputStream, item: BackupItem) {
        when (item) {
            is BackupItem.Text -> {
                out.putNextEntry(parameters(item.zipPath, CompressionMethod.DEFLATE))
                out.write(item.content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }

            is BackupItem.FileSource -> {
                // zip4j 要求 STORE 条目显式给出未压缩大小（它据此写本地文件头），
                // 否则 putNextEntry 抛 "uncompressed size should be set ... store"。
                val parameters = parameters(item.zipPath, CompressionMethod.STORE)
                parameters.setEntrySize(item.bytes)
                out.putNextEntry(parameters)
                // 直读源文件流式进加密流：不把整个壁纸读进内存
                item.file.inputStream().use { input -> input.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** 统一的加密参数：AES-256；清空口令/非法参数由 zip4j 抛 ZipException。 */
    private fun parameters(path: String, compressionMethod: CompressionMethod): ZipParameters =
        ZipParameters().apply {
            setFileNameInZip(path)
            setEncryptFiles(true)
            setEncryptionMethod(EncryptionMethod.AES)
            setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
            setCompressionMethod(compressionMethod)
        }
}
