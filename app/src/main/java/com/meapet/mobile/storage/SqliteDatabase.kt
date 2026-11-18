package com.meapet.mobile.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话 + 记忆共用的 SQLite 数据库。
 *
 * - 单连接，所有访问经 [mutex] 串行（数据量小，避免多连接与 WAL 并发的复杂度）；
 * - 首次访问时惰性打开、配置 PRAGMA 并执行 [Migrations]；
 * - 驱动由外部注入：生产用 AndroidSQLiteDriver，JVM 单测用 sqlite-jdbc 适配器。
 *
 * 只使用保守 SQL 语法（无 UPSERT / 窗口函数 / RETURNING），兼容 minSdk 26 的框架 SQLite。
 */
class SqliteDatabase(
    private val driver: SQLiteDriver,
    private val path: String
) {
    private val mutex = Mutex()
    private var conn: SQLiteConnection? = null

    /** 在锁内执行一段读（或无需事务的写）。block 不得挂起。 */
    suspend fun <T> withConnection(block: (SQLiteConnection) -> T): T =
        mutex.withLock { block(openLocked()) }

    /** 在一个 BEGIN IMMEDIATE 事务内执行 block；抛异常则整体回滚。 */
    suspend fun <T> transaction(block: (SQLiteConnection) -> T): T = mutex.withLock {
        val c = openLocked()
        c.exec("BEGIN IMMEDIATE")
        try {
            val result = block(c)
            c.exec("COMMIT")
            result
        } catch (t: Throwable) {
            runCatching { c.exec("ROLLBACK") }
            throw t
        }
    }

    /** 关闭连接（测试与应用退出用）。之后再访问会重新打开。 */
    suspend fun close() = mutex.withLock {
        runCatching { conn?.close() }
        conn = null
    }

    private fun openLocked(): SQLiteConnection {
        conn?.let { return it }
        val c = driver.open(path)
        try {
            // 外键必须每个连接都开
            c.exec("PRAGMA foreign_keys=ON")
            c.exec("PRAGMA journal_mode=WAL")
            c.exec("PRAGMA synchronous=NORMAL")
            c.exec("PRAGMA busy_timeout=5000")
            Migrations.migrate(c)
        } catch (t: Throwable) {
            runCatching { c.close() }
            throw t
        }
        conn = c
        return c
    }
}

/** 执行一条无参语句（读出全部结果行前不需要的场景，如 DDL / PRAGMA / BEGIN）。 */
internal fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

/** 读取单个整数结果（如 PRAGMA user_version）。 */
internal fun SQLiteConnection.queryLong(sql: String): Long =
    prepare(sql).use { st -> if (st.step()) st.getLong(0) else 0L }

/**
 * 按 PRAGMA user_version 顺序应用 schema 迁移，每个版本在一个事务里完成。
 * 只加表/列，不删；库版本高于代码已知版本时拒绝打开（不支持降级）。
 */
internal object Migrations {
    const val CURRENT_VERSION = 1

    private val V1 = listOf(
        """CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
        """CREATE TABLE conversations(
            id TEXT PRIMARY KEY,
            title TEXT,
            state TEXT NOT NULL DEFAULT 'active' CHECK(state IN ('active','cleared')),
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            cleared_at INTEGER)""",
        """CREATE TABLE messages(
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL UNIQUE,
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            turn_id TEXT,
            role TEXT NOT NULL CHECK(role IN ('system','user','assistant','tool')),
            content TEXT NOT NULL,
            memory_ops_block TEXT,
            tool_calls TEXT,
            tool_call_id TEXT,
            tool_name TEXT,
            extra_json TEXT,
            origin TEXT NOT NULL DEFAULT 'chat',
            created_at INTEGER NOT NULL,
            token_est INTEGER,
            prompt_tokens INTEGER)""",
        """CREATE INDEX idx_messages_conv_seq ON messages(conversation_id, seq)""",
        """CREATE INDEX idx_messages_turn ON messages(conversation_id, turn_id)""",
        """CREATE TABLE compaction_summaries(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            covered_from_seq INTEGER NOT NULL,
            covered_to_seq INTEGER NOT NULL,
            prev_summary_id INTEGER REFERENCES compaction_summaries(id),
            summary TEXT NOT NULL,
            token_est INTEGER,
            model TEXT,
            created_at INTEGER NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            UNIQUE(conversation_id, covered_to_seq))""",
        """CREATE UNIQUE INDEX idx_one_active_summary ON compaction_summaries(conversation_id) WHERE active = 1""",
        """CREATE TABLE memories(
            id TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            type TEXT NOT NULL CHECK(type IN ('SHORT_TERM','LONG_TERM','CORE_TRAIT','FACTUAL')),
            importance REAL NOT NULL,
            created_at INTEGER NOT NULL,
            last_accessed_at INTEGER NOT NULL,
            access_count INTEGER NOT NULL DEFAULT 1,
            source_message_id TEXT,
            search_text TEXT)""",
        """CREATE INDEX idx_memories_type ON memories(type)""",
        """CREATE TABLE memory_keywords(
            memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
            position INTEGER NOT NULL,
            keyword TEXT NOT NULL,
            keyword_norm TEXT NOT NULL,
            PRIMARY KEY(memory_id, position))""",
        """CREATE INDEX idx_memory_keywords_norm ON memory_keywords(keyword_norm)"""
    )

    fun migrate(c: SQLiteConnection) {
        val current = c.queryLong("PRAGMA user_version").toInt()
        check(current <= CURRENT_VERSION) {
            "数据库版本 $current 高于应用支持的 $CURRENT_VERSION，不支持降级"
        }
        if (current >= CURRENT_VERSION) return
        if (current < 1) apply(c, 1, V1)
    }

    private fun apply(c: SQLiteConnection, version: Int, statements: List<String>) {
        c.exec("BEGIN IMMEDIATE")
        try {
            statements.forEach { c.exec(it) }
            c.exec("PRAGMA user_version = $version")
            c.exec("COMMIT")
        } catch (t: Throwable) {
            runCatching { c.exec("ROLLBACK") }
            throw t
        }
    }
}
