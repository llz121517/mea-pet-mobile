package com.meapet.mobile.chat

import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.core.UndoableAction
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStats

/**
 * 一轮对话的进行阶段（驱动输入框可用性与「Mea 还在…」状态条）。
 *
 * - [IDLE]：没有在途 turn，输入框可正常发送；
 * - [WAITING_FIRST_REPLY]：已发出、还没收到任何可见回复。输入框保持禁用/转圈，
 *   此时插队没有意义（用户看不到任何东西，只会白白打断）；
 * - [REPLYING]：已经收到过至少一条片段/最终回复。输入框恢复可用，再发送即插队
 *   （取消当前 turn 的后续请求后立刻开始新一轮），并显示带「停止」的状态条。
 */
enum class TurnPhase { IDLE, WAITING_FIRST_REPLY, REPLYING }

/**
 * 聊天 UI 状态。
 *
 * 不可变数据类，由 ChatViewModel 持有并通过 StateFlow 下发。
 * UI 层仅读取此状态驱动渲染，不直接修改。
 *
 * @property messages 消息列表（UI 可见消息；tool 与空 assistant 不进来）
 * @property turnPhase 当前 turn 阶段
 * @property isLoading 是否正在等待 AI 响应（= [turnPhase] != [TurnPhase.IDLE]，
 *   保留该字段是为了不破坏 UI 现有用法）
 * @property error 错误信息（非 null 时在对话流末尾显示错误卡片）
 * @property inputText 输入框当前文本
 * @property updateNotice 启动静默检测发现的新版本提示（Snackbar 用）
 * @property memoryDialog 记忆查看对话框数据（null = 不显示）
 * @property pendingUndo 刚执行、仍在撤销窗口内的破坏性操作（null = 无可撤销项）
 * @property hasMoreHistory 仓库里是否还有更早的历史可向上加载
 * @property isLoadingOlder 是否正在加载更早的一页
 * @property compactHint 是否应弹出「上下文较长，要不要整理」的提醒（同一会话只弹一次）
 * @property canCompactContext 上下文是否已长到值得显示手动压缩入口
 * @property isCompacting 是否正在压缩
 * @property compactedUpToMessageId 已被摘要覆盖的最后一条消息 id（UI 在此后画「早些时候」分隔条）
 * @property initialLoadDone 启动时首屏历史是否已加载完成（完成前不要自动滚动，避免滚到一半又插页）
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val turnPhase: TurnPhase = TurnPhase.IDLE,
    val error: String? = null,
    val inputText: String = "",
    val updateNotice: UpdateNoticeUi? = null,
    val memoryDialog: MemoryDialogUi? = null,
    val pendingUndo: ChatUndo? = null,
    val hasMoreHistory: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val compactHint: Boolean = false,
    val canCompactContext: Boolean = false,
    val isCompacting: Boolean = false,
    val compactedUpToMessageId: String? = null,
    val initialLoadDone: Boolean = false,
    /** 压缩完成后的一次性提示（成功/失败文案），由 UI 弹完即清空。 */
    val compactionNotice: String? = null,
    /**
     * 会话代际：清空对话 / 撤销恢复时自增。
     *
     * UI 以它作为 key 重建消息列表与 [androidx.compose.foundation.lazy.LazyListState]：
     * 把「300 多条消息的滚动与子组合状态」直接原地切成 0 条时，Compose 的
     * LazyLayout 在回收 item 布局的过程中会触发内部 NPE
     * （`SubcomposeLayout.getSlotIdAtIndex`）。换一代状态、整块重建，比让框架
     * 原地回收安全得多；清空/撤销本来就是低频操作，重建成本可忽略。
     */
    val conversationEpoch: Long = 0
) {
    /** 是否正在等待 AI 响应（派生自 [turnPhase]，避免两处状态不一致）。 */
    val isLoading: Boolean get() = turnPhase != TurnPhase.IDLE
}

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

    /**
     * 清除对话：快照为清空前的完整会话历史**与压缩状态**。
     *
     * 压缩状态必须一起存：清除时摘要已被删除，还原时若只回放历史、不还原摘要，那段被
     * 压缩过的内容对模型就永久不可见（原文在覆盖点之前不再逐字发送，摘要又是空的）。
     *
     * @property compaction 清空前的压缩状态（[CompactionState] 无摘要时表示本来没压缩过）
     */
    data class ClearConversation(
        override val id: Long,
        override val deadlineMs: Long,
        val snapshot: List<ChatMessage>,
        val compaction: CompactionState = CompactionState()
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

    /**
     * 模型通过 `delete_memory` 工具删除的记忆：快照为删除前的完整条目（墓碑）。
     *
     * 删除在 turn 成功时一次性提交，用户可能并不想要这个结果；撤销即按墓碑原样恢复。
     */
    data class ToolDeletedMemories(
        override val id: Long,
        override val deadlineMs: Long,
        val snapshot: List<MemoryItem>
    ) : ChatUndo {
        override val message: String get() = "已删除 ${snapshot.size} 条记忆"
    }
}
