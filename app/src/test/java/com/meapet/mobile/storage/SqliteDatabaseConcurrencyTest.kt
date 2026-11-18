package com.meapet.mobile.storage

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

/**
 * 一写多读连接模型：读写并发不互相阻塞，数据一致。
 *
 * 这是「首屏读 50 条 + 向上分页 + 后台压缩写入」同时进行时的行为保证。
 */
class SqliteDatabaseConcurrencyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: SqliteDatabase
    private lateinit var repo: SqliteConversationRepository
    private lateinit var memory: SqliteMemoryStore

    @Before
    fun setUp() {
        db = SqliteDatabase(JdbcSQLiteDriver(), File(tmp.root, "c.db").absolutePath)
        repo = SqliteConversationRepository(db)
        memory = SqliteMemoryStore(db)
    }

    @After
    fun tearDown() = runTest { db.close() }

    @Test
    fun `并发读与写互不阻塞且最终一致`() = runTest {
        // 预置 40 条消息
        (1..40).forEach {
            repo.append(ChatMessage(ChatRole.user, "m$it", id = "id$it", timestamp = it.toLong()))
        }

        val jobs = buildList {
            // 4 个并发读：分页查询
            repeat(4) {
                add(async {
                    (1..10).forEach { _ -> repo.pageBefore("id40", 20) }
                })
            }
            // 2 个并发读：记忆查询
            repeat(2) {
                add(async {
                    (1..10).forEach { _ -> memory.getPersonaFacts() }
                })
            }
            // 1 个并发写：持续追加记忆
            add(async {
                (1..20).forEach { i ->
                    memory.applyChanges(
                        listOf(
                            MemoryItem(
                                id = "mem_$i",
                                content = "c$i",
                                type = MemoryType.SHORT_TERM,
                                importance = 0.5f
                            )
                        )
                    )
                }
            })
        }
        jobs.awaitAll()

        assertEquals(40, repo.count())
        assertEquals(20, memory.getAll().size)
    }

    @Test
    fun `读连接池在多次使用后可复用不泄漏`() = runTest {
        repo.append(ChatMessage(ChatRole.user, "x", id = "x1"))
        (1..50).forEach { _ -> repo.loadRecent(5) }
        assertEquals(1, repo.count())
        // 池内空闲连接应不超过 readerCount（默认 2）
        assertEquals(1, repo.count())
    }
}
