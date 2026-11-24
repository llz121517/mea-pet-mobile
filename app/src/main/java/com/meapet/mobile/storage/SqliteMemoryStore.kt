package com.meapet.mobile.storage

import android.util.Log
import androidx.sqlite.SQLiteConnection
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStats
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.memory.MemoryType
import kotlin.math.max

/**
 * 基于 [SqliteDatabase] 的记忆存储。
 *
 * 记忆条数很小（SHORT/LONG_TERM 上限 [maxItems]，永久类无上限但同量级），
 * 所以业务逻辑（打分、淘汰、封顶）沿用 Kotlin 实现，保证与旧 JSON 版行为完全一致；
 * SQLite 负责原子持久化。keywords 存子表，为将来的检索工具与 FTS 预留。
 */
class SqliteMemoryStore(
    private val db: SqliteDatabase,
    private val maxItems: Int = 500
) : MemoryStore {

    companion object {
        private const val TAG = "SqliteMemoryStore"

        /** meta 表中存对话轮数计数的键。属记忆运行状态，不是用户设置。 */
        internal const val META_EXCHANGE_COUNT = "memory_exchange_count"

        private fun isPermanent(item: MemoryItem) =
            item.type == MemoryType.FACTUAL || item.type == MemoryType.CORE_TRAIT
    }

    override suspend fun save(item: MemoryItem): MemoryItem {
        applyChanges(listOf(item), emptyList())
        return item
    }

    override suspend fun saveAll(items: List<MemoryItem>) = applyChanges(items, emptyList())

    override suspend fun applyChanges(upserts: List<MemoryItem>, deleteIds: Collection<String>) {
        if (upserts.isEmpty() && deleteIds.isEmpty()) return
        db.transaction { c ->
            upserts.forEach { upsert(c, it) }
            deleteIds.toSet().forEach { deleteRow(c, it) }
            enforceCapacity(c)
        }
    }

    override suspend fun findById(id: String): MemoryItem? =
        db.withConnection { c -> loadAll(c).firstOrNull { it.id == id } }

    override suspend fun getAll(): List<MemoryItem> =
        db.withConnection { c -> loadAll(c).sortedByDescending { it.importance } }

    override suspend fun getByType(type: MemoryType): List<MemoryItem> =
        db.withConnection { c ->
            loadAll(c).filter { it.type == type }.sortedByDescending { it.importance }
        }

    override suspend fun getPersonaFacts(maxCount: Int): List<MemoryItem> =
        db.withConnection { c ->
            val all = loadAll(c).filter { isPermanent(it) }
            val kept = if (all.size <= maxCount) all else {
                Log.d(TAG, "Persona facts capped: ${all.size} -> $maxCount for injection")
                all.sortedByDescending { it.importance }.take(maxCount)
            }
            // id 作次级键：createdAt 相同时输出顺序也固定
            kept.sortedWith(compareBy<MemoryItem> { it.createdAt }.thenBy { it.id })
        }

    override suspend fun delete(id: String) {
        db.transaction { c -> deleteRow(c, id) }
    }

    override suspend fun deleteAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.transaction { c -> ids.toSet().forEach { deleteRow(c, it) } }
    }

    override suspend fun clear() {
        db.transaction { c ->
            c.exec("DELETE FROM memory_keywords")
            c.exec("DELETE FROM memories")
        }
        Log.i(TAG, "All memories cleared")
    }

    override suspend fun search(query: String): List<MemoryItem> =
        db.withConnection { c ->
            val q = query.lowercase()
            loadAll(c).filter {
                it.content.lowercase().contains(q) || it.keywords.any { kw -> kw.lowercase().contains(q) }
            }.sortedByDescending { it.importance }
        }

    override suspend fun getRelevant(userInput: String, maxCount: Int): List<MemoryItem> =
        db.withConnection { c ->
            val input = userInput.lowercase()
            loadAll(c)
                .filter { it.type == MemoryType.SHORT_TERM || it.type == MemoryType.LONG_TERM }
                .mapNotNull { item ->
                    if (item.keywords.isEmpty()) return@mapNotNull null
                    val matched = item.keywords.count { kw -> kw.isNotBlank() && input.contains(kw.lowercase()) }
                    if (matched == 0) return@mapNotNull null
                    item to (matched.toFloat() / item.keywords.size * item.importance)
                }
                .sortedByDescending { it.second }
                .take(maxCount)
                .map { it.first }
        }

    override suspend fun markAccessed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.transaction { c ->
            val wanted = ids.toSet()
            loadAll(c).filter { it.id in wanted }.forEach { item ->
                val updated = item.accessed()
                c.prepare("UPDATE memories SET last_accessed_at = ?, access_count = ? WHERE id = ?").use { st ->
                    st.bindLong(1, updated.lastAccessedAt)
                    st.bindLong(2, updated.accessCount.toLong())
                    st.bindText(3, item.id)
                    st.step()
                }
            }
        }
    }

    override suspend fun getStats(): MemoryStats = db.withConnection { c ->
        val all = loadAll(c)
        if (all.isEmpty()) return@withConnection MemoryStats()
        MemoryStats(
            totalCount = all.size,
            shortTermCount = all.count { it.type == MemoryType.SHORT_TERM },
            longTermCount = all.count { it.type == MemoryType.LONG_TERM },
            coreTraitsCount = all.count { it.type == MemoryType.CORE_TRAIT },
            factualsCount = all.count { it.type == MemoryType.FACTUAL },
            averageImportance = all.map { it.importance }.sum() / all.size
        )
    }

    override suspend fun trimCache() {
        db.transaction { c ->
            val evictable = loadAll(c).filter { !isPermanent(it) }
            val keepCount = max(100, maxItems / 3)
            if (evictable.size <= keepCount) return@transaction
            val keep = evictable.sortedByDescending { it.importance * it.accessCount.toFloat() }
                .take(keepCount).map { it.id }.toSet()
            evictable.filter { it.id !in keep }.forEach { deleteRow(c, it.id) }
        }
        Log.d(TAG, "Memory cache trimmed")
    }

    /** 预热：触发数据库打开与 schema 迁移。 */
    override suspend fun loadFromDisk() {
        db.withConnection { }
    }

    override suspend fun getExchangeCount(): Int = db.withConnection { c ->
        c.prepare("SELECT value FROM meta WHERE key = ?").use { st ->
            st.bindText(1, META_EXCHANGE_COUNT)
            if (st.step()) st.getText(0).toIntOrNull() ?: 0 else 0
        }
    }

    override suspend fun setExchangeCount(count: Int) {
        db.transaction { c ->
            c.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)").use { st ->
                st.bindText(1, META_EXCHANGE_COUNT)
                st.bindText(2, count.toString())
                st.step()
            }
        }
    }

    /**
     * 备份导入：清空记忆 + 写入新条目 + 写回轮数计数，**同一个事务**（任一环节失败整类回滚）。
     */
    override suspend fun replaceAll(items: List<MemoryItem>, exchangeCount: Int) {
        db.transaction { c ->
            c.exec("DELETE FROM memory_keywords")
            c.exec("DELETE FROM memories")
            items.forEach { upsert(c, it) }
            c.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)").use { st ->
                st.bindText(1, META_EXCHANGE_COUNT)
                st.bindText(2, exchangeCount.toString())
                st.step()
            }
        }
        Log.i(TAG, "Memories replaced from backup: ${items.size} items")
    }

    // ── 内部（均在 db 锁内调用）─────────────────────────

    private fun upsert(c: SQLiteConnection, item: MemoryItem) {
        val exists = c.prepare("SELECT 1 FROM memories WHERE id = ?").use { st ->
            st.bindText(1, item.id)
            st.step()
        }
        if (exists) {
            c.prepare(
                "UPDATE memories SET content = ?, type = ?, importance = ?, created_at = ?, " +
                    "last_accessed_at = ?, access_count = ?, source_message_id = ? WHERE id = ?"
            ).use { st ->
                st.bindText(1, item.content)
                st.bindText(2, item.type.name)
                st.bindDouble(3, item.importance.toDouble())
                st.bindLong(4, item.createdAt)
                st.bindLong(5, item.lastAccessedAt)
                st.bindLong(6, item.accessCount.toLong())
                bindNullableText(st, 7, item.sourceMessageId)
                st.bindText(8, item.id)
                st.step()
            }
        } else {
            c.prepare(
                "INSERT INTO memories(id, content, type, importance, created_at, last_accessed_at, " +
                    "access_count, source_message_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { st ->
                st.bindText(1, item.id)
                st.bindText(2, item.content)
                st.bindText(3, item.type.name)
                st.bindDouble(4, item.importance.toDouble())
                st.bindLong(5, item.createdAt)
                st.bindLong(6, item.lastAccessedAt)
                st.bindLong(7, item.accessCount.toLong())
                bindNullableText(st, 8, item.sourceMessageId)
                st.step()
            }
        }
        c.prepare("DELETE FROM memory_keywords WHERE memory_id = ?").use { st ->
            st.bindText(1, item.id)
            st.step()
        }
        item.keywords.forEachIndexed { index, kw ->
            c.prepare(
                "INSERT INTO memory_keywords(memory_id, position, keyword, keyword_norm) VALUES(?, ?, ?, ?)"
            ).use { st ->
                st.bindText(1, item.id)
                st.bindLong(2, index.toLong())
                st.bindText(3, kw)
                st.bindText(4, kw.lowercase())
                st.step()
            }
        }
    }

    private fun bindNullableText(st: androidx.sqlite.SQLiteStatement, index: Int, value: String?) {
        if (value == null) st.bindNull(index) else st.bindText(index, value)
    }

    private fun deleteRow(c: SQLiteConnection, id: String) {
        c.prepare("DELETE FROM memory_keywords WHERE memory_id = ?").use { st ->
            st.bindText(1, id)
            st.step()
        }
        c.prepare("DELETE FROM memories WHERE id = ?").use { st ->
            st.bindText(1, id)
            st.step()
        }
    }

    /** 事实/特质不参与淘汰；其余按 重要性*访问次数 保留前 [maxItems] 条。 */
    private fun enforceCapacity(c: SQLiteConnection) {
        val evictable = loadAll(c).filter { !isPermanent(it) }
        if (evictable.size <= maxItems) return
        val keep = evictable.sortedByDescending { it.importance * it.accessCount.toFloat() }
            .take(maxItems).map { it.id }.toSet()
        evictable.filter { it.id !in keep }.forEach { deleteRow(c, it.id) }
        Log.d(TAG, "Memory capacity enforced: $maxItems kept")
    }

    /** 读出全部记忆（含 keywords），按 创建时间、id 升序，输出稳定。 */
    private fun loadAll(c: SQLiteConnection): List<MemoryItem> {
        val keywords = HashMap<String, MutableList<String>>()
        c.prepare("SELECT memory_id, keyword FROM memory_keywords ORDER BY memory_id, position").use { st ->
            while (st.step()) keywords.getOrPut(st.getText(0)) { mutableListOf() }.add(st.getText(1))
        }
        val items = ArrayList<MemoryItem>()
        c.prepare(
            "SELECT id, content, type, importance, created_at, last_accessed_at, access_count, source_message_id " +
                "FROM memories ORDER BY created_at, id"
        ).use { st ->
            while (st.step()) {
                val type = runCatching { MemoryType.valueOf(st.getText(2)) }.getOrNull() ?: continue
                val id = st.getText(0)
                items.add(
                    MemoryItem(
                        id = id,
                        content = st.getText(1),
                        type = type,
                        importance = st.getDouble(3).toFloat(),
                        createdAt = st.getLong(4),
                        lastAccessedAt = st.getLong(5),
                        accessCount = st.getLong(6).toInt(),
                        sourceMessageId = if (st.isNull(7)) null else st.getText(7),
                        keywords = keywords[id] ?: emptyList()
                    )
                )
            }
        }
        return items
    }
}
