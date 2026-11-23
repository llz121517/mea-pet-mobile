package com.meapet.mobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * manifest 编解码、两级版本校验与包内路径归属。
 *
 * 这些规则是「整包拒绝」的第一道闸：路径归属错误或版本不符必须在解包前被挡住。
 */
@Suppress("NonAsciiCharacters")
class BackupFormatTest {

    private fun manifest(format: Int = BackupFormat.SUPPORTED_FORMAT): BackupManifest =
        BackupManifest(
            format = format,
            createdAt = "2026-11-22T15:30:00Z",
            appVersion = "1.10.0",
            entries = listOf(
                ManifestEntry(
                    name = BackupCategory.SETTINGS.entryName,
                    schema = BackupFormat.schemaFor(BackupCategory.SETTINGS),
                    bytes = 1234,
                    path = "files/settings",
                ),
            ),
        )

    @Test
    fun `manifest 往返保留全部字段`() {
        val decoded = BackupFormat.decode(BackupFormat.encode(manifest()))
        assertEquals(BackupFormat.SUPPORTED_FORMAT, decoded.format)
        assertEquals("2026-11-22T15:30:00Z", decoded.createdAt)
        assertEquals("1.10.0", decoded.appVersion)
        assertEquals(1, decoded.entries.size)
        assertEquals("settings", decoded.entries[0].name)
        assertEquals(1234L, decoded.entries[0].bytes)
    }

    @Test
    fun `format 不等于支持值即拒绝`() {
        val error = runCatching { BackupFormat.decode(BackupFormat.encode(manifest(format = 2))) }
            .exceptionOrNull()
        assertTrue(error is ManifestRejectException)
        assertTrue(error!!.message!!.contains("不支持的版本"))
    }

    @Test
    fun `manifest 无法解析按结构不完整拒绝`() {
        val error = runCatching { BackupFormat.decode("{ not json") }.exceptionOrNull()
        assertTrue(error is ManifestRejectException)
        assertEquals(BackupFormat.STRUCTURE_INCOMPLETE, error!!.message)
    }

    @Test
    fun `条目 schema 相等才受支持`() {
        val supported = ManifestEntry("settings", 1, 0, "files/settings")
        assertTrue(BackupFormat.entrySupported(supported))
        assertFalse(BackupFormat.entrySupported(supported.copy(schema = 2)))
        assertFalse(BackupFormat.entrySupported(supported.copy(name = "unknown_key", schema = 1)))
    }

    @Test
    fun `路径能归入已知类别`() {
        assertEquals(BackupCategory.SETTINGS, BackupCategory.fromPath("files/settings"))
        assertEquals(BackupCategory.CHAT, BackupCategory.fromPath("files/chat"))
        assertEquals(BackupCategory.MEMORY, BackupCategory.fromPath("files/memory"))
        assertEquals(BackupCategory.COMPACTION, BackupCategory.fromPath("files/compaction"))
        assertEquals(
            BackupCategory.WALLPAPER,
            BackupCategory.fromPath("files/wallpaper/1758123456_wallpaper.jpg")
        )
    }

    @Test
    fun `未知与越界路径不归属任何类别`() {
        val rejected = listOf(
            "",
            "settings",
            "files",
            "files/unknown",
            "files/wallpaper",
            "files/wallpaper/a/b.jpg",
            "files/settings/extra",
            "files/../settings",
            "/files/settings",
            "files\\settings",
            "files//settings",
            "files/./settings",
            "files/settings\u0000",
            "files/" + "a".repeat(BackupFormat.MAX_PATH_LENGTH),
        )
        rejected.forEach { path ->
            assertNull("路径应被拒绝：$path", BackupCategory.fromPath(path))
        }
    }

    @Test
    fun `类别对应固定包内路径与 schema`() {
        BackupCategory.entries.forEach { category ->
            assertEquals("files/${category.entryName}", BackupFormat.pathFor(category))
            assertEquals(1, BackupFormat.schemaFor(category))
        }
    }

    @Test
    fun `壁纸文件名安全化只留最后一段与允许字符`() {
        assertEquals("passwd", safeWallpaperFileName("../../etc/passwd"))
        assertEquals("1.jpg", safeWallpaperFileName("C:\\Users\\me\\Pictures\\壁纸 1.jpg"))
        assertEquals("wallpaper..2.jpg", safeWallpaperFileName("wallpaper..2.jpg"))
        assertEquals("wallpaper.jpg", safeWallpaperFileName(""))
        assertEquals("wallpaper.jpg", safeWallpaperFileName(".."))
        assertEquals("wallpaper.jpg", safeWallpaperFileName("..."))
        assertEquals("abc.jpg", safeWallpaperFileName("壁纸 abc.jpg"))
        assertEquals(96, safeWallpaperFileName("a".repeat(200)).length)
    }

    @Test
    fun `已知类别条目版本不受支持时给出可读文案`() {
        val message = BackupFormat.unsupportedEntryMessage(
            ManifestEntry("settings", 2, 0, "files/settings")
        )
        assertTrue(message.contains("settings"))
        assertTrue(message.contains("schema"))
    }
}
