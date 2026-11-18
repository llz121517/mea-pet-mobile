package com.meapet.mobile.storage

import androidx.sqlite.SQLiteConnection
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M0 spike：证明 JVM 单测能在真实 SQLite 上跑建表、事务、外键级联、WAL、部分唯一索引、
 * user_version 与中文/emoji 往返。这些是 1.9 存储层（会话 + 记忆 + 压缩摘要）依赖的能力。
 */
class SqliteDriverSpikeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(): SQLiteConnection {
        val f = File(tmp.root, "spike.db")
        return JdbcSQLiteDriver().open(f.absolutePath)
    }

    private fun SQLiteConnection.exec(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun SQLiteConnection.queryLong(sql: String): Long =
        prepare(sql).use { st ->
            check(st.step()) { "no row for: $sql" }
            st.getLong(0)
        }

    private fun SQLiteConnection.queryText(sql: String): String =
        prepare(sql).use { st ->
            check(st.step()) { "no row for: $sql" }
            st.getText(0)
        }

    private fun SQLiteConnection.insertMessage(convId: String, seq: Long?, content: String) {
        prepare("INSERT INTO messages(id, conversation_id, content) VALUES(?, ?, ?)").use { st ->
            st.bindText(1, "m-${seq ?: content.hashCode()}")
            st.bindText(2, convId)
            st.bindText(3, content)
            st.step()
        }
    }

    private fun SQLiteConnection.createSchema() {
        exec("PRAGMA foreign_keys=ON")
        exec("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
        exec(
            "CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, " +
                "conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, content TEXT NOT NULL)"
        )
        exec(
            "CREATE TABLE summaries(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, active INTEGER NOT NULL)"
        )
        exec("CREATE UNIQUE INDEX idx_one_active ON summaries(conversation_id) WHERE active = 1")
    }

    @Test
    fun transactionCommitAndRollback() {
        open().use { c ->
            c.createSchema()
            c.exec("INSERT INTO conversations(id) VALUES('c1')")

            c.exec("BEGIN IMMEDIATE")
            c.insertMessage("c1", 1, "a")
            c.exec("ROLLBACK")
            assertEquals(0, c.queryLong("SELECT COUNT(*) FROM messages"))

            c.exec("BEGIN IMMEDIATE")
            c.insertMessage("c1", 1, "a")
            c.insertMessage("c1", 2, "b")
            c.exec("COMMIT")
            assertEquals(2, c.queryLong("SELECT COUNT(*) FROM messages"))
        }
    }

    @Test
    fun foreignKeyCascadeAndRejection() {
        open().use { c ->
            c.createSchema()
            c.exec("INSERT INTO conversations(id) VALUES('c1')")
            c.insertMessage("c1", 1, "a")

            // 外键必须真的生效：引用不存在的会话应当失败
            assertFailsWith<Exception> { c.insertMessage("nope", 2, "b") }

            c.exec("DELETE FROM conversations WHERE id='c1'")
            assertEquals(0, c.queryLong("SELECT COUNT(*) FROM messages"))
        }
    }

    @Test
    fun partialUniqueIndexAllowsOnlyOneActiveSummary() {
        open().use { c ->
            c.createSchema()
            c.exec("INSERT INTO conversations(id) VALUES('c1')")
            c.exec("INSERT INTO summaries(conversation_id, active) VALUES('c1', 1)")
            c.exec("INSERT INTO summaries(conversation_id, active) VALUES('c1', 0)")
            assertFailsWith<Exception> {
                c.exec("INSERT INTO summaries(conversation_id, active) VALUES('c1', 1)")
            }
        }
    }

    @Test
    fun walUserVersionAndUnicodeRoundTrip() {
        open().use { c ->
            assertEquals("wal", c.queryText("PRAGMA journal_mode=WAL").lowercase())
            c.exec("PRAGMA user_version = 7")
            assertEquals(7, c.queryLong("PRAGMA user_version"))

            c.createSchema()
            c.exec("INSERT INTO conversations(id) VALUES('c1')")
            val text = "你好，梅尔 \uD83D\uDE00 \"引号\"\n换行"
            c.insertMessage("c1", 1, text)
            assertEquals(text, c.queryText("SELECT content FROM messages"))
        }
    }

    @Test
    fun keysetPaginationByPrimaryKey() {
        open().use { c ->
            c.createSchema()
            c.exec("INSERT INTO conversations(id) VALUES('c1')")
            for (i in 1..10) c.insertMessage("c1", i.toLong(), "msg$i")

            // 首屏取最新 3 条，再向上翻一页
            val firstPage = c.prepare(
                "SELECT seq FROM messages WHERE conversation_id=? ORDER BY seq DESC LIMIT 3"
            ).use { st ->
                st.bindText(1, "c1")
                buildList { while (st.step()) add(st.getLong(0)) }
            }
            assertEquals(listOf(10L, 9L, 8L), firstPage)

            val secondPage = c.prepare(
                "SELECT seq FROM messages WHERE conversation_id=? AND seq < ? ORDER BY seq DESC LIMIT 3"
            ).use { st ->
                st.bindText(1, "c1")
                st.bindLong(2, firstPage.last())
                buildList { while (st.step()) add(st.getLong(0)) }
            }
            assertEquals(listOf(7L, 6L, 5L), secondPage)
        }
    }
}
