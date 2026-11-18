package com.meapet.mobile.chat.compaction

import kotlinx.serialization.Serializable

/**
 * 上下文压缩状态（一个会话一份）。
 *
 * @property summary 滚动摘要正文；空串表示从未压缩过
 * @property coveredToMessageId 已被摘要覆盖的最后一条消息 id（UI 用来画「早些时候」分隔条）
 * @property coveredCount 已被摘要覆盖的消息条数（从当前窗口最早一条起算）
 * @property updatedAt 最近一次压缩完成时间
 */
@Serializable
data class CompactionState(
    val summary: String = "",
    val coveredToMessageId: String? = null,
    val coveredCount: Int = 0,
    val updatedAt: Long = 0L
) {
    val hasSummary: Boolean get() = summary.isNotBlank()
}

/**
 * 压缩状态存储。
 *
 * 与「完整历史」分开存：摘要只需覆盖区间与正文，不需要按消息粒度查询，
 * 因此实现为一个整体状态对象（SQLite 实现存在 `meta` 表的单行 JSON 里）。
 */
interface CompactionStore {
    suspend fun load(): CompactionState

    suspend fun save(state: CompactionState)

    /** 清除压缩状态（清除对话、撤销清除时使用）。 */
    suspend fun clear()
}

/** 纯内存实现：JVM 单测与无库场景使用。 */
class InMemoryCompactionStore(initial: CompactionState = CompactionState()) : CompactionStore {
    private val lock = Any()
    private var state = initial

    override suspend fun load(): CompactionState = synchronized(lock) { state }

    override suspend fun save(state: CompactionState) {
        synchronized(lock) { this.state = state }
    }

    override suspend fun clear() {
        synchronized(lock) { state = CompactionState() }
    }
}
