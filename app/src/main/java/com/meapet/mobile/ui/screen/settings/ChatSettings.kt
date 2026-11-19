package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meapet.mobile.config.MemoryMode
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  对话：System Prompt 与记忆系统
// ═══════════════════════════════════════════════════

/**
 * System Prompt 编辑区。
 *
 * 「恢复默认」立即生效（本地编辑态 + 落盘），随后由设置页的撤销条给一次反悔机会：
 * 撤销条是页面级能力，不属于这个叶子组件。
 */
@Composable
internal fun SystemPromptSection(
    viewModel: SettingsViewModel,
    local: SettingsLocalState
) {
    SectionTitle("System Prompt")

    Column {
        OutlinedTextField(
            value = local.systemPrompt,
            onValueChange = { local.systemPrompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .saveOnFocusChange { viewModel.saveSystemPrompt(local.systemPrompt) },
            maxLines = 6
        )

        Spacer(Modifier.height(8.dp))

        // 恢复默认：先改本地编辑态让文本框立刻变化，再异步落盘并开撤销窗口。
        // 快照由 ViewModel 从传入的旧文本取——用户可能改完还没失焦保存，
        // 用 DataStore 里的值当快照会把眼前内容丢掉。
        val isDefault = local.systemPrompt == SettingsKeys.Defaults.SYSTEM_PROMPT
        OutlinedButton(
            onClick = {
                val previous = local.systemPrompt
                local.systemPrompt = SettingsKeys.Defaults.SYSTEM_PROMPT
                viewModel.resetSystemPrompt(previous)
            },
            // 已是默认值就置灰：没有可重置的内容（与背景壁纸的「恢复默认」一致）
            enabled = !isDefault,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "恢复默认",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}


/** 记忆系统：开关 + 摘要轮次滑杆。 */
@Composable
internal fun MemorySection(
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

    // 记忆检索方式：只影响「模型怎么找到记忆」，与是否记忆无关
    if (state.enableMemory) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MemoryMode.entries.forEach { mode ->
                FilterChipLike(
                    label = memoryModeLabel(mode),
                    selected = state.memoryMode == mode,
                    enabled = true,
                    onClick = { viewModel.updateMemoryMode(mode) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = memoryModeHint(state.memoryMode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
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

/** 记忆检索方式的短标签（四个选项同一行，保持简短）。 */
private fun memoryModeLabel(mode: MemoryMode): String = when (mode) {
    MemoryMode.AUTO -> "自动"
    MemoryMode.TOOLS -> "工具"
    MemoryMode.TEXT -> "文本"
    MemoryMode.OFF -> "关闭"
}

/** 记忆检索方式的一句话说明。 */
private fun memoryModeHint(mode: MemoryMode): String = when (mode) {
    MemoryMode.AUTO -> "自动：优先让模型自己调用检索工具，端点不支持时回退关键词注入"
    MemoryMode.TOOLS -> "工具：模型主动检索/写入记忆（需要端点支持 function calling）"
    MemoryMode.TEXT -> "文本：按关键词注入相关记忆，写入走文本协议（旧版行为，可能在后续版本移除支持）"
    MemoryMode.OFF -> "关闭检索：不注入也不检索，记忆仍会保存"
}
