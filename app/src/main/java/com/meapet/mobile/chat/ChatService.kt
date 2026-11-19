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
import com.meapet.mobile.client.exception.ApiException
import com.meapet.mobile.client.model.ApiRequest
import com.meapet.mobile.client.model.ApiResponse
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.config.MemoryMode
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryOpsProtocol
import com.meapet.mobile.settings.SettingsManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
 * ## 一次 turn 的完整模型
 * 一轮不再等于「一条 user + 一条 assistant」：工具模式下模型可能先给一段文字并调用工具，
 * 执行后再说下一段，最后才给最终回复。因此一轮由若干消息组成：
 * ```
 * user(turnId) → assistant(turnId, 可能为空, rawAssistantJson) → tool(turnId) → … → assistant(最终)
 * ```
 * 所有消息共用同一个 [ChatMessage.turnId]，成功时由 [ConversationManager.addMessages]
 * **一个事务**落库；失败/超时整轮回滚（只移除 user）；被插队/停止时按已完成部分提交
 * （只提交正文非空的 assistant 片段，丢掉 tool_calls 与 tool 消息，避免留下无法配对的半轮）。
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
     * 本轮输出上限（`max_tokens`）的上界，由容量适配器给出（容量约 25%）。
     *
     * 上下文窗口是输入输出合计：窗口很小时，用户设置的大 `max_tokens` 会把输入预算
     * 吃穿、导致请求直接被服务端拒绝。默认 [Int.MAX_VALUE] = 不夹（测试/无容量信息时）。
     */
    private val maxTokensCapProvider: () -> Int = { Int.MAX_VALUE },
    /**
     * 记忆工具循环（null = 未启用）。仅在 [AppConfig.enableMemoryTools] 为 true 时使用，
     * 否则走原来的单次请求 + 文本协议链路。
     */
    private val toolLoop: com.meapet.mobile.chat.tools.ChatToolLoop? = null,
    /** 工具执行器（与 [toolLoop] 共用同一实例：暂存写入需要从它身上取回）。 */
    private val memoryTools: com.meapet.mobile.chat.tools.TurnScoped? = null
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
                "不要记录你自己的提议或猜测。" +
                "调用工具前只说一句简短的话（不要长篇铺垫），最终回复不要重复前面说过的内容。"

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

    /** 是否有在途 turn（主界面与悬浮窗共用同一 ChatService，必须进程级可见）。 */
    private val turnActive = AtomicBoolean(false)

    /** 在途 turn 的协程 Job（[cancelActiveTurn] 的取消句柄）。 */
    private val activeTurnJob = AtomicReference<Job?>(null)

    /** 解析 rawAssistantJson 用（忽略未知字段，思考模式字段不丢）。 */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 当前是否有在途 turn。
     *
     * 插队发送 / 状态条「停止」都据此判断；悬浮窗等其他 UI 与主界面共享同一个实例。
     */
    val isTurnActive: Boolean get() = turnActive.get()

    /**
     * 取消当前在途 turn（用户点「停止」或插队发送时由调用方触发）。
     *
     * 取消会走 [sendMessage] 的取消分支：已展示的正文片段按「已完成部分」提交，
     * 未完成的 tool_calls 与写类暂存丢弃。无在途 turn 时为 no-op。
     */
    fun cancelActiveTurn() {
        activeTurnJob.get()?.cancel()
    }

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
     * ## 回调与历史
     * [onFragment] 在工具循环每轮「同时带正文和 tool_calls」时被调用，参数是**已带
     * turnId/rawAssistantJson 的中间 assistant 记录**。UI 收到即插入气泡（连发多条），
     * 但历史只在整轮成功时一次性写入，失败时同一条片段不会残留在历史里。
     *
     * @param userMessage 用户消息（id 由调用方决定）
     * @param onFragment 可见的中间回复片段回调（默认空实现，方便测试/悬浮窗忽略）
     * @return 包含用户消息与 AI 回复的 Pair（用户消息带本轮 turnId）
     */
    suspend fun sendMessage(
        userMessage: ChatMessage,
        onFragment: (ChatMessage) -> Unit = {}
    ): Result<Pair<ChatMessage, ChatMessage>> {
        val startedAt = System.currentTimeMillis()
        val turnId = UUID.randomUUID().toString()
        val turnUser = userMessage.copy(turnId = turnId)
        // 本轮记录：工具循环产生的中间 assistant / tool 消息。成功时一次性入史，
        // 失败/超时从未入史，被取消时只挑已展示的正文提交（见下方 catch 分支）。
        val transcript = mutableListOf<ChatMessage>()

        // 模式解析放在最前：失败路径也要把本轮模式记进观察指标
        val memoryMode = if (config.enableMemory) settingsManager.getMemoryMode() else MemoryMode.OFF
        val wantsTools = config.enableMemoryTools && memoryMode.usesTools && !toolsUnsupported

        turnActive.set(true)
        activeTurnJob.set(coroutineContext[Job])
        try {
            return withContext(Dispatchers.IO) {
                try {
                    // 整个 turn（多轮工具请求 + tools 不支持降级 + 空回复兜底）共用一个总超时：
                    // 移动网络下工具循环最坏是 maxRounds 次请求叠加，没有总超时用户只能强杀
                    val execution = withTimeout(config.turnTimeoutMs) {
                        runTurn(
                            turnUser = turnUser,
                            turnId = turnId,
                            memoryMode = memoryMode,
                            wantsTools = wantsTools,
                            transcript = transcript,
                            onVisibleFragment = onFragment
                        )
                    }

                    // 1) 解析最终回复：先剥离可能以文本形式泄漏的工具调用标记，再剥离记忆协议块
                    val parsed = MemoryOpsProtocol.extract(execution.content)
                    val finalAssistant = ChatMessage(
                        role = ChatRole.assistant,
                        content = parsed.visibleReply,
                        // 块留在消息上（不展示），下一轮贴回历史当格式范例，见 ConversationManager
                        memoryOpsBlock = parsed.rawBlock,
                        turnId = turnId,
                        // 原始回包（含 reasoning_content 等）供下一轮原样透传
                        rawAssistantJson = execution.finalRaw
                    )
                    // 2) 一个事务落库整轮：user 已在 runTurn 开头入史；这里补中间记录 + 最终回复。
                    //    纯 tool_calls 的空 assistant 与 tool 消息也必须落库（下一轮请求需要）。
                    conversationManager.addMessages(transcript + finalAssistant)

                    // 3) 工具模式：整个 turn 成功后才提交暂存写入（失败/取消已在各 catch 中丢弃）
                    if (config.enableMemoryTools) {
                        memoryTools?.onTurnSuccess()
                    }

                    // 4) 事后处理：应用记忆操作/触发摘要转后台，不阻塞本次回复返回
                    //   （摘要是一次额外 LLM 请求，同步等它会让 UI 在拿到回复后仍长时间显示加载中）
                    memoryManager?.let { mm ->
                        postProcessScope.launch {
                            try {
                                mm.onExchangeComplete(parsed.ops)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Memory post-processing failed", e)
                            }
                        }
                    }

                    TurnMetrics.record(
                        mode = memoryMode,
                        toolMode = execution.toolsUsed,
                        rounds = execution.rounds,
                        toolCalls = execution.toolCalls,
                        durationMs = System.currentTimeMillis() - startedAt,
                        outcome = TurnOutcome.SUCCESS,
                        promptTokens = execution.promptTokens,
                        cacheHitTokens = execution.cacheHitTokens
                    )
                    Log.d(TAG, "Response received (${parsed.visibleReply.length} chars)")
                    Result.success(turnUser to finalAssistant)
                } catch (e: TimeoutCancellationException) {
                    // 单独先于 CancellationException 捕获：超时是失败，不是用户取消
                    Log.w(TAG, "Turn timed out after ${config.turnTimeoutMs} ms")
                    conversationManager.removeMessage(turnUser.id)
                    memoryTools?.onTurnAbort()
                    TurnMetrics.record(
                        mode = memoryMode,
                        toolMode = wantsTools,
                        rounds = estimateRounds(transcript, wantsTools),
                        toolCalls = transcript.count { it.isTool },
                        durationMs = System.currentTimeMillis() - startedAt,
                        outcome = TurnOutcome.TIMEOUT
                    )
                    Result.failure(
                        IllegalStateException(
                            "请求超时（${config.turnTimeoutMs / 1000} 秒），请检查网络后重试"
                        )
                    )
                } catch (e: CancellationException) {
                    // 插队发送 / 点「停止」：已展示的气泡按「已完成部分」提交；没有任何回复则不留孤儿 user
                    val visible = transcript
                        .filter { it.role == ChatRole.assistant && it.content.isNotBlank() }
                        .map { it.copy(rawAssistantJson = null) }
                    if (visible.isNotEmpty()) {
                        conversationManager.addMessages(visible)
                        Log.i(TAG, "Turn cancelled, committed ${visible.size} visible fragment(s)")
                    } else {
                        conversationManager.removeMessage(turnUser.id)
                    }
                    memoryTools?.onTurnAbort()
                    TurnMetrics.record(
                        mode = memoryMode,
                        toolMode = wantsTools,
                        rounds = estimateRounds(transcript, wantsTools),
                        toolCalls = transcript.count { it.isTool },
                        durationMs = System.currentTimeMillis() - startedAt,
                        outcome = TurnOutcome.CANCELLED
                    )
                    throw e
                } catch (e: Exception) {
                    // 失败路径：transcript 从未入史，只需回滚开头的 user 消息
                    Log.e(TAG, "Failed to send message", e)
                    conversationManager.removeMessage(turnUser.id)
                    memoryTools?.onTurnAbort()
                    TurnMetrics.record(
                        mode = memoryMode,
                        toolMode = wantsTools,
                        rounds = estimateRounds(transcript, wantsTools),
                        toolCalls = transcript.count { it.isTool },
                        durationMs = System.currentTimeMillis() - startedAt,
                        outcome = TurnOutcome.FAILURE
                    )
                    Result.failure(e)
                }
            }
        } finally {
            activeTurnJob.set(null)
            turnActive.set(false)
        }
    }

    // ── 单轮执行 ──────────────────────────────────────

    /** 组装好的本地请求：既有领域副本（估算/日志用），也有最终 JSON 序列化结果。 */
    private data class LocalRequest(
        val messages: List<ChatMessage>,
        val jsonMessages: List<JsonObject>,
        val body: String,
        val rawEstimate: Int
    )

    /** 一轮执行的结果（尚未解析记忆协议块）。 */
    private data class TurnExecution(
        val content: String,
        val finalRaw: String?,
        val rounds: Int,
        val toolCalls: Int,
        val toolsUsed: Boolean,
        /** 服务端报告的首轮输入 token（缺 usage 时为 null，仅观察用）。 */
        val promptTokens: Int? = null,
        /** 服务端报告的前缀缓存命中 token（缺字段时为 null，仅观察用）。 */
        val cacheHitTokens: Int? = null
    )

    /**
     * 执行一轮请求并返回最终正文。
     *
     * 只做「拿到最终正文」这一件事；历史写入/回滚/指标都在 [sendMessage] 里统一处理，
     * 保证成功、失败、取消、超时四条路径的收尾逻辑不会各写一遍而走偏。
     */
    private suspend fun runTurn(
        turnUser: ChatMessage,
        turnId: String,
        memoryMode: MemoryMode,
        wantsTools: Boolean,
        transcript: MutableList<ChatMessage>,
        onVisibleFragment: (ChatMessage) -> Unit
    ): TurnExecution {
        onBeforeSend()
        if (config.enableMemoryTools) memoryTools?.onTurnStart()
        // 上一轮的墓碑（若未被 UI 取走）属于旧 turn，不能混进本轮的撤销项
        memoryManager?.clearRecentToolDeletions()

        // 用户消息先入史：apiMessages 由历史构建，当前消息必须已在其中。
        // 代价是任何失败路径都必须回滚（见 sendMessage 的 fail/timeout 分支），
        // 否则留下从未发出的「幽灵消息」污染后续上下文并被持久化
        conversationManager.addMessage(turnUser)
        val content = turnUser.content

        // 模式解析（需求 §5.2）：
        // - 工具模式：stable = 固定工具说明 + **人设事实**（FACTUAL/CORE_TRAIT 仍全量注入，
        //   与当前输入无关、轮间稳定；短期/长期相关回忆改由模型自己调用 search_memory）；
        //   不再注入文本协议说明与关键词回忆（两套协议同时存在会让模型在「输出围栏块」
        //   和「调用工具」之间摇摆）
        // - 非工具模式且记忆未关闭：走文本协议，stable + tail
        // - OFF：stable 为空，也不注入
        val textProtocol = !wantsTools && memoryMode != MemoryMode.OFF
        val memoryContext = if (textProtocol) memoryManager?.buildContext(content) else null
        var stableContext = if (wantsTools) {
            val persona = memoryManager?.buildPersonaSection().orEmpty()
            if (persona.isBlank()) MEMORY_TOOL_HINT else "$MEMORY_TOOL_HINT\n\n$persona"
        } else {
            memoryContext?.stable ?: ""
        }
        var tailFragments = listOfNotNull(
            TimeContext.describe(),
            memoryContext?.tail?.takeIf { it.isNotBlank() }
        )
        // 记忆关闭时 stable 为空（模型压根没收到协议说明），历史里也不该回贴协议块
        var memoryOpsEchoTurns =
            if (stableContext.isNotBlank() && !wantsTools) config.memoryOpsEchoTurns else 0

        val systemPrompt = settingsManager.getSystemPrompt()
        val model = settingsManager.getModel()
        val temperature = settingsManager.getTemperature()
        // 输出上限按容量 25% 夹住：窗口很小时 max_tokens 会把输入预算吃穿
        val maxTokens = settingsManager.getMaxTokens().coerceAtMost(maxTokensCapProvider())

        // 上下文压缩：先取状态与「已覆盖多少」，再判断是否需要硬触发兜底。
        // 折叠/丢弃切点由 compaction 包按 user 消息边界对齐，不会切在 turn 中间
        var state = ensureCompactionLoaded()
        val window = conversationManager.getMessages().filter { it.role != ChatRole.system }
        val budget = budgetProvider()
        var skipOldest = resolveSkip(window, state)
        var usable = window.drop(skipOldest)
        val overhead = estimator.estimate(systemPrompt) +
            estimator.estimate(stableContext) +
            estimator.estimate(composeTail(tailFragments)) +
            estimator.estimate(state.summary)
        var estimated = overhead + estimator.estimate(usable)

        if (budget > 0 && estimated > budget * CompactionConfig.HARD_TRIGGER_RATIO) {
            // 自动压缩只做兜底：小窗口直接放弃摘要，只截断
            if (budget > CompactionConfig.SMALL_WINDOW_TOKENS) {
                val fold = foldCountFor(
                    usable,
                    targetTokens = (budget * CompactionConfig.TARGET_RATIO).toInt(),
                    estimator = estimator
                )
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

        val compactionEnabled = compactionStore != null && compactor != null
        val maxMessages = if (compactionEnabled) Int.MAX_VALUE else config.maxHistoryMessages

        // 局部组装函数：tools 不支持降级 / 空回复兜底都要换参数重建请求
        fun buildLocalRequest(
            includeToolHistory: Boolean,
            stable: String,
            tail: List<String>,
            echo: Int
        ): LocalRequest {
            val apiMessages = conversationManager.buildApiMessages(
                systemPrompt = systemPrompt,
                stableContext = stable,
                tailFragments = tail,
                maxMessages = maxMessages,
                memoryOpsEchoTurns = echo,
                summary = state.summary,
                skipOldest = skipOldest,
                includeToolHistory = includeToolHistory
            )
            val jsonMessages = apiMessages.map { toApiJson(it, includeRaw = includeToolHistory) }
            val body = ApiRequest.chatCompletion(
                model = model,
                messages = jsonMessages,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false
            )
            return LocalRequest(
                messages = apiMessages,
                jsonMessages = jsonMessages,
                body = body,
                rawEstimate = apiMessages.sumOf { estimator.rawTokens(it) }
            )
        }

        // 最近一次响应的服务端 usage（观察期指标：输入 token 与 prefix cache 命中数）
        var usagePromptTokens: Int? = null
        var usageCacheHitTokens: Int? = null

        suspend fun requestOnce(request: LocalRequest): String {
            val responseJson = clientProvider().chatCompletion(request.body)
            // 用服务端真实 prompt_tokens 校准本地估算（缺 usage 时保持原比值）
            ApiResponse.usagePromptTokens(responseJson)?.let { actual ->
                estimator.calibrate(request.rawEstimate, actual)
                usagePromptTokens = actual
                usageCacheHitTokens = ApiResponse.usagePromptCacheHitTokens(responseJson)
            }
            return ApiResponse.chatCompletionContent(responseJson).orEmpty()
        }

        val loop = if (wantsTools) toolLoop else null
        var local = buildLocalRequest(
            includeToolHistory = wantsTools,
            stable = stableContext,
            tail = tailFragments,
            echo = memoryOpsEchoTurns
        )
        Log.d(
            TAG,
            "Sending request to $model (${local.messages.size} messages, " +
                "${local.messages.sumOf { it.content.length }} chars, ~$estimated tokens)"
        )
        if (com.meapet.mobile.BuildConfig.DEBUG) logRequestDetail(local.messages)

        var toolsUsed = false
        var rounds = 1
        var toolCalls = 0
        var finalContent: String
        var finalRaw: String? = null

        if (loop != null) {
            try {
                val loopResult = loop.run(
                    model = model,
                    messages = local.jsonMessages,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    turnId = turnId,
                    onVisibleFragment = onVisibleFragment,
                    onTranscript = { record -> transcript += record }
                )
                // 用第一轮的真实 prompt_tokens 校准本地估算；cache hit 只进观察指标
                loopResult.firstPromptTokens?.let { estimator.calibrate(local.rawEstimate, it) }
                usagePromptTokens = loopResult.firstPromptTokens ?: usagePromptTokens
                usageCacheHitTokens = loopResult.firstPromptCacheHit ?: usageCacheHitTokens
                toolsUsed = true
                rounds = loopResult.rounds
                toolCalls = loopResult.toolCalls
                finalContent = loopResult.finalContent
                finalRaw = loopResult.finalRaw
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = (e as? ApiException)?.statusCode
                if (status == null || status !in TOOLS_UNSUPPORTED_STATUS) throw e
                // 端点明确不支持 tools：本 turn 立即降级为文本协议重发，聊天不中断；
                // 之后本会话不再尝试 tools（toolsUnsupported 已置位）
                Log.w(TAG, "Endpoint rejected tools (HTTP $status), falling back to text protocol")
                toolsUnsupported = true
                val fallbackContext =
                    if (memoryMode != MemoryMode.OFF) memoryManager?.buildContext(content) else null
                stableContext = fallbackContext?.stable ?: ""
                tailFragments = listOfNotNull(
                    TimeContext.describe(),
                    fallbackContext?.tail?.takeIf { it.isNotBlank() }
                )
                memoryOpsEchoTurns =
                    if (stableContext.isNotBlank()) config.memoryOpsEchoTurns else 0
                local = buildLocalRequest(
                    // 文本协议不能带工具历史：端点连 tools 都不支持，tool_calls 更解析不了
                    includeToolHistory = false,
                    stable = stableContext,
                    tail = tailFragments,
                    echo = memoryOpsEchoTurns
                )
                finalContent = requestOnce(local)
                finalRaw = null
            }
        } else {
            finalContent = requestOnce(local)
        }

        // 工具模式下模型只给 tool_calls / 空回复（服务端偶有）时，补一次不带 tools 的请求，
        // 尽量给用户一句正常回复，而不是把整轮判失败
        var reply = finalContent
        if (reply.isBlank() && toolsUsed) {
            Log.w(TAG, "Empty reply after tool loop, retrying without tools")
            val fallback = buildLocalRequest(
                includeToolHistory = false,
                stable = stableContext,
                tail = tailFragments,
                echo = 0
            )
            reply = requestOnce(fallback)
            finalRaw = null
        }
        if (reply.isBlank()) {
            throw IllegalStateException("API 响应中没有有效的回复内容")
        }
        return TurnExecution(
            content = reply,
            finalRaw = finalRaw,
            rounds = rounds,
            toolCalls = toolCalls,
            toolsUsed = toolsUsed,
            promptTokens = usagePromptTokens,
            cacheHitTokens = usageCacheHitTokens
        )
    }

    /**
     * **仅 debug 构建**：把本次请求的结构打进 logcat，方便排查「实际发出去的 system
     * prompt / 上下文到底是什么」。
     *
     * 只打印第一条 system 的全文（它由 设置里的 System Prompt + 工具说明 + 压缩摘要
     * 拼成，排查人设不生效时第一眼看的就是它）；其余消息只打印角色、长度与是否有
     * tool_calls，不把用户对话正文写进 logcat。
     *
     * Release 构建不会调用本方法（入口处 `BuildConfig.DEBUG` 判断）。
     */
    private fun logRequestDetail(messages: List<ChatMessage>) {
        Log.d(TAG, "── request detail (${messages.size} msgs) ──")
        messages.forEachIndexed { index, msg ->
            val flags = buildString {
                if (msg.rawAssistantJson != null) append(" raw")
                if (!msg.toolCallId.isNullOrBlank()) append(" toolCallId=${msg.toolCallId}")
            }
            Log.d(
                TAG,
                "  #$index ${msg.role} ${msg.content.length} chars$flags"
            )
        }
        messages.firstOrNull { it.role == ChatRole.system }?.let { system ->
            Log.d(TAG, "  system[${system.content.length}]: ${system.content}")
        }
    }

    /**
     * 把领域消息映射成请求 JSON。
     *
     * - `role=tool`：必须带 `tool_call_id`（正常路径下 ConversationManager 已清洗掉非法项）；
     * - assistant 且有原始回包且当前请求保留工具历史：原样透传原始 message
     *   （raw 里已包含 content，不能再拼一次）；解析失败退回纯文本；
     * - 其余按纯文本发送（含 tail 已并入的最后一条 user）。
     */
    private fun toApiJson(msg: ChatMessage, includeRaw: Boolean): JsonObject {
        if (msg.role == ChatRole.tool) {
            val toolCallId = msg.toolCallId
            if (toolCallId != null) return ApiRequest.toolMessage(toolCallId, msg.content)
            // 兜底：无 id 的 tool 行无法组成合法请求，当普通文本发（正常不会走到这里）
            Log.w(TAG, "Tool message without tool_call_id, sent as plain text")
            return ApiRequest.textMessage("user", msg.content)
        }
        if (includeRaw) {
            val raw = msg.rawAssistantJson
            if (raw != null) {
                val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                if (parsed != null) return parsed
                Log.w(TAG, "Failed to parse raw assistant json, falling back to text message")
            }
        }
        return ApiRequest.textMessage(msg.role.name, msg.content)
    }

    /** 失败/取消/超时时的轮数下限估算（指标用，不参与业务逻辑）。 */
    private fun estimateRounds(transcript: List<ChatMessage>, toolMode: Boolean): Int =
        if (toolMode && transcript.isNotEmpty()) {
            transcript.count { it.role == ChatRole.assistant } + 1
        } else {
            1
        }

    /**
     * 重新发送指定的用户消息（用于失败重试）。
     *
     * **整轮语义**：在内存历史里找到该 user 消息下标，连同它之后的所有消息一起移除
     * （后续只可能是同一轮的 assistant/tool 片段），然后交给 [sendMessage] 重新生成
     * 新 turnId 发送。不这样清的话，重试会留下旧轮的片段/工具记录，导致同一轮被拆成
     * 两段、或出现无法配对的 tool_calls。
     *
     * **重试目标必须由调用方给出**，不能在这里"取历史里最后一条 user"——发送失败时
     * 该消息已被回滚出历史，那样取到的是**上一条早已成功的消息**，会把它重发一遍，
     * 并在 UI 列表里造出同一个 id 的两份副本（LazyColumn 重复 key 直接崩溃）。
     *
     * @param userMessage 要重发的用户消息（沿用原 id 与 timestamp）
     * @param onFragment 可见中间片段回调（与 [sendMessage] 相同，重试时也要能连发多条）
     */
    suspend fun retryMessage(
        userMessage: ChatMessage,
        onFragment: (ChatMessage) -> Unit = {}
    ): Result<Pair<ChatMessage, ChatMessage>> {
        val history = conversationManager.getMessages()
        val userIdx = history.indexOfLast { it.id == userMessage.id }
        if (userIdx >= 0) {
            // 一次移除「该 user + 它之后的全部消息」：分多次 remove 期间若有新消息插入，
            // 会删到不属于这一轮的内容
            val removed = history.drop(userIdx).map { it.id }
            conversationManager.removeMessages(removed)
            Log.i(TAG, "Retry: removed ${removed.size} message(s) from turn start")
        } else {
            // 正常情况：发送失败已把它回滚出历史。也可能是历史被裁剪或清空。
            // 直接按新消息重发即可。
            Log.d(TAG, "Retry target not in history, sending as a new message")
        }
        return sendMessage(userMessage, onFragment)
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
            // 手动压缩是用户明确要求「把前面的内容整理掉」：按最大可折量折叠（保留最近
            // MIN_KEEP_TURNS 轮原文），不做目标比例裁剪——否则大窗口用户点一下只折 2 条，
            // 等于没整理。目标比例只用于自动兜底压缩。
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

    /**
     * 完整历史（撤销「清除对话」的快照用，不受内存窗口裁剪影响）。
     *
     * **不过滤** tool/空 assistant：撤销要恢复成清除前的精确状态。
     */
    suspend fun loadFullHistory(): List<ChatMessage> = conversationManager.loadFullHistory()

    /**
     * UI 首屏：完整历史里最近 [limit] 条，**过滤掉 UI 不可见消息**
     * （tool 与纯 tool_calls 的空 assistant，见 [forUi]）。
     */
    suspend fun loadRecentForUi(limit: Int): List<ChatMessage> =
        conversationManager.loadRecentForUi(limit).forUi()

    /** UI 懒加载：[beforeId] 之前的 [limit] 条完整历史（按时间正序，同样过滤不可见消息）。 */
    suspend fun loadOlder(beforeId: String, limit: Int): List<ChatMessage> =
        conversationManager.pageBefore(beforeId, limit).forUi()

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
