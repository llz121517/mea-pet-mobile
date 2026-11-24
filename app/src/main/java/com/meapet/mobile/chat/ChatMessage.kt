package com.meapet.mobile.chat

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 消息角色枚举。
 *
 * [tool] 是**工具执行结果**（OpenAI `role=tool`）：只在一次 turn 内部与历史里存在，
 * 永远不显示在 UI 上。
 */
@Serializable
enum class ChatRole { system, user, assistant, tool }

/**
 * 聊天消息领域模型。
 *
 * 该模型贯穿整个聊天子系统，从 ChatService → ConversationManager → ViewModel → UI。
 * 可序列化以支持会话历史持久化（[ConversationStore]）。
 *
 * ## 一次 turn 的完整形态
 * ```
 * user(turnId)
 *   assistant(turnId, content 可能为空, rawAssistantJson 含 tool_calls)
 *   tool(turnId, toolCallId, toolName)
 *   …
 *   assistant(turnId, 最终正文, rawAssistantJson)
 * ```
 * 同一轮所有消息共用 [turnId]，因此失败/重试/被打断都能整轮处理，而不会拆出
 * 「有 tool_calls 没有结果」或「有结果没有声明」的半轮（服务端会直接 400）。
 *
 * @property role 消息角色
 * @property content 消息文本内容；`role=tool` 时是工具返回的 JSON 信封
 * @property id 唯一标识（UUID）
 * @property timestamp 消息时间戳
 * @property isStreaming 是否正在流式输出中（运行时状态，不持久化）
 * @property turnId 所属 turn 的 id（同一轮的 user + 全部 assistant 片段 + tool 消息共用）
 * @property toolCallId 仅 `role=tool` 使用：对应 assistant 声明里 `tool_calls[].id`
 * @property toolName 仅 `role=tool` 使用：被调用的工具名（排查用，不参与请求）
 * @property rawAssistantJson assistant 消息的**原始响应 JSON**（含 `tool_calls`、
 *   `reasoning_content` 等）。下一次请求原样回传，重新拼装会丢字段；`role=tool` 时为 null。
 *   全部字段可空且默认 null，保证旧数据（旧 JSON/旧 SQLite 行）反序列化兼容。
 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    @Transient val isStreaming: Boolean = false,
    val turnId: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val rawAssistantJson: String? = null
) {
    /** 是否为用户消息。 */
    val isUser: Boolean get() = role == ChatRole.user

    /** 是否为助手消息。 */
    val isAssistant: Boolean get() = role == ChatRole.assistant

    /** 是否为系统消息。 */
    val isSystem: Boolean get() = role == ChatRole.system

    /** 是否为工具结果消息。 */
    val isTool: Boolean get() = role == ChatRole.tool

    /**
     * 是否应该在 UI 里显示。
     *
     * - `tool` 消息永远不显示（用户看不懂 JSON 信封，且它只是请求协议的一部分）；
     * - **content 为空的 assistant** 不显示：纯 tool_calls 轮次会产出这种消息，
     *   它必须进历史/发给模型，但显示出来就是一个空气泡。
     */
    val isVisibleInUi: Boolean
        get() = when (role) {
            ChatRole.tool -> false
            ChatRole.assistant -> content.isNotBlank()
            else -> true
        }
}

/**
 * 过滤出 UI 可见的消息（见 [ChatMessage.isVisibleInUi]）。
 *
 * UI 读取历史（首屏/翻页）与渲染消息列表前都要过一遍：tool 与空 assistant
 * 既没有展示价值，也会把「按天的分隔条」「自动滚动到底部」等按条数计算的逻辑带偏。
 * 注意 `loadFullHistory()`（撤销快照）**不能**过滤，撤销要恢复完整数据。
 */
fun List<ChatMessage>.forUi(): List<ChatMessage> = filter { it.isVisibleInUi }
