package com.meapet.mobile.backup

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * [BackupExporter] 的纯 JVM 单测（zip4j 在 JVM 可用，不需要 Android 运行时）。
 *
 * 覆盖：加密包能被正确密码读回、错误密码打不开、明文不落进文件、manifest 与实际条目对账、
 * 默认文件名，以及「导出结束（含失败）关闭目标流」的契约。
 */
@Suppress("NonAsciiCharacters")
class BackupExporterTest {

    private val password = "correct-horse-battery-2026".toCharArray()

    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    // ── 用例 ─────────────────────────────────────────

    @Test
    fun `文本与二进制条目混合写出后可被正确密码读回`() {
        val settings = """{"values":{"theme_mode":"dark"}}"""
        val chat = """{"messages":[{"role":"user","content":"你好"}]}"""
        val wallpaperBytes = ByteArray(4096) { (it * 31 % 251).toByte() }
        val wallpaper = tempFile("wallpaper-", ".jpg").apply { writeBytes(wallpaperBytes) }

        val items = listOf(
            BackupItem.Text("files/settings", settings),
            BackupItem.Text("files/chat", chat),
            BackupItem.FileSource("files/wallpaper/wall.jpg", wallpaper),
        )
        val file = exportToFile(items)

        ZipFile(file, password).use { zip ->
            val names = zip.fileHeaders.map { it.fileName }
            assertEquals(4, names.size)
            assertEquals(
                setOf("files/settings", "files/chat", "files/wallpaper/wall.jpg", BackupFormat.MANIFEST_ENTRY),
                names.toSet(),
            )
            // manifest 固定最后写
            assertEquals(BackupFormat.MANIFEST_ENTRY, names.last())

            assertEquals(settings, String(readEntry(zip, "files/settings"), Charsets.UTF_8))
            assertEquals(chat, String(readEntry(zip, "files/chat"), Charsets.UTF_8))
            assertArrayEquals(wallpaperBytes, readEntry(zip, "files/wallpaper/wall.jpg"))
        }
    }

    @Test
    fun `manifest 登记条目与包内实际条目一一对应`() {
        val chat = """{"messages":[]}"""
        val wallpaperBytes = ByteArray(1024) { it.toByte() }
        val wallpaper = tempFile("wallpaper-", ".png").apply { writeBytes(wallpaperBytes) }
        val items = listOf(
            BackupItem.Text("files/settings", """{"values":{}}"""),
            BackupItem.Text("files/chat", chat),
            BackupItem.FileSource("files/wallpaper/w.png", wallpaper),
        )
        val file = exportToFile(items)

        ZipFile(file, password).use { zip ->
            val manifest = BackupFormat.decode(
                String(readEntry(zip, BackupFormat.MANIFEST_ENTRY), Charsets.UTF_8)
            )
            assertEquals(BackupFormat.SUPPORTED_FORMAT, manifest.format)
            assertEquals(items.size, manifest.entries.size)

            val registeredPaths = manifest.entries.map { it.path }.toSet()
            assertEquals(items.map { it.zipPath }.toSet(), registeredPaths)

            items.forEach { item ->
                val entry = manifest.entries.single { it.path == item.zipPath }
                val category = requireNotNull(BackupCategory.fromPath(item.zipPath))
                assertEquals(category.entryName, entry.name)
                assertEquals(BackupFormat.schemaFor(category), entry.schema)
                assertEquals(item.bytes, entry.bytes)
            }
            // bytes 是未压缩字节数
            assertEquals(chat.toByteArray(Charsets.UTF_8).size.toLong(), manifest.entries.single { it.name == "chat" }.bytes)
            assertEquals(wallpaperBytes.size.toLong(), manifest.entries.single { it.name == "wallpaper" }.bytes)
        }
    }

    @Test
    fun `错误密码解不开任何条目`() {
        val file = exportToFile(listOf(BackupItem.Text("files/settings", """{"values":{}}""")))

        val error = runCatching {
            ZipFile(file, "totally-wrong-password".toCharArray()).use { zip ->
                readEntry(zip, BackupFormat.MANIFEST_ENTRY)
            }
        }.exceptionOrNull()

        assertTrue("错误密码应以 ZipException 拒绝，实际：$error", error is ZipException)
    }

    @Test
    fun `明文与 API Key 不出现在导出文件字节里`() {
        val secretChat = "SUPER_SECRET_CHAT_CONTENT_9f3a7d"
        val secretKey = "sk-meapet-secret-api-key-1234567890"
        val items = listOf(
            BackupItem.Text(
                "files/chat",
                """{"messages":[{"role":"user","content":"$secretChat"}]}""",
            ),
            BackupItem.Text(
                "files/settings",
                """{"values":{"api_key":"$secretKey"}}""",
            ),
        )

        val out = ByteArrayOutputStream()
        BackupExporter.export(items, manifestFor(items), password, out)
        val haystack = String(out.toByteArray(), Charsets.ISO_8859_1)

        assertFalse("对话明文泄漏进 zip 字节", haystack.contains(secretChat))
        assertFalse("API Key 明文泄漏进 zip 字节", haystack.contains(secretKey))
        // 连 manifest 的 JSON 字段名都不应以明文出现（manifest 条目同样加密）
        assertFalse(haystack.contains("\"format\""))
        assertFalse(haystack.contains("\"entries\""))
    }

    @Test
    fun `没有勾选任何类别时仍写出可解开的 manifest`() {
        val file = exportToFile(emptyList())

        ZipFile(file, password).use { zip ->
            assertEquals(listOf(BackupFormat.MANIFEST_ENTRY), zip.fileHeaders.map { it.fileName })
            val manifest = BackupFormat.decode(
                String(readEntry(zip, BackupFormat.MANIFEST_ENTRY), Charsets.UTF_8)
            )
            assertEquals(BackupFormat.SUPPORTED_FORMAT, manifest.format)
            assertTrue(manifest.entries.isEmpty())
        }
    }

    @Test
    fun `默认文件名是 UTC 紧凑时间戳加固定后缀`() {
        assertEquals(
            "20261122T153000Z_MeaPet-backup.zip",
            BackupExporter.defaultFileName("20261122T153000Z"),
        )
        assertTrue(BackupExporter.defaultFileName("20260101T000000Z").endsWith("_MeaPet-backup.zip"))
        assertEquals("manifest.json", BackupExporter.MANIFEST_ENTRY)
    }

    @Test
    fun `导出成功与失败都会关闭目标流`() {
        val successSink = RecordingOutputStream()
        val items = listOf(BackupItem.Text("files/settings", """{"values":{}}"""))
        BackupExporter.export(items, manifestFor(items), password, successSink)
        assertTrue("成功导出后应关闭目标流", successSink.closed)
        assertTrue("应写出非空 zip", successSink.bytes().isNotEmpty())

        // 空密码会让 zip4j 在写第一条时拒绝；即使失败也不能泄漏 SAF 目标流
        val failureSink = RecordingOutputStream()
        val failure = runCatching {
            BackupExporter.export(items, manifestFor(items), CharArray(0), failureSink)
        }.exceptionOrNull()
        assertTrue("空密码应导出失败，实际：$failure", failure != null)
        assertTrue("导出失败后也应关闭目标流", failureSink.closed)
    }

    // ── 辅助 ─────────────────────────────────────────

    private fun exportToFile(items: List<BackupItem>): File {
        val out = ByteArrayOutputStream()
        BackupExporter.export(items, manifestFor(items), password, out)
        return tempFile("backup-export-", ".zip").apply { writeBytes(out.toByteArray()) }
    }

    private fun manifestFor(items: List<BackupItem>): BackupManifest = BackupManifest(
        format = BackupFormat.SUPPORTED_FORMAT,
        createdAt = "2026-11-22T15:30:00Z",
        appVersion = "1.9.0",
        entries = items.map { item ->
            val category = requireNotNull(BackupCategory.fromPath(item.zipPath)) {
                "测试条目路径无法归入类别：${item.zipPath}"
            }
            ManifestEntry(
                name = category.entryName,
                schema = BackupFormat.schemaFor(category),
                bytes = item.bytes,
                path = item.zipPath,
            )
        },
    )

    private fun readEntry(zip: ZipFile, path: String): ByteArray =
        zip.getInputStream(zip.getFileHeader(path)).use { it.readBytes() }

    private fun tempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix).also { tempFiles += it }

    /** 记录是否被关闭的目标流（验证 export 的 close 契约）。 */
    private class RecordingOutputStream : OutputStream() {
        private val delegate = ByteArrayOutputStream()

        var closed = false
            private set

        override fun write(b: Int) = delegate.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun close() {
            closed = true
            delegate.close()
        }

        fun bytes(): ByteArray = delegate.toByteArray()
    }
}
