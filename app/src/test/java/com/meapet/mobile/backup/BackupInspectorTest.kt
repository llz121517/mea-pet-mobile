package com.meapet.mobile.backup

import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [BackupInspector] 的纯 JVM 单测：用 zip4j 现场造包，验证「任一失败整包拒绝」的各条闸门，
 * 以及文本体积闸与二进制流式写盘。
 *
 * 造包与解包走同一把口令；错误口令/损坏场景都在同一条「口令错误或文件已损坏」文案上收敛。
 */
@Suppress("NonAsciiCharacters")
class BackupInspectorTest {

    private val password = "correct-horse-battery-2026".toCharArray()

    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    // ── 正常路径 ─────────────────────────────────────

    @Test
    fun `正常包解析出类别与条目并解出文本与二进制`() {
        val settingsText = """{"values":{"theme_mode":"dark"}}"""
        val settingsBytes = settingsText.toByteArray(Charsets.UTF_8)
        val wallpaperBytes = ByteArray(2048) { (it * 13 % 256).toByte() }
        val manifest = manifestOf(
            register("files/settings", settingsBytes),
            register("files/wallpaper/w1.jpg", wallpaperBytes),
        )
        val file = writePackage(
            dataEntries = listOf(
                "files/settings" to settingsBytes,
                "files/wallpaper/w1.jpg" to wallpaperBytes,
            ),
            manifestText = manifest,
        )

        BackupInspector.open(file, password).use { reader ->
            assertEquals(BackupFormat.SUPPORTED_FORMAT, reader.contents.manifest.format)
            assertEquals("2026-11-22T15:30:00Z", reader.contents.manifest.createdAt)
            assertEquals(setOf(BackupCategory.SETTINGS, BackupCategory.WALLPAPER), reader.contents.categories)

            val settingsEntry = reader.contents.entries.getValue(BackupCategory.SETTINGS)
            assertEquals("files/settings", settingsEntry.path)
            assertEquals(settingsBytes.size.toLong(), settingsEntry.bytes)

            assertEquals(settingsText, reader.extractText(BackupCategory.SETTINGS))

            val target = tempFile("extracted-", ".jpg").apply { writeBytes(ByteArray(16 * 1024)) }
            reader.extractToFile(BackupCategory.WALLPAPER, target)
            assertArrayEquals(wallpaperBytes, target.readBytes())

            // 包里没有的类别：读取器按结构不完整拒绝（调用方应先看 contents.categories）
            val error = runCatching { reader.extractText(BackupCategory.CHAT) }.exceptionOrNull()
            assertTrue(error is ManifestRejectException)
        }
    }

    @Test
    fun `extractToFile 覆盖已有文件并截断旧内容`() {
        val newBytes = ByteArray(1024) { (it + 1).toByte() }
        val manifest = manifestOf(register("files/wallpaper/new.jpg", newBytes))
        val file = writePackage(listOf("files/wallpaper/new.jpg" to newBytes), manifest)
        val target = tempFile("extracted-", ".jpg").apply { writeBytes(ByteArray(8192) { 7 }) }

        BackupInspector.open(file, password).use { reader ->
            reader.extractToFile(BackupCategory.WALLPAPER, target)
        }
        assertArrayEquals(newBytes, target.readBytes())
    }

    // ── 口令 / 损坏 ───────────────────────────────────

    @Test
    fun `口令错误统一按口令错误或文件已损坏拒绝且不泄露包内信息`() {
        val file = simpleSettingsPackage()

        val error = runCatching { BackupInspector.open(file, "wrong-password".toCharArray()) }
            .exceptionOrNull()

        assertTrue("应以 ManifestRejectException 拒绝，实际：$error", error is ManifestRejectException)
        assertEquals("口令错误或文件已损坏", error!!.message)
    }

    @Test
    fun `不是 zip 的文件按文件损坏拒绝`() {
        val file = tempFile("not-a-zip-", ".zip").apply { writeText("这不是一个 zip 文件") }

        val error = runCatching { BackupInspector.open(file, password) }.exceptionOrNull()

        assertTrue(error is ManifestRejectException)
        assertEquals("口令错误或文件已损坏", error!!.message)
    }

    @Test
    fun `截断的包按文件损坏拒绝`() {
        val whole = simpleSettingsPackage().readBytes()
        val broken = tempFile("truncated-", ".zip").apply { writeBytes(whole.copyOf(whole.size / 2)) }

        val error = runCatching { BackupInspector.open(broken, password) }.exceptionOrNull()

        assertTrue(error is ManifestRejectException)
        assertEquals("口令错误或文件已损坏", error!!.message)
    }

    // ── 结构 / 版本闸门 ───────────────────────────────

    @Test
    fun `缺少 manifest 按结构不完整拒绝`() {
        val file = writePackage(
            dataEntries = listOf("files/settings" to """{"values":{}}""".toByteArray()),
            manifestText = null,
        )

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `manifest 不是合法 JSON 按结构不完整拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        val file = writePackage(listOf("files/settings" to settings), manifestText = "{ 不是 json")

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `format 不受支持整包拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        val manifest = manifestOf(register("files/settings", settings), format = BackupFormat.SUPPORTED_FORMAT + 1)
        val file = writePackage(listOf("files/settings" to settings), manifest)

        val error = assertRejected(file)
        assertTrue("实际文案：${error.message}", error.message!!.contains("不支持的版本"))
    }

    @Test
    fun `条目 schema 不受支持整包拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        val manifest = manifestOf(register("files/settings", settings, schema = 2))
        val file = writePackage(listOf("files/settings" to settings), manifest)

        val error = assertRejected(file)
        assertTrue("实际文案：${error.message}", error.message!!.contains("schema"))
        assertTrue("实际文案：${error.message}", error.message!!.contains("settings"))
    }

    @Test
    fun `未知路径的条目整包拒绝`() {
        val file = writePackage(
            dataEntries = listOf("files/definitely_unknown" to "x".toByteArray()),
            manifestText = BackupFormat.encode(
                BackupManifest(
                    format = BackupFormat.SUPPORTED_FORMAT,
                    createdAt = "2026-11-22T15:30:00Z",
                    entries = emptyList(),
                )
            ),
        )

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `包内存在未登记条目时整包拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        val memory = """{"items":[]}""".toByteArray()
        val manifest = manifestOf(register("files/settings", settings))
        val file = writePackage(
            listOf("files/settings" to settings, "files/memory" to memory),
            manifest,
        )

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `manifest 登记但包里没有的条目整包拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        val manifest = manifestOf(
            register("files/settings", settings),
            register("files/chat", """{"messages":[]}""".toByteArray()),
        )
        val file = writePackage(listOf("files/settings" to settings), manifest)

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `一个类别出现两次整包拒绝`() {
        val first = ByteArray(8) { 1 }
        val second = ByteArray(8) { 2 }
        val manifest = manifestOf(
            register("files/wallpaper/a.jpg", first),
            register("files/wallpaper/b.jpg", second),
        )
        val file = writePackage(
            listOf("files/wallpaper/a.jpg" to first, "files/wallpaper/b.jpg" to second),
            manifest,
        )

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `manifest 同路径重复登记整包拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        val entry = register("files/settings", settings)
        val manifestJson = BackupFormat.encode(
            BackupManifest(
                format = BackupFormat.SUPPORTED_FORMAT,
                createdAt = "2026-11-22T15:30:00Z",
                entries = listOf(entry, entry),
            )
        )
        val file = writePackage(listOf("files/settings" to settings), manifestJson)

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `manifest 的 name 与路径归属不一致整包拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        val manifest = manifestOf(
            ManifestEntry(
                name = BackupCategory.CHAT.entryName,
                schema = 1,
                bytes = settings.size.toLong(),
                path = "files/settings",
            )
        )
        val file = writePackage(listOf("files/settings" to settings), manifest)

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    @Test
    fun `manifest 超过大小上限整包拒绝`() {
        val settings = """{"values":{}}""".toByteArray()
        // 超过 MAX_MANIFEST_BYTES 的 manifest：不要求是合法 JSON，体积闸先于解析
        val oversized = "{" + " ".repeat((BackupInspector.MAX_MANIFEST_BYTES + 16).toInt()) + "}"
        val file = writePackage(listOf("files/settings" to settings), oversized)

        assertRejected(file, BackupFormat.STRUCTURE_INCOMPLETE)
    }

    // ── 文本体积闸 ────────────────────────────────────

    @Test
    fun `文本条目超过 maxBytes 时按体积闸拒绝`() {
        val content = "a".repeat(64)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val manifest = manifestOf(register("files/settings", bytes))
        val file = writePackage(listOf("files/settings" to bytes), manifest)

        BackupInspector.open(file, password).use { reader ->
            // 默认上限下可以正常读出
            assertEquals(content, reader.extractText(BackupCategory.SETTINGS))

            // 用小的 maxBytes 参数验证闸门（实际值进入文案）
            val error = runCatching { reader.extractText(BackupCategory.SETTINGS, maxBytes = 16) }
                .exceptionOrNull()
            assertTrue("应触发体积闸，实际：$error", error is ManifestRejectException)
            val message = error!!.message!!
            assertTrue("文案应提示过大：$message", message.contains("过大"))
            assertTrue("文案应带上实际上限：$message", message.contains("16"))
        }
    }

    @Test
    fun `文本体积闸的默认文案与 PRD 一致`() {
        val content = "b".repeat(1024)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val file = writePackage(
            listOf("files/settings" to bytes),
            manifestOf(register("files/settings", bytes)),
        )

        BackupInspector.open(file, password).use { reader ->
            // 紧贴上限（内容小于上限）不触发，超一位触发
            assertEquals(content, reader.extractText(BackupCategory.SETTINGS, maxBytes = bytes.size.toLong()))
            val error = runCatching {
                reader.extractText(BackupCategory.SETTINGS, maxBytes = (bytes.size - 1).toLong())
            }.exceptionOrNull()
            assertTrue(error is ManifestRejectException)
            assertTrue(error!!.message!!.contains("过大"))
        }
        // 默认 64 MiB 的文案在实现里由 maxBytes 生成；这里断言常量与 PRD 口径一致
        assertEquals(64L * 1024 * 1024, BackupInspector.MAX_TEXT_BYTES)
        assertEquals(1024L * 1024, BackupInspector.MAX_MANIFEST_BYTES)
    }

    // ── 辅助：造包 ────────────────────────────────────

    private fun simpleSettingsPackage(): File {
        val settings = """{"values":{"theme_mode":"dark"}}""".toByteArray()
        return writePackage(
            listOf("files/settings" to settings),
            manifestOf(register("files/settings", settings)),
        )
    }

    /**
     * 用 zip4j 现场造一个 AES-256 包，结构与导出器一致（条目在前、manifest 最后）。
     *
     * @param manifestText null 表示故意不写 manifest
     */
    private fun writePackage(
        dataEntries: List<Pair<String, ByteArray>>,
        manifestText: String?,
    ): File {
        val file = tempFile("backup-inspector-", ".zip")
        ZipOutputStream(file.outputStream(), password).use { out ->
            dataEntries.forEach { (path, content) -> writeEntry(out, path, content) }
            if (manifestText != null) {
                writeEntry(out, BackupFormat.MANIFEST_ENTRY, manifestText.toByteArray(Charsets.UTF_8))
            }
        }
        return file
    }

    private fun writeEntry(out: ZipOutputStream, path: String, content: ByteArray) {
        val parameters = ZipParameters().apply {
            setFileNameInZip(path)
            setEncryptFiles(true)
            setEncryptionMethod(EncryptionMethod.AES)
            setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
            setCompressionMethod(CompressionMethod.DEFLATE)
        }
        out.putNextEntry(parameters)
        out.write(content)
        out.closeEntry()
    }

    private fun manifestOf(
        vararg entries: ManifestEntry,
        format: Int = BackupFormat.SUPPORTED_FORMAT,
    ): String = BackupFormat.encode(
        BackupManifest(
            format = format,
            createdAt = "2026-11-22T15:30:00Z",
            appVersion = "1.10.0",
            entries = entries.toList(),
        )
    )

    /** 造一个登记正确的 manifest 条目（name/schema/bytes 都取自内容与路径归属）。 */
    private fun register(
        path: String,
        content: ByteArray,
        schema: Int = 1,
    ): ManifestEntry {
        val category = requireNotNull(BackupCategory.fromPath(path)) { "测试路径无法归入类别：$path" }
        return ManifestEntry(
            name = category.entryName,
            schema = schema,
            bytes = content.size.toLong(),
            path = path,
        )
    }

    private fun assertRejected(file: File, expectedMessage: String? = null): ManifestRejectException {
        val error = runCatching { BackupInspector.open(file, password) }.exceptionOrNull()
        assertTrue("应以 ManifestRejectException 拒绝，实际：$error", error is ManifestRejectException)
        if (expectedMessage != null) {
            assertEquals(expectedMessage, error!!.message)
        }
        return error as ManifestRejectException
    }

    private fun tempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix).also { tempFiles += it }
}
