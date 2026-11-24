package com.meapet.mobile.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.R
import com.meapet.mobile.ui.component.AppSnackbarHost
import com.meapet.mobile.ui.component.UndoSnackbarEffect
import com.meapet.mobile.ui.component.PrivacyPolicyContent
import com.meapet.mobile.ui.theme.findPreset
import com.meapet.mobile.ui.theme.isDarkTheme
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsUndo
import com.meapet.mobile.viewmodel.SettingsViewModel

/**
 * 设置页的二级页面。
 *
 * [ROOT] 是入口列表，其余各项对应一个功能域子页。枚举顺序即列表顺序，也被
 * [AnimatedContent] 用来判断进入/返回方向，故 [POLICY] 须排在 [ABOUT] 之后。
 */
private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    PROVIDER("提供商"),
    CHAT("对话"),
    APPEARANCE("外观"),
    VOICE("语音"),
    DATA("数据与备份"),
    ABOUT("关于"),
    POLICY("隐私政策"),
}

/**
 * 设置页面：入口列表 + 若干二级子页。
 *
 * 根页只列功能域入口，设置项都在子页里，避免所有分组堆在同一个滚动列表。
 * 隐私政策全文是「关于」再往里一层的子页，整棵导航子树由本函数持有，
 * 因此从政策页返回能回到「关于」而不是弹回根页。
 *
 * 各子页的 Section 在同包的 `*Settings.kt`，公共件在 `SettingsCommon.kt`。
 *
 * @param onBack 从根页返回（退出设置）
 * @param onExitApp 取消数据采集授权后终止进程
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onExitApp: () -> Unit = {},
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val state by settingsViewModel.state.collectAsState()

    // 深色与否跟随应用内主题设置（与页面整体配色取值一致），仅"跟随系统"时看系统
    val darkTheme = isDarkTheme(state.themeMode)

    var page by remember { mutableStateOf(SettingsPage.ROOT) }

    // 设置页的 Snackbar 宿主：供「可撤销操作」的撤销条使用。
    // 提到 Scaffold 层，子页切换（AnimatedContent 重建）时撤销条不会丢。
    val snackbarHostState = remember { SnackbarHostState() }

    // 本地编辑状态（进入设置时取一次已存值，失焦/离开设置时才写回）。
    // 提到根页持有，这样在子页之间来回切不会丢掉正在编辑的内容。
    val local = rememberSettingsLocalState(state, settingsViewModel)

    // ── 可撤销操作的撤销条 ──
    // 快照与到期定时器都在 SettingsViewModel 里：转屏、切子页都不影响窗口，
    // 本页只负责把 pendingUndo 画成带倒计时的 Snackbar。
    UndoSnackbarEffect(
        pending = state.pendingUndo,
        hostState = snackbarHostState,
        onUndo = { undo ->
            settingsViewModel.undoPending()
            // 文本框是本地编辑态、不跟随 DataStore 流，撤销后要手动同步回原值
            if (undo is SettingsUndo.ResetPrompt) local.updateSystemPrompt(undo.previousPrompt)
        }
    )

    // 离开整个设置页时兜底保存（焦点还留在输入框内的场景）
    DisposableEffect(Unit) {
        onDispose { local.persist(settingsViewModel) }
    }

    // 外部写入（典型是「数据与备份」里的导入）后，把未编辑的字段同步到界面。
    // 用户正在改的字段有脏标记保护，不会被覆盖；未编辑字段若不同步，离开页面时的
    // 兜底保存会把本地旧副本写回，等于把刚导入的设置冲掉（API Key 就是这样丢的）。
    LaunchedEffect(state.apiKey, state.apiUrl, state.model, state.systemPrompt, state.contextWindow) {
        local.syncFrom(
            state = state,
            anySearchKey = settingsViewModel.currentWebSearchAnySearchKey(),
            deepSeekKey = settingsViewModel.currentWebSearchDeepSeekKey(),
        )
    }

    // 离开关于页时清掉手动检测更新的结果文案，避免下次进入还残留着上次的回执
    LaunchedEffect(page) {
        if (page != SettingsPage.ABOUT) settingsViewModel.dismissUpdateMessage()
    }

    // 子页优先拦截返回：政策页回"关于"，其余子页回入口列表。根页时本 handler 关闭，
    // 交给 ChatScreen 那层的 BackHandler 退出设置（内层优先，见 Compose BackHandler 语义）。
    BackHandler(enabled = page != SettingsPage.ROOT) {
        page = if (page == SettingsPage.POLICY) SettingsPage.ABOUT else SettingsPage.ROOT
    }

    Scaffold(
        // 让整页躲开软键盘：内容区与 snackbar 槽位都会随之收窄，
        // 撤销条因此落在键盘上方（见 AppSnackbarHost 的说明）
        modifier = Modifier.imePadding(),
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            page = when (page) {
                                SettingsPage.ROOT -> {
                                    onBack()
                                    SettingsPage.ROOT
                                }
                                SettingsPage.POLICY -> SettingsPage.ABOUT
                                else -> SettingsPage.ROOT
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = ALPHA_TOP_BAR)
                )
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // 与 ChatScreen 的页面过渡保持一致：进入深层从右滑入，返回从左滑入
                val forward = targetState.ordinal > initialState.ordinal
                if (forward) {
                    (slideInHorizontally { w -> w } + fadeIn())
                        .togetherWith(slideOutHorizontally { w -> -w / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { w -> -w } + fadeIn())
                        .togetherWith(slideOutHorizontally { w -> w / 3 } + fadeOut())
                }
            },
            label = "settingsPageTransition"
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                when (current) {
                    SettingsPage.ROOT -> RootPage(state = state, onNavigate = { page = it })

                    SettingsPage.PROVIDER -> {
                        SettingsCard { ApiConfigSection(state, settingsViewModel, local) }
                        SettingsCard {
                            ModelParamsSection(state, settingsViewModel, local, darkTheme)
                        }
                        SettingsCard { WebSearchSection(state, settingsViewModel, local, darkTheme) }
                    }

                    SettingsPage.CHAT -> {
                        SettingsCard {
                            SystemPromptSection(
                                viewModel = settingsViewModel,
                                local = local
                            )
                        }
                        SettingsCard { MemorySection(state, settingsViewModel, local, darkTheme) }
                    }

                    // AppearanceSection 内部已含背景壁纸 / 气泡透明度 / 主题三小节
                    SettingsPage.APPEARANCE -> {
                        SettingsCard {
                            AppearanceSection(
                                state = state,
                                viewModel = settingsViewModel,
                                darkTheme = darkTheme
                            )
                        }
                    }

                    SettingsPage.VOICE -> {
                        SettingsCard { TtsSection(state, settingsViewModel, darkTheme) }
                    }

                    // 数据与备份：导出 / 导入两个可折叠块，回执走页面级 Snackbar
                    SettingsPage.DATA -> {
                        SettingsCard { DataSettings(snackbarHostState = snackbarHostState) }
                    }

                    SettingsPage.ABOUT -> {
                        SettingsCard { AppInfoSection() }
                        SettingsCard { UpdateSection(state, settingsViewModel, darkTheme) }
                        SettingsCard {
                            PrivacySection(
                                state = state,
                                viewModel = settingsViewModel,
                                onOpenPrivacyPolicy = { page = SettingsPage.POLICY },
                                onExitApp = onExitApp
                            )
                        }
                        SettingsCard { AttributionSection() }
                    }

                    // 政策全文不套卡片：长文本直接铺开更好读
                    SettingsPage.POLICY -> PrivacyPolicyContent()
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 入口列表：六个功能域，每项显示当前状态摘要。 */
@Composable
private fun RootPage(
    state: SettingsUiState,
    onNavigate: (SettingsPage) -> Unit
) {
    val entries = listOf(
        SettingsEntry(
            page = SettingsPage.PROVIDER,
            iconRes = R.drawable.ic_settings_provider,
            summary = state.model.ifBlank { "未设置模型" } +
                if (state.webSearchEnabled) " · 联网已开启" else " · 联网已关闭"
        ),
        SettingsEntry(
            page = SettingsPage.CHAT,
            iconRes = R.drawable.ic_settings_chat,
            summary = "System Prompt、" + if (state.enableMemory) "记忆已开启" else "记忆已关闭"
        ),
        SettingsEntry(
            page = SettingsPage.APPEARANCE,
            iconRes = R.drawable.ic_settings_appearance,
            summary = appearanceSummary(state)
        ),
        SettingsEntry(
            page = SettingsPage.VOICE,
            iconRes = R.drawable.ic_settings_voice,
            summary = if (state.ttsMainEnabled || state.ttsOverlayEnabled) "发声已开启"
                      else "发声已关闭"
        ),
        SettingsEntry(
            page = SettingsPage.DATA,
            iconRes = R.drawable.ic_settings_backup,
            summary = "导出与导入设置、对话、记忆"
        ),
        SettingsEntry(
            page = SettingsPage.ABOUT,
            iconRes = R.drawable.ic_settings_about,
            summary = if (state.appVersion.isBlank()) "版本信息、隐私政策"
                      else "版本 ${state.appVersion}"
        ),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                SettingsEntryRow(entry = entry, onClick = { onNavigate(entry.page) })
                if (index != entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                            .copy(alpha = ALPHA_DIVIDER)
                    )
                }
            }
        }
    }
}

/** 入口列表的一行数据。 */
private data class SettingsEntry(
    val page: SettingsPage,
    val iconRes: Int,
    val summary: String,
)

/** 入口行：MD3 ListItem（前置图标 + 标题 + 摘要 + 右侧箭头）。 */
@Composable
private fun SettingsEntryRow(
    entry: SettingsEntry,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        // 容器透明，露出外层卡片的半透明底色（否则 ListItem 会盖上不透明 surface）
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                painter = painterResource(entry.iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        },
        headlineContent = { Text(entry.page.title) },
        supportingContent = {
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_FAINT_TEXT)
            )
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.size(20.dp)
            )
        }
    )
}

/** 外观入口摘要：主题模式 + 配色来源。 */
private fun appearanceSummary(state: SettingsUiState): String {
    val mode = when (state.themeMode) {
        "light" -> "浅色"
        "dark" -> "深色"
        else -> "跟随系统"
    }
    val color = if (state.enableDynamicColor) "动态取色"
                else findPreset(state.colorPreset).name
    return "$mode · $color"
}
