package com.meapet.mobile.chat

import android.util.Log
import com.meapet.mobile.chat.compaction.CompactionConfig
import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.chat.compaction.CompactionStore
import com.meapet.mobile.chat.compaction.ContextCompactor
import com.meapet.mobile.chat.compaction.TokenEstimator
import com.meapet.mobile.chat.compaction.dropCountToFit
import com.meapet.mobile.chat.compaction.foldCountFor
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.model.ApiRequest
import com.meapet.mobile.client.model.ApiResponse
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryOpsProtocol
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天业务服务。
 *
 * ## 职责
 * - 发送消息 → 调用 [OpenAiCompatibleClient] → 解析响应；
 * - 与 [MemoryManager] 协作注入记忆上下文；
 * - 将对话记录交给 [ConversationManager] 管理；
 * - 每次对话后触发记忆提取；
 * - 上下文压缩：把较早轮次折叠成滚动摘要，摘要并入首条 system 末尾。
 *
 * ## 上下文压缩的两层触发
 * - **手动**（主路径）：上下文估算超过 [CompactionConfig.COMPACT_HINT_TOKENS] 时由 UI 提醒，
 *   用户点「整理一下」才执行（[compactContext]）。
 * - **自动**（仅兜底）：即将超出有效预算 90% 时同步压缩，避免请求被服务端拒绝；
 *   失败则只在发给模型的窗口内按轮丢弃（库里不删，UI 不受影响）。
 *
 * ## 低耦合
 * - 不依赖任何 UI 组件；
 * - 通过 [MemoryManager] 与记忆系统交互（而非直接操作 MemoryRepository）；
 * - 通过 [SettingsManager] 获取配置（而非硬编码）。
 *
 * @param clientProvider 提供 OpenAI 兼容 HTTP 客户端的 provider（每次请求重新获取，
 *   以便 API Key / Base URL 变更重建客户端后立即生效）
 * @param onBeforeSend 发送前的轻量钩子（首次发送且无容量缓存时触发后台探测）
 * @param conversationManager 会话管理器
 * @param memoryManager 记忆管理器（null = 禁用记忆）
 * @param settingsManager 设置管理器
 * @param postProcessScope 事后处理（记忆提取/摘要）用的应用级作用域
 * @param config 应用配置
 * @param compactionStore 压缩状态存储（null = 不启用压缩）
 * @param compactor 上下文压缩器（null = 只做窗口丢弃兜底）
 * @param estimator 本地 token 估算器（用 usage 校准）
 * @param budgetProvider 有效上下文预算（token）；由容量获取模块提供
 */
class ChatService(
    private val clientProvider: () -> OpenAiCompatibleClient,
    /** 首次发送且无容量缓存时触发后台探测（不阻塞本次请求）。 */
    private val onBeforeSend: () -> Unit = {},
    private val conversationManager: ConversationManager,
    private val memoryManager: MemoryManager?,
    private val settingsManager: SettingsManager,
    private val postProcessScope: CoroutineScope,
    private val config: AppConfig = AppConfig.DEFAULT,
    private val compactionStore: CompactionStore? = null,
    private val compactor: ContextCompactor? = null,
    private val estimator: TokenEstimator = TokenEstimator(),
    private val budgetProvider: () -> Int = { CompactionConfig.COMPACT_HINT_TOKENS },
    /**
     * 记忆工具循环（null = 未启用）。仅在 [AppConfig.enableMemoryTools] 为 true 时使用，
     * 否则走原来的单次请求 + 文本协议链路。
     */
    private val toolLoop: com.meapet.mobile.chat.tools.ChatToolLoop? = null,
    /** 工具执行器（与 [toolLoop] 共用同一实例：暂存写入需要从它身上取回）。 */
    private val memoryTools: com.meapet.mobile.memory.tools.MemoryToolExecutor? = null
) {
    companion object {
        private const val TAG = "ChatService"

        /**
         * 工具模式下的固定说明（拼进首条 system）。
         *
         * 必须**字节级稳定**：它属于请求前缀，含任何动态内容都会破坏缓存。
         * 只讲「何时用」，具体参数由 tools schema 承担。
         */
        internal const val MEMORY_TOOL_HINT: String =
            "【记忆工具】你不确定是否记过用户过去说过的事时，先调用 search_memory 检索；" +
                "用户明确要求记住、忘记或纠正信息时，用 create_memory / update_memory / delete_memory。" +
                "不要记录你自己的提议或猜测。"

        /** 端点「不支持 tools」的典型状态码（OpenAI 兼容网关多为 400，部分为 422）。 */
        private val TOOLS_UNSUPPORTED_STATUS = setOf(400, 404, 422, 501)
    }

    /**
     * 当前端点是否已被判定为不支持 tools（会话内缓存，避免每轮都先失败一次）。
     * 一旦置位，本进程内退回单次请求（文本协议）。
     */
    @Volatile
    private var toolsUnsupported = false

    /** 压缩状态（内存缓存，首次访问时从存储加载）。 */
    @Volatile
    private var compactionState = CompactionState()

    @Volatile
    private var compactionLoaded = false

    /** 最近一次「即将发送的上下文」的本地估算 token（UI 提示与调试用）。 */
    @Volatile
    private var lastContextTokens = 0

    /**
     * 发送消息并获取 AI 回复。
     *
     * @param content 用户消息文本
     * @return 包含用户消息与 AI 回复的 Pair
     */
    suspend fun sendMessage(content: String): Result<Pair<ChatMessage, ChatMessage>> =
        sendMessage(ChatMessage(role = ChatRole.user, content = content))

    /**
     * 发送一条**已构建好**的用户消息并获取 AI 回复。
     *
     * 调用方自带 [ChatMessage] 的意义：UI 层的乐观消息与入史的消息是同一个 id。
     * 若各建一条，同一句话就有两个 id，`ChatViewModel.mergeWithHistory` 无法把它们
     * 认作同一条，一次 `reloadHistory()` 就会并列成两条。
     *
     * @param userMessage 用户消息（id 由调用方决定）
     * @return 包含用户消息与 AI 回复的 Pair
     */
    suspend fun sendMessage(userMessage: ChatMessage): Result<Pair<ChatMessage, ChatMessage>> {
        return withContext(Dispatchers.IO) {
            val content = userMessage.content
            try {
                onBeforeSend()

                // 工具模式的写类操作是「turn 内暂存、成功后提交」：每轮开始先清理上一轮的残留
                if (config.enableMemoryTools) memoryTools?.discardPending()

                // 1) 用户消息先入史：apiMessages 由历史构建，当前消息必须已在其中。
                //    代价是任何失败路径都必须回滚，否则留下从未发出的"幽灵消息"
                //    （污染后续请求上下文，且会被持久化）。
                conversationManager.addMessage(userMessage)

                // 2) 获取记忆上下文（拆成稳定段与每轮都变的尾部段，见 MemoryContext）。
                //    工具模式下不再注入文本协议说明与关键词回忆：记忆通过 search_memory 工具检索，
                //    两套协议同时存在会让模型在「输出围栏块」和「调用工具」之间摇摆。
                val toolsOn = config.enableMemoryTools && !toolsUnsupported
                val memoryContext = if (toolsOn) null else memoryManager?.buildContext(content)
                val stableContext = if (toolsOn) MEMORY_TOOL_HINT else (memoryContext?.stable ?: "")

                // 3) 获取设置
                val systemPrompt = settingsManager.getSystemPrompt()
                val model = settingsManager.getModel()
                val temperature = settingsManager.getTemperature()
                val maxTokens = settingsManager.getMaxTokens()

                // 4) 构建 API 请求。时间与记忆回忆一起并入最后一条 user：都是每轮都变的内容，
                //    放前面会让排在其后的全部历史都无法命中服务端 prefix cache
                val tailFragments = listOfNotNull(
                    TimeContext.describe(),
                    memoryContext?.tail?.takeIf { it.isNotBlank() }
                )

                // 4b) 上下文压缩：先取状态与「已覆盖多少」，再判断是否需要硬触发兜底
                var state = ensureCompactionLoaded()
                val window = conversationManager.getMessages().filter { it.role != ChatRole.system }
                val budget = budgetProvider()
                var skipOldest = resolveSkip(window, state)
                var usable = window.drop(skipOldest)
                val overhead = estimator.estimate(systemPrompt) +
                    estimator.estimate(memoryContext?.stable ?: "") +
                    estimator.estimate(composeTail(tailFragments)) +
                    estimator.estimate(state.summary)
                var estimated = overhead + estimator.estimate(usable)

                if (budget > 0 && estimated > budget * CompactionConfig.HARD_TRIGGER_RATIO) {
                    // 自动压缩只做兜底：小窗口直接放弃摘要，只截断
                    if (budget > CompactionConfig.SMALL_WINDOW_TOKENS) {
                        val fold = foldCountFor(usable)
                        val newSummary = if (fold > 0) compactor?.compact(usable, state.summary, fold) else null
                        if (newSummary != null) {
                            state = state.copy(
                                summary = newSummary,
                                coveredToMessageId = usable[fold - 1].id,
                                coveredCount = skipOldest + fold,
                                updatedAt = System.currentTimeMillis()
                            )
                            compactionState = state
                            compactionStore?.save(state)
                            skipOldest += fold
                            usable = window.drop(skipOldest)
                            estimated = overhead + estimator.estimate(usable)
                            Log.i(TAG, "Auto compaction done (hard trigger), $fold message(s) folded")
                        }
                    }
                    // 压缩失败或仍超预算：只在发给模型的窗口内按轮丢弃（库里一条不删）
                    if (estimated > budget) {
                        val drop = dropCountToFit(
                            usable,
                            estimator,
                            (budget - overhead).coerceAtLeast(1)
                        )
                        if (drop > 0) {
                            skipOldest += drop
                            usable = window.drop(skipOldest)
                            estimated = overhead + estimator.estimate(usable)
                            Log.w(TAG, "Hard window trim: dropped $drop message(s) from send window")
                        }
                    }
                }
                lastContextTokens = estimated

                // 记忆关闭时 stable 为空（模型压根没收到协议说明），历史里也不该回贴协议块
                val memoryOn = stableContext.isNotBlank() && !toolsOn
                val compactionEnabled = compactionStore != null && compactor != null
                val apiMessages = conversationManager.buildApiMessages(
                    systemPrompt = systemPrompt,
                    stableContext = stableContext,
                    tailFragments = tailFragments,
                    maxMessages = if (compactionEnabled) Int.MAX_VALUE else config.maxHistoryMessages,
                    memoryOpsEchoTurns = if (memoryOn) config.memoryOpsEchoTurns else 0,
                    summary = state.summary,
                    skipOldest = skipOldest
                )

                val jsonMessages = apiMessages.map { msg ->
                    ApiRequest.textMessage(msg.role.name, msg.content)
                }

                val requestBody = ApiRequest.chatCompletion(
                    model = model,
                    messages = jsonMessages,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    stream = false
                )

                Log.d(
                    TAG,
                    "Sending request to $model (${apiMessages.size} messages, " +
                        "${apiMessages.sumOf { it.content.length }} chars, ~$estimated tokens)"
                )
                // 校准用的原始估算（未乘校准系数），与 usage.prompt_tokens 同口径比较
                val rawEstimate = apiMessages.sumOf { estimator.rawTokens(it) }

                // 5) 发送请求。工具模式下走工具循环（可能多轮）；端点明确不支持 tools 时
                //    本次自动降级为单次请求，并记住「本会话不再尝试工具」，保证聊天不中断。
                val loop = if (toolsOn) toolLoop else null
                val assistantContent: String? = if (loop != null) {
                    try {
                        val loopResult = loop.run(model, jsonMessages, temperature, maxTokens)
                        // 用第一轮的真实 prompt_tokens 校准本地估算
                        loopResult.firstPromptTokens?.let { actual -> estimator.calibrate(rawEstimate, actual) }
                        loopResult.finalContent
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is com.meapet.mobile.client.exception.ApiException &&
                            e.statusCode in TOOLS_UNSUPPORTED_STATUS
                        ) {
                            Log.w(TAG, "Endpoint rejected tools (HTTP ${e.statusCode}), falling back to single request")
                            toolsUnsupported = true
                            val fallbackJson = clientProvider().chatCompletion(requestBody)
                            ApiResponse.usagePromptTokens(fallbackJson)?.let { actual ->
                                estimator.calibrate(rawEstimate, actual)
                            }
                            ApiResponse.chatCompletionContent(fallbackJson)?.takeIf { it.isNotBlank() }
                        } else {
                            Log.w(TAG, "Tool loop failed", e)
                            conversationManager.removeMessage(userMessage.id)
                            return@withContext Result.failure(e)
                        }
                    }
                } else {
                    val responseJson = clientProvider().chatCompletion(requestBody)
                    // 5b) 用服务端真实 prompt_tokens 校准本地估算（缺 usage 时保持原比值）
                    ApiResponse.usagePromptTokens(responseJson)?.let { actual ->
                        estimator.calibrate(rawEstimate, actual)
                    }
                    ApiResponse.chatCompletionContent(responseJson)?.takeIf { it.isNotBlank() }
                }

                // 6) 解析响应（choices 为空或 content 缺失视为失败，不入史）
                if (assistantContent == null) {
                    Log.w(TAG, "Unexpected API response: missing choices or content")
                    conversationManager.removeMessage(userMessage.id)
                    return@withContext Result.failure(
                        IllegalStateException("API 响应中没有有效的回复内容")
                    )
                }
                // 6b) 剥离模型附在回复末尾的记忆协议块（对用户不可见，见 MemoryOpsProtocol）
                val parsed = MemoryOpsProtocol.extract(assistantContent)
                val memoryOps = parsed.ops
                val assistantMessage = ChatMessage(
                    role = ChatRole.assistant,
                    content = parsed.visibleReply,
                    // 块留在消息上（不展示），下一轮贴回历史当格式范例，见 ConversationManager
                    memoryOpsBlock = parsed.rawBlock
                )
                conversationManager.addMessage(assistantMessage)

                // 工具模式：整个 turn 成功后才提交暂存写入（失败/取消已在 catch/提前 return 中丢弃）
                if (config.enableMemoryTools) {
                    val pending = memoryTools?.drainPending()
                    if (pending != null && !pending.isEmpty) {
                        memoryManager?.applyToolChanges(pending)
                    }
                }

                // 7) 事后处理：应用记忆操作/触发摘要转后台，不阻塞本次回复返回
                //   （摘要是一次额外 LLM 请求，同步等它会让 UI 在拿到回复后仍长时间显示加载中）
                memoryManager?.let { mm ->
                    postProcessScope.launch {
                        try {
                            mm.onExchangeComplete(memoryOps)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Memory post-processing failed", e)
                        }
                    }
                }

                Log.d(TAG, "Response received (${parsed.visibleReply.length} chars)")
                Result.success(userMessage to assistantMessage)

            } catch (e: CancellationException) {
                // 取消同样要回滚：清空会话会取消在途请求，留下的消息既无回复也无人清理
                conversationManager.removeMessage(userMessage.id)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                conversationManager.removeMessage(userMessage.id)
                Result.failure(e)
            }
        }
    }

    /**
     * 重新发送指定的用户消息（用于失败重试）。
     *
     * **重试目标必须由调用方给出**，不能在这里"取历史里最后一条 user"——发送失败时
     * 该消息已被回滚出历史，那样取到的是**上一条早已成功的消息**，会把它重发一遍，
     * 并在 UI 列表里造出同一个 id 的两份副本（LazyColumn 重复 key 直接崩溃）。
     *
     * @param userMessage 要重发的用户消息（沿用原 id 与 timestamp）
     */
    suspend fun retryMessage(userMessage: ChatMessage): Result<Pair<ChatMessage, ChatMessage>> {
        val history = conversationManager.getMessages()
        val userIdx = history.indexOfLast { it.id == userMessage.id }
        if (userIdx >= 0) {
            // 把该条 user 之后的 assistant 回复（若有）连同 user 本身一并移除，
            // 重发时由 sendMessage 统一重新入史，避免消息重复。
            // drop 只在 userIdx >= 0 时执行：userIdx == -1 时 drop(0) 等于「整个历史」，
            // 会把历史里所有 assistant 回复一并误删。
            history.drop(userIdx + 1)
                .filter { it.role == ChatRole.assistant }
                .forEach { conversationManager.removeMessage(it.id) }
            conversationManager.removeMessage(userMessage.id)
        } else {
            // 正常情况：发送失败已把它回滚出历史。也可能是历史被裁剪或清空。
            // 直接按新消息重发即可。
            Log.d(TAG, "Retry target not in history, sending as a new message")
        }

        return sendMessage(userMessage)
    }

    // ── 上下文压缩 ────────────────────────────────────

    /** 最近一次即将发送的上下文估算 token 数（UI 判断是否显示压缩提醒）。 */
    fun currentContextTokens(): Int = lastContextTokens

    /**
     * 手动压缩上下文（用户点「整理一下」/菜单「压缩上下文」）。
     *
     * 与自动压缩共用同一套滚动摘要与按轮对齐规则；失败时给出可展示的原因，不做重试风暴。
     *
     * @return 成功时返回本次折叠的消息条数（供 UI 反馈），失败时返回可展示的原因
     */
    suspend fun compactContext(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val store = compactionStore
                ?: return@withContext Result.failure(IllegalStateException("当前不支持整理上下文"))
            val state = ensureCompactionLoaded()
            val window = conversationManager.getMessages().filter { it.role != ChatRole.system }
            val skip = resolveSkip(window, state)
            val usable = window.drop(skip)
            val fold = foldCountFor(usable)
            if (fold <= 0) {
                return@withContext Result.failure(
                    IllegalStateException("对话还不够长，暂时不用整理喵")
                )
            }
            val newSummary = compactor?.compact(usable, state.summary, fold)
                ?: return@withContext Result.failure(
                    IllegalStateException("整理失败了，稍后再试试喵")
                )
            val newState = state.copy(
                summary = newSummary,
                coveredToMessageId = usable[fold - 1].id,
                coveredCount = skip + fold,
                updatedAt = System.currentTimeMillis()
            )
            store.save(newState)
            compactionState = newState
            refreshContextTokens()
            Log.i(TAG, "Manual compaction done, $fold message(s) folded")
            Result.success(fold)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Manual compaction failed", e)
            Result.failure(e)
        }
    }

    /** 重新估算「即将发送的上下文」并写入 [lastContextTokens]（压缩后 UI 立即刷新）。 */
    suspend fun refreshContextTokens(): Int = withContext(Dispatchers.IO) {
        val window = conversationManager.getMessages().filter { it.role != ChatRole.system }
        val state = ensureCompactionLoaded()
        val skip = resolveSkip(window, state)
        val overhead = estimator.estimate(settingsManager.getSystemPrompt()) +
            estimator.estimate(state.summary)
        lastContextTokens = overhead + estimator.estimate(window.drop(skip))
        lastContextTokens
    }

    /** 当前压缩状态（UI 用来画「早些时候」分隔条）。 */
    suspend fun compaction(): CompactionState = ensureCompactionLoaded()

    /** 当前是否应该向用户提示压缩（超过 [CompactionConfig.COMPACT_HINT_TOKENS]）。 */
    fun shouldHintCompaction(): Boolean =
        lastContextTokens >= CompactionConfig.COMPACT_HINT_TOKENS

    private suspend fun ensureCompactionLoaded(): CompactionState = withContext(Dispatchers.IO) {
        if (!compactionLoaded) {
            compactionState = runCatching { compactionStore?.load() }.getOrNull() ?: CompactionState()
            compactionLoaded = true
        }
        compactionState
    }

    /**
     * 已覆盖消息在窗口中的条数：找 `coveredToMessageId` 的位置。
     * 找不到（窗口被裁剪/清空重建）时返回 0——摘要保留，最多重复一点内容，不影响正确性。
     */
    private fun resolveSkip(window: List<ChatMessage>, state: CompactionState): Int {
        val id = state.coveredToMessageId ?: return 0
        val idx = window.indexOfLast { it.id == id }
        return if (idx >= 0) idx + 1 else 0
    }

    /** 获取当前会话历史（仅内存窗口，即发给模型的那部分）。 */
    fun getHistory(): List<ChatMessage> = conversationManager.getMessages()

    /** 完整历史（撤销「清除对话」的快照用，不受内存窗口裁剪影响）。 */
    suspend fun loadFullHistory(): List<ChatMessage> = conversationManager.loadFullHistory()

    /** UI 首屏：完整历史里最近 [limit] 条。 */
    suspend fun loadRecentForUi(limit: Int): List<ChatMessage> = conversationManager.loadRecentForUi(limit)

    /** UI 懒加载：[beforeId] 之前的 [limit] 条完整历史（按时间正序）。 */
    suspend fun loadOlder(beforeId: String, limit: Int): List<ChatMessage> =
        conversationManager.pageBefore(beforeId, limit)

    /** 清除历史（同时清除压缩状态：摘要已不对应任何现存历史）。 */
    fun clearHistory() {
        conversationManager.clear()
        compactionState = CompactionState()
        compactionLoaded = true
        lastContextTokens = 0
        postProcessScope.launch {
            runCatching { compactionStore?.clear() }
                .onFailure { Log.w(TAG, "Failed to clear compaction state", it) }
        }
        Log.i(TAG, "Chat history cleared")
    }

    /**
     * 用快照恢复历史（撤销「清除对话」用）。
     *
     * @param snapshot 清空前的 [getHistory] 结果
     */
    fun restoreHistory(snapshot: List<ChatMessage>) {
        conversationManager.replaceAll(snapshot)
        Log.i(TAG, "Chat history restored from undo snapshot (${snapshot.size} messages)")
    }
}
