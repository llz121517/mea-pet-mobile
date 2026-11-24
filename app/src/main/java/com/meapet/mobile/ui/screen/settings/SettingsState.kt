package com.meapet.mobile.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  设置页本地编辑状态
// ═══════════════════════════════════════════════════

/**
 * 本地编辑状态 holder。
 *
 * 进入页面时从 [SettingsUiState] 取一次初值，之后独立于 state（失焦才写回），
 * 避免 DataStore 流更新把用户正在编辑的内容覆盖掉。
 *
 * ## 外部变更与「脏字段」
 * 「完全不理 DataStore 流」会带来一个问题：**备份导入**在页面背后把设置整批替换了，
 * 但本地副本还是进页面时的旧值；用户离开设置页时 `onDispose` 的兜底保存会把旧值
 * 又写回去，等于导入没生效（API Key 尤其明显——它不在任何断点上被重读）。
 *
 * 因此每个可编辑字段记录一个「用户改过没」的脏标记：
 * - 用户改过（dirty）：保持用户输入，不被外部变更覆盖；
 * - 用户没动过（clean）：跟随最新 state 同步，外部写入（如导入）能反映到界面。
 *
 * 用户主动清空/改写时置脏由各 `onValueChange` 通过 [markDirty] 触发；[persist] 时
 * 只写回 dirty 字段，避免把未编辑的旧副本盖到刚从外部导入的新值上。
 */
internal class SettingsLocalState(
    initial: SettingsUiState,
    initialAnySearchKey: String,
    initialDeepSeekKey: String
) {
    var apiKey by mutableStateOf(initial.apiKey)
        private set
    var apiUrl by mutableStateOf(initial.apiUrl)
        private set
    var model by mutableStateOf(initial.model)
        private set
    var systemPrompt by mutableStateOf(initial.systemPrompt)
        private set
    var temperature by mutableStateOf(initial.temperature.toFloat())
    var maxTokens by mutableStateOf(initial.maxTokens.toFloat())
    var summaryInterval by mutableStateOf(initial.summaryInterval.toFloat())
    var contextWindow by mutableStateOf(initial.contextWindow?.toString() ?: "")
        private set
    var webSearchAnySearchKey by mutableStateOf(initialAnySearchKey)
        private set
    var webSearchDeepSeekKey by mutableStateOf(initialDeepSeekKey)
        private set
    var webSearchDeepSeekKeyVisible by mutableStateOf(false)
    var apiKeyVisible by mutableStateOf(false)
    var webSearchAnySearchKeyVisible by mutableStateOf(false)

    /** 用户改过的字段名（只有这些才会在 [persist] 时写回、且不被外部变更覆盖）。 */
    private val dirty = mutableSetOf<String>()

    /** 用户在输入框里改了某个字段：置脏，之后不再跟随外部变更。 */
    fun markDirty(field: String) {
        dirty += field
    }

    private fun isDirty(field: String): Boolean = field in dirty

    /** 字段名常量，供 UI 调 [updateXxx] / [markDirty] 与 [syncFrom] 对齐。 */
    object Field {
        const val API_KEY = "api_key"
        const val API_URL = "api_url"
        const val MODEL = "model"
        const val SYSTEM_PROMPT = "system_prompt"
        const val CONTEXT_WINDOW = "context_window"
        const val ANYSEARCH_KEY = "web_search_anysearch_key"
        const val DEEPSEEK_KEY = "web_search_deepseek_key"
    }

    // 各字段的统一入口：用户输入 → 置脏并改本地值
    fun updateApiKey(value: String) { markDirty(Field.API_KEY); apiKey = value }
    fun updateApiUrl(value: String) { markDirty(Field.API_URL); apiUrl = value }
    fun updateModel(value: String) { markDirty(Field.MODEL); model = value }
    fun updateSystemPrompt(value: String) { markDirty(Field.SYSTEM_PROMPT); systemPrompt = value }
    fun updateContextWindow(value: String) { markDirty(Field.CONTEXT_WINDOW); contextWindow = value }
    fun updateAnySearchKey(value: String) { markDirty(Field.ANYSEARCH_KEY); webSearchAnySearchKey = value }
    fun updateDeepSeekKey(value: String) { markDirty(Field.DEEPSEEK_KEY); webSearchDeepSeekKey = value }

    /**
     * 把最新 [state] 同步进未编辑的字段（外部写入，例如备份导入）。
     *
     * dirty 字段保持用户正在编辑的内容；其余字段跟随 state，保证导入后界面能看到新值，
     * 且 [persist] 不会把旧副本写回去。
     */
    fun syncFrom(state: SettingsUiState, anySearchKey: String, deepSeekKey: String) {
        if (!isDirty(Field.API_KEY) && apiKey != state.apiKey) apiKey = state.apiKey
        if (!isDirty(Field.API_URL) && apiUrl != state.apiUrl) apiUrl = state.apiUrl
        if (!isDirty(Field.MODEL) && model != state.model) model = state.model
        if (!isDirty(Field.SYSTEM_PROMPT) && systemPrompt != state.systemPrompt) {
            systemPrompt = state.systemPrompt
        }
        if (!isDirty(Field.CONTEXT_WINDOW)) {
            val latest = state.contextWindow?.toString() ?: ""
            if (contextWindow != latest) contextWindow = latest
        }
        if (!isDirty(Field.ANYSEARCH_KEY) && webSearchAnySearchKey != anySearchKey) {
            webSearchAnySearchKey = anySearchKey
        }
        if (!isDirty(Field.DEEPSEEK_KEY) && webSearchDeepSeekKey != deepSeekKey) {
            webSearchDeepSeekKey = deepSeekKey
        }
    }

    /**
     * 离开页面时的兜底保存（值有变化才落盘，见 ViewModel）。
     *
     * **只写回用户改过的字段**：未编辑字段可能是外部（备份导入）刚写入的新值，
     * 把它们拿本地旧副本覆盖，等于把导入结果冲掉。
     */
    fun persist(viewModel: SettingsViewModel) {
        if (isDirty(Field.API_KEY)) viewModel.saveApiKey(apiKey)
        if (isDirty(Field.API_URL)) viewModel.saveApiUrl(apiUrl)
        if (isDirty(Field.MODEL)) viewModel.saveModel(model)
        if (isDirty(Field.CONTEXT_WINDOW)) viewModel.saveContextWindow(contextWindow)
        if (isDirty(Field.SYSTEM_PROMPT)) viewModel.saveSystemPrompt(systemPrompt)
        if (isDirty(Field.ANYSEARCH_KEY)) viewModel.saveWebSearchAnySearchKey(webSearchAnySearchKey)
        if (isDirty(Field.DEEPSEEK_KEY)) viewModel.saveWebSearchDeepSeekKey(webSearchDeepSeekKey)
    }
}

@Composable
internal fun rememberSettingsLocalState(
    state: SettingsUiState,
    viewModel: SettingsViewModel
): SettingsLocalState = remember {
    SettingsLocalState(
        state,
        viewModel.currentWebSearchAnySearchKey(),
        viewModel.currentWebSearchDeepSeekKey()
    )
}

// ═══════════════════════════════════════════════════
//  Section 组件
// ═══════════════════════════════════════════════════
