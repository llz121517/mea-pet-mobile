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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
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
import com.meapet.mobile.ui.component.ChatBubble
import com.meapet.mobile.ui.component.ChatInputBar
import com.meapet.mobile.ui.component.ErrorBubble
import com.meapet.mobile.ui.component.OverlayMenu
import com.meapet.mobile.ui.screen.settings.SettingsScreen
import com.meapet.mobile.viewmodel.ChatViewModel

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
    LaunchedEffect(state.messages.size, state.error) {
        val lastIndex = state.messages.size - 1 + if (state.error != null) 1 else 0
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // 清除记忆/对话后回显 Snackbar
    LaunchedEffect(state.memoryContextInfo) {
        state.memoryContextInfo?.let {
            snackbarHostState.showSnackbar(it)
            chatViewModel.onEvent(ChatEvent.DismissMemoryInfo)
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                chatViewModel.onEvent(ChatEvent.ClearConversation)
            },
            onShowMemories = {
                chatViewModel.onEvent(ChatEvent.ShowMemories)
            },
            onSettings = onOpenSettings,
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

        // ── Layer 4: Snackbar ──
        // 动作文案（如「查看」）跟随主题 primary，避免默认 inversePrimary 固定偏蓝
        val snackbarActionColor = MaterialTheme.colorScheme.primary
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                actionColor = snackbarActionColor,
                actionContentColor = snackbarActionColor
            )
        }

        // ── Layer 5: 记忆查看对话框 ──
        state.memoryDialog?.let { dialog ->
            MemoryDialog(
                dialog = dialog,
                onDismiss = { chatViewModel.onEvent(ChatEvent.DismissMemories) },
                onDeleteMemory = { id -> chatViewModel.onEvent(ChatEvent.DeleteMemory(id)) },
                onClearAll = { chatViewModel.onEvent(ChatEvent.ClearMemory) }
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
        items(
            items = uniqueMessages,
            key = { it.id }
        ) { message ->
            ChatBubble(message = message, alpha = bubbleAlpha)
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
 * 记忆查看对话框：统计 + 条目列表 + 单条删除 + 清除全部（带确认）。
 */
@Composable
private fun MemoryDialog(
    dialog: MemoryDialogUi,
    onDismiss: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var confirmClearAll by remember { mutableStateOf(false) }

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
                    if (confirmClearAll) {
                        TextButton(onClick = { confirmClearAll = false; onClearAll() }) {
                            Text("确认清除？", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(
                            onClick = { confirmClearAll = true },
                            enabled = dialog.memories.isNotEmpty()
                        ) {
                            Text("清除全部")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

/** 记忆类型的中文标签。 */
private fun memoryTypeLabel(type: MemoryType): String = when (type) {
    MemoryType.SHORT_TERM -> "短期"
    MemoryType.LONG_TERM -> "长期"
    MemoryType.CORE_TRAIT -> "特质"
    MemoryType.FACTUAL -> "事实"
}

/** 立即结束应用进程（隐私撤销授权后的合规退出，确保上报线程停止）。 */
private fun exitAppSilently() {
    android.os.Process.killProcess(android.os.Process.myPid())
}
