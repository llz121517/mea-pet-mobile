package com.meapet.mobile.chat

import android.util.Log

/**
 * 会话历史管理器。
 *
 * ## 职责
 * - 维护消息列表的添加、查询、清空；
 * - 实现滑动窗口：超出 [maxSize] 时丢弃最早的非 system 消息；
 * - 为 API 请求组装消息列表（含 system prompt）；
 * - 消息变更后通过 [ConversationStore] 异步持久化（可选）。
 *
 * ## 低耦合
 * - 内存操作 + 可选的存储回调，不依赖其他业务模块；
 * - 但**确实关心消息角色**：system 消息不参与窗口裁剪（见 [trimWindow]）、
 *   也不进 API 历史（[buildApiMessages] 自行重组 system 前缀），
 *   并提供 [lastUserMessage] / [lastAssistantMessage] 按角色查询。
 *
 * ## 线程安全
 * 启动时 [restore] 在 IO 线程执行，可能与发送链路并发访问，
 * 所有读写方法用 [lock] 串行化。
 *
 * @param maxSize 最大保留消息数
 * @param trimBatch 超限时一次裁掉的条数（见 [trimWindow]）
 * @param store 会话持久化存储（null = 纯内存，不落盘）
 */
class ConversationManager(
    private val maxSize: Int = 50,
    private val trimBatch: Int = 1,
    private val store: ConversationStore? = null
) {
    private val lock = Any()
    private val messages = mutableListOf<ChatMessage>()

    companion object {
        private const val TAG = "ConversationManager"
    }

    /** 当前消息数量。 */
    val size: Int get() = synchronized(lock) { messages.size }

    /** 添加一条消息。自动触发窗口裁剪。 */
    fun addMessage(message: ChatMessage) {
        synchronized(lock) {
            messages.add(message)
            trimWindow()
            persistLocked()
        }
        // 只记角色与长度，对话内容不进 Logcat（隐私）
        Log.d(TAG, "Message added [${message.role}] (${message.content.length} chars, total: $size)")
    }

    /** 批量添加。 */
    fun addMessages(newMessages: List<ChatMessage>) {
        synchronized(lock) {
            messages.addAll(newMessages)
            trimWindow()
            persistLocked()
        }
    }

    /**
     * 恢复持久化的会话历史（启动加载用）。
     *
     * 与 [addMessages] 的区别：插到已有消息**前面**（加载完成前可能已产生新对话），
     * 按 id 去重，且不触发回写（避免加载即覆盖）。
     */
    fun restore(persisted: List<ChatMessage>) {
        if (persisted.isEmpty()) return
        synchronized(lock) {
            val existingIds = messages.map { it.id }.toSet()
            messages.addAll(0, persisted.filter { it.id !in existingIds })
            trimWindow()
        }
        Log.i(TAG, "Restored ${persisted.size} messages from disk (total: $size)")
    }

    /** 获取所有消息（不可变快照）。 */
    fun getMessages(): List<ChatMessage> = synchronized(lock) { messages.toList() }

    /**
     * 获取用于 API 请求的消息列表（含 system prompt）。
     *
     * 消息按「是否每轮都变」分层，尽量让前缀在轮与轮之间保持一致，
     * 服务端的自动 prefix cache 才有可能命中：
     * ```
     * system  : systemPrompt + stableContext   ← 基本不变
     * 历史消息 ...                              ← 追加式增长（裁剪见 trimWindow）
     * system  : tailContext                    ← 每轮都变，但压在最尾部
     * ```
     *
     * @param systemPrompt 系统提示词
     * @param stableContext 轮与轮之间基本不变的上下文，拼进首条 system 消息
     * @param tailContext 每轮都变的上下文（时间、相关回忆、收尾提醒），
     *   作为对话历史之后的尾部 system 消息；空则不生成该条
     * @param maxMessages 最大消息数（滑动窗口，从末尾取）
     * @param memoryOpsEchoTurns 最近多少条助手消息要把 [ChatMessage.memoryOpsBlock] 贴回正文
     *   （0 = 不贴）。仅影响发给模型的副本，不改动会话历史本身
     */
    fun buildApiMessages(
        systemPrompt: String,
        stableContext: String = "",
        tailContext: String = "",
        maxMessages: Int = 30,
        memoryOpsEchoTurns: Int = 0
    ): List<ChatMessage> {
        val systemContent = buildString {
            append(systemPrompt)
            if (stableContext.isNotBlank()) {
                append("\n\n$stableContext")
            }
        }

        val recentMessages = synchronized(lock) {
            messages.filter { it.role != ChatRole.system }.takeLast(maxMessages)
        }

        return buildList {
            add(ChatMessage(role = ChatRole.system, content = systemContent))
            addAll(echoMemoryOps(recentMessages, memoryOpsEchoTurns))
            if (tailContext.isNotBlank()) {
                add(ChatMessage(role = ChatRole.system, content = tailContext))
            }
        }
    }

    /** 清除所有消息。 */
    fun clear() {
        synchronized(lock) {
            messages.clear()
            persistLocked()
        }
        Log.i(TAG, "Conversation cleared")
    }

    /** 按 id 移除消息。@return 是否有消息被移除 */
    fun removeMessage(id: String): Boolean = synchronized(lock) {
        val removed = messages.removeAll { it.id == id }
        if (removed) persistLocked()
        removed
    }

    /** 获取最后一条 user 消息（不含 assistant / system）。 */
    fun lastUserMessage(): ChatMessage? = synchronized(lock) {
        messages.lastOrNull { it.role == ChatRole.user }
    }

    /** 获取最后一条助手消息。 */
    fun lastAssistantMessage(): ChatMessage? = synchronized(lock) {
        messages.lastOrNull { it.role == ChatRole.assistant }
    }

    // ── 内部 ──────────────────────────────────────────

    /**
     * 把最近 [turns] 条助手消息的 [ChatMessage.memoryOpsBlock] 贴回正文，
     * 让模型在历史里能看到自己写过的协议块正例。
     *
     * 只回贴**真实**存过的块；没有块的轮次保持原样，不注入合成 few-shot。
     * 只影响发给模型的副本，不改动会话历史本身。
     */
    private fun echoMemoryOps(msgs: List<ChatMessage>, turns: Int): List<ChatMessage> {
        if (turns <= 0) return msgs

        val echoIds = msgs.asReversed()
            .filter { it.role == ChatRole.assistant && !it.memoryOpsBlock.isNullOrBlank() }
            .take(turns)
            .mapTo(mutableSetOf()) { it.id }

        if (echoIds.isEmpty()) return msgs

        return msgs.map { msg ->
            if (msg.id in echoIds) msg.copy(content = "${msg.content}\n\n${msg.memoryOpsBlock}") else msg
        }
    }

    /** 必须在持有 [lock] 时调用。 */
    private fun persistLocked() {
        store?.persistAsync(messages.toList())
    }

    /**
     * 必须在持有 [lock] 时调用。
     *
     * 超限时一次裁掉 [trimBatch] 条而非刚好一条：逐条裁剪会让每轮请求的消息前缀
     * 都往后挪一格，服务端的自动 prefix cache 全程无法命中。批量裁一次，
     * 之后的 [trimBatch] 轮前缀完全相同。代价是有效窗口在 maxSize-trimBatch..maxSize 间浮动。
     */
    private fun trimWindow() {
        if (messages.size <= maxSize) return
        val systemCount = messages.count { it.role == ChatRole.system }
        val nonSystemCount = messages.size - systemCount
        val budget = (maxSize - systemCount - trimBatch).coerceAtLeast(1)
        val excess = nonSystemCount - budget
        if (excess > 0) {
            // 就地从头部逐条裁掉最旧的 excess 条非 system 消息。
            // 不能用「system 提前 + 尾部拼接」重建：会把 system 消息整体挪到最前，
            // 打乱与 user/assistant 的原始交错顺序。
            var toRemove = excess
            val iterator = messages.iterator()
            while (toRemove > 0 && iterator.hasNext()) {
                if (iterator.next().role != ChatRole.system) {
                    iterator.remove()
                    toRemove--
                }
            }
            Log.d(TAG, "Window trimmed: removed $excess messages (batch=$trimBatch)")
        }
    }
}
