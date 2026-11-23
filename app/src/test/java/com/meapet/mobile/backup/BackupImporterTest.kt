package com.meapet.mobile.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.chat.compaction.InMemoryCompactionStore
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.settings.SettingsKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 备份导入（BackupImporter）单测：按类别整体覆盖、单类别失败隔离、
 * 压缩游标校验、壁纸安全化与未勾选类别不动。
 *
 * 纯 JVM：设置用 [FakeSettingsSink]（DataStore 的 `preferencesOf` 同族 API，无需
 * Robolectric），会话用 InMemory 实现，记忆/读取器用本目录的替身。
 */
class BackupImporterTest {

    private lateinit var tempDir: File
    private lateinit var filesDir: File
    private lateinit var settings: FakeSettingsSink
    private lateinit var conversations: FakeConversationRepository
    private lateinit var memories: FakeMemoryStore
    private lateinit var compaction: InMemoryCompactionStore
    private lateinit var target: BackupTarget

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("meapet-backup-importer").toFile()
        filesDir = File(tempDir, "files").apply { mkdirs() }
        settings = FakeSettingsSink()
        conversations = FakeConversationRepository()
        memories = FakeMemoryStore()
        compaction = InMemoryCompactionStore()
        target = BackupTarget(
            conversationRepository = conversations,
            memoryStore = memories,
            compactionStore = compaction,
            filesDir = filesDir,
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun reader(
        categories: List<BackupCategory>,
        texts: Map<BackupCategory, String> = emptyMap(),
        files: Map<BackupCategory, ByteArray> = emptyMap(),
        wallpaperPath: String = "files/wallpaper/wallpaper.jpg",
    ): FakeBackupContentReader = FakeBackupContentReader(
        contents = backupContentsOf(*categories.toTypedArray(), wallpaperPath = wallpaperPath),
        texts = texts,
        files = files,
    )

    private suspend fun runImport(
        reader: FakeBackupContentReader,
        selected: Set<BackupCategory>,
    ): ImportOutcome = BackupImporter.import(reader, selected, target)

    // ── 对话 ──────────────────────────────────────────

    @Test
    fun `导入对话会整体覆盖已有历史`() = runBlocking {
        conversations.seed(testMessage("old-1"), testMessage("old-2"))
        val restored = listOf(
            testMessage("new-1"),
            testMessage("new-2", role = ChatRole.assistant),
            testMessage("new-3"),
        )
        val input = reader(
            texts = mapOf(BackupCategory.CHAT to BackupCodec.encodeChat(restored)),
            categories = listOf(BackupCategory.CHAT),
        )

        val outcome = runImport(input, setOf(BackupCategory.CHAT))

        assertEquals(setOf(BackupCategory.CHAT), outcome.succeeded)
        assertFalse(outcome.hasFailure)
        assertEquals(
            restored.map { it.id },
            conversations.messages().map { it.id },
        )
    }

    @Test
    fun `导入对话到空库会写入全部消息`() = runBlocking {
        val restored = listOf(testMessage("a"), testMessage("b"))
        val input = reader(
            texts = mapOf(BackupCategory.CHAT to BackupCodec.encodeChat(restored)),
            categories = listOf(BackupCategory.CHAT),
        )

        val outcome = runImport(input, setOf(BackupCategory.CHAT))

        assertEquals(setOf(BackupCategory.CHAT), outcome.succeeded)
        assertEquals(listOf("a", "b"), conversations.messages().map { it.id })
    }

    // ── 记忆 ──────────────────────────────────────────

    @Test
    fun `导入记忆会覆盖记忆条目与轮数计数`() = runBlocking {
        memories.saveAll(listOf(testMemory("mem_old", "本机旧记忆")))
        memories.setExchangeCount(9)
        val restored = listOf(
            testMemory("mem_a", "新记忆A"),
            testMemory("mem_b", "新记忆B", type = MemoryType.LONG_TERM),
        )
        val input = reader(
            texts = mapOf(
                BackupCategory.MEMORY to BackupCodec.encodeMemory(restored, exchangeCount = 3),
            ),
            categories = listOf(BackupCategory.MEMORY),
        )

        val outcome = runImport(input, setOf(BackupCategory.MEMORY))

        assertEquals(setOf(BackupCategory.MEMORY), outcome.succeeded)
        assertEquals(listOf("mem_a", "mem_b"), memories.getAll().map { it.id })
        assertEquals(3, memories.getExchangeCount())
        // 走的是 replaceAll（单事务接口），不是 clear + saveAll 多次落盘。
        assertEquals(1, memories.replaceAllCalls.size)
        assertEquals(3, memories.replaceAllCalls.single().second)
    }

    @Test
    fun `导入记忆到空库会写入条目与轮数`() = runBlocking {
        val restored = listOf(testMemory("mem_a", "记忆A"))
        val input = reader(
            texts = mapOf(
                BackupCategory.MEMORY to BackupCodec.encodeMemory(restored, exchangeCount = 1),
            ),
            categories = listOf(BackupCategory.MEMORY),
        )

        val outcome = runImport(input, setOf(BackupCategory.MEMORY))

        assertEquals(setOf(BackupCategory.MEMORY), outcome.succeeded)
        assertEquals(listOf("mem_a"), memories.getAll().map { it.id })
        assertEquals(1, memories.getExchangeCount())
    }

    // ── 设置 ──────────────────────────────────────────

    @Test
    fun `导入设置会写入白名单值并保留包里没有的键`() = runBlocking {
        settings.seed {
            it[stringPreferencesKey(SettingsKeys.THEME_MODE)] = "light"
            it[stringPreferencesKey(SettingsKeys.API_KEY)] = "local-secret"
            it[booleanPreferencesKey(SettingsKeys.FIRST_LAUNCH)] = true
        }
        val payload = """
            {"values":{
              "theme_mode":"dark",
              "temperature":0.3,
              "wallpaper_blur":0.25,
              "unknown_future_key":"应被忽略"
            }}
        """.trimIndent()
        val input = reader(
            texts = mapOf(BackupCategory.SETTINGS to payload),
            categories = listOf(BackupCategory.SETTINGS),
        )

        val outcome = runImport(input, setOf(BackupCategory.SETTINGS))

        assertEquals(setOf(BackupCategory.SETTINGS), outcome.succeeded)
        assertEquals("dark", settings.getString(SettingsKeys.THEME_MODE))
        assertEquals(0.3, settings.getDouble(SettingsKeys.TEMPERATURE)!!, 1e-9)
        assertEquals(0.25, settings.getDouble(SettingsKeys.WALLPAPER_BLUR)!!, 1e-9)
        // 包里没有的键保持本机原值（导入只覆盖包里实际存在的白名单键）。
        assertEquals("local-secret", settings.getString(SettingsKeys.API_KEY))
        assertEquals(true, settings.getBoolean(SettingsKeys.FIRST_LAUNCH))
        // 未知键被忽略，不会写进设置。
        assertEquals(1, settings.applied.size)
        assertEquals(3, settings.applied.single().size)
    }

    @Test
    fun `导入设置到空设置库会写入白名单值`() = runBlocking {
        val input = reader(
            texts = mapOf(BackupCategory.SETTINGS to """{"values":{"theme_mode":"dark"}}"""),
            categories = listOf(BackupCategory.SETTINGS),
        )

        val outcome = runImport(input, setOf(BackupCategory.SETTINGS))

        assertEquals(setOf(BackupCategory.SETTINGS), outcome.succeeded)
        assertEquals("dark", settings.getString(SettingsKeys.THEME_MODE))
    }

    // ── 失败隔离 ──────────────────────────────────────

    @Test
    fun `单个类别失败不影响其它类别且失败原因不泄露正文`() = runBlocking {
        conversations.seed(testMessage("old"))
        conversations.failReplaceAll = true
        val input = reader(
            texts = mapOf(
                BackupCategory.SETTINGS to """{"values":{"theme_mode":"dark"}}""",
                BackupCategory.CHAT to BackupCodec.encodeChat(listOf(testMessage("new"))),
                BackupCategory.MEMORY to BackupCodec.encodeMemory(
                    listOf(testMemory("mem_a")),
                    exchangeCount = 2,
                ),
            ),
            categories = listOf(
                BackupCategory.SETTINGS,
                BackupCategory.CHAT,
                BackupCategory.MEMORY,
            ),
        )

        val outcome = runImport(
            input,
            setOf(BackupCategory.SETTINGS, BackupCategory.CHAT, BackupCategory.MEMORY),
        )

        assertEquals(
            setOf(BackupCategory.SETTINGS, BackupCategory.MEMORY),
            outcome.succeeded,
        )
        assertEquals(setOf(BackupCategory.CHAT), outcome.failed.keys)
        assertTrue(outcome.hasFailure)
        // 失败原因简短，且不透传底层异常消息（其中含正文占位信息）。
        val reason = outcome.failed.getValue(BackupCategory.CHAT)
        assertTrue(reason.isNotEmpty())
        assertFalse(reason.contains("secret"))
        assertFalse(reason.contains("new"))
        // 其它类别照常落地。
        assertEquals("dark", settings.getString(SettingsKeys.THEME_MODE))
        assertEquals(listOf("mem_a"), memories.getAll().map { it.id })
        // 对话类别失败后保持本机原数据。
        assertEquals(listOf("old"), conversations.messages().map { it.id })
    }

    // ── 压缩状态 ──────────────────────────────────────

    @Test
    fun `压缩摘要游标在目标对话中不存在时重置为空状态`() = runBlocking {
        compaction.save(
            CompactionState(
                summary = "本机旧摘要",
                coveredToMessageId = "local-only",
                coveredCount = 2,
                updatedAt = 1L,
            )
        )
        val restored = CompactionState(
            summary = "备份摘要",
            coveredToMessageId = "missing-id",
            coveredCount = 5,
            updatedAt = 9L,
        )
        val input = reader(
            texts = mapOf(BackupCategory.COMPACTION to BackupCodec.encodeCompaction(restored)),
            categories = listOf(BackupCategory.COMPACTION),
        )

        val outcome = runImport(input, setOf(BackupCategory.COMPACTION))

        // 游标失效不是失败：类别仍算成功，只是状态被重置。
        assertEquals(setOf(BackupCategory.COMPACTION), outcome.succeeded)
        assertFalse(outcome.hasFailure)
        val state = compaction.load()
        assertFalse(state.hasSummary)
        assertNull(state.coveredToMessageId)
        assertEquals(0, state.coveredCount)
    }

    @Test
    fun `压缩摘要游标有效时原样落地`() = runBlocking {
        conversations.seed(testMessage("m1"))
        val restored = CompactionState(
            summary = "摘要A",
            coveredToMessageId = "m1",
            coveredCount = 3,
            updatedAt = 7L,
        )
        val input = reader(
            texts = mapOf(BackupCategory.COMPACTION to BackupCodec.encodeCompaction(restored)),
            categories = listOf(BackupCategory.COMPACTION),
        )

        val outcome = runImport(input, setOf(BackupCategory.COMPACTION))

        assertEquals(setOf(BackupCategory.COMPACTION), outcome.succeeded)
        assertEquals(restored, compaction.load())
    }

    @Test
    fun `压缩状态在对话之后落地以校验导入的游标`() = runBlocking {
        conversations.seed(testMessage("old-only"))
        val imported = testMessage("backup-msg-1")
        val restored = CompactionState(
            summary = "摘要",
            coveredToMessageId = imported.id,
            coveredCount = 1,
            updatedAt = 2L,
        )
        val input = reader(
            texts = mapOf(
                BackupCategory.CHAT to BackupCodec.encodeChat(listOf(imported)),
                BackupCategory.COMPACTION to BackupCodec.encodeCompaction(restored),
            ),
            categories = listOf(BackupCategory.CHAT, BackupCategory.COMPACTION),
        )

        // 故意让选中的 Set 迭代顺序与固定落地顺序相反：游标只有「先对话后压缩」才有效。
        val outcome = runImport(input, setOf(BackupCategory.COMPACTION, BackupCategory.CHAT))

        assertEquals(
            listOf(BackupCategory.CHAT, BackupCategory.COMPACTION),
            outcome.succeeded.toList(),
        )
        assertEquals("摘要", compaction.load().summary)
        assertEquals(imported.id, compaction.load().coveredToMessageId)
    }

    // ── 壁纸 ──────────────────────────────────────────

    @Test
    fun `壁纸按安全文件名落地并更新设置路径`() = runBlocking {
        val input = reader(
            files = mapOf(BackupCategory.WALLPAPER to byteArrayOf(1, 2, 3)),
            categories = listOf(BackupCategory.WALLPAPER),
            // Inspector 会拒绝 `..` 段；这里刻意绕过校验，验证导入侧自身也不越界。
            wallpaperPath = "files/wallpaper/../../private/holiday photo(1).png",
        )

        val outcome = runImport(input, setOf(BackupCategory.WALLPAPER))

        assertEquals(setOf(BackupCategory.WALLPAPER), outcome.succeeded)
        val dir = File(filesDir, "wallpaper")
        val saved = File(dir, "holidayphoto1.png")
        assertTrue(saved.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), saved.readBytes())
        assertEquals(saved.absolutePath, settings.getString(SettingsKeys.WALLPAPER_PATH))
        assertEquals(dir.canonicalFile, saved.canonicalFile.parentFile)
        // 越界路径没有在 wallpaper 目录之外创建任何东西。
        assertFalse(File(tempDir, "private").exists())
    }

    @Test
    fun `导入壁纸后清理同目录旧文件`() = runBlocking {
        val dir = File(filesDir, "wallpaper").apply { mkdirs() }
        val oldJpg = File(dir, "old-1.jpg").apply { writeBytes(byteArrayOf(9)) }
        val oldPng = File(dir, "old-2.png").apply { writeBytes(byteArrayOf(8)) }
        val outside = File(filesDir, "notes.txt").apply { writeText("keep") }
        val input = reader(
            files = mapOf(BackupCategory.WALLPAPER to byteArrayOf(4, 5)),
            categories = listOf(BackupCategory.WALLPAPER),
            wallpaperPath = "files/wallpaper/new.png",
        )

        val outcome = runImport(input, setOf(BackupCategory.WALLPAPER))

        assertEquals(setOf(BackupCategory.WALLPAPER), outcome.succeeded)
        assertTrue(File(dir, "new.png").isFile)
        assertFalse(oldJpg.exists())
        assertFalse(oldPng.exists())
        // 只清理壁纸目录内的旧文件，不影响其它文件。
        assertTrue(outside.isFile)
    }

    @Test
    fun `壁纸写出失败会删除半成品且不更新设置`() = runBlocking {
        settings.seed {
            it[stringPreferencesKey(SettingsKeys.WALLPAPER_PATH)] = "old-path"
        }
        val input = FakeBackupContentReader(
            contents = backupContentsOf(
                BackupCategory.WALLPAPER,
                wallpaperPath = "files/wallpaper/broken.png",
            ),
            files = mapOf(BackupCategory.WALLPAPER to byteArrayOf(1)),
        )
        // 模拟写到一半失败：文件已创建但内容不完整。
        input.onExtractToFile = { _, file ->
            file.writeBytes(byteArrayOf(7, 7))
            throw IOException("磁盘空间不足（模拟）")
        }

        val outcome = runImport(input, setOf(BackupCategory.WALLPAPER))

        assertEquals(setOf(BackupCategory.WALLPAPER), outcome.failed.keys)
        assertFalse(File(File(filesDir, "wallpaper"), "broken.png").exists())
        assertEquals("old-path", settings.getString(SettingsKeys.WALLPAPER_PATH))
        assertEquals("写入失败，请检查可用空间", outcome.failed.getValue(BackupCategory.WALLPAPER))
    }

    // ── 选择与包内容 ──────────────────────────────────

    @Test
    fun `未勾选的类别不会被读取或覆盖`() = runBlocking {
        settings.seed {
            it[stringPreferencesKey(SettingsKeys.THEME_MODE)] = "light"
        }
        conversations.seed(testMessage("old"))
        memories.saveAll(listOf(testMemory("mem_old")))
        val input = reader(
            texts = mapOf(
                BackupCategory.SETTINGS to """{"values":{"theme_mode":"dark"}}""",
                BackupCategory.CHAT to BackupCodec.encodeChat(listOf(testMessage("new"))),
                BackupCategory.MEMORY to BackupCodec.encodeMemory(
                    listOf(testMemory("mem_new")),
                    exchangeCount = 1,
                ),
            ),
            categories = listOf(
                BackupCategory.SETTINGS,
                BackupCategory.CHAT,
                BackupCategory.MEMORY,
            ),
        )

        val outcome = runImport(input, setOf(BackupCategory.CHAT))

        assertEquals(setOf(BackupCategory.CHAT), outcome.succeeded)
        assertEquals(listOf(BackupCategory.CHAT), input.extracted)
        // 设置与记忆完全没被动过（连读取都没发生）。
        assertTrue(settings.applied.isEmpty())
        assertEquals("light", settings.getString(SettingsKeys.THEME_MODE))
        assertTrue(memories.replaceAllCalls.isEmpty())
        assertEquals(listOf("mem_old"), memories.getAll().map { it.id })
        // 勾选的对话类别正常覆盖。
        assertEquals(listOf("new"), conversations.messages().map { it.id })
    }

    @Test
    fun `包内不存在的类别被静默跳过`() = runBlocking {
        val input = reader(
            texts = mapOf(BackupCategory.SETTINGS to """{"values":{"theme_mode":"dark"}}"""),
            categories = listOf(BackupCategory.SETTINGS),
        )

        val outcome = runImport(
            input,
            setOf(BackupCategory.SETTINGS, BackupCategory.MEMORY),
        )

        assertEquals(setOf(BackupCategory.SETTINGS), outcome.succeeded)
        assertTrue(outcome.failed.isEmpty())
        // 包里没有的记忆类别不被当成失败，也不会去读/写目标存储。
        assertTrue(input.extracted.none { it == BackupCategory.MEMORY })
        assertTrue(memories.replaceAllCalls.isEmpty())
    }

    @Test
    fun `全类别成功时按固定顺序报告成功类别`() = runBlocking {
        conversations.seed(testMessage("m1"))
        val input = reader(
            texts = mapOf(
                BackupCategory.SETTINGS to """{"values":{"theme_mode":"dark"}}""",
                BackupCategory.CHAT to BackupCodec.encodeChat(listOf(testMessage("m1"))),
                BackupCategory.MEMORY to BackupCodec.encodeMemory(
                    listOf(testMemory("mem_a")),
                    exchangeCount = 3,
                ),
                BackupCategory.COMPACTION to BackupCodec.encodeCompaction(
                    CompactionState(
                        summary = "摘要",
                        coveredToMessageId = "m1",
                        coveredCount = 1,
                        updatedAt = 2L,
                    )
                ),
            ),
            files = mapOf(BackupCategory.WALLPAPER to byteArrayOf(1)),
            categories = BackupCategory.entries,
            wallpaperPath = "files/wallpaper/wallpaper.png",
        )

        val outcome = runImport(input, BackupCategory.entries.toSet())

        assertFalse(outcome.hasFailure)
        assertEquals(BackupCategory.entries.toList(), outcome.succeeded.toList())
        assertEquals("摘要", compaction.load().summary)
        assertTrue(File(File(filesDir, "wallpaper"), "wallpaper.png").isFile)
    }
}

/**
 * 备份采集（BackupCollector）单测：可用类别判定、只输出勾选类别、manifest 与壁纸路径。
 */
class BackupCollectorTest {

    private lateinit var tempDir: File
    private lateinit var filesDir: File
    private lateinit var settings: FakeSettingsSink
    private lateinit var conversations: FakeConversationRepository
    private lateinit var memories: FakeMemoryStore
    private lateinit var compaction: InMemoryCompactionStore
    private lateinit var collector: BackupCollector

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("meapet-backup-collector").toFile()
        filesDir = File(tempDir, "files").apply { mkdirs() }
        settings = FakeSettingsSink()
        conversations = FakeConversationRepository()
        memories = FakeMemoryStore()
        compaction = InMemoryCompactionStore()
        collector = BackupCollector(
            BackupSources(
                settings = settings,
                conversationRepository = conversations,
                memoryStore = memories,
                compactionStore = compaction,
                filesDir = filesDir,
                appVersion = "1.10.0-test",
            )
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun wallpaperFile(name: String = "bg.png"): File {
        val file = File(File(filesDir, "wallpaper").apply { mkdirs() }, name)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        return file
    }

    private fun pointWallpaperSettingAt(path: String) {
        settings.seed { it[stringPreferencesKey(SettingsKeys.WALLPAPER_PATH)] = path }
    }

    @Test
    fun `可用类别默认只有设置`() = runBlocking {
        assertEquals(setOf(BackupCategory.SETTINGS), collector.availableCategories())
    }

    @Test
    fun `对话记忆压缩摘要与壁纸有内容时可用类别按枚举顺序返回`() = runBlocking {
        conversations.seed(testMessage("m1"))
        memories.saveAll(listOf(testMemory("mem_a")))
        compaction.save(CompactionState(summary = "摘要"))
        pointWallpaperSettingAt(wallpaperFile().absolutePath)

        val available = collector.availableCategories()

        assertEquals(BackupCategory.entries.toList(), available.toList())
    }

    @Test
    fun `壁纸路径指向已不存在的文件时不算可用类别`() = runBlocking {
        pointWallpaperSettingAt(File(filesDir, "wallpaper/missing.png").absolutePath)

        assertEquals(setOf(BackupCategory.SETTINGS), collector.availableCategories())
    }

    @Test
    fun `采集只输出勾选的类别并登记manifest`() = runBlocking {
        conversations.seed(testMessage("m1"), testMessage("m2"))
        memories.saveAll(listOf(testMemory("mem_a")))
        val collected = collector.collect(setOf(BackupCategory.CHAT), includeApiKeys = false)

        assertEquals(listOf("files/chat"), collected.items.map { it.zipPath })
        val item = collected.items.single() as BackupItem.Text
        val entry = collected.manifest.entries.single()
        assertEquals("chat", entry.name)
        assertEquals(BackupFormat.schemaFor(BackupCategory.CHAT), entry.schema)
        assertEquals(item.bytes, entry.bytes)
        assertEquals("files/chat", entry.path)
        assertEquals(BackupFormat.SUPPORTED_FORMAT, collected.manifest.format)
        assertEquals("1.10.0-test", collected.manifest.appVersion)
        assertTrue(collected.manifest.createdAt.endsWith("Z"))
        // 未勾选的记忆类别不进 manifest。
        assertFalse(collected.manifest.entries.any { it.name == "memory" })
    }

    @Test
    fun `采集对话会带上完整历史`() = runBlocking {
        conversations.seed(testMessage("m1"), testMessage("m2"), testMessage("m3"))

        val collected = collector.collect(setOf(BackupCategory.CHAT), includeApiKeys = false)

        val messages = BackupCodec.decodeChat((collected.items.single() as BackupItem.Text).content)
        assertEquals(listOf("m1", "m2", "m3"), messages.map { it.id })
    }

    @Test
    fun `采集设置默认不含API Key勾选后才包含`() = runBlocking {
        settings.seed {
            it[stringPreferencesKey(SettingsKeys.API_KEY)] = "secret-key"
            it[stringPreferencesKey(SettingsKeys.THEME_MODE)] = "dark"
        }

        val withoutKeys = collector.collect(setOf(BackupCategory.SETTINGS), includeApiKeys = false)
        val withoutJson = (withoutKeys.items.single() as BackupItem.Text).content
        assertFalse(withoutJson.contains("secret-key"))
        assertTrue(withoutJson.contains("theme_mode"))

        val withKeys = collector.collect(setOf(BackupCategory.SETTINGS), includeApiKeys = true)
        assertTrue((withKeys.items.single() as BackupItem.Text).content.contains("secret-key"))
    }

    @Test
    fun `采集壁纸使用安全文件名输出文件源`() = runBlocking {
        val file = wallpaperFile("holiday photo.png")
        pointWallpaperSettingAt(file.absolutePath)

        val collected = collector.collect(setOf(BackupCategory.WALLPAPER), includeApiKeys = false)

        val item = collected.items.single() as BackupItem.FileSource
        assertEquals("files/wallpaper/holidayphoto.png", item.zipPath)
        assertEquals(file, item.file)
        assertEquals(4L, item.bytes)
        val entry = collected.manifest.entries.single()
        assertEquals("wallpaper", entry.name)
        assertEquals("files/wallpaper/holidayphoto.png", entry.path)
        assertEquals(4L, entry.bytes)
    }

    @Test
    fun `采集空勾选输出空包`() = runBlocking {
        val collected = collector.collect(emptySet(), includeApiKeys = false)

        assertTrue(collected.items.isEmpty())
        assertTrue(collected.manifest.entries.isEmpty())
        assertEquals(BackupFormat.SUPPORTED_FORMAT, collected.manifest.format)
    }
}
