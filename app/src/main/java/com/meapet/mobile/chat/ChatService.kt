package com.meapet.mobile.chat

import android.util.Log
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
 * - 每次对话后触发记忆提取。
 *
 * ## 低耦合
 * - 不依赖任何 UI 组件；
 * - 通过 [MemoryManager] 与记忆系统交互（而非直接操作 MemoryRepository）；
 * - 通过 [SettingsManager] 获取配置（而非硬编码）。
 *
 * @param clientProvider 提供 OpenAI 兼容 HTTP 客户端的 provider（每次请求重新获取，
 *   以便 API Key / Base URL 变更重建客户端后立即生效）
 * @param conversationManager 会话管理器
 * @param memoryManager 记忆管理器（null = 禁用记忆）
 * @param settingsManager 设置管理器
 * @param postProcessScope 事后处理（记忆提取/摘要）用的应用级作用域；
 *   摘要可能触发额外网络请求，不能阻塞 sendMessage 返回
 * @param config 应用配置
 */
class ChatService(
    private val clientProvider: () -> OpenAiCompatibleClient,
    private val conversationManager: ConversationManager,
    private val memoryManager: MemoryManager?,
    private val settingsManager: SettingsManager,
    private val postProcessScope: CoroutineScope,
    private val config: AppConfig = AppConfig.DEFAULT
) {
    companion object {
        private const val TAG = "ChatService"
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
     * @param userMessage 用户消息（id 由调用方决定）
     * @return 包含用户消息与 AI 回复的 Pair
     */
    suspend fun sendMessage(userMessage: ChatMessage): Result<Pair<ChatMessage, ChatMessage>> {
        return withContext(Dispatchers.IO) {
            val content = userMessage.content
            try {
                // 1) 用户消息先入史：apiMessages 由历史构建，当前消息必须已在其中。
                //    代价是任何失败路径都必须回滚，否则留下从未发出的"幽灵消息"
                //    （污染后续请求上下文，且会被持久化）。
                conversationManager.addMessage(userMessage)

                // 2) 获取记忆上下文（拆成稳定段与每轮都变的尾部段，见 MemoryContext）
                val memoryContext = memoryManager?.buildContext(content)

                // 3) 获取设置
                val systemPrompt = settingsManager.getSystemPrompt()
                val model = settingsManager.getModel()
                val temperature = settingsManager.getTemperature()
                val maxTokens = settingsManager.getMaxTokens()

                // 4) 构建 API 请求。时间与记忆回忆一起压在历史之后：都是每轮都变的内容，
                //    放前面会让排在其后的协议说明与全部历史都无法命中服务端 prefix cache
                val tailContext = listOfNotNull(
                    TimeContext.describe(),
                    memoryContext?.tail?.takeIf { it.isNotBlank() }
                ).joinToString("\n\n")

                // 记忆关闭时 stable 为空（模型压根没收到协议说明），历史里也不该回贴协议块
                val memoryOn = !memoryContext?.stable.isNullOrBlank()
                val apiMessages = conversationManager.buildApiMessages(
                    systemPrompt = systemPrompt,
                    stableContext = memoryContext?.stable ?: "",
                    tailContext = tailContext,
                    maxMessages = config.maxHistoryMessages,
                    memoryOpsEchoTurns = if (memoryOn) config.memoryOpsEchoTurns else 0
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
                        "${apiMessages.sumOf { it.content.length }} chars)"
                )

                // 5) 发送请求
                val responseJson = clientProvider().chatCompletion(requestBody)

                // 6) 解析响应（choices 为空或 content 缺失视为失败，不入史）
                val assistantContent = ApiResponse.chatCompletionContent(responseJson)
                    ?.takeIf { it.isNotBlank() }
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

    /** 获取当前会话历史。 */
    fun getHistory(): List<ChatMessage> = conversationManager.getMessages()

    /** 清除历史。 */
    fun clearHistory() {
        conversationManager.clear()
        Log.i(TAG, "Chat history cleared")
    }
}
