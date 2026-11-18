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
    fun unicodeAndMemoryOpsBlockRoundTrip() = runBlocking {
        val m = ChatMessage(
            ChatRole.assistant, "你好 \uD83D\uDE00 \"q\"\n换行",
            memoryOpsBlock = "```memory-ops\n[]\n```", id = "u1", timestamp = 5
        )
        repo.append(m)
        assertEquals(m, repo.loadRecent(1).single())
    }

    @Test
    fun reopenKeepsData() = runBlocking {
        repo.append(msg(1))
        db.close()
        assertEquals(1, repo.count())
    }
}
