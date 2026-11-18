package com.meapet.mobile.chat

import com.meapet.mobile.core.UndoableAction
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStats

/**
 * 聊天 UI 状态。
 *
 * 不可变数据类，由 ChatViewModel 持有并通过 StateFlow 下发。
 * UI 层仅读取此状态驱动渲染，不直接修改。
 *
 * @property messages 消息列表
 * @property isLoading 是否正在等待 AI 响应
 * @property error 错误信息（非 null 时在对话流末尾显示错误卡片）
 * @property inputText 输入框当前文本
 * @property updateNotice 启动静默检测发现的新版本提示（Snackbar 用）
 * @property memoryDialog 记忆查看对话框数据（null = 不显示）
 * @property pendingUndo 刚执行、仍在撤销窗口内的破坏性操作（null = 无可撤销项）
 * @property hasMoreHistory 仓库里是否还有更早的历史可向上加载
 * @property isLoadingOlder 是否正在加载更早的一页
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val inputText: String = "",
    val updateNotice: UpdateNoticeUi? = null,
    val memoryDialog: MemoryDialogUi? = null,
    val pendingUndo: ChatUndo? = null,
    val hasMoreHistory: Boolean = false,
    val isLoadingOlder: Boolean = false
)

/** UI 层展示的更新提示。 */
data class UpdateNoticeUi(
    val message: String,
    val url: String
)

/** 记忆查看对话框数据。 */
data class MemoryDialogUi(
    val memories: List<MemoryItem> = emptyList(),
    val stats: MemoryStats = MemoryStats(),
    val isMemoryEnabled: Boolean = true
)

/**
 * 聊天页可撤销的破坏性操作。
 *
 * 每个分支携带**执行前的快照**：这是撤销窗口内唯一的回滚依据，因此必须在
 * 真正执行清空之前取好。窗口到期后快照随之丢弃（ViewModel 置空 [ChatUiState.pendingUndo]）。
 */
sealed interface ChatUndo : UndoableAction {
    override val undoLabel: String get() = "撤销"

    /** 清除对话：快照为清空前的完整会话历史。 */
    data class ClearConversation(
        override val id: Long,
        override val deadlineMs: Long,
        val snapshot: List<ChatMessage>
    ) : ChatUndo {
        override val message: String get() = "已清除 ${snapshot.size} 条对话"
    }

    /**
     * 清除全部记忆：快照为清空前的全部条目。
     *
     * @property exchangeCount 清空时被一并重置的对话轮数计数（撤销时还原）
     */
    data class ClearMemory(
        override val id: Long,
        override val deadlineMs: Long,
        val snapshot: List<MemoryItem>,
        val exchangeCount: Int
    ) : ChatUndo {
        override val message: String get() = "已清除 ${snapshot.size} 条记忆"
    }
}
