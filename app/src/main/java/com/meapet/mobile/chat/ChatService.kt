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
    suspend fun sendMessage(content: String): Result<Pair<ChatMessage, ChatMessage>> {
        return withContext(Dispatchers.IO) {
            try {
                // 1) 构建用户消息
                val userMessage = ChatMessage(
                    role = ChatRole.user,
                    content = content
                )
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
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 重新发送上一条消息（用于失败重试）。
     */
    suspend fun retryLastMessage(): Result<Pair<ChatMessage, ChatMessage>> {
        val lastUserMsg = conversationManager.lastUserMessage()
            ?: return Result.failure(IllegalStateException("No message to retry"))

        // 把该条 user 之后的 assistant 回复（若有）连同 user 本身一并移除，
        // 重发时由 sendMessage 统一重新入史，避免消息重复
        val history = conversationManager.getMessages()
        val userIdx = history.indexOfLast { it.id == lastUserMsg.id }
        // lastUserMessage() 与 getMessages() 是两次独立加锁，之间该消息若被移除
        // （清空会话 / 窗口裁剪），userIdx 会是 -1，drop(0) 就等于「整个历史」，
        // 会把历史里所有 assistant 回复一并误删。
        if (userIdx < 0) {
            Log.w(TAG, "Retry target no longer in history, aborting")
            return Result.failure(IllegalStateException("该消息已不在会话历史中，无法重试"))
        }
        history.drop(userIdx + 1)
            .filter { it.role == ChatRole.assistant }
            .forEach { conversationManager.removeMessage(it.id) }
        conversationManager.removeMessage(lastUserMsg.id)

        return sendMessage(lastUserMsg.content)
    }

    /** 获取当前会话历史。 */
    fun getHistory(): List<ChatMessage> = conversationManager.getMessages()

    /** 清除历史。 */
    fun clearHistory() {
        conversationManager.clear()
        Log.i(TAG, "Chat history cleared")
    }
}
