package com.meapet.mobile.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [BackupService] 的纯 JVM 单测。
 *
 * `BackupService` 实例本身依赖 Android Context（真实 SAF），纯 JVM 下不实例化；
 * 这里只测不依赖 Context 的两部分：文件名的 UTC 紧凑时间戳、以及进页清扫的纯函数
 * [sweepStagingDir]（目录保留、内容删净）。
 */
@Suppress("NonAsciiCharacters")
class BackupServiceTest {

    private val utcCompactFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    @Test
    fun `utcCompactNow 输出 yyyyMMddTHHmmssZ 格式且为当前 UTC 时间`() {
        val text = BackupService.utcCompactNow()

        assertTrue("实际输出：$text", text.matches(Regex("\\d{8}T\\d{6}Z")))

        // 可被同名 pattern 解析，且与当前 UTC 时间相差不超过 60 秒（避开秒边界抖动，不做字符串相等）
        val parsed = Instant.from(utcCompactFormatter.parse(text))
        val deltaSeconds = kotlin.math.abs(parsed.epochSecond - Instant.now().epochSecond)
        assertTrue("时间戳与当前 UTC 相差 ${deltaSeconds}s：$text", deltaSeconds <= 60)
    }

    @Test
    fun `utcCompactNow 生成的文件名可用作默认导出名`() {
        val name = BackupExporter.defaultFileName(BackupService.utcCompactNow())
        assertTrue("实际文件名：$name", name.matches(Regex("\\d{8}T\\d{6}Z_MeaPet-Android\\.zip")))
    }

    @Test
    fun `暂存目录名为 backup`() {
        assertEquals("backup", BackupService.STAGING_DIR_NAME)
    }

    @Test
    fun `清扫暂存目录删除全部残留文件并保留目录本身`() {
        val dir = createTempDir()
        try {
            File(dir, "import_1.zip").writeText("stale")
            File(dir, "import_2.zip").writeText("stale")
            File(dir, "nested").mkdirs()
            File(dir, "nested/old.zip").writeText("stale")
            assertTrue(File(dir, "import_1.zip").exists())

            sweepStagingDir(dir)

            assertTrue("目录本身应保留", dir.isDirectory)
            assertEquals("暂存目录应被清空", 0, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `清扫不存在的目录不抛异常`() {
        val base = createTempDir()
        try {
            sweepStagingDir(File(base, "not-created"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `清扫空目录是幂等的`() {
        val dir = createTempDir()
        try {
            sweepStagingDir(dir)
            sweepStagingDir(dir)
            assertTrue(dir.isDirectory)
            assertEquals(0, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File = Files.createTempDirectory("meapet-backup-staging-").toFile()
}
