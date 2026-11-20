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
 */
internal class SettingsLocalState(
    initial: SettingsUiState,
    initialAnySearchKey: String,
    initialDeepSeekKey: String
) {
    var apiKey by mutableStateOf(initial.apiKey)
    var apiUrl by mutableStateOf(initial.apiUrl)
    var model by mutableStateOf(initial.model)
    var systemPrompt by mutableStateOf(initial.systemPrompt)
    var temperature by mutableStateOf(initial.temperature.toFloat())
    var maxTokens by mutableStateOf(initial.maxTokens.toFloat())
    var summaryInterval by mutableStateOf(initial.summaryInterval.toFloat())
    var contextWindow by mutableStateOf(initial.contextWindow?.toString() ?: "")
    var webSearchAnySearchKey by mutableStateOf(initialAnySearchKey)
    var webSearchDeepSeekKey by mutableStateOf(initialDeepSeekKey)
    var webSearchDeepSeekKeyVisible by mutableStateOf(false)
    var apiKeyVisible by mutableStateOf(false)
    var webSearchAnySearchKeyVisible by mutableStateOf(false)

    /** 离开页面时的兜底保存（值有变化才落盘，见 ViewModel）。 */
    fun persist(viewModel: SettingsViewModel) {
        viewModel.saveApiKey(apiKey)
        viewModel.saveApiUrl(apiUrl)
        viewModel.saveModel(model)
        viewModel.saveContextWindow(contextWindow)
        viewModel.saveSystemPrompt(systemPrompt)
        viewModel.saveWebSearchAnySearchKey(webSearchAnySearchKey)
        viewModel.saveWebSearchDeepSeekKey(webSearchDeepSeekKey)
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
