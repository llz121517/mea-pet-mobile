package com.meapet.mobile.backup

import androidx.datastore.preferences.core.emptyPreferences
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.compaction.InMemoryCompactionStore
import com.meapet.mobile.memory.MemoryType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 导入前条数对比：对话 / 记忆的「本机 → 包内」数字。
 *
 * 这是强制确认弹窗里唯一的量化依据，算错会让用户误判覆盖规模，
 * 所以单独覆盖：包内条数取自条目 JSON、本机条数取自仓库/存储、非条数类别不参与。
 */
@Suppress("NonAsciiCharacters")
class BackupCounterTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("backup-counter").toFile()
    }

    @Test
    fun `对话与记忆给出本机与包内条数`() = runTest {
        val reader = FakeBackupContentReader(
            contents = backupContentsOf(
                BackupCategory.CHAT,
                BackupCategory.MEMORY,
                BackupCategory.SETTINGS,
            ),
            texts = mapOf(
                BackupCategory.CHAT to BackupCodec.encodeChat(
                    listOf(
                        testMessage("m1"),
                        testMessage("m2", role = ChatRole.assistant),
                        testMessage("m3"),
                    )
                ),
                BackupCategory.MEMORY to BackupCodec.encodeMemory(
                    listOf(testMemory("mem_1"), testMemory("mem_2", type = MemoryType.FACTUAL)),
                    exchangeCount = 2,
                ),
                BackupCategory.SETTINGS to SettingsWhitelist.encode(emptyPreferences(), false),
            ),
        )
        val target = targetWith(
            chat = listOf(testMessage("old_1")),
            memories = listOf(testMemory("mem_old")),
        )

        val counts = BackupCounter.compare(reader, target)

        assertEquals(3, counts.getValue(BackupCategory.CHAT).inPackage)
        assertEquals(1, counts.getValue(BackupCategory.CHAT).local)
        assertEquals(2, counts.getValue(BackupCategory.MEMORY).inPackage)
        assertEquals(1, counts.getValue(BackupCategory.MEMORY).local)
        // 设置不是条数概念，不参与对比
        assertNull(counts[BackupCategory.SETTINGS])
        assertEquals(2, counts.size)
    }

    @Test
    fun `空库与空包都给出 0`() = runTest {
        val reader = FakeBackupContentReader(
            contents = backupContentsOf(BackupCategory.CHAT, BackupCategory.MEMORY),
            texts = mapOf(
                BackupCategory.CHAT to BackupCodec.encodeChat(emptyList()),
                BackupCategory.MEMORY to BackupCodec.encodeMemory(emptyList(), 0),
            ),
        )
        val counts = BackupCounter.compare(reader, targetWith(chat = emptyList(), memories = emptyList()))

        assertEquals(ImportCount(inPackage = 0, local = 0), counts.getValue(BackupCategory.CHAT))
        assertEquals(ImportCount(inPackage = 0, local = 0), counts.getValue(BackupCategory.MEMORY))
    }

    @Test
    fun `包内没有的类别与未勾选的类别不参与对比`() = runTest {
        val reader = FakeBackupContentReader(
            contents = backupContentsOf(BackupCategory.CHAT),
            texts = mapOf(BackupCategory.CHAT to BackupCodec.encodeChat(listOf(testMessage("m1")))),
        )
        val target = targetWith(chat = listOf(testMessage("old")), memories = emptyList())

        // 包内只有 chat：即使勾选了 memory 也没有对比项
        val onlyChat = BackupCounter.compare(reader, target)
        assertEquals(setOf(BackupCategory.CHAT), onlyChat.keys)

        // 显式只勾 memory（包内不存在）→ 空结果
        val none = BackupCounter.compare(reader, target, selected = setOf(BackupCategory.MEMORY))
        assertTrue(none.isEmpty())
    }

    @Test
    fun `条目无法解析时跳过该类别而不抛异常`() = runTest {
        val reader = FakeBackupContentReader(
            contents = backupContentsOf(BackupCategory.CHAT, BackupCategory.MEMORY),
            texts = mapOf(
                // chat 条目损坏：条数对比跳过它，memory 仍正常
                BackupCategory.CHAT to "{broken",
                BackupCategory.MEMORY to BackupCodec.encodeMemory(listOf(testMemory("mem_1")), 0),
            ),
        )
        val counts = BackupCounter.compare(reader, targetWith(chat = emptyList(), memories = emptyList()))

        assertNull(counts[BackupCategory.CHAT])
        assertEquals(1, counts.getValue(BackupCategory.MEMORY).inPackage)
    }

    private suspend fun targetWith(
        chat: List<com.meapet.mobile.chat.ChatMessage>,
        memories: List<com.meapet.mobile.memory.MemoryItem>,
    ): BackupTarget = BackupTarget(
        conversationRepository = FakeConversationRepository().apply {
            if (chat.isNotEmpty()) seed(*chat.toTypedArray())
        },
        memoryStore = FakeMemoryStore().apply { memories.forEach { save(it) } },
        compactionStore = InMemoryCompactionStore(),
        filesDir = File(root, "files").apply { mkdirs() },
        settings = FakeSettingsSink(),
    )
}
