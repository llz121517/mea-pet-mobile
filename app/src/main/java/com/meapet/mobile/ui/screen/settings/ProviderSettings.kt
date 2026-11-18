package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  提供商：API 接入与模型参数
// ═══════════════════════════════════════════════════

/** API 配置：端点说明 + API Key / 地址输入。 */
@Composable
internal fun ApiConfigSection(
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
        placeholder = { Text("https://api.deepseek.com/v1") }
    )
}

/** 模型参数：模型名 + 拉取列表 + Temperature / MaxToken 滑杆。 */
@Composable
internal fun ModelParamsSection(
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
        placeholder = { Text("deepseek-flash") }
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

    ParamLabelWithHelp(
        label = "Temperature: ${"%.2f".format(local.temperature)}",
        helpTitle = "Temperature（随机度）",
        helpText = "决定回复的随机程度。数值越低，模型越倾向挑高概率的词，回答更稳定、" +
            "更贴合设定；越高则用词更多样、更有意外感，但也更容易偏题或前后矛盾。\n\n" +
            "桌宠闲聊建议 0.8~1.2；需要它严格听指令时降到 0.3 以下。\n\n" +
            "注意：设为 0 也不保证每次输出完全一致；部分提供商上限为 1.0，" +
            "推理模型会忽略此参数。"
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
