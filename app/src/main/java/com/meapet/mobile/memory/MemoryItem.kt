package com.meapet.mobile.memory

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 记忆类型枚举。
 *
 * - SHORT_TERM: 短期记忆，当前对话上下文中提取的即时信息。
 * - LONG_TERM:   长期记忆，经摘要压缩后的持久化知识。
 * - CORE_TRAIT:  用户核心特质（如偏好、性格习惯）。永不自动淘汰。
 * - FACTUAL:     事实性信息（如用户姓名、宠物名字）。永不自动淘汰。
 */
enum class MemoryType {
    SHORT_TERM,
    LONG_TERM,
    CORE_TRAIT,
    FACTUAL;

    /**
     * 是否为永不自动淘汰的永久类（核心特质 / 事实）。
     *
     * 永久类不允许被工具改类型，删除必须显式确认（见 `MemoryToolExecutor`）。
     */
    val isPermanent: Boolean get() = this == CORE_TRAIT || this == FACTUAL
}

/**
 * 记忆条目——记忆系统的最小数据单元。
 *
 * 记忆条目的显式创建、修改由记忆工具负责；这里仅定义存储数据结构。
 *
 * @property id 唯一标识（短 id，见 [newId]）
 * @property content 记忆文本内容（自然语言表述）
 * @property type 记忆类型
 * @property importance 重要性评分 0.0~1.0（越高越不易被遗忘，由模型给出）
 * @property createdAt 创建时间戳
 * @property lastAccessedAt 最后访问时间戳（用于 LRU 淘汰）
 * @property accessCount 访问次数
 * @property sourceMessageId 来源消息 ID（关联回对话）
 * @property keywords 查找词（模型创建时给出，3~8 个：用户以后聊相关话题时会说出口的词，
     *   覆盖实体与类别，而非从 content 机械摘词）；[MemoryRepository.getRelevant] 仅匹配这份列表
 */
@Serializable
data class MemoryItem(
    val id: String,
    val content: String,
    val type: MemoryType = MemoryType.SHORT_TERM,
    val importance: Float = 0.5f,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = createdAt,
    val accessCount: Int = 1,
    val sourceMessageId: String? = null,
    val keywords: List<String> = emptyList()
) {
    /** 标记访问（更新访问时间与计数）。 */
    fun accessed(): MemoryItem = copy(
        lastAccessedAt = System.currentTimeMillis(),
        accessCount = accessCount + 1
    )

    /** 提升/降低重要性。 */
    fun withImportance(delta: Float): MemoryItem =
        copy(importance = (importance + delta).coerceIn(0f, 1f))

    companion object {
        private const val ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

        /**
         * 生成短 id（如 `mem_a1b2c3d4`）。
         *
         * 短 id 用于工具返回的记忆条目标识；比完整 UUID 更省传输空间，也更容易准确引用。
         */
        fun newId(): String {
            val suffix = (1..8).map { ID_CHARS.random(Random) }.joinToString("")
            return "mem_$suffix"
        }
    }
}

/**
 * 记忆系统统计信息。
 */
data class MemoryStats(
    val totalCount: Int = 0,
    val shortTermCount: Int = 0,
    val longTermCount: Int = 0,
    val coreTraitsCount: Int = 0,
    val factualsCount: Int = 0,
    val averageImportance: Float = 0f
)
