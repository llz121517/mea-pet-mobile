package com.meapet.mobile.storage

import com.meapet.mobile.chat.compaction.CompactionState
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

/** 压缩状态存进 meta 表：往返、覆盖、清除、坏数据自愈。 */
class MetaCompactionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: SqliteDatabase
    private lateinit var store: MetaCompactionStore

    @Before
    fun setUp() {
        db = SqliteDatabase(JdbcSQLiteDriver(), File(tmp.root, "c.db").absolutePath)
        store = MetaCompactionStore(db)
    }

    @After
    fun tearDown() = runBlocking { db.close() }

    @Test
    fun `无记录时返回空状态`() = runBlocking {
        val state = store.load()
        assertFalse(state.hasSummary)
        assertEquals(null, state.coveredToMessageId)
        assertEquals(0, state.coveredCount)
    }

    @Test
    fun `保存后可读回且再保存覆盖`() = runBlocking {
        store.save(CompactionState(summary = "摘要A", coveredToMessageId = "m1", coveredCount = 3, updatedAt = 100))
        assertEquals("摘要A", store.load().summary)
        assertEquals("m1", store.load().coveredToMessageId)

        store.save(CompactionState(summary = "摘要B", coveredToMessageId = "m9", coveredCount = 9, updatedAt = 200))
        val reloaded = store.load()
        assertEquals("摘要B", reloaded.summary)
        assertEquals(9, reloaded.coveredCount)
    }

    @Test
    fun `清除后回到空状态`() = runBlocking {
        store.save(CompactionState(summary = "摘要", coveredCount = 5))
        store.clear()
        assertFalse(store.load().hasSummary)
    }

    @Test
    fun `坏数据不崩溃`() = runBlocking {
        db.transaction { c ->
            c.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)").use { st ->
                st.bindText(1, MetaCompactionStore.META_KEY)
                st.bindText(2, "]{ not json")
                st.step()
            }
        }
        assertFalse(store.load().hasSummary)
        assertTrue(store.load().coveredCount == 0)
    }
}
