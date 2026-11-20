package com.meapet.mobile.chat

import com.meapet.mobile.storage.InMemoryConversationRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ConversationStore + ConversationManager 持久化链路的 JVM 单元测试。
 *
 * 依赖 build.gradle.kts 中 `unitTests.isReturnDefaultValues = true`，
 * 使 android.util.Log 调用在 JVM 上返回默认值。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun msg(role: ChatRole, content: String) = ChatMessage(role = role, content = content)

    @Test
    fun persistThenLoadRoundTrip() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir, backgroundScope)

        val messages = listOf(
            msg(ChatRole.user, "你好，我叫小明 🐱"),
            msg(ChatRole.assistant, "你好小明喵~\n多行\"内容\"也要撑住"),
            msg(ChatRole.system, "[系统] 触摸事件")
        )
        store.persist(messages)

        val loaded = ConversationStore(dir, backgroundScope).load()
        assertEquals(messages, loaded)
    }

    @Test
    fun oldMemoryOpsFieldIsIgnoredAndNotWrittenBack() = runTest {
        val dir = tmp.newFolder()
        val file = File(dir, "meapet_conversation.json")
        file.writeText("""[
            {"role":"assistant","content":"保留正文 🐱","memoryOpsBlock":"legacy block",
             "id":"assistant","timestamp":37,"turnId":"turn-1",
             "rawAssistantJson":"{\"role\":\"assistant\",\"content\":\"保留正文 🐱\"}"},
            {"role":"tool","content":"工具结果","memoryOpsBlock":null,"id":"tool","timestamp":12,
             "turnId":"turn-1","toolCallId":"call-1","toolName":"search_memory"}
        ]""")
        val store = ConversationStore(dir, backgroundScope)

        val loaded = store.load()
        assertEquals(
            listOf(
                ChatMessage(
                    ChatRole.assistant, "保留正文 🐱", id = "assistant", timestamp = 37, turnId = "turn-1",
                    rawAssistantJson = """{"role":"assistant","content":"保留正文 🐱"}"""
                ),
                ChatMessage(
                    ChatRole.tool, "工具结果", id = "tool", timestamp = 12, turnId = "turn-1",
                    toolCallId = "call-1", toolName = "search_memory"
                )
            ),
            loaded
        )
        store.persist(loaded)
        val saved = Json.parseToJsonElement(file.readText()).jsonArray
        assertTrue(saved.all { "memoryOpsBlock" !in it.jsonObject })
        assertEquals(loaded, store.load())
    }

    @Test
    fun loadReturnsEmptyWhenNoFile() = runTest {
        val store = ConversationStore(tmp.newFolder(), backgroundScope)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun corruptedFileIsBackedUpAndIgnored() = runTest {
        val dir = tmp.newFolder()
        File(dir, "meapet_conversation.json").writeText("]]] 不是 JSON")

        val store = ConversationStore(dir, backgroundScope)
        assertTrue(store.load().isEmpty())
        assertTrue(File(dir, "meapet_conversation.json.corrupt").exists())
    }

    @Test
    fun isStreamingIsNotPersisted() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir, backgroundScope)
        store.persist(listOf(msg(ChatRole.assistant, "回复中").copy(isStreaming = true)))

        val loaded = ConversationStore(dir, backgroundScope).load()
        assertEquals(false, loaded.single().isStreaming)
    }

    // ── 合并写队列与同步落盘的顺序 ────────────────────

    /**
     * 复现回归：合并写队列里的**旧**快照不能在更新的同步落盘之后写入。
     *
     * 时序：writer 手里已取到旧快照（尚未拿锁）→ 同步落盘写入更新的快照 → writer
     * 拿到锁后把旧快照写下去，磁盘倒退到旧内容（丢掉最新消息）。
     */
    @Test
    fun queuedStaleSnapshotDoesNotOverwriteNewerWrite() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir, backgroundScope, StandardTestDispatcher(testScheduler))
        val oldSnapshot = listOf(msg(ChatRole.user, "第一句"))
        val newSnapshot = oldSnapshot + msg(ChatRole.assistant, "第二句")

        store.persistAsync(oldSnapshot)   // 旧快照进队列，writer 尚未被调度
        store.persist(newSnapshot)        // 同步写入更新后的快照（等价于 flush）
        runCurrent()                      // 再让排队的 writer 跑：它手里只有旧快照

        assertEquals(
            newSnapshot.map { it.content },
            store.load().map { it.content }
        )
    }

    // ── ConversationManager.restore 语义 ──────────────

    @Test
    fun restoreMergesBeforeExistingAndDeduplicates() {
        val manager = ConversationManager(maxSize = 50)
        val newDuringLoad = msg(ChatRole.user, "加载期间的新消息")
        manager.addMessage(newDuringLoad)

        val persisted = listOf(
            msg(ChatRole.user, "历史1"),
            msg(ChatRole.assistant, "历史2"),
            newDuringLoad // 重复 id，应被去重
        )
        manager.restore(persisted)

        val contents = manager.getMessages().map { it.content }
        assertEquals(listOf("历史1", "历史2", "加载期间的新消息"), contents)
    }

    @Test
    fun managerPersistsOnChange() = runTest {
        // ConversationManager 现在写入 ConversationRepository（有序异步队列），flush 等队列排空
        val repo = InMemoryConversationRepository()
        val manager = ConversationManager(maxSize = 50, repository = repo, persistScope = backgroundScope)

        manager.addMessage(msg(ChatRole.user, "第一条"))
        manager.flush()

        val loaded = repo.loadRecent(10)
        assertEquals(1, loaded.size)
        assertEquals("第一条", loaded.single().content)
    }
}
