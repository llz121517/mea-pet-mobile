package com.meapet.mobile.chat

import android.util.Log
import com.meapet.mobile.storage.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 会话历史管理器。
 *
 * ## 职责
 * - 维护**发给模型的内存窗口**：添加、查询、清空，超出 [maxSize] 时丢弃最早的非 system 消息；
 * - 为 API 请求组装消息列表（含 system prompt）；
 * - 把每次变更按顺序写入 [ConversationRepository]（可选）。
 *
 * ## 与 UI 历史解耦
 * 内存窗口被裁剪**不影响**仓库：仓库保存完整历史（UI 通过仓库分页读取），
 * 窗口只决定本轮发给模型多少上下文。
 *
 * ## 持久化顺序
 * 所有写操作在持锁时入同一个有序队列，由单个消费协程依次执行，
 * 因此仓库里的顺序与内存一致；[flush] 等队列排空后返回。
 *
 * ## 线程安全
 * 所有读写方法用 [lock] 串行化。
 *
 * @param maxSize 内存窗口最大消息数
 * @param trimBatch 超限时一次裁掉的条数（见 [trimWindow]）
 * @param repository 会话仓库（null = 纯内存，不落库）
 * @param persistScope 落库消费协程的作用域（应用级；与 repository 同时提供才会落库）
 */
class ConversationManager(
    private val maxSize: Int = 50,
    private val trimBatch: Int = 1,
    private val repository: ConversationRepository? = null,
    persistScope: CoroutineScope? = null
) {
    private val lock = Any()
    private val messages = mutableListOf<ChatMessage>()

    companion object {
        private const val TAG = "ConversationManager"
    }

    private sealed interface PersistOp {
        class Append(val message: ChatMessage) : PersistOp
        class Remove(val id: String) : PersistOp
        object Clear : PersistOp
        class ReplaceAll(val messages: List<ChatMessage>) : PersistOp
        class Flush(val done: CompletableDeferred<Unit>) : PersistOp
    }

    private val persistEnabled = repository != null && persistScope != null
    private val persistQueue = Channel<PersistOp>(Channel.UNLIMITED)

    init {
        if (repository != null && persistScope != null) {
            persistScope.launch(Dispatchers.IO) {
                for (op in persistQueue) {
                    try {
                        when (op) {
                            is PersistOp.Append -> repository.append(op.message)
                            is PersistOp.Remove -> repository.remove(op.id)
                            PersistOp.Clear -> repository.clear()
                            is PersistOp.ReplaceAll -> repository.replaceAll(op.messages)
                            is PersistOp.Flush -> op.done.complete(Unit)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Persist op failed: ${op::class.simpleName}", e)
                        if (op is PersistOp.Flush) op.done.complete(Unit)
                    }
                }
            }
        }
    }

    /** 当前内存窗口内的消息数量。 */
    val size: Int get() = synchronized(lock) { messages.size }

    /** 添加一条消息。自动触发窗口裁剪，并入队落库。 */
    fun addMessage(message: ChatMessage) {
        synchronized(lock) {
            messages.add(message)
            trimWindow()
            enqueue(PersistOp.Append(message))
        }
        // 只记角色与长度，对话内容不进 Logcat（隐私）
        Log.d(TAG, "Message added [${message.role}] (${message.content.length} chars, total: $size)")
    }

    /** 批量添加。 */
    fun addMessages(newMessages: List<ChatMessage>) {
        synchronized(lock) {
            messages.addAll(newMessages)
            trimWindow()
            newMessages.forEach { enqueue(PersistOp.Append(it)) }
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
        Log.i(TAG, "Restored ${persisted.size} messages from storage (window: $size)")
    }

    /** 从仓库取最近 [maxSize] 条恢复进内存窗口（启动预热用）。无仓库时为 no-op。 */
    suspend fun restoreFromRepository() {
        val repo = repository ?: return
        restore(repo.loadRecent(maxSize))
    }

    /**
     * UI 懒加载：从仓库取 [beforeId] 之前的 [limit] 条（完整历史，不受内存窗口裁剪影响）。
     * 先等写队列排空，保证刚发出的消息已落库、分页边界一致。无仓库时返回空。
     */
    suspend fun pageBefore(beforeId: String?, limit: Int): List<ChatMessage> {
        val repo = repository ?: return emptyList()
        flush()
        return repo.pageBefore(beforeId, limit)
    }

    /** 完整历史（清除对话前取撤销快照用）：先排空写队列，再读仓库全部消息；无仓库时退化为内存窗口。 */
    suspend fun loadFullHistory(): List<ChatMessage> {
        val repo = repository ?: return getMessages()
        flush()
        return repo.loadRecent(Int.MAX_VALUE)
    }

    /** UI 首屏：取仓库里最近 [limit] 条；无仓库时退化为内存窗口。 */
    suspend fun loadRecentForUi(limit: Int): List<ChatMessage> {
        val repo = repository ?: return getMessages()
        flush()
        return repo.loadRecent(limit)
    }

    /** 获取内存窗口内的所有消息（不可变快照）。 */
    fun getMessages(): List<ChatMessage> = synchronized(lock) { messages.toList() }

    /**
     * 获取用于 API 请求的消息列表（含 system prompt）。
     *
     * 消息按「是否每轮都变」分层，尽量让前缀在轮与轮之间保持一致，
     * 服务端的自动 prefix cache 才有可能命中：
     * ```
     * system  : systemPrompt + stableContext   ← 基本不变
     * 历史消息 ...                              ← 追加式增长（裁剪见 trimWindow）
     * 最后一条 user 的发出副本 += tail           ← 每轮都变，只并入副本，不进历史
     * ```
     *
     * @param systemPrompt 系统提示词
     * @param stableContext 轮与轮之间基本不变的上下文，拼进首条 system 消息
     * @param tailFragments 每轮都变的上下文片段（时间等，有序），拼成 tail 后按 [tailMode]
     *   并入最后一条 user 的发出副本，**不写入历史**；全部为空则不附加
     * @param tailMode tail 的发送方式，见 [TailMode]
     * @param maxMessages 最大消息数（滑动窗口，从末尾取）
     * @param memoryOpsEchoTurns 最近多少条助手消息要把 [ChatMessage.memoryOpsBlock] 贴回正文
     *   （0 = 不贴）。仅影响发给模型的副本，不改动会话历史本身
     */
    fun buildApiMessages(
        systemPrompt: String,
        stableContext: String = "",
        tailFragments: List<String> = emptyList(),
        tailMode: TailMode = DEFAULT_TAIL_MODE,
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
            addAll(
                applyTail(
                    echoMemoryOps(recentMessages, memoryOpsEchoTurns),
                    composeTail(tailFragments),
                    tailMode
                )
            )
        }
    }

    /** 清除所有消息（内存窗口与仓库中的完整历史）。 */
    fun clear() {
        synchronized(lock) {
            messages.clear()
            enqueue(PersistOp.Clear)
        }
        Log.i(TAG, "Conversation cleared")
    }

    /**
     * 用给定列表整体替换会话历史（撤销「清除对话」用）。
     *
     * 与 [restore] 的区别：不插队、不去重，按传入顺序**原样**恢复并落库。
     *
     * @param snapshot 清空前的 [getMessages] 快照
     */
    fun replaceAll(snapshot: List<ChatMessage>) {
        synchronized(lock) {
            messages.clear()
            messages.addAll(snapshot)
            // 内存窗口只保留发给模型的部分；仓库按完整快照恢复
            trimWindow()
            enqueue(PersistOp.ReplaceAll(snapshot.toList()))
        }
        Log.i(TAG, "Conversation restored from undo snapshot (${snapshot.size} messages)")
    }

    /**
     * 按 id 移除消息。仓库侧无论窗口里有没有都会尝试删除
     * （消息可能已被窗口裁掉，但仍在完整历史里）。
     *
     * @return 内存窗口里是否有消息被移除
     */
    fun removeMessage(id: String): Boolean = synchronized(lock) {
        val removed = messages.removeAll { it.id == id }
        enqueue(PersistOp.Remove(id))
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

    /**
     * 等待此前入队的所有写操作落库完成。
     *
     * 在 `onStop` 等「即将进后台或被杀」的时机调用。纯内存无仓库时为 no-op。
     */
    suspend fun flush() {
        if (!persistEnabled) return
        val done = CompletableDeferred<Unit>()
        persistQueue.send(PersistOp.Flush(done))
        done.await()
    }

    // ── 内部 ──────────────────────────────────────────

    /** 必须在持有 [lock] 时调用，保证入队顺序与内存变更顺序一致。 */
    private fun enqueue(op: PersistOp) {
        if (persistEnabled) persistQueue.trySend(op)
    }

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

    /**
     * 必须在持有 [lock] 时调用。只裁**内存窗口**，仓库里的完整历史不动。
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
