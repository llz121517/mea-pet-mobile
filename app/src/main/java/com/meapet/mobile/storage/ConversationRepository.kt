package com.meapet.mobile.storage

import com.meapet.mobile.chat.ChatMessage

/**
 * 会话历史的持久化接口：UI 展示用的完整历史永久保存在这里，
 * 与发给模型的窗口（由 ConversationManager 在内存中维护）解耦。
 *
 * 约定：
 * - 消息顺序以写入顺序（seq）为准，不依赖 timestamp；
 * - 同一 id 重复写入被忽略（幂等）；
 * - 不持久化 isStreaming 等运行时状态。
 */
interface ConversationRepository {
    /** 追加一条消息（id 已存在则忽略）。 */
    suspend fun append(message: ChatMessage)

    /**
     * 在一个事务里批量追加多条消息（id 已存在则忽略，保持传入顺序）。
     *
     * 一个 turn 的 user + 中间 assistant(tool_calls) + tool 结果 + 最终回复必须**同生共死**：
     * 逐条 append 中间崩溃会留下「有 tool_calls 没结果」的残轮，服务端下一次请求直接 400。
     * 因此 ChatService 成功时只调用本方法一次。
     */
    suspend fun appendAll(messages: List<ChatMessage>)

    /** 按 id 删除消息。@return 是否有消息被删除 */
    suspend fun remove(id: String): Boolean

    /** 清空当前会话的全部消息。 */
    suspend fun clear()

    /** 用给定列表整体替换当前会话（撤销清除用），按传入顺序写入。 */
    suspend fun replaceAll(messages: List<ChatMessage>)

    /** 取最近 [limit] 条消息，按时间正序返回。 */
    suspend fun loadRecent(limit: Int): List<ChatMessage>

    /**
     * keyset 分页：取 [beforeId] 所指消息之前的 [limit] 条，按时间正序返回；
     * [beforeId] 为 null 时等价于 [loadRecent]。
     */
    suspend fun pageBefore(beforeId: String?, limit: Int): List<ChatMessage>

    /** 当前会话消息总数。 */
    suspend fun count(): Int

    /**
     * 是否存在指定 id 的消息。
     *
     * 备份导入校验压缩摘要的 `coveredToMessageId` 时使用：该 id 在新库里不存在时，
     * 必须丢弃旧摘要，避免把旧摘要注入新会话（见 PRD/LLD 的 G2 同类问题）。
     */
    suspend fun contains(id: String): Boolean
}

/** 纯内存实现：JVM 单测与无库场景使用，不落盘。 */
class InMemoryConversationRepository : ConversationRepository {
    private val lock = Any()
    private val items = mutableListOf<ChatMessage>()

    override suspend fun append(message: ChatMessage) {
        synchronized(lock) {
            if (items.none { it.id == message.id }) items.add(message)
        }
    }

    override suspend fun appendAll(messages: List<ChatMessage>) {
        synchronized(lock) {
            messages.forEach { m -> if (items.none { it.id == m.id }) items.add(m) }
        }
    }

    override suspend fun remove(id: String): Boolean =
        synchronized(lock) { items.removeAll { it.id == id } }

    override suspend fun clear() {
        synchronized(lock) { items.clear() }
    }

    override suspend fun replaceAll(messages: List<ChatMessage>) {
        synchronized(lock) {
            items.clear()
            messages.forEach { m -> if (items.none { it.id == m.id }) items.add(m) }
        }
    }

    override suspend fun loadRecent(limit: Int): List<ChatMessage> = pageBefore(null, limit)

    override suspend fun pageBefore(beforeId: String?, limit: Int): List<ChatMessage> =
        synchronized(lock) {
            val end = if (beforeId == null) items.size else items.indexOfFirst { it.id == beforeId }
            if (end <= 0) emptyList() else items.subList((end - limit).coerceAtLeast(0), end).toList()
        }

    override suspend fun count(): Int = synchronized(lock) { items.size }

    override suspend fun contains(id: String): Boolean =
        synchronized(lock) { items.any { it.id == id } }
}
