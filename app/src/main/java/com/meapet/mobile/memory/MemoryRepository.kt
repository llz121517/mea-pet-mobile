package com.meapet.mobile.memory

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.max

/**
 * 记忆存储器。
 *
 * ## 存储策略
 * - 主存储：内存 `MutableList`（高速读写）；
 * - 持久化：JSON 文件（应用退出不丢失），写入采用临时文件 + rename 保证原子性；
 * - 加载策略：任意公共方法首次访问时惰性加载磁盘数据（[ensureLoadedLocked]），
 *   [loadFromDisk] 仅作为启动预热提前触发同一逻辑——保证"先写后载"不会
 *   用只含新条目的内存列表覆盖磁盘上的旧记忆；
 * - 淘汰策略：[MemoryType.FACTUAL]/[MemoryType.CORE_TRAIT] 永不参与自动淘汰
 *   （由大模型自己决定是否 update/delete，见 [MemoryOpsProtocol]）；
 *   超过 [maxItems] 时只在 SHORT_TERM/LONG_TERM 里淘汰 LRU + 低重要性条目。
 *
 * ## 低耦合
 * - 不依赖任何业务模块与 Android 组件，仅操作 [MemoryItem] 数据与文件，
 *   可直接在 JVM 单元测试中用临时目录验证；
 * - 可替换为 Room / SQLite 实现。
 *
 * @param dir 存储目录（应用 filesDir；JVM 测试传临时目录）
 * @param maxItems SHORT_TERM/LONG_TERM 合计最大条目数，超限自动淘汰（不含永久保留的事实/特质）
 */
class MemoryRepository(
    private val dir: File,
    private val maxItems: Int = 500
) {
    private val mutex = Mutex()
    private val memories = mutableListOf<MemoryItem>()

    /** 是否已从磁盘加载过（成功或损坏丢弃均算完成）。 */
    private var loaded = false

    companion object {
        private const val TAG = "MemoryRepository"
        private const val FILE_NAME = "meapet_memories.json"

        private fun isPermanent(item: MemoryItem) =
            item.type == MemoryType.FACTUAL || item.type == MemoryType.CORE_TRAIT
    }

    private val file: File get() = File(dir, FILE_NAME)

    // ── CRUD ──────────────────────────────────────────

    /** 保存一条记忆（若 id 已存在则覆盖，不存在则新增）。 */
    suspend fun save(item: MemoryItem): MemoryItem {
        mutex.withLock {
            ensureLoadedLocked()
            val idx = memories.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                memories[idx] = item
            } else {
                memories.add(item)
            }
            enforceCapacity()
            persistLocked()
        }
        return item
    }

    /** 批量保存。 */
    suspend fun saveAll(items: List<MemoryItem>) {
        mutex.withLock {
            ensureLoadedLocked()
            items.forEach { item ->
                val idx = memories.indexOfFirst { it.id == item.id }
                if (idx >= 0) memories[idx] = item
                else memories.add(item)
            }
            enforceCapacity()
            persistLocked()
        }
    }

    /**
     * 在一次持锁内应用一批新增/覆盖 + 删除，**整批只落盘一次**。
     *
     * 模型一轮回复常声明多条记忆操作（[MemoryService.applyOps]），逐条
     * [save]/[delete] 会把整个记忆库全量重写同样多次（写放大）。本方法先在内存
     * 里应用完所有变更，再统一 [persistLocked] 一次。
     *
     * @param upserts 要新增或按 id 覆盖的条目（按入参顺序应用）
     * @param deleteIds 要删除的条目 id
     */
    suspend fun applyChanges(upserts: List<MemoryItem>, deleteIds: Collection<String> = emptyList()) {
        if (upserts.isEmpty() && deleteIds.isEmpty()) return
        mutex.withLock {
            ensureLoadedLocked()
            upserts.forEach { item ->
                val idx = memories.indexOfFirst { it.id == item.id }
                if (idx >= 0) memories[idx] = item
                else memories.add(item)
            }
            if (deleteIds.isNotEmpty()) {
                val set = deleteIds.toSet()
                memories.removeAll { it.id in set }
            }
            enforceCapacity()
            persistLocked()
        }
    }

    /** 根据 ID 查询。 */
    suspend fun findById(id: String): MemoryItem? = mutex.withLock {
        ensureLoadedLocked()
        memories.find { it.id == id }
    }

    /** 获取所有记忆（按重要性降序）。 */
    suspend fun getAll(): List<MemoryItem> = mutex.withLock {
        ensureLoadedLocked()
        memories.sortedByDescending { it.importance }.toList()
    }

    /** 按类型过滤。 */
    suspend fun getByType(type: MemoryType): List<MemoryItem> = mutex.withLock {
        ensureLoadedLocked()
        memories.filter { it.type == type }
            .sortedByDescending { it.importance }
    }

    /**
     * 获取事实（FACTUAL）+ 特质（CORE_TRAIT），供每轮注入 system prompt
     * （相当于固定人设表，不做关键词过滤，见 [MemoryManager.buildContext]）。
     *
     * 这两类永不自动淘汰，条数只增不减，因此注入时必须封顶：超过 [maxCount] 时
     * 按重要性取前 N。**只影响注入**，存储与「查看记忆」界面仍是全量。
     *
     * 返回顺序固定按 [MemoryItem.createdAt] 升序——注入内容越稳定，
     * 服务端 prefix cache 越容易命中。
     */
    suspend fun getPersonaFacts(maxCount: Int = Int.MAX_VALUE): List<MemoryItem> = mutex.withLock {
        ensureLoadedLocked()
        val all = memories.filter { isPermanent(it) }
        val kept = if (all.size <= maxCount) all else {
            Log.d(TAG, "Persona facts capped: ${all.size} -> $maxCount for injection")
            all.sortedByDescending { it.importance }.take(maxCount)
        }
        kept.sortedBy { it.createdAt }
    }

    /** 删除指定记忆。 */
    suspend fun delete(id: String) {
        mutex.withLock {
            ensureLoadedLocked()
            memories.removeAll { it.id == id }
            persistLocked()
        }
    }

    /**
     * 批量删除。整批只落盘一次——摘要一次要吃掉几十条短期记忆，
     * 逐条 [delete] 会把整个记忆库重写同样多次。
     */
    suspend fun deleteAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            ensureLoadedLocked()
            val set = ids.toSet()
            if (!memories.removeAll { it.id in set }) return@withLock
            persistLocked()
        }
    }

    /** 清除所有记忆（包括事实/特质——这是用户手动操作，不受自动淘汰豁免规则限制）。 */
    suspend fun clear() {
        mutex.withLock {
            // 即使还没加载也要清：标记已加载，防止之后惰性加载又把旧数据捞回来
            loaded = true
            memories.clear()
            persistLocked()
        }
        Log.i(TAG, "All memories cleared")
    }

    // ── 查询 ──────────────────────────────────────────

    /**
     * 全文关键词搜索（简单包含匹配，未来可替换为向量搜索）。
     */
    suspend fun search(query: String): List<MemoryItem> = mutex.withLock {
        ensureLoadedLocked()
        val q = query.lowercase()
        memories.filter {
            it.content.lowercase().contains(q) ||
                it.keywords.any { kw -> kw.lowercase().contains(q) }
        }.sortedByDescending { it.importance }
    }

    /**
     * 获取与当前用户输入相关的短期/长期记忆。**纯读，不产生副作用。**
     *
     * 只匹配大模型创建记忆时给出的 [MemoryItem.keywords]（不再对 content 做切词/bigram）：
     * 关键词是模型精选出的检索词，直接用 `input.contains(keyword)` 判断命中，
     * 中英文都适用。事实/特质不参与这里的检索——它们每轮全量注入，见 [getPersonaFacts]。
     *
     * 命中条目的访问计数（LRU 权重）不在此更新：读写分离，由调用方在拿到结果后
     * 显式调用 [markAccessed]，避免每次纯查询都整库落盘一次。
     *
     * @param userInput 当前对话上下文/用户输入
     * @param maxCount 最大返回条数
     */
    suspend fun getRelevant(userInput: String, maxCount: Int = 5): List<MemoryItem> = mutex.withLock {
        ensureLoadedLocked()
        val input = userInput.lowercase()
        val candidates = memories.filter {
            it.type == MemoryType.SHORT_TERM || it.type == MemoryType.LONG_TERM
        }
        if (candidates.isEmpty()) return@withLock emptyList()

        candidates.mapNotNull { item ->
            if (item.keywords.isEmpty()) return@mapNotNull null
            val matched = item.keywords.count { kw -> kw.isNotBlank() && input.contains(kw.lowercase()) }
            if (matched == 0) return@mapNotNull null
            item to (matched.toFloat() / item.keywords.size * item.importance)
        }
            .sortedByDescending { it.second }
            .take(maxCount)
            .map { it.first }
    }

    /**
     * 标记一批记忆被访问（更新 accessCount / lastAccessedAt 的 LRU 权重），整批只落盘一次。
     *
     * 与 [getRelevant] 配对：查询是纯读，访问记账作为独立命令由调用方
     * （[MemoryManager.buildContext]）在注入后显式触发。找不到的 id 静默跳过。
     */
    suspend fun markAccessed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            ensureLoadedLocked()
            var changed = false
            ids.toSet().forEach { id ->
                val idx = memories.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    memories[idx] = memories[idx].accessed()
                    changed = true
                }
            }
            if (changed) persistLocked()
        }
    }

    /** 获取统计数据。 */
    suspend fun getStats(): MemoryStats = mutex.withLock {
        ensureLoadedLocked()
        if (memories.isEmpty()) return MemoryStats()
        MemoryStats(
            totalCount = memories.size,
            shortTermCount = memories.count { it.type == MemoryType.SHORT_TERM },
            longTermCount = memories.count { it.type == MemoryType.LONG_TERM },
            coreTraitsCount = memories.count { it.type == MemoryType.CORE_TRAIT },
            factualsCount = memories.count { it.type == MemoryType.FACTUAL },
            averageImportance = memories.map { it.importance }.let { scores ->
                if (scores.isEmpty()) 0f else scores.sum() / scores.size
            }
        )
    }

    /** 清除低价值缓存（内存压力时调用）。事实/特质不受影响。 */
    suspend fun trimCache() {
        mutex.withLock {
            ensureLoadedLocked()
            val permanent = memories.filter { isPermanent(it) }
            val evictable = memories.filter { !isPermanent(it) }
            // 只保留下限 100 条 + 高重要性条目
            val keepCount = max(100, maxItems / 3)
            if (evictable.size <= keepCount) return
            val sorted = evictable.sortedByDescending { it.importance * it.accessCount.toFloat() }
            memories.clear()
            memories.addAll(permanent)
            memories.addAll(sorted.take(keepCount))
            persistLocked()
        }
        Log.d(TAG, "Memory cache trimmed")
    }

    // ── 持久化 ────────────────────────────────────────

    /**
     * 从磁盘加载（启动预热用，可选——任何公共方法都会先惰性加载）。
     */
    suspend fun loadFromDisk() {
        mutex.withLock {
            ensureLoadedLocked()
        }
    }

    /**
     * 惰性加载磁盘数据。必须在持有 [mutex] 时调用。
     *
     * 加载前产生的新写入按 id 去重保留，磁盘数据排在前面；
     * 文件损坏时备份为 `.corrupt` 并丢弃，不影响后续使用。
     */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.exists()) {
            Log.d(TAG, "No persisted memories found")
            return
        }
        try {
            val items = MemorySerialization.decode(file.readText())
            val existingIds = memories.map { it.id }.toSet()
            val restored = items.filter { it.id !in existingIds }
            memories.addAll(0, restored)
            enforceCapacity()
            Log.i(TAG, "Loaded ${restored.size} memories from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memories from disk, backing up corrupted file", e)
            backupCorruptedFileLocked()
        }
    }

    /** 持久化当前列表。必须在持有 [mutex] 时调用，保证写入内容与内存状态一致。 */
    private fun persistLocked() {
        try {
            val json = MemorySerialization.encode(memories.toList())
            dir.mkdirs()
            // 先写临时文件再 rename，避免写入中途崩溃导致文件截断
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    Log.e(TAG, "Failed to replace memories file")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist memories", e)
        }
    }

    /** 将损坏的持久化文件挪到 `.corrupt` 备份，防止每次启动重复解析失败。 */
    private fun backupCorruptedFileLocked() {
        try {
            val backup = File(dir, "$FILE_NAME.corrupt")
            if (backup.exists()) backup.delete()
            if (!file.renameTo(backup)) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to back up corrupted memories file", e)
        }
    }

    // ── 容量控制 ──────────────────────────────────────

    /** 必须在持有 [mutex] 时调用。事实/特质不参与淘汰候选池。 */
    private fun enforceCapacity() {
        val permanent = memories.filter { isPermanent(it) }
        val evictable = memories.filter { !isPermanent(it) }
        if (evictable.size <= maxItems) return
        // 淘汰策略：重要性 * 访问次数 最低的条目
        val sorted = evictable.sortedByDescending { it.importance * it.accessCount.toFloat() }
        memories.clear()
        memories.addAll(permanent)
        memories.addAll(sorted.take(maxItems))
        Log.d(TAG, "Memory capacity enforced: ${sorted.take(maxItems).size}/$maxItems (+${permanent.size} permanent)")
    }
}
