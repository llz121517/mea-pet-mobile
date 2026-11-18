package com.meapet.mobile.memory

/**
 * 记忆存储接口。
 *
 * 上层（MemoryManager、MemoryService、将来的记忆工具）只依赖本接口，
 * 不关心底层是 JSON 文件（旧版备胎）还是 SQLite。
 *
 * 契约：
 * - [applyChanges] 必须原子（SQLite 中对应一个事务），整批只落盘一次；
 * - [getPersonaFacts] 返回顺序固定为 createdAt 升序（先按重要度封顶），
 *   注入内容越稳定，服务端 prefix cache 越容易命中；
 * - [MemoryItem.id] 格式保持 mem_ + 8 位，供模型引用。
 */
interface MemoryStore {
    suspend fun save(item: MemoryItem): MemoryItem
    suspend fun saveAll(items: List<MemoryItem>)
    suspend fun applyChanges(upserts: List<MemoryItem>, deleteIds: Collection<String> = emptyList())
    suspend fun findById(id: String): MemoryItem?
    suspend fun getAll(): List<MemoryItem>
    suspend fun getByType(type: MemoryType): List<MemoryItem>
    suspend fun getPersonaFacts(maxCount: Int = Int.MAX_VALUE): List<MemoryItem>
    suspend fun delete(id: String)
    suspend fun deleteAll(ids: Collection<String>)
    suspend fun clear()
    suspend fun search(query: String): List<MemoryItem>
    suspend fun getRelevant(userInput: String, maxCount: Int = 5): List<MemoryItem>
    suspend fun markAccessed(ids: Collection<String>)
    suspend fun getStats(): MemoryStats
    suspend fun trimCache()
    suspend fun loadFromDisk()
}
