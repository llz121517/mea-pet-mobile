package com.meapet.mobile.storage

import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SqliteMemoryStore 与旧 JSON 版行为一致：批量原子、人设排序、关键词检索、淘汰、访问记账。 */
class SqliteMemoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: SqliteDatabase

    @Before
    fun setUp() {
        db = SqliteDatabase(JdbcSQLiteDriver(), File(tmp.root, "m.db").absolutePath)
    }

    @After
    fun tearDown() = runBlocking { db.close() }

    private fun item(
        id: String,
        type: MemoryType = MemoryType.SHORT_TERM,
        importance: Float = 0.5f,
        createdAt: Long = 1L,
        keywords: List<String> = emptyList()
    ) = MemoryItem(id = id, content = "c-$id", type = type, importance = importance, createdAt = createdAt, keywords = keywords)

    @Test
    fun applyChangesUpsertsAndDeletesTogether() = runBlocking {
        val s = SqliteMemoryStore(db)
        s.applyChanges(listOf(item("a"), item("b")), emptyList())
        s.applyChanges(listOf(item("a").copy(content = "改")), listOf("b"))
        assertEquals(listOf("a"), s.getAll().map { it.id })
        assertEquals("改", s.findById("a")?.content)
        assertNull(s.findById("b"))
    }

    @Test
    fun personaFactsSortedByCreatedAtAndCappedByImportance() = runBlocking {
        val s = SqliteMemoryStore(db)
        s.saveAll(
            listOf(
                item("x", MemoryType.FACTUAL, 0.9f, 30),
                item("y", MemoryType.CORE_TRAIT, 0.8f, 10),
                item("z", MemoryType.FACTUAL, 0.1f, 20),
                item("s", MemoryType.SHORT_TERM, 1f, 1)
            )
        )
        assertEquals(listOf("y", "z", "x"), s.getPersonaFacts().map { it.id })
        assertEquals(listOf("y", "x"), s.getPersonaFacts(2).map { it.id })
    }

    @Test
    fun getRelevantMatchesKeywordsInInputAndSkipsPermanent() = runBlocking {
        val s = SqliteMemoryStore(db)
        s.saveAll(
            listOf(
                item("hit", keywords = listOf("炸鸡", "火锅"), importance = 1f),
                item("miss", keywords = listOf("篮球")),
                item("perm", MemoryType.FACTUAL, keywords = listOf("炸鸡"))
            )
        )
        assertEquals(listOf("hit"), s.getRelevant("今天想吃炸鸡", 5).map { it.id })
    }

    @Test
    fun capacityEvictsOnlyNonPermanent() = runBlocking {
        val s = SqliteMemoryStore(db, maxItems = 2)
        s.saveAll(
            listOf(
                item("p", MemoryType.FACTUAL, 0.1f),
                item("a", importance = 0.9f),
                item("b", importance = 0.8f),
                item("c", importance = 0.1f)
            )
        )
        assertEquals(setOf("p", "a", "b"), s.getAll().map { it.id }.toSet())
    }

    @Test
    fun markAccessedBumpsCountAndKeywordsRoundTrip() = runBlocking {
        val s = SqliteMemoryStore(db)
        s.save(item("a", keywords = listOf("一", "二", "三")))
        s.markAccessed(listOf("a", "nope"))
        val got = s.findById("a")!!
        assertEquals(2, got.accessCount)
        assertEquals(listOf("一", "二", "三"), got.keywords)
    }

    @Test
    fun exchangeCountPersistsInMetaTable() = runBlocking {
        val s = SqliteMemoryStore(db)
        assertEquals(0, s.getExchangeCount())
        s.setExchangeCount(7)
        assertEquals(7, s.getExchangeCount())
        // 换一个实例仍读得到（存在库里而非内存）
        assertEquals(7, SqliteMemoryStore(db).getExchangeCount())
        s.setExchangeCount(0)
        assertEquals(0, s.getExchangeCount())
    }

    @Test
    fun clearRemovesEverythingAndStatsCount() = runBlocking {
        val s = SqliteMemoryStore(db)
        s.saveAll(listOf(item("a"), item("b", MemoryType.LONG_TERM)))
        assertEquals(2, s.getStats().totalCount)
        s.clear()
        assertEquals(0, s.getStats().totalCount)
    }
}
