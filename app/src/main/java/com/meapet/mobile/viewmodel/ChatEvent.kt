package com.meapet.mobile.viewmodel

/**
 * 聊天事件——ViewModel 接收的用户操作。
 */
sealed interface ChatEvent {
    /** 发送消息。 */
    data class SendMessage(val content: String) : ChatEvent

    /** 更新输入框文本。 */
    data class UpdateInput(val text: String) : ChatEvent

    /** 清除对话历史。 */
    data object ClearConversation : ChatEvent

    /** 清除记忆。 */
    data object ClearMemory : ChatEvent

    /** 打开记忆查看对话框。 */
    data object ShowMemories : ChatEvent

    /** 关闭记忆查看对话框。 */
    data object DismissMemories : ChatEvent

    /** 删除单条记忆。 */
    data class DeleteMemory(val id: String) : ChatEvent

    /** 重新发送上一条消息（失败后重试）。 */
    data object RetryLastMessage : ChatEvent

    /** 清除错误。 */
    data object DismissError : ChatEvent

    /** 启动静默检测更新提示已展示。 */
    data object DismissUpdateNotice : ChatEvent
}
