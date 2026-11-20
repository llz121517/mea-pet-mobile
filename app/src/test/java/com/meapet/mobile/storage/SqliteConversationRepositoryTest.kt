package com.meapet.mobile.storage

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 会话仓库在真实 SQLite（sqlite-jdbc）上的行为：顺序、幂等、分页、删除、替换、Unicode。 */
class SqliteConversationRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: SqliteDatabase
    private lateinit var repo: SqliteConversationRepository

    @Before
    fun setUp() {
        db = SqliteDatabase(JdbcSQLiteDriver(), File(tmp.root, "t.db").absolutePath)
        repo = SqliteConversationRepository(db)
    }

    @After
    fun tearDown() = runBlocking { db.close() }

    private fun msg(i: Int, role: ChatRole = if (i % 2 == 1) ChatRole.user else ChatRole.assistant) =
        ChatMessage(role = role, content = "m$i", id = "id$i", timestamp = 1000L + i)

    @Test
    fun appendKeepsInsertionOrderNotTimestampOrder() = runBlocking {
        repo.append(ChatMessage(ChatRole.user, "late", id = "a", timestamp = 9000))
        repo.append(ChatMessage(ChatRole.assistant, "early", id = "b", timestamp = 1))
        assertEquals(listOf("a", "b"), repo.loadRecent(10).map { it.id })
    }

    @Test
    fun duplicateIdIsIgnored() = runBlocking {
        repo.append(msg(1))
        repo.append(msg(1))
        assertEquals(1, repo.count())
    }

    @Test
    fun keysetPaginationWalksBackwardsWithoutOverlap() = runBlocking {
        (1..10).forEach { repo.append(msg(it)) }
        val p1 = repo.loadRecent(4)
        assertEquals(listOf("id7", "id8", "id9", "id10"), p1.map { it.id })
        val p2 = repo.pageBefore(p1.first().id, 4)
        assertEquals(listOf("id3", "id4", "id5", "id6"), p2.map { it.id })
        val p3 = repo.pageBefore(p2.first().id, 4)
        assertEquals(listOf("id1", "id2"), p3.map { it.id })
        assertTrue(repo.pageBefore(p3.first().id, 4).isEmpty())
    }

    @Test
    fun removeAndClear() = runBlocking {
        (1..3).forEach { repo.append(msg(it)) }
        assertTrue(repo.remove("id2"))
        assertFalse(repo.remove("id2"))
        assertEquals(2, repo.count())
        repo.clear()
        assertEquals(0, repo.count())
    }

    @Test
    fun replaceAllRestoresExactOrder() = runBlocking {
        (1..3).forEach { repo.append(msg(it)) }
        val snapshot = repo.loadRecent(10)
        repo.clear()
        repo.replaceAll(snapshot)
        assertEquals(snapshot.map { it.id }, repo.loadRecent(10).map { it.id })
    }

    @Test
    fun unicodeAndTimestampRoundTrip() = runBlocking {
        val m = ChatMessage(
            ChatRole.assistant, "你好 \uD83D\uDE00 \"q\"\n换行",
            id = "u1", timestamp = 5
        )
        repo.append(m)
        assertEquals(m, repo.loadRecent(1).single())
    }

    @Test
    fun existingDatabaseWithUnusedMemoryOpsColumnPreservesHistoryAndAcceptsWrites() = runBlocking {
        val oldMessage = ChatMessage(ChatRole.assistant, "旧回复", id = "old", timestamp = 17)
        repo.append(oldMessage)
        db.transaction { c ->
            val hasLegacyColumn = c.prepare("PRAGMA table_info(messages)").use { st ->
                var found = false
                while (st.step()) {
                    if (st.getText(1) == "memory_ops_block") found = true
                }
                found
            }
            if (!hasLegacyColumn) c.exec("ALTER TABLE messages ADD COLUMN memory_ops_block TEXT")
            c.exec("UPDATE messages SET memory_ops_block = 'legacy block' WHERE id = 'old'")
        }
        db.close()

        assertEquals(listOf(oldMessage), repo.loadRecent(10))
        val newTurn = toolTurn("new-turn", start = 21)
        repo.appendAll(newTurn)
        assertEquals(listOf(oldMessage) + newTurn, repo.loadRecent(10))
        assertEquals(listOf(oldMessage), repo.pageBefore("u21", 10))
        db.withConnection { c ->
            c.prepare("SELECT memory_ops_block FROM messages WHERE id = 'old'").use { st ->
                assertTrue(st.step())
                assertEquals("legacy block", st.getText(0), "旧列保留原值，不执行破坏性迁移")
            }
            assertEquals(4L, c.queryLong("SELECT COUNT(*) FROM messages WHERE memory_ops_block IS NULL"))
        }
    }

    @Test
    fun reopenKeepsData() = runBlocking {
        repo.append(msg(1))
        db.close()
        assertEquals(1, repo.count())
    }

    // ── 工具 turn 的整轮写入/读回 ──────────────────────

    /** 一个 turn：user → assistant(tool_calls) → tool → assistant(最终)。 */
    private fun toolTurn(turnId: String, start: Int): List<ChatMessage> {
        val raw = """{"role":"assistant","content":"","tool_calls":[{"id":"call_$start",""" +
            """"type":"function","function":{"name":"search_memory","arguments":"{}"}}]}"""
        return listOf(
            ChatMessage(ChatRole.user, "u$start", id = "u$start", timestamp = start.toLong(), turnId = turnId),
            ChatMessage(
                ChatRole.assistant, "", id = "a$start", timestamp = start + 1L,
                turnId = turnId, rawAssistantJson = raw
            ),
            ChatMessage(
                ChatRole.tool, """{"ok":true}""", id = "tr$start", timestamp = start + 2L,
                turnId = turnId, toolCallId = "call_$start", toolName = "search_memory"
            ),
            ChatMessage(
                ChatRole.assistant, "final$start", id = "f$start", timestamp = start + 3L,
                turnId = turnId, rawAssistantJson = """{"role":"assistant","content":"final$start"}"""
            )
        )
    }

    @Test
    fun appendAllRoundTripsWholeToolTurn() = runBlocking {
        val turn = toolTurn("turn-1", start = 1)
        repo.appendAll(turn)

        // 顺序、角色、tool_call_id、rawAssistantJson、turnId 全部原样读回
        val recent = repo.loadRecent(10)
        assertEquals(listOf("u1", "a1", "tr1", "f1"), recent.map { it.id })
        assertEquals(turn, recent, "appendAll 后应逐字段等价读回")

        val assistantWithCalls = recent[1]
        assertEquals(ChatRole.assistant, assistantWithCalls.role)
        assertEquals("turn-1", assistantWithCalls.turnId)
        assertTrue(assistantWithCalls.rawAssistantJson!!.contains("tool_calls"))
        assertEquals("call_1", recent[2].toolCallId)
        assertEquals("search_memory", recent[2].toolName)
        assertEquals(turn[0].rawAssistantJson, null, "user 行不带原始助手 JSON（前置断言）")
        assertEquals("""{"role":"assistant","content":"final1"}""", recent[3].rawAssistantJson)

        // keyset 分页同样能读到 tool 行（不能被当成未知角色跳过）
        val older = repo.pageBefore("tr1", 10)
        assertEquals(listOf("u1", "a1"), older.map { it.id })
        assertTrue(older[1].rawAssistantJson!!.contains("tool_calls"))
    }

    @Test
    fun appendAllKeepsTurnIdsSeparated() = runBlocking {
        repo.appendAll(toolTurn("turn-1", start = 1))
        repo.appendAll(toolTurn("turn-2", start = 11))

        val all = repo.loadRecent(10)
        assertEquals(8, all.size)
        assertEquals(
            listOf("u1", "a1", "tr1", "f1"),
            all.filter { it.turnId == "turn-1" }.map { it.id }
        )
        assertEquals(
            listOf("u11", "a11", "tr11", "f11"),
            all.filter { it.turnId == "turn-2" }.map { it.id }
        )
        // 两个 turn 的 tool_call_id 不串
        assertEquals(
            listOf("call_1", "call_11"),
            all.filter { it.isTool }.map { it.toolCallId }
        )
    }
}
