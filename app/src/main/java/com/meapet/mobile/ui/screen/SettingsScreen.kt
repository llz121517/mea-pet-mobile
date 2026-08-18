package com.meapet.mobile.ui.screen
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.ui.theme.THEME_PRESETS
import com.meapet.mobile.ui.theme.isDarkTheme
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel
// ── 视觉常量（语义命名，避免魔法数字） ──────────────────
/** 顶栏半透明背景 alpha。 */
private const val ALPHA_TOP_BAR = 0.85f
/** 次要/说明文字 alpha。 */
private const val ALPHA_MUTED_TEXT = 0.6f
/** 更淡文字 alpha（提示/列表说明）。 */
private const val ALPHA_FAINT_TEXT = 0.7f
/** 卡片/行背景表面变体 alpha。 */
private const val ALPHA_CARD_BG = 0.3f
/** 模型列表卡片背景 alpha。 */
private const val ALPHA_CARD_BG_MID = 0.45f
/** 分割线 / 禁用文字 alpha。 */
private const val ALPHA_DIVIDER = 0.4f
/** 滑杆未激活轨道色（深/浅主题）。 */
private fun sliderTrackColor(darkTheme: Boolean): Color =
if (darkTheme) Color(0xFF999999).copy(alpha = ALPHA_CARD_BG)
else Color.White.copy(alpha = 0.35f)
// ── Slider 规格（范围 + 步进） ────────────────────────
private val TEMPERATURE_RANGE = 0f..2f
private const val TEMPERATURE_STEPS = 19
private val MAX_TOKENS_RANGE = 256f..8192f
private const val MAX_TOKENS_STEPS = 30
private val SUMMARY_INTERVAL_RANGE = 3f..30f
private const val SUMMARY_INTERVAL_STEPS = 26
/** 失焦时保存的扩展（统一 onFocusChanged 样板）。 */
private fun Modifier.saveOnFocusChange(action: () -> Unit): Modifier =
onFocusChanged { if (!it.isFocused) action() }
/**
* 设置页面。
*
* 主体按功能拆分为 6 个 Section：[ApiConfigSection]、[ModelParamsSection]、
* [SystemPromptSection]、[MemorySection]、[ThemeSection]、[PrivacySection]；
* 本地编辑状态封装在 [SettingsLocalState]，本函数只负责状态管理与编排。
*/
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
onBack: () -> Unit,
onOpenPrivacyPolicy: () -> Unit = {},
onExitApp: () -> Unit = {},
settingsViewModel: SettingsViewModel = viewModel()
) {
val state by settingsViewModel.state.collectAsState()
// 深色与否跟随应用内主题设置（与页面整体配色取值一致），仅"跟随系统"时看系统
val darkTheme = isDarkTheme(state.themeMode)
// 本地编辑状态（进入页面时取一次已存值，失焦/离开页面时才写回）
val local = rememberSettingsLocalState(state)
// 离开页面时兜底保存（焦点还留在输入框内的场景）
DisposableEffect(Unit) {
onDispose { local.persist(settingsViewModel) }
}
// 从列表点选模型时，同步本地输入框
LaunchedEffect(state.model) {
if (local.model != state.model) local.model = state.model
}
Scaffold(
topBar = {
TopAppBar(
title = { Text("设置") },
navigationIcon = {
IconButton(onClick = onBack) {
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
Column(
modifier = Modifier
.fillMaxSize()
.padding(padding)
.verticalScroll(rememberScrollState())
.padding(horizontal = 16.dp)
) {
ApiConfigSection(state, settingsViewModel, local)
ModelParamsSection(state, settingsViewModel, local, darkTheme)
SystemPromptSection(settingsViewModel, local)
MemorySection(state, settingsViewModel, local, darkTheme)
ThemeSection(state, settingsViewModel, darkTheme)
PrivacySection(state, settingsViewModel, onOpenPrivacyPolicy, onExitApp)
Spacer(Modifier.height(24.dp))
}
}
}
/**
* 本地编辑状态 holder。
*
* 进入页面时从 [SettingsUiState] 取一次初值，之后独立于 state（失焦才写回），
* 避免 DataStore 流更新把用户正在编辑的内容覆盖掉。
*/
private class SettingsLocalState(initial: SettingsUiState) {
var apiKey by mutableStateOf(initial.apiKey)
var apiUrl by mutableStateOf(initial.apiUrl)
var model by mutableStateOf(initial.model)
var systemPrompt by mutableStateOf(initial.systemPrompt)
var temperature by mutableStateOf(initial.temperature.toFloat())
var maxTokens by mutableStateOf(initial.maxTokens.toFloat())
var summaryInterval by mutableStateOf(initial.summaryInterval.toFloat())
var apiKeyVisible by mutableStateOf(false)
/** 离开页面时的兜底保存（值有变化才落盘，见 ViewModel）。 */
fun persist(viewModel: SettingsViewModel) {
viewModel.saveApiKey(apiKey)
viewModel.saveApiUrl(apiUrl)
viewModel.saveModel(model)
viewModel.saveSystemPrompt(systemPrompt)
}
}
@Composable
private fun rememberSettingsLocalState(state: SettingsUiState): SettingsLocalState =
remember { SettingsLocalState(state) }
// ═══════════════════════════════════════════════════
//  Section 组件
// ═══════════════════════════════════════════════════
/** API 配置：端点说明 + API Key / 地址输入。 */
@Composable
private fun ApiConfigSection(
state: SettingsUiState,
viewModel: SettingsViewModel,
local: SettingsLocalState
) {
SectionTitle("API 配置")
Text(
"需要一个 OpenAI 兼容的 API 端点",
style = MaterialTheme.typography.bodySmall,
color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
modifier = Modifier.padding(bottom = 8.dp)
)
OutlinedTextField(
value = local.apiKey,
onValueChange = { local.apiKey = it },
label = { Text("API Key") },
placeholder = { Text("sk-...") },
modifier = Modifier
.fillMaxWidth()
.saveOnFocusChange { viewModel.saveApiKey(local.apiKey) },
singleLine = true,
visualTransformation = if (local.apiKeyVisible)
VisualTransformation.None
else
PasswordVisualTransformation(),
trailingIcon = {
TextButton(
onClick = { local.apiKeyVisible = !local.apiKeyVisible },
modifier = Modifier.width(56.dp)
) {
Text(
text = if (local.apiKeyVisible) "隐藏" else "显示",
style = MaterialTheme.typography.labelSmall,
color = MaterialTheme.colorScheme.primary
)
}
},
keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
)
Spacer(Modifier.height(8.dp))
OutlinedTextField(
value = local.apiUrl,
onValueChange = { local.apiUrl = it },
label = { Text("API 地址") },
modifier = Modifier
.fillMaxWidth()
.saveOnFocusChange { viewModel.saveApiUrl(local.apiUrl) },
singleLine = true,
placeholder = { Text("https://api.openai.com/v1") }
)
}
/** 模型参数：模型名 + 拉取列表 + Temperature / MaxToken 滑杆。 */
@Composable
private fun ModelParamsSection(
state: SettingsUiState,
viewModel: SettingsViewModel,
local: SettingsLocalState,
darkTheme: Boolean
) {
SectionTitle("模型参数")
OutlinedTextField(
value = local.model,
onValueChange = { local.model = it },
label = { Text("模型") },
modifier = Modifier
.fillMaxWidth()
.saveOnFocusChange { viewModel.saveModel(local.model) },
singleLine = true,
placeholder = { Text("gpt-4o-mini") }
)
Spacer(Modifier.height(8.dp))
OutlinedButton(
onClick = {
// 先落盘当前编辑中的 Key/URL，再拉列表
viewModel.saveApiKey(local.apiKey)
viewModel.saveApiUrl(local.apiUrl)
viewModel.fetchModels(local.apiKey, local.apiUrl)
},
enabled = !state.isLoadingModels,
modifier = Modifier.fillMaxWidth()
) {
if (state.isLoadingModels) {
CircularProgressIndicator(
modifier = Modifier.size(16.dp),
strokeWidth = 2.dp
)
Spacer(Modifier.width(8.dp))
Text("获取中…")
} else {
Text("获取模型列表")
}
}
state.modelsError?.let { err ->
Spacer(Modifier.height(6.dp))
Text(
text = err,
style = MaterialTheme.typography.bodySmall,
color = MaterialTheme.colorScheme.error,
modifier = Modifier
.fillMaxWidth()
.clickable { viewModel.dismissModelsError() }
)
}
if (state.availableModels.isNotEmpty()) {
Spacer(Modifier.height(8.dp))
Text(
text = "共 ${state.availableModels.size} 个模型，点选填入上方",
style = MaterialTheme.typography.bodySmall,
color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_FAINT_TEXT)
)
Spacer(Modifier.height(4.dp))
Card(
modifier = Modifier
.fillMaxWidth()
.heightIn(max = 240.dp),
colors = CardDefaults.cardColors(
containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG_MID)
),
shape = RoundedCornerShape(12.dp)
) {
LazyColumn(modifier = Modifier.fillMaxWidth()) {
items(state.availableModels, key = { it }) { modelId ->
val selected = modelId == local.model
Row(
modifier = Modifier
.fillMaxWidth()
.clickable {
local.model = modelId
viewModel.selectModel(modelId)
}
.padding(horizontal = 14.dp, vertical = 12.dp),
verticalAlignment = Alignment.CenterVertically
) {
Text(
text = modelId,
style = MaterialTheme.typography.bodyMedium,
color = if (selected)
MaterialTheme.colorScheme.primary
else
MaterialTheme.colorScheme.onSurface,
fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
modifier = Modifier.weight(1f)
)
if (selected) {
Icon(
imageVector = Icons.Filled.Check,
contentDescription = "已选中",
tint = MaterialTheme.colorScheme.primary,
modifier = Modifier.size(18.dp)
)
}
}
HorizontalDivider(
color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ALPHA_DIVIDER)
)
}
}
}
}
Spacer(Modifier.height(8.dp))
Text(
text = "Temperature: ${"%.2f".format(local.temperature)}",
style = MaterialTheme.typography.bodyMedium,
color = MaterialTheme.colorScheme.onSurfaceVariant
)
Slider(
value = local.temperature,
onValueChange = { local.temperature = it },
onValueChangeFinished = {
viewModel.updateTemperature(local.temperature.toDouble())
},
valueRange = TEMPERATURE_RANGE,
steps = TEMPERATURE_STEPS,
modifier = Modifier.fillMaxWidth(),
colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
)
Spacer(Modifier.height(8.dp))
Text(
text = "最大 Token: ${local.maxTokens.toInt()}",
style = MaterialTheme.typography.bodyMedium,
color = MaterialTheme.colorScheme.onSurfaceVariant
)
Slider(
value = local.maxTokens,
onValueChange = { local.maxTokens = it },
onValueChangeFinished = {
viewModel.updateMaxTokens(local.maxTokens.toInt())
},
valueRange = MAX_TOKENS_RANGE,
steps = MAX_TOKENS_STEPS,
modifier = Modifier.fillMaxWidth(),
colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
)
}
/** System Prompt 编辑区，带"恢复默认"按钮。 */
@Composable
private fun SystemPromptSection(
viewModel: SettingsViewModel,
local: SettingsLocalState
) {
SectionTitle("System Prompt")
OutlinedTextField(
value = local.systemPrompt,
onValueChange = { local.systemPrompt = it },
modifier = Modifier
.fillMaxWidth()
.height(120.dp)
.saveOnFocusChange { viewModel.saveSystemPrompt(local.systemPrompt) },
maxLines = 6
)
// ── 恢复默认按钮（右对齐） ──
Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.End
) {
TextButton(
onClick = {
val defaultPrompt = SettingsKeys.Defaults.SYSTEM_PROMPT
local.systemPrompt = defaultPrompt
viewModel.resetSystemPrompt()
}
) {
Text("恢复默认")
}
}
}
/** 记忆系统：开关 + 摘要轮次滑杆。 */
@Composable
private fun MemorySection(
state: SettingsUiState,
viewModel: SettingsViewModel,
local: SettingsLocalState,
darkTheme: Boolean
) {
SectionTitle("记忆系统")
SettingsSwitchRow(
label = "启用记忆",
description = "保留对话中提取的重要信息",
checked = state.enableMemory,
darkTheme = darkTheme,
onCheckedChange = { viewModel.updateEnableMemory(it) }
)
SettingsSwitchRow(
label = "自动摘要",
description = "定期总结对话为长期记忆",
checked = state.enableAutoSummary,
darkTheme = darkTheme,
onCheckedChange = { viewModel.updateEnableAutoSummary(it) }
)
Spacer(Modifier.height(8.dp))
Text(
text = "摘要轮次: 每 ${local.summaryInterval.toInt()} 轮对话总结一次",
style = MaterialTheme.typography.bodyMedium,
color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
alpha = if (state.enableAutoSummary) 1f else 0.5f
)
)
Slider(
value = local.summaryInterval,
onValueChange = { local.summaryInterval = it },
onValueChangeFinished = {
viewModel.updateSummaryInterval(local.summaryInterval.toInt())
},
valueRange = SUMMARY_INTERVAL_RANGE,
steps = SUMMARY_INTERVAL_STEPS,
enabled = state.enableAutoSummary,
modifier = Modifier.fillMaxWidth(),
colors = SliderDefaults.colors(
inactiveTrackColor = sliderTrackColor(darkTheme)
)
)
}
/** 主题：模式选择 + 动态颜色开关 + 颜色预设。 */
@Composable
private fun ThemeSection(
state: SettingsUiState,
viewModel: SettingsViewModel,
darkTheme: Boolean
) {
SectionTitle("主题")
// ── 主题模式 ──
ThemeModeSelector(
current = state.themeMode,
onSelect = { viewModel.updateThemeMode(it) }
)
Spacer(Modifier.height(12.dp))
// ── 动态颜色开关 ──
val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
SettingsSwitchRow(
label = "使用系统动态颜色",
description = if (dynamicColorSupported) "关闭后可选择预设主题色" else "当前系统不支持动态颜色",
checked = state.enableDynamicColor && dynamicColorSupported,
darkTheme = darkTheme,
onCheckedChange = { if (dynamicColorSupported) viewModel.updateEnableDynamicColor(it) },
enabled = dynamicColorSupported
)
// ── 颜色预设选择区（关闭动态颜色时展开） ──
AnimatedVisibility(
visible = !(state.enableDynamicColor && dynamicColorSupported),
enter = expandVertically(),
exit = shrinkVertically()
) {
ColorPresetSelector(
currentPreset = state.colorPreset,
onSelect = { viewModel.updateColorPreset(it) }
)
}
}
/** 隐私与数据：查看隐私政策 + 友盟采集授权管理。 */
@Composable
private fun PrivacySection(
state: SettingsUiState,
viewModel: SettingsViewModel,
onOpenPrivacyPolicy: () -> Unit,
onExitApp: () -> Unit
) {
SectionTitle("隐私与数据")
// 响应式读取授权状态（由 SettingsViewModel 订阅 PrivacyConsentManager.agreedFlow 维护）
val umengAgreed = state.privacyAgreed
var showRevokeDialog by remember { mutableStateOf(false) }
// 查看隐私协议
Surface(
modifier = Modifier
.fillMaxWidth()
.clickable { onOpenPrivacyPolicy() },
color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
shape = RoundedCornerShape(12.dp)
) {
Row(
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 16.dp, vertical = 14.dp),
verticalAlignment = Alignment.CenterVertically
) {
Text(
text = "查看隐私政策",
style = MaterialTheme.typography.bodyLarge,
color = MaterialTheme.colorScheme.onSurface,
modifier = Modifier.weight(1f)
)
Icon(
imageVector = Icons.AutoMirrored.Filled.ArrowBack,
contentDescription = null,
tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
modifier = Modifier
.size(20.dp)
.graphicsLayer { rotationZ = 180f }
)
}
}
Spacer(Modifier.height(8.dp))
// 友盟统计数据采集授权状态
Card(
modifier = Modifier.fillMaxWidth(),
colors = CardDefaults.cardColors(
containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
),
shape = RoundedCornerShape(12.dp)
) {
Column(
modifier = Modifier
.fillMaxWidth()
.padding(16.dp)
) {
Text(
text = "统计数据采集",
style = MaterialTheme.typography.bodyLarge,
color = MaterialTheme.colorScheme.onSurface
)
Text(
text = if (umengAgreed)
"已授权：友盟统计 SDK 正在采集去标识化的使用数据"
else
"未授权：不会采集任何统计数据，App 正常使用",
style = MaterialTheme.typography.bodySmall,
color = if (umengAgreed)
MaterialTheme.colorScheme.onSurfaceVariant
else
MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
modifier = Modifier.padding(top = 4.dp)
)
if (umengAgreed) {
Spacer(Modifier.height(8.dp))
OutlinedButton(
onClick = { showRevokeDialog = true },
modifier = Modifier.fillMaxWidth()
) {
Text("取消数据采集授权")
}
}
}
}
if (showRevokeDialog) {
AlertDialog(
onDismissRequest = { showRevokeDialog = false },
title = { Text("取消数据采集授权") },
text = {
Text(
"为确保撤回后立即、彻底停止数据采集，取消授权后 App 将自动退出；重新打开即可正常使用，且不会再进行任何统计采集。"
)
},
confirmButton = {
TextButton(onClick = {
viewModel.revokePrivacyConsent()
showRevokeDialog = false
onExitApp()
}) {
Text("确认取消并退出")
}
},
dismissButton = {
TextButton(onClick = { showRevokeDialog = false }) {
Text("保留授权")
}
}
)
}
}
// ═══════════════════════════════════════════════════
//  通用子组件
// ═══════════════════════════════════════════════════
@Composable
private fun SectionTitle(title: String) {
Text(
text = title,
style = MaterialTheme.typography.titleSmall,
fontWeight = FontWeight.SemiBold,
color = MaterialTheme.colorScheme.primary,
modifier = Modifier.padding(vertical = 8.dp)
)
}
@Composable
private fun SettingsSwitchRow(
label: String,
description: String,
checked: Boolean,
darkTheme: Boolean,
onCheckedChange: (Boolean) -> Unit,
enabled: Boolean = true
) {
Row(
modifier = Modifier
.fillMaxWidth()
.padding(vertical = 8.dp),
verticalAlignment = Alignment.CenterVertically
) {
Column(Modifier.weight(1f)) {
Text(
label,
style = MaterialTheme.typography.bodyLarge,
color = if (enabled) MaterialTheme.colorScheme.onSurface
else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
)
Text(
description,
style = MaterialTheme.typography.bodySmall,
color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
)
}
Switch(
checked = checked,
onCheckedChange = onCheckedChange,
enabled = enabled,
colors = SwitchDefaults.colors(
checkedThumbColor = MaterialTheme.colorScheme.background,
uncheckedTrackColor = MaterialTheme.colorScheme.background,
uncheckedThumbColor = if (darkTheme) MaterialTheme.colorScheme.outline
else Color.White,
)
)
}
}
@Composable
private fun ThemeModeSelector(
current: String,
onSelect: (String) -> Unit
) {
val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
var expanded by remember { mutableStateOf(false) }
var showPopup by remember { mutableStateOf(false) }
var boxWidthPx by remember { mutableStateOf(0) }
var boxHeightPx by remember { mutableStateOf(0) }
val density = LocalDensity.current
val animProgress = remember { Animatable(0f) }
LaunchedEffect(expanded) {
if (expanded) {
showPopup = true
animProgress.animateTo(1f, animationSpec = tween(200))
} else if (showPopup) {
animProgress.animateTo(0f, animationSpec = tween(200))
showPopup = false
}
}
Box(
modifier = Modifier
.fillMaxWidth()
.onGloballyPositioned {
boxWidthPx = it.size.width
boxHeightPx = it.size.height
}
) {
OutlinedTextField(
value = options.firstOrNull { it.first == current }?.second ?: "跟随系统",
onValueChange = {},
readOnly = true,
label = { Text("主题模式") },
trailingIcon = {
Icon(
imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
else Icons.Filled.KeyboardArrowDown,
contentDescription = null
)
},
modifier = Modifier.fillMaxWidth()
)
// 透明点击层——避免与 OutlinedTextField 的内部触摸处理冲突
Box(
modifier = Modifier
.matchParentSize()
.clickable { expanded = !expanded }
)
if (showPopup) {
val popupWidth = with(density) { boxWidthPx.toDp().coerceAtLeast(160.dp) }
Popup(
alignment = Alignment.TopStart,
offset = IntOffset(x = 0, y = boxHeightPx + 4),
onDismissRequest = { expanded = false },
properties = PopupProperties(focusable = true)
) {
Surface(
modifier = Modifier
.width(popupWidth)
.graphicsLayer {
alpha = animProgress.value
scaleX = 0.95f + 0.05f * animProgress.value
scaleY = 0.95f + 0.05f * animProgress.value
transformOrigin = TransformOrigin(0f, 0f)
},
color = MaterialTheme.colorScheme.surface,
contentColor = MaterialTheme.colorScheme.onSurface,
shape = RoundedCornerShape(8.dp),
tonalElevation = 2.dp,
shadowElevation = 8.dp
) {
Column {
options.forEach { (value, label) ->
DropdownMenuItem(
text = { Text(label) },
onClick = { onSelect(value); expanded = false }
)
}
}
}
}
}
}
}
/**
* 颜色预设选择区——色块网格。
*/
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresetSelector(
currentPreset: String,
onSelect: (String) -> Unit
) {
Column(modifier = Modifier.padding(top = 8.dp)) {
Text(
"主题色预设",
style = MaterialTheme.typography.labelLarge,
color = MaterialTheme.colorScheme.onSurfaceVariant,
modifier = Modifier.padding(bottom = 8.dp)
)
FlowRow(
horizontalArrangement = Arrangement.spacedBy(12.dp),
verticalArrangement = Arrangement.spacedBy(12.dp)
) {
THEME_PRESETS.forEach { preset ->
val isSelected = preset.id == currentPreset
Column(
horizontalAlignment = Alignment.CenterHorizontally,
modifier = Modifier
.clickable { onSelect(preset.id) }
.width(56.dp)
) {
// 色块圆
val borderMod = if (isSelected) {
Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
} else {
Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
}
Box(
modifier = Modifier
.size(40.dp)
.clip(CircleShape)
.background(preset.seed)
.then(borderMod)
)
Spacer(Modifier.height(4.dp))
Text(
text = preset.name,
style = MaterialTheme.typography.labelSmall,
color = if (isSelected)
MaterialTheme.colorScheme.primary
else
MaterialTheme.colorScheme.onSurfaceVariant,
maxLines = 1
)
}
}
}
}
}

