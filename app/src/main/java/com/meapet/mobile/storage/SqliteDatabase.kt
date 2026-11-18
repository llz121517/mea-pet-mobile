package com.meapet.mobile.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 会话 + 记忆 + 压缩状态共用的 SQLite 数据库。
 *
 * ## 连接模型：一写多读
 * WAL 允许「一个写者 + 多个读者」并发。本类据此拆成：
 * - **写连接单例**：所有写事务串行（`BEGIN IMMEDIATE`），用 [writeMutex] 保护；
 * - **读连接池**（默认 [readerCount] = 2 个）：`withConnection` 从池里取一条，
 *   与写连接互不阻塞，读快照不受写事务影响。
 *
 * 这样首屏读 50 条、向上 keyset 懒加载、后台压缩写入不会互相排队；
 * 某个事务稍长时，滚动也不会顿（代价只是读到的是提交前的一致快照，符合预期）。
 *
 * ## 铁律：事务内绝不 await 网络或 LLM
 * 写事务持锁期间所有读直接排队。**禁止在事务里发 HTTP / 调 LLM / 等待信号量等任何挂起操作**——
 * 一次 LLM 往返可能数秒，会把整个读路径堵死。
 * 类型上也做了兜底：[transaction] 的 block 是**非挂起**函数，编译器不允许在事务里 `await`；
 * 需要网络的数据应在事务外取好，再传入事务写入（摘要 / 导入 / applyChanges 都遵此约定）。
 *
 * ## 迁移
 * 首次访问时初始化写连接并执行 [Migrations]；读连接在拿到后各自设置 PRAGMA
 * （`foreign_keys` 是连接级开关，必须每条连接都设）。
 *
 * 只使用保守 SQL 语法（无 UPSERT / 窗口函数 / RETURNING），兼容 minSdk 26 的框架 SQLite。
 */
class SqliteDatabase(
    private val driver: SQLiteDriver,
    private val path: String,
    /** 读连接数量。默认 2：够覆盖「列表分页 + 后台读」两类并发，再多收益有限。 */
    private val readerCount: Int = 2
) {
    private val initMutex = Mutex()
    private val writeMutex = Mutex()
    private val readersMutex = Mutex()
    private val readerSlots = Semaphore(readerCount)
    private val idleReaders = ArrayDeque<SQLiteConnection>()

    @Volatile
    private var writer: SQLiteConnection? = null

    @Volatile
    private var initialized = false

    /** 在**读连接**上执行一段只读（或无需事务保证的）代码。block 不得挂起。 */
    suspend fun <T> withConnection(block: (SQLiteConnection) -> T): T {
        ensureInitialized()
        return readerSlots.withPermit {
            val conn = acquireReader()
            try {
                block(conn)
            } finally {
                releaseReader(conn)
            }
        }
    }

    /**
     * 在**写连接**上的 `BEGIN IMMEDIATE` 事务内执行 [block]；抛异常则整体回滚。
     *
     * block 是非挂起函数：**不要在事务里发网络请求或调用 LLM**（见类注释铁律）。
     */
    suspend fun <T> transaction(block: (SQLiteConnection) -> T): T {
        ensureInitialized()
        return writeMutex.withLock {
            val c = checkNotNull(writer) { "write connection not initialized" }
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
    }

    /** 关闭所有连接（测试与应用退出用）。之后访问会重新初始化。 */
    suspend fun close() {
        initMutex.withLock {
            runCatching { writer?.close() }
            writer = null
            initialized = false
        }
        readersMutex.withLock {
            idleReaders.forEach { runCatching { it.close() } }
            idleReaders.clear()
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return@withLock
            val w = openConfigured()
            try {
                Migrations.migrate(w)
            } catch (t: Throwable) {
                runCatching { w.close() }
                throw t
            }
            writer = w
            initialized = true
        }
    }

    private suspend fun acquireReader(): SQLiteConnection = readersMutex.withLock {
        idleReaders.removeLastOrNull() ?: openConfigured()
    }

    private suspend fun releaseReader(conn: SQLiteConnection) {
        readersMutex.withLock { idleReaders.addLast(conn) }
    }

    private fun openConfigured(): SQLiteConnection {
        val c = driver.open(path)
        try {
            // foreign_keys 是连接级开关，每条连接都必须单独开启
            c.exec("PRAGMA foreign_keys=ON")
            c.exec("PRAGMA journal_mode=WAL")
            c.exec("PRAGMA synchronous=NORMAL")
            c.exec("PRAGMA busy_timeout=5000")
        } catch (t: Throwable) {
            runCatching { c.close() }
            throw t
        }
        return c
    }
}

/** 执行一条无参语句（DDL / PRAGMA / BEGIN / COMMIT）。 */
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
