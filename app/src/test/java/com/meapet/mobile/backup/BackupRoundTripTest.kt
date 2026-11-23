package com.meapet.mobile.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.chat.compaction.InMemoryCompactionStore
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.settings.SettingsKeys
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 全链路往返：真实采集 → AES-256 加密 zip 写出 → 校验读包 → 按类别导入。
 *
 * 用内存替身代替 SQLite / DataStore（真实 SQLite 事务语义另有
 * `SqliteMemoryStoreTest` / `SqliteConversationRepositoryTest` 覆盖），
 * 这里验证的是**数据在包内往返后语义不变**，以及口令、壁纸路径、压缩游标等跨类别联动。
 */
@Suppress("NonAsciiCharacters")
class BackupRoundTripTest {

    private val password = "round-trip-1234".toCharArray()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("backup-roundtrip").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        password.fill('\u0000')
    }

    @Test
    fun `全类别导出导入后业务数据一致`() = runTest {
        val sourceDir = File(root, "source").apply { mkdirs() }
        val wallpaper = File(sourceDir, "wallpaper").apply { mkdirs() }
            .resolve("1758123456_wallpaper.jpg")
            .apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33)) }

        val sourceSettings = FakeSettingsSink().apply {
            seed { prefs ->
                prefs[stringPreferencesKey(SettingsKeys.THEME_MODE)] = "dark"
                prefs[booleanPreferencesKey(SettingsKeys.ENABLE_MEMORY)] = false
                prefs[doublePreferencesKey(SettingsKeys.TEMPERATURE)] = 0.3
                prefs[stringPreferencesKey(SettingsKeys.API_URL)] = "https://backup.test/v1"
                prefs[stringPreferencesKey(SettingsKeys.WALLPAPER_PATH)] = wallpaper.absolutePath
                prefs[stringPreferencesKey(SettingsKeys.API_KEY)] = "sk-roundtrip"
            }
        }
        val sourceChat = FakeConversationRepository().apply {
            seed(
                testMessage("m1"),
                testMessage("m2", role = ChatRole.assistant),
            )
        }
        val sourceMemory = FakeMemoryStore().apply {
            save(testMemory("mem_1", type = MemoryType.FACTUAL))
            setExchangeCount(5)
        }
        val sourceCompaction = InMemoryCompactionStore(
            CompactionState(
                summary = "前面聊了炸鸡",
                coveredToMessageId = "m2",
                coveredCount = 2,
                updatedAt = 99L,
            )
        )
        val sources = BackupSources(
            settings = sourceSettings,
            conversationRepository = sourceChat,
            memoryStore = sourceMemory,
            compactionStore = sourceCompaction,
            filesDir = sourceDir,
            appVersion = "1.10.0-roundtrip",
        )

        // ── 导出 ──
        val collected = BackupCollector(sources)
            .collect(BackupCategory.entries.toSet(), includeApiKeys = true)
        assertEquals(BackupCategory.entries.size, collected.items.size)
        assertEquals(BackupFormat.SUPPORTED_FORMAT, collected.manifest.format)
        assertEquals("1.10.0-roundtrip", collected.manifest.appVersion)
        assertEquals(
            BackupCategory.entries.map { it.entryName }.toSet(),
            collected.manifest.entries.map { it.name }.toSet(),
        )

        val zip = File(root, "export.zip")
        zip.outputStream().use { sink ->
            BackupExporter.export(collected.items, collected.manifest, password, sink)
        }
        assertTrue("导出包不应为空", zip.length() > 0)

        // ── 口令错误整包拒绝 ──
        assertThrows(ManifestRejectException::class.java) {
            BackupInspector.open(zip, "wrong-password".toCharArray())
        }

        // ── 读包并导入到「已有旧数据」的目标 ──
        val targetDir = File(root, "target").apply { mkdirs() }
        val targetSettings = FakeSettingsSink()
        val targetChat = FakeConversationRepository().apply { seed(testMessage("old")) }
        val targetMemory = FakeMemoryStore().apply {
            save(testMemory("mem_old"))
            setExchangeCount(1)
        }
        val targetCompaction = InMemoryCompactionStore(
            CompactionState(
                summary = "旧摘要",
                coveredToMessageId = "old",
                coveredCount = 1,
            )
        )
        val target = BackupTarget(
            conversationRepository = targetChat,
            memoryStore = targetMemory,
            compactionStore = targetCompaction,
            filesDir = targetDir,
            settings = targetSettings,
        )

        BackupInspector.open(zip, password).use { reader ->
            val outcome = BackupImporter.import(
                reader,
                BackupCategory.entries.toSet(),
                target,
            )
            assertEquals(BackupCategory.entries.toSet(), outcome.succeeded)
            assertTrue(outcome.failed.isEmpty())
        }

        // 对话：旧数据被整体覆盖，顺序与内容一致
        assertEquals(listOf("m1", "m2"), targetChat.messages().map { it.id })
        assertEquals("内容-m1", targetChat.messages()[0].content)
        assertEquals(ChatRole.assistant, targetChat.messages()[1].role)

        // 记忆：条目与摘要轮数一起恢复
        val memories = targetMemory.getAll()
        assertEquals(1, memories.size)
        assertEquals("mem_1", memories[0].id)
        assertEquals(MemoryType.FACTUAL, memories[0].type)
        assertEquals(5, targetMemory.getExchangeCount())

        // 设置：白名单值恢复，可选 Key 因勾选而进包
        assertEquals("dark", targetSettings.getString(SettingsKeys.THEME_MODE))
        assertEquals(false, targetSettings.getBoolean(SettingsKeys.ENABLE_MEMORY))
        assertEquals(0.3, targetSettings.getDouble(SettingsKeys.TEMPERATURE)!!, 1e-9)
        assertEquals("https://backup.test/v1", targetSettings.getString(SettingsKeys.API_URL))
        assertEquals("sk-roundtrip", targetSettings.getString(SettingsKeys.API_KEY))

        // 压缩状态：游标在导入的对话里存在，摘要保留
        assertEquals("前面聊了炸鸡", targetCompaction.load().summary)
        assertEquals("m2", targetCompaction.load().coveredToMessageId)

        // 壁纸：写到目标 filesDir/wallpaper/，路径被改写为本机路径，字节一致
        val restoredPath = targetSettings.getString(SettingsKeys.WALLPAPER_PATH)!!
        assertNotEquals(wallpaper.absolutePath, restoredPath)
        assertTrue(restoredPath.startsWith(File(targetDir, "wallpaper").absolutePath + File.separator))
        assertArrayEquals(wallpaper.readBytes(), File(restoredPath).readBytes())
    }

    @Test
    fun `未勾选的类别保持本机数据不变`() = runTest {
        val sourceDir = File(root, "source").apply { mkdirs() }
        val sourceSettings = FakeSettingsSink()
        val sourceChat = FakeConversationRepository().apply {
            seed(testMessage("m1"), testMessage("m2", role = ChatRole.assistant))
        }
        val sources = BackupSources(
            settings = sourceSettings,
            conversationRepository = sourceChat,
            memoryStore = FakeMemoryStore(),
            compactionStore = InMemoryCompactionStore(),
            filesDir = sourceDir,
            appVersion = "test",
        )
        val collected = BackupCollector(sources)
            .collect(setOf(BackupCategory.SETTINGS, BackupCategory.CHAT), includeApiKeys = false)
        val zip = File(root, "partial.zip")
        zip.outputStream().use { BackupExporter.export(collected.items, collected.manifest, password, it) }

        val targetChat = FakeConversationRepository().apply { seed(testMessage("keep")) }
        val targetSettings = FakeSettingsSink()
        val target = BackupTarget(
            conversationRepository = targetChat,
            memoryStore = FakeMemoryStore().apply { save(testMemory("keep_mem")) },
            compactionStore = InMemoryCompactionStore(CompactionState(summary = "keep")),
            filesDir = File(root, "target").apply { mkdirs() },
            settings = targetSettings,
        )

        BackupInspector.open(zip, password).use { reader ->
            val outcome = BackupImporter.import(reader, setOf(BackupCategory.CHAT), target)
            assertEquals(setOf(BackupCategory.CHAT), outcome.succeeded)
        }
        // 只导入对话：对话被覆盖，设置与记忆保持原样
        assertEquals(listOf("m1", "m2"), targetChat.messages().map { it.id })
        assertNull(targetSettings.getString(SettingsKeys.THEME_MODE))
        assertEquals("keep_mem", target.memoryStore.getAll().single().id)
    }
}
