package com.meapet.mobile.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.viewmodel.ChatEvent
import com.meapet.mobile.chat.ChatUiState
import com.meapet.mobile.chat.MemoryDialogUi
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.ui.component.AppSnackbarHost
import com.meapet.mobile.ui.component.ChatBubble
import com.meapet.mobile.ui.component.ChatInputBar
import com.meapet.mobile.ui.component.ErrorBubble
import com.meapet.mobile.ui.component.OverlayMenu
import com.meapet.mobile.ui.component.UndoSnackbarEffect
import com.meapet.mobile.ui.screen.settings.SettingsScreen
import com.meapet.mobile.viewmodel.ChatViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged

/** 内部页面导航。 */
private enum class Page { CHAT, SETTINGS }

/**
 * 聊天界面入口。
 *
 * 内部管理 CHAT / SETTINGS 页面切换，带滑动过渡动画。设置页内部的二级子页
 * （含隐私政策全文）由 SettingsScreen 自己持有。
 */
@Composable
fun ChatScreenContent(
    onToggleOverlay: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel()
) {
    var currentPage by remember { mutableStateOf(Page.CHAT) }

    // 后台切前台时固定刷新一次聊天历史：悬浮窗期间对话发生在共享的会话里，
    // 主界面内存状态不会自动同步。ON_RESUME 每次前台都会触发（首次启动时
    // observer 已在 RESUMED 之后注册，由 ViewModel 初始化加载兜底）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                chatViewModel.reloadHistory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 在设置页时拦截系统返回键 → 回到聊天页。
    // 设置页内部的子页返回由 SettingsScreen 自己的 BackHandler 处理（内层优先）。
    BackHandler(enabled = currentPage == Page.SETTINGS) {
        currentPage = Page.CHAT
    }

    // 切换页面时同步触摸分区开关（设置页内禁止穿透）——经 ViewModel 访问领域单例
    LaunchedEffect(currentPage) {
        chatViewModel.updateZoneTouchEnabled(currentPage == Page.CHAT)
    }

    AnimatedContent(
        targetState = currentPage,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            if (forward) {
                // 进入深层页面：新页从右滑入，当前页向左滑出
                (slideInHorizontally { width -> width } + fadeIn())
                    .togetherWith(slideOutHorizontally { width -> -width / 3 } + fadeOut())
            } else {
                // 返回浅层页面：新页从左滑入，当前页向右滑出
                (slideInHorizontally { width -> -width } + fadeIn())
                    .togetherWith(slideOutHorizontally { width -> width / 3 } + fadeOut())
            }
        },
        label = "pageTransition"
    ) { page ->
        when (page) {
            Page.SETTINGS -> SettingsScreen(
                onBack = { currentPage = Page.CHAT },
                onExitApp = {
                    // 取消数据采集授权后需立即终止进程：友盟 SDK 有独立上报线程，
                    // 仅 finish 页面无法保证其立即停止，kill 是隐私合规的兜底
                    exitAppSilently()
                }
            )

            Page.CHAT -> ChatPage(
                chatViewModel = chatViewModel,
                onToggleOverlay = onToggleOverlay,
                onOpenSettings = { currentPage = Page.SETTINGS }
            )
        }
    }
}

/**
 * 聊天主页面。
 */
@Composable
private fun ChatPage(
    chatViewModel: ChatViewModel,
    onToggleOverlay: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by chatViewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 破坏性操作是「立即执行 + 可撤销」：执行结果与撤销快照都在
    // state.pendingUndo 里，本页只负责把撤销条显示出来。
    UndoSnackbarEffect(
        pending = state.pendingUndo,
        hostState = snackbarHostState,
        onUndo = { chatViewModel.undoPending() }
    )

    // 主页聊天气泡透明度：订阅设置流（默认 1.0 不透明），实时反映设置页的滑杆
    val context = LocalContext.current
    val bubbleAlphaFlow = remember(context) {
        (context.applicationContext as MeaPetApplication).container.settingsManager
            .chatBubbleAlphaFlow
    }
    val bubbleAlphaState = bubbleAlphaFlow.collectAsState(initial = SettingsKeys.Defaults.CHAT_BUBBLE_ALPHA)
    val bubbleAlpha = bubbleAlphaState.value.toFloat()

    // 新消息或错误卡片出现时自动滚动到底部。
    // 错误卡片是列表末尾的额外 item，所以它出现时也要滚一次，否则用户看不到重试按钮。
    // 以「最后一条消息的 id」为触发键而非条数：向上懒加载会在头部插入消息，
    // 条数变化不能把用户拽回底部（LazyColumn 带 key，头部插入时滚动位置自动锚定）。
    LaunchedEffect(state.messages.lastOrNull()?.id, state.error) {
        val lastIndex = state.messages.size - 1 + if (state.error != null) 1 else 0
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // 懒加载：滚动到接近顶部时向上加载更早的一页（ViewModel 内部防重入、判断是否还有更多）
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index <= 3) chatViewModel.loadOlderMessages()
            }
    }

    // 启动静默更新提示：有新版本时底部轻提示，可点「查看」打开 GitHub Release
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.updateNotice) {
        val notice = state.updateNotice ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = notice.message,
            actionLabel = "查看",
            duration = SnackbarDuration.Long,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) {
            // 防御：无浏览器/URL 异常时静默忽略，不影响主流程
            try {
                uriHandler.openUri(notice.url)
            } catch (_: Exception) {
                // ignore
            }
        }
        chatViewModel.onEvent(ChatEvent.DismissUpdateNotice)
    }

    // 上下文过长提醒：越过阈值后只弹一次（ViewModel 保证），动作直接触发整理。
    // 文案刻意口语化，与主界面「开始和 Mea 对话吧」保持同一种语气。
    LaunchedEffect(state.compactHint) {
        if (!state.compactHint) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "我们聊了好久啦，要不要让 Mea 把前面的内容整理一下喵？",
            actionLabel = "整理一下",
            duration = SnackbarDuration.Long,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) {
            chatViewModel.onEvent(ChatEvent.CompactContext)
        }
        chatViewModel.onEvent(ChatEvent.DismissCompactHint)
    }

    // 整页躲开软键盘：输入栏、撤销条、消息列表都随键盘收窄（窗口不再替我们
    // resize/pan，见 MainActivity 的 edge-to-edge 说明）
    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        // ── Layer 1: 消息列表 ──
        MessageList(
            state = state,
            listState = listState,
            bubbleAlpha = bubbleAlpha,
            onDismissError = { chatViewModel.onEvent(ChatEvent.DismissError) },
            onRetry = { chatViewModel.onEvent(ChatEvent.RetryLastMessage) }
        )

        // ── Layer 2: 顶部菜单 ──
        OverlayMenu(
            onToggleOverlay = onToggleOverlay,
            onClearConversation = {
                // 立即清空，随后由撤销条给一次反悔机会（见 UndoSnackbarEffect）
                chatViewModel.onEvent(ChatEvent.ClearConversation)
            },
            onShowMemories = {
                chatViewModel.onEvent(ChatEvent.ShowMemories)
            },
            onSettings = onOpenSettings,
            showCompactContext = state.canCompactContext || state.isCompacting,
            isCompacting = state.isCompacting,
            onCompactContext = { chatViewModel.onEvent(ChatEvent.CompactContext) },
            modifier = Modifier.align(Alignment.TopEnd)
        )

        // ── Layer 3: 底部输入栏 ──
        ChatInputBar(
            inputText = state.inputText,
            onInputChange = { chatViewModel.onEvent(ChatEvent.UpdateInput(it)) },
            onSend = { chatViewModel.onEvent(ChatEvent.SendMessage(state.inputText)) },
            isLoading = state.isLoading,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // ── Layer 4: Snackbar（提示 + 破坏性操作的二次确认） ──
        // 动作文案跟随主题 primary，见 AppSnackbarHost
        AppSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp)
        )

        // ── Layer 5: 记忆查看对话框 ──
        state.memoryDialog?.let { dialog ->
            MemoryDialog(
                dialog = dialog,
                onDismiss = { chatViewModel.onEvent(ChatEvent.DismissMemories) },
                onDeleteMemory = { id -> chatViewModel.onEvent(ChatEvent.DeleteMemory(id)) },
                onClearAll = {
                    // 先关对话框再清空：撤销条在主窗口，Dialog 是独立窗口且带遮罩，
                    // 不关掉它提示条会被压在后面看不见也点不到
                    chatViewModel.onEvent(ChatEvent.DismissMemories)
                    chatViewModel.onEvent(ChatEvent.ClearMemory)
                }
            )
        }
    }
}

/** 消息列表：空态提示 + 消息气泡 + 加载中。 */
@Composable
private fun MessageList(
    state: ChatUiState,
    listState: LazyListState,
    bubbleAlpha: Float,
    onDismissError: () -> Unit,
    onRetry: () -> Unit
) {
    // 兜底去重：消息列表出现重复 id 一定是上游 bug，但 LazyColumn 撞到重复 key 是
    // 直接抛 IllegalArgumentException 崩掉整个应用（不是渲染错乱），代价过高，
    // 所以在唯一的消费点加一道保险。真正的修复始终在产生 state 的地方。
    val uniqueMessages = remember(state.messages) { state.messages.distinctBy { it.id } }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 88.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (state.messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "开始和 Mea 对话吧！\n发送一条消息开始聊天 🐾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // 兜底去重见函数开头 uniqueMessages
        itemsIndexed(
            items = uniqueMessages,
            key = { _, message -> message.id }
        ) { index, message ->
            // 按天轻时间分隔条：相邻消息跨自然日时插一条（纯 UI 派生，不入库）
            val previousTs = if (index > 0) uniqueMessages[index - 1].timestamp else null
            dayDividerLabel(previousTs, message.timestamp)?.let { DividerLabel(it) }
            ChatBubble(message = message, alpha = bubbleAlpha)
            // 压缩分隔条：已折叠进摘要的内容与仍逐字发送的内容之间
            if (message.id == state.compactedUpToMessageId) {
                DividerLabel("早些时候")
            }
        }

        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Mea 正在思考...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // 错误卡片跟在最后一条消息之后，随对话一起滚动。
        // 只从 state.error 渲染，不进 messages，因此不会被写入会话历史。
        state.error?.let { message ->
            item(key = "error-bubble") {
                ErrorBubble(
                    message = message,
                    onDismiss = onDismissError,
                    onRetry = onRetry,
                    alpha = bubbleAlpha
                )
            }
        }
    }
}


/**
 * 记忆查看对话框：统计 + 条目列表 + 单条删除 + 清除全部。
 *
 * 「清除全部」只上报点击（[onClearAll]）：清空与撤销条都由页面负责，
 * 本对话框不自己维护任何确认态——对话框是独立窗口，在这里做确认反而
 * 会被自己的遮罩挡住提示条。
 */
@Composable
private fun MemoryDialog(
    dialog: MemoryDialogUi,
    onDismiss: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 48.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Mea 的记忆", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))

                val stats = dialog.stats
                Text(
                    text = if (dialog.isMemoryEnabled) {
                        "共 ${stats.totalCount} 条 · 短期 ${stats.shortTermCount} · " +
                            "长期 ${stats.longTermCount} · 事实 ${stats.factualsCount}"
                    } else {
                        "记忆功能已关闭（可在设置中开启）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))

                if (dialog.memories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "还没有记忆喵~\n多和 Mea 聊聊天吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                    ) {
                        items(items = dialog.memories, key = { it.id }) { memory ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = memory.content,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = buildString {
                                            append(memoryTypeLabel(memory.type))
                                            append(" · 重要性 ")
                                            append((memory.importance * 100).toInt())
                                            append("%")
                                            if (memory.keywords.isNotEmpty()) {
                                                append(" · 关键词: ")
                                                append(memory.keywords.joinToString(", "))
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                TextButton(onClick = { onDeleteMemory(memory.id) }) {
                                    Text("删除", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onClearAll,
                        enabled = dialog.memories.isNotEmpty()
                    ) {
                        Text("清除全部")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

/**
 * 分隔条：`──────── 今天 ────────` 样式的淡色横线 + 居中文案。
 *
 * 用于「按天时间」与「早些时候（已压缩）」两种分隔，两者共用同一组件，仅文案不同。
 */
@Composable
private fun DividerLabel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        HorizontalDivider(modifier = Modifier.weight(1f), color = lineColor)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = lineColor)
    }
}

/**
 * 按天分隔条文案：相邻两条消息跨自然日（设备本地时区）时返回文案，同一天返回 null。
 *
 * @param previousTs 上一条可见消息时间戳；null（列表首条）时总是给出当天文案
 * @param currentTs 当前消息时间戳
 */
private fun dayDividerLabel(
    previousTs: Long?,
    currentTs: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String? {
    val current = Instant.ofEpochMilli(currentTs).atZone(zone).toLocalDate()
    if (previousTs != null) {
        val previous = Instant.ofEpochMilli(previousTs).atZone(zone).toLocalDate()
        if (previous == current) return null
    }
    val today = LocalDate.now(zone)
    return when {
        current == today -> "今天"
        current == today.minusDays(1) -> "昨天"
        current.year == today.year -> "${current.monthValue}月${current.dayOfMonth}日"
        else -> "${current.year}年${current.monthValue}月${current.dayOfMonth}日"
    }
}

/** 记忆类型的中文标签。 */private fun memoryTypeLabel(type: MemoryType): String = when (type) {
    MemoryType.SHORT_TERM -> "短期"
    MemoryType.LONG_TERM -> "长期"
    MemoryType.CORE_TRAIT -> "特质"
    MemoryType.FACTUAL -> "事实"
}

/** 立即结束应用进程（隐私撤销授权后的合规退出，确保上报线程停止）。 */
private fun exitAppSilently() {
    android.os.Process.killProcess(android.os.Process.myPid())
}
