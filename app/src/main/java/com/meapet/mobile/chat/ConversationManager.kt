package com.meapet.mobile.chat

import android.util.Log
import com.meapet.mobile.storage.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    persistScope: CoroutineScope? = null,
    /**
     * 是否启用内存窗口批量裁剪。
     *
     * 开启上下文压缩时置 **false**：窗口追加式增长，由「压缩摘要 + 发送预算」控制发给模型的内容。
     * 否则会出现「消息被裁掉、但还没进摘要」的静默上下文丢失（库里和 UI 还在，模型却完全看不到）。
     * 纯窗口模式（压缩关闭/不可用）仍用 true，按 [maxSize]/[trimBatch] 批量裁剪。
     */
    private val autoTrim: Boolean = true
) {
    private val lock = Any()
    private val messages = mutableListOf<ChatMessage>()

    /** 解析 rawAssistantJson 里的 tool_calls 声明（只读、无并发写）用。 */
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "ConversationManager"
    }

    private sealed interface PersistOp {
        class Append(val message: ChatMessage) : PersistOp

        /** 一个 turn 的全部消息一次事务落库。 */
        class AppendAll(val messages: List<ChatMessage>) : PersistOp
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
                            is PersistOp.AppendAll -> repository.appendAll(op.messages)
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

    /**
     * 批量添加（一个 turn 的片段 + tool 消息 + 最终回复）。
     *
     * 内存里一次性并入，落库只入队**一次** [PersistOp.AppendAll]：
     * 逐条 Append 会让一个 turn 拆成多个写事务，中途被杀就留下半轮。
     */
    fun addMessages(newMessages: List<ChatMessage>) {
        if (newMessages.isEmpty()) return
        synchronized(lock) {
            messages.addAll(newMessages)
            trimWindow()
            enqueue(PersistOp.AppendAll(newMessages.toList()))
        }
        // 只记角色与长度，对话内容不进 Logcat（隐私）
        Log.d(TAG, "Messages added (${newMessages.size} msgs, total: $size)")
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

    /** 从仓库恢复内存窗口（启动预热用）。无仓库时为 no-op。 */
    suspend fun restoreFromRepository() {
        val repo = repository ?: return
        // 关闭批量裁剪时把完整历史装进窗口：否则「已压缩覆盖点」可能落在窗口之外，
        // 导致摘要与原文重复发送（或重复裁剪）
        val limit = if (autoTrim) maxSize else Int.MAX_VALUE
        restore(repo.loadRecent(limit))
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
     * @param includeToolHistory 是否保留工具历史（`assistant.rawAssistantJson` 与 `role=tool`）。
     *   工具模式（端点支持 tools）为 true：原样回传 `tool_calls` / `reasoning_content`；
     *   只读模式或端点不支持 tools 时为 false：过滤 tool 消息、assistant 只留正文
     *   （正文为空的纯 tool_calls 消息一并丢弃），保证请求序列合法。
     */
    fun buildApiMessages(
        systemPrompt: String,
        stableContext: String = "",
        tailFragments: List<String> = emptyList(),
        tailMode: TailMode = DEFAULT_TAIL_MODE,
        maxMessages: Int = 30,
        summary: String = "",
        skipOldest: Int = 0,
        includeToolHistory: Boolean = true
    ): List<ChatMessage> {
        val systemContent = buildString {
            append(systemPrompt)
            if (stableContext.isNotBlank()) {
                append("\n\n$stableContext")
            }
            if (summary.isNotBlank()) {
                // 摘要并入首条 system 末尾（不做独立 system/user 消息，兼容严格模板）
                append("\n\n【对话摘要】（较早内容的压缩，供参考）\n")
                append(summary)
            }
        }

        val recentMessages = synchronized(lock) {
            val all = messages.filter { it.role != ChatRole.system }
            // 已被摘要覆盖的旧消息不再逐字发送，只留摘要
            val afterSkip = if (skipOldest > 0) all.drop(skipOldest.coerceIn(0, all.size)) else all
            afterSkip.takeLast(maxMessages)
        }

        // 先按模式过滤，再清洗成模型能接受的消息序列。
        // 窗口是从「最近 N 条」硬切的，边界可能落在 turn 中间（尤其 maxMessages 很小时），
        // 清洗只做兜底，不重排消息。
        val prepared = if (includeToolHistory) {
            sanitizeToolWindow(recentMessages)
        } else {
            toTextOnlyWindow(recentMessages)
        }

        return buildList {
            add(ChatMessage(role = ChatRole.system, content = systemContent))
            addAll(
                applyTail(
                    prepared,
                    composeTail(tailFragments),
                    tailMode
                )
            )
        }
    }

    /**
     * 工具历史清洗：丢弃找不到声明的 `role=tool` 消息。
     *
     * 合法序列要求每个 tool 结果都能对应到前面某条 assistant 的 `tool_calls[].id`：
     * - 窗口从 turn 中间开始时，开头可能是一条孤立 tool 消息；
     * - 历史里也可能残留失败/被打断轮次的半成品。
     * 服务端遇到孤立 `tool_call_id` 会直接 400，所以宁可少发一条也不能发非法序列。
     * 遇到新的 user 消息时清空已声明集合（工具调用只属于它前面最近的那一批 assistant）。
     */
    private fun sanitizeToolWindow(msgs: List<ChatMessage>): List<ChatMessage> {
        if (msgs.none { it.isTool || it.rawAssistantJson != null }) return msgs
        val declared = HashSet<String>()
        val result = ArrayList<ChatMessage>(msgs.size)
        for (msg in msgs) {
            when (msg.role) {
                ChatRole.system -> result.add(msg)
                ChatRole.user -> {
                    declared.clear()
                    result.add(msg)
                }
                ChatRole.assistant -> {
                    declared += declaredToolCallIds(msg.rawAssistantJson)
                    result.add(msg)
                }
                ChatRole.tool -> {
                    val id = msg.toolCallId
                    if (id != null && id in declared) {
                        result.add(msg)
                    } else {
                        Log.w(TAG, "Dropped orphan tool message (tool_call_id missing declaration)")
                    }
                }
            }
        }
        return result
    }

    /**
     * 纯文本发送窗口：去掉结构化工具消息及字段，保留可见正文。
     *
     * TEXT 模式或端点不支持 tools 时，历史里既不能有 `role=tool`，也不能带 `tool_calls`
     * 的 assistant 原始 JSON（端点会尝试解析不存在的能力）。纯 tool_calls 的 assistant
     * 正文为空，没有可发送的内容，直接丢弃。
     */
    private fun toTextOnlyWindow(msgs: List<ChatMessage>): List<ChatMessage> = buildList {
        for (msg in msgs) {
            when (msg.role) {
                ChatRole.tool -> Unit
                ChatRole.assistant -> {
                    if (msg.content.isNotBlank()) {
                        add(msg.copy(rawAssistantJson = null))
                    }
                }
                else -> add(msg)
            }
        }
    }

    /** 从 assistant 原始 JSON 里取出它声明的 `tool_calls[].id`（解析失败按无声明处理）。 */
    private fun declaredToolCallIds(rawAssistantJson: String?): List<String> {
        if (rawAssistantJson.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(rawAssistantJson).jsonObject["tool_calls"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())
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

    /**
     * 一次从内存窗口移除多条消息，并逐个入队仓库删除（供整轮回滚/重试用）。
     *
     * 「一次移除」指的是内存视图一次性对齐：重试要删掉某条 user 及其之后的所有消息，
     * 分多次调用期间若有新的发送插入，会删到不属于旧轮的消息。
     *
     * @return 实际从内存窗口移除的条数
     */
    fun removeMessages(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val idSet = ids.toSet()
        return synchronized(lock) {
            val before = messages.size
            messages.removeAll { it.id in idSet }
            // 入队必须在持锁期间完成，保证与内存变更同序（见 enqueue 注释）
            idSet.forEach { enqueue(PersistOp.Remove(it)) }
            before - messages.size
        }
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
     * 必须在持有 [lock] 时调用。只裁**内存窗口**，仓库里的完整历史不动。
     *
     * 超限时一次裁掉 [trimBatch] 条而非刚好一条：逐条裁剪会让每轮请求的消息前缀
     * 都往后挪一格，服务端的自动 prefix cache 全程无法命中。批量裁一次，
     * 之后的 [trimBatch] 轮前缀完全相同。代价是有效窗口在 maxSize-trimBatch..maxSize 间浮动。
     */
    private fun trimWindow() {
        // 压缩模式：窗口追加式增长，不再批量裁剪（由压缩/预算控制发送量）
        if (!autoTrim) return
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
