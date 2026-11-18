package com.meapet.mobile.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ChatService
import com.meapet.mobile.chat.ChatUiState
import com.meapet.mobile.chat.ChatUndo
import com.meapet.mobile.chat.SystemBubblePolicy
import com.meapet.mobile.chat.MemoryDialogUi
import com.meapet.mobile.chat.UpdateNoticeUi
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.core.undoWindowMs
import com.meapet.mobile.live2d.Live2dDelegate
import com.meapet.mobile.live2d.Live2dManager
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryStats
import com.meapet.mobile.update.UpdateCheckResult
import com.meapet.mobile.update.UpdateChecker
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** UI 懒加载每页条数（首屏与向上翻页共用）。 */
private const val UI_PAGE_SIZE = 50

/**
 * 单条系统气泡的生命周期状态。
 *
 * @property deadlineMs 到期移除时刻（[SystemClock.elapsedRealtime] 基准，单调不受改表影响）
 * @property job 到期移除任务
 * @property reduceCount 已被「挤旧」扣减的次数（上限 [SystemBubblePolicy.MAX_REDUCE_COUNT]）
 */
private data class BubbleLife(
    val deadlineMs: Long,
    val job: Job,
    val reduceCount: Int
)

/**
 * 聊天界面 ViewModel。
 *
 * ## 职责
 * - 持有 [ChatUiState] 并通过 StateFlow 暴露给 UI；
 * - 处理 [ChatEvent] 用户交互事件；
 * - 调用 [ChatService] 发送消息；
 * - 调用 [MemoryManager] 管理记忆。
 *
 * ## 生命周期
 * - 通过 AndroidViewModel 获取 Application Context；
 * - 从 [MeaPetApplication.container] 获取依赖。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = MeaPetApplication.from(application)
    private val chatService: ChatService = container.chatService
    private val memoryManager: MemoryManager = container.memoryManager
    private val updateChecker: UpdateChecker = container.updateChecker

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 在途发送任务；清空会话时取消，避免回复回写到已清空的会话。 */
    private var sendJob: Job? = null

    /** 撤销窗口的到期定时器（窗口内撤销会取消它）。 */
    private var undoTimer: Job? = null

    /** 快照唯一标识递增源：同一操作连续触发时让 UI 的撤销条重新弹出。 */
    private var undoSeq = 0L

    /** 本机撤销窗口时长（无障碍开启时由系统建议值延长）。 */
    private val undoWindow: Long by lazy { undoWindowMs(getApplication()) }

    init {
        // 初始化时从 ConversationManager 加载已有消息
        val existingMessages = chatService.getHistory()
        if (existingMessages.isNotEmpty()) {
            _state.update { it.copy(messages = existingMessages) }
        }

        // 等启动预热（磁盘加载会话历史/记忆）完成后刷新消息列表：
        // ViewModel 往往先于异步加载完成而创建，此时 getHistory() 还是空的
        viewModelScope.launch {
            container.warmUpJob.join()
            // UI 首屏读仓库里的完整历史（最近一页），与发给模型的内存窗口解耦
            val restored = chatService.loadRecentForUi(UI_PAGE_SIZE)
            if (restored.isNotEmpty()) {
                _state.update { current ->
                    current.copy(
                        messages = mergeWithHistory(restored, current.messages),
                        hasMoreHistory = restored.size >= UI_PAGE_SIZE
                    )
                }
            }
        }

        // 监听 Live2D 触摸分区事件→添加系统消息气泡
        // 每条气泡独立生命周期，从产生开始倒计时 7 秒。
        // 新消息到达时按位置扣除旧气泡的剩余寿命（扣的是「还剩多久」，不是重新计时）：
        //   position 1-3（最新）→ 不扣
        //   position 4-5          → 扣 2 秒
        //   position 6+（最旧）→ 扣 4 秒（首次扣 2 秒 + 再次扣 2 秒）
        viewModelScope.launch {
            // msgId → 气泡生命周期（到期时刻 / 移除Job / 已扣减次数）
            // 线程约束：lifeMap 无同步，安全性依赖「所有读写都经 viewModelScope.launch
            // 运行在 Dispatchers.Main.immediate 单线程上」这一前提（collect 协程写、
            // scheduleRemove 协程删）。若未来把任一访问挪到别的调度器，必须改用
            // Mutex / actor 保护，否则会并发修改非线程安全 Map。
            val lifeMap = LinkedHashMap<String, BubbleLife>()

            Live2dManager.tapMessageEvent.collect { text ->
                val newMsg = ChatMessage(role = ChatRole.system, content = text)
                _state.update { it.copy(messages = it.messages + newMsg) }

                // 本轮统一取一次「现在」，保证新气泡定寿命与旧气泡扣寿命同基准
                val now = SystemClock.elapsedRealtime()

                // 为本条启动独立倒计时，初始寿命见 SystemBubblePolicy
                val job = scheduleRemove(lifeMap, newMsg.id, SystemBubblePolicy.BASE_LIFE_MS)
                lifeMap[newMsg.id] = BubbleLife(now + SystemBubblePolicy.BASE_LIFE_MS, job, 0)

                // 重新排位：按 timestamp 降序（最新在前），重新分配剩余寿命
                val sysIds = _state.value.messages
                    .filter { it.role == ChatRole.system }
                    .sortedByDescending { it.timestamp }
                    .map { it.id }

                sysIds.forEachIndexed { index, id ->
                    val position = index + 1 // 1 = 最新
                    val entry = lifeMap[id] ?: return@forEachIndexed

                    // 按排位扣减剩余寿命（策略见 SystemBubblePolicy）
                    val (newDeadline, newCount) = SystemBubblePolicy.computeNextDeadline(
                        deadlineMs = entry.deadlineMs,
                        nowMs = now,
                        reduceCount = entry.reduceCount,
                        position = position
                    )

                    if (newDeadline != entry.deadlineMs) {
                        entry.job.cancel()
                        val newJob = scheduleRemove(lifeMap, id, newDeadline - now)
                        lifeMap[id] = BubbleLife(newDeadline, newJob, newCount)
                    }
                }
            }
        }

        // 启动时静默检测一次：仅在有新版本时提示，失败不打扰。
        // 受设置页「启动自动检查更新」开关控制（默认开启）。
        if (container.settingsManager.isAutoUpdateCheckEnabled()) {
            viewModelScope.launch {
                when (val result = updateChecker.check()) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        _state.update {
                            it.copy(
                                updateNotice = UpdateNoticeUi(
                                    message = "发现新版本 v${result.release.versionName}",
                                    url = result.release.htmlUrl
                                )
                            )
                        }
                    }
                    is UpdateCheckResult.UpToDate,
                    is UpdateCheckResult.Failed -> Unit
                }
            }
        }
    }

    /**
     * 重新从会话历史加载消息列表。
     *
     * 悬浮窗期间对话发生在共享的 [ChatService]（同一 ConversationManager）里，
     * 本 ViewModel 的内存状态不会自动同步；后台切前台时调用一次，把最新历史合并
     * 进列表。
     *
     * ## 合并规则（修复长对话错位）
     * [ConversationManager] 有滑动窗口（超 [config.maxHistoryMessages] 从头部裁剪最旧消息），
     * 而本 ViewModel 的内存列表**从不裁剪**。若用「history + 所有不在 history 的消息」重建，
     * 被窗口裁掉的旧消息会被误判为"新消息"拼到列表末尾 → 顺序错乱。
     *
     * 正确合并：**以 history 最后一个 id 为分界**——history 之前的消息一律以 history 为准
     * （丢弃本 ViewModel 里已落后的旧副本）；history 之后才是真正的新追加（乐观消息、
     * 触摸系统气泡），按原顺序保留在尾部。历史本身保证有序（同源 ConversationManager）。
     */
    fun reloadHistory() {
        if (_state.value.isLoading) return
        val history = chatService.getHistory()
        if (history.isEmpty()) return
        _state.update { cur ->
            // 已向上加载的更早页要保留：history 只是内存窗口，合并会丢掉它之前的内容
            val firstIdx = cur.messages.indexOfFirst { it.id == history.first().id }
            val olderPrefix = if (firstIdx > 0) cur.messages.take(firstIdx) else emptyList()
            cur.copy(messages = olderPrefix + mergeWithHistory(history, cur.messages.drop(olderPrefix.size)))
        }
    }

    /**
     * 向上懒加载更早的一页历史（滚动接近列表顶部时由 UI 触发）。
     * 以当前列表里最早的非系统消息为锚点做 keyset 分页，加载完成前重复触发会被忽略。
     */
    fun loadOlderMessages() {
        val s = _state.value
        if (!s.hasMoreHistory || s.isLoadingOlder) return
        val anchor = s.messages.firstOrNull { it.role != ChatRole.system }?.id ?: return
        _state.update { it.copy(isLoadingOlder = true) }
        viewModelScope.launch {
            val older = try {
                chatService.loadOlder(anchor, UI_PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { cur ->
                val ids = cur.messages.mapTo(HashSet()) { it.id }
                cur.copy(
                    messages = older.filter { it.id !in ids } + cur.messages,
                    isLoadingOlder = false,
                    hasMoreHistory = older.size >= UI_PAGE_SIZE
                )
            }
        }
    }

    /**
     * 把会话历史合并进当前 UI 消息列表（[reloadHistory] 与启动预热共用）。
     *
     * [ConversationManager] 有滑动窗口（超 [config.maxHistoryMessages] 从头部裁剪最旧消息），
     * 而本 ViewModel 的内存列表**从不裁剪**。若用「history + 所有不在 history 的消息」重建，
     * 被窗口裁掉的旧消息会被误判为"新消息"拼到列表末尾 → 顺序错乱。
     *
     * 正确合并：**以 history 最后一个 id 为分界**——history 之前的消息一律以 history 为准
     * （丢弃本 ViewModel 里已落后的旧副本）；history 之后才是真正的新追加（乐观消息、
     * 触摸系统气泡），按原顺序保留在尾部。历史本身保证有序（同源 ConversationManager）。
     */
    internal fun mergeWithHistory(history: List<ChatMessage>, current: List<ChatMessage>): List<ChatMessage> {
        if (history.isEmpty()) return current
        val lastHistoryId = history.last().id
        val historyIds = history.mapTo(HashSet()) { it.id }
        val idxInCurrent = current.indexOfLast { it.id == lastHistoryId }
        val tailExtra = if (idxInCurrent >= 0) {
            // current 里位于该分界之后的都是新追加，按原顺序保留
            current.drop(idxInCurrent + 1)
        } else {
            // 分界消息在当前列表里找不到（极端情况：history 整体都领先于 current），
            // 保守起见保留 current 末尾最近的几条（系统气泡等短生命周期消息）。
            // 必须再按时间戳过滤：本分支下"末尾几条"里可能混着已经在历史里、
            // 只是 id 不同的副本（如失败发送残留的同一句话），只保留确实比历史
            // 更新的消息，才符合"history 之后才是新追加"这一合并前提。
            val lastHistoryTs = history.last().timestamp
            current.takeLast(5).filter { it.timestamp > lastHistoryTs }
        }
        // 去重：tailExtra 里若已包含 history 中出现的 id（如悬浮窗已落库），去掉
        val tailExtraDeduped = tailExtra.filter { it.id !in historyIds }
        return history + tailExtraDeduped
    }

    /**
     * 更新 Live2D 触摸分区开关。
     *
     * 仅聊天页启用（设置页/隐私页禁止触摸穿透触发语音），由 ChatScreen 页面切换时调用。
     */
    fun updateZoneTouchEnabled(enabled: Boolean) {
        Live2dDelegate.getInstance().zoneTouchEnabled = enabled
    }

    /**
     * 处理 UI 事件。
     */
    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.SendMessage -> sendMessage(event.content)
            is ChatEvent.UpdateInput -> updateInput(event.text)
            is ChatEvent.ClearConversation -> clearConversation()
            is ChatEvent.ClearMemory -> clearMemory()
            is ChatEvent.ShowMemories -> showMemories()
            is ChatEvent.DismissMemories -> dismissMemories()
            is ChatEvent.DeleteMemory -> deleteMemory(event.id)
            is ChatEvent.RetryLastMessage -> retryLastMessage()
            is ChatEvent.DismissError -> dismissError()
            is ChatEvent.DismissUpdateNotice -> dismissUpdateNotice()
        }
    }

    // ── 事件处理 ──────────────────────────────────────

    private fun sendMessage(content: String) {
        if (content.isBlank()) return
        // 新的对话已经产生：清除前的快照不再代表当前会话，撤销会把新消息一起抹掉
        discardPendingUndo()

        val userMessage = ChatMessage(role = ChatRole.user, content = content)
        _state.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                error = null,
                inputText = ""
            )
        }

        sendJob = viewModelScope.launch {
            // 传入乐观消息本身：入史与 UI 共用同一个 id，reloadHistory 合并时才认得出
            // 是同一条（各建一条会让同一句话有两个 id，一次 resume 就并列成两条）
            val result = chatService.sendMessage(userMessage)
            result.fold(
                onSuccess = { (userMsg, assistantMsg) ->
                    _state.update { current ->
                        // 按 ViewModel 的 ID 移除乐观消息，再换上 ChatService 的正式消息
                        val updatedMessages = current.messages
                            .filterNot { it.id == userMessage.id }
                            .let { list -> list + listOf(userMsg, assistantMsg) }
                        current.copy(
                            messages = updatedMessages,
                            isLoading = false
                        )
                    }
                    // 主界面语音（模型就绪且开关开启时才真正发声，否则静默跳过）
                    container.ttsManager.speak(assistantMsg, com.meapet.mobile.tts.TtsManager.Source.MAIN)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "发送失败，请检查网络和 API Key"
                        )
                    }
                }
            )
        }
    }

    private fun retryLastMessage() {
        val lastUserMsg = _state.value.messages.lastOrNull { it.isUser }
            ?: return
        // 重试会产生新的消息对，与「清除前快照」冲突，先作废旧窗口
        discardPendingUndo()

        _state.update {
            it.copy(
                isLoading = true,
                error = null
            )
        }

        sendJob = viewModelScope.launch {
            // 重试目标由 UI 指定：失败的消息已被回滚出历史，服务端侧再去"取历史里最后
            // 一条 user"会重发另一条早已成功的消息
            val result = chatService.retryMessage(lastUserMsg)
            result.fold(
                onSuccess = { (userMsg, assistantMsg) ->
                    _state.update { current ->
                        // 分界一次性定位（原实现 indexOf 套 filterNot 为 O(n²)）：
                        // 去掉该 user 消息本身，及其之后的所有 assistant 回复
                        val cutIndex = current.messages.indexOfLast { it.id == lastUserMsg.id }
                        val updated = current.messages.filterIndexed { index, msg ->
                            when {
                                // 目标消息一律剔除（下面重新追加），无论它在列表哪个位置——
                                // 只按 index <= cutIndex 判断的话，重复副本会被漏掉，
                                // 追加后 LazyColumn 就拿到两个相同 key 而崩溃
                                msg.id == lastUserMsg.id -> false
                                // cutIndex < 0（目标已不在列表，理论上不可达）时不删任何
                                // assistant：此时全部下标都 > -1，否则等于清空界面所有回复
                                cutIndex >= 0 && index > cutIndex -> !msg.isAssistant
                                else -> true
                            }
                        }
                        current.copy(
                            messages = updated + listOf(userMsg, assistantMsg),
                            isLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "重试失败"
                        )
                    }
                }
            )
        }
    }

    private fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    private fun clearConversation() {
        // 取消在途发送，否则回复到达后会把消息回写进已清空的会话
        sendJob?.cancel()
        sendJob = null
        viewModelScope.launch {
        // 快照必须在清空之前取：撤销窗口内这是唯一的回滚依据。
        // 取仓库里的完整历史（不是内存窗口），否则撤销会丢掉窗口之前的消息
        val snapshot = chatService.loadFullHistory()
        chatService.clearHistory()
        _state.update {
            // 只清对话相关字段，保留 updateNotice / memoryDialog 等无关状态
            it.copy(
                messages = emptyList(),
                isLoading = false,
                error = null,
                inputText = ""
            )
        }
        if (snapshot.isNotEmpty()) {
            beginUndo(
                ChatUndo.ClearConversation(
                    id = nextUndoId(),
                    deadlineMs = nextUndoDeadline(),
                    snapshot = snapshot
                )
            )
        }
        }
    }

    private fun clearMemory() {
        viewModelScope.launch {
            // 快照含对话轮数：clearAll 会把它一并归零，撤销要一起还原
            val snapshot = memoryManager.getAllMemories()
            val exchangeCount = memoryManager.getExchangeCount()
            memoryManager.clearAll()
            _state.update {
                it.copy(
                    // 对话框开着时同步清空列表
                    memoryDialog = it.memoryDialog?.copy(
                        memories = emptyList(),
                        stats = MemoryStats()
                    )
                )
            }
            if (snapshot.isNotEmpty()) {
                beginUndo(
                    ChatUndo.ClearMemory(
                        id = nextUndoId(),
                        deadlineMs = nextUndoDeadline(),
                        snapshot = snapshot,
                        exchangeCount = exchangeCount
                    )
                )
            }
        }
    }

    /** 打开记忆查看对话框：拉取全部记忆与统计。 */
    private fun showMemories() {
        val mm = memoryManager
        viewModelScope.launch {
            val memories = mm.getAllMemories()
            val stats = mm.getStats()
            _state.update {
                it.copy(
                    memoryDialog = MemoryDialogUi(
                        memories = memories,
                        stats = stats,
                        isMemoryEnabled = mm.isMemoryEnabled()
                    )
                )
            }
        }
    }

    private fun dismissMemories() {
        _state.update { it.copy(memoryDialog = null) }
    }

    /** 删除单条记忆并刷新对话框列表。 */
    private fun deleteMemory(id: String) {
        val mm = memoryManager
        // 记忆已被单独修改：整批清除的快照不再等同于当前状态
        discardPendingUndo()
        viewModelScope.launch {
            mm.delete(id)
            _state.update { current ->
                val dialog = current.memoryDialog ?: return@update current
                current.copy(
                    memoryDialog = dialog.copy(
                        memories = dialog.memories.filterNot { it.id == id },
                        stats = mm.getStats()
                    )
                )
            }
        }
    }

    private fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── 撤销窗口 ──────────────────────────────────────

    /**
     * 撤销最近一次破坏性操作（Snackbar 的「撤销」键）。
     *
     * 用 pendingUndo 里携带的快照回滚；回滚完成后立即清空 pendingUndo，
     * 让 UI 的撤销条随之消失。
     */
    fun undoPending() {
        val undo = _state.value.pendingUndo ?: return
        undoTimer?.cancel()
        undoTimer = null
        when (undo) {
            is ChatUndo.ClearConversation -> {
                chatService.restoreHistory(undo.snapshot)
                _state.update {
                    // UI 只展示最近一页，更早的仍可向上懒加载
                    it.copy(
                        messages = undo.snapshot.takeLast(UI_PAGE_SIZE),
                        hasMoreHistory = undo.snapshot.size > UI_PAGE_SIZE,
                        pendingUndo = null
                    )
                }
            }
            is ChatUndo.ClearMemory -> {
                viewModelScope.launch {
                    memoryManager.restoreAll(undo.snapshot)
                    memoryManager.setExchangeCount(undo.exchangeCount)
                    _state.update { it.copy(pendingUndo = null) }
                }
            }
        }
    }

    /**
     * 丢弃未到期的撤销窗口（不做任何回滚）。
     *
     * 用于「同一份状态已被新的写入覆盖」的场景（发了新消息、删了单条记忆）：
     * 旧快照已不代表当前状态，此时撤销会把新内容一起抹掉。
     */
    private fun discardPendingUndo() {
        if (_state.value.pendingUndo == null) return
        undoTimer?.cancel()
        undoTimer = null
        _state.update { it.copy(pendingUndo = null) }
    }

    /** 登记一个撤销窗口：把快照放进状态，并起一个到期丢弃的定时器。 */
    private fun beginUndo(undo: ChatUndo) {
        undoTimer?.cancel()
        _state.update { it.copy(pendingUndo = undo) }
        undoTimer = viewModelScope.launch {
            // 用剩余时间而非窗口全长：被别的操作顶掉前若已过去一段时间，这里自然对齐
            delay((undo.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            // 只结算自己这一代：期间可能已被更新的操作顶掉，
            // 那时槽里是别人的快照，不该由这条定时器关闭
            if (_state.value.pendingUndo?.id == undo.id) {
                undoTimer = null
                _state.update { it.copy(pendingUndo = null) }
            }
        }
    }

    private fun nextUndoId(): Long = ++undoSeq

    private fun nextUndoDeadline(): Long = SystemClock.elapsedRealtime() + undoWindow

    private fun dismissUpdateNotice() {
        _state.update { it.copy(updateNotice = null) }
    }

    /**
     * 调度系统气泡的延时移除，返回对应的 Job。
     * @param lifeMap 到期后从此 map 中摘掉对应条目
     * @param msgId 消息 ID
     * @param delayMs 距到期还有多少毫秒（≤0 表示立即到期）
     */
    private fun scheduleRemove(
        lifeMap: MutableMap<String, BubbleLife>,
        msgId: String,
        delayMs: Long
    ): Job = viewModelScope.launch {
        // 必须先让出一次再动 lifeMap：viewModelScope 用 Dispatchers.Main.immediate，
        // delay(0) 不会挂起，协程体会同步跑在调用方写回 lifeMap 之前，
        // 于是刚被摘掉的条目又被写回去，留下持有已完成 Job 的僵尸条目。
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs.milliseconds) else yield()
        removeBubble(msgId)
        lifeMap.remove(msgId)
    }

    private fun removeBubble(msgId: String) {
        _state.update {
            it.copy(messages = it.messages.filterNot { m -> m.id == msgId })
        }
    }

}
