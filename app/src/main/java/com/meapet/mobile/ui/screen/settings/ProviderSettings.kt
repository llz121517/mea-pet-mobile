package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import com.meapet.mobile.R
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

    OutlinedTextField(
        value = local.contextWindow,
        onValueChange = { v -> local.contextWindow = v.filter { it.isDigit() }.take(8) },
        label = {
            ParamLabelWithHelp(
                label = "上下文窗口",
                helpTitle = "上下文窗口",
                helpText = "模型一次请求可容纳的输入与输出总量。" +
                    "留空时自动探测；无法探测时使用兜底值。手动填写会优先于探测结果。\n\n" +
                    "应用按容量预留余量，实际使用约为其 85%。本地推理服务请填写实际加载的窗口，" +
                    "不要直接照抄模型标称上限。点击重新检测会清除手动值并再次探测。"
            )
        },
        placeholder = { Text("单位 Token ，留空 = 自动") },
        modifier = Modifier
            .fillMaxWidth()
            .saveOnFocusChange { viewModel.saveContextWindow(local.contextWindow) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            if (local.contextWindow.isNotEmpty()) {
                IconButton(onClick = {
                    local.contextWindow = ""
                    viewModel.saveContextWindow("")
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "重置为自动")
                }
            }
        },
        supportingText = {
            val src = when (state.capacitySource) {
                com.meapet.mobile.client.CapacitySource.MANUAL -> "手动"
                com.meapet.mobile.client.CapacitySource.PROBED -> "探测"
                com.meapet.mobile.client.CapacitySource.FALLBACK -> "兜底"
            }
            Text("当前生效 ${state.effectiveCapacity}（$src）；实际使用约为其 85%。本地推理服务请按实际加载的窗口填写")
        }
    )

    Spacer(Modifier.height(4.dp))

    // 重新检测：清除手动值并强制重新探测（探测失败会回退兜底，不报错）
    TextButton(
        onClick = {
            local.contextWindow = ""
            viewModel.redetectContextWindow()
        },
        modifier = Modifier.saveOnFocusChange { }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_refresh),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text("重新检测", style = MaterialTheme.typography.labelMedium)
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

    ParamLabelWithHelp(
        label = "最大 Token: ${local.maxTokens.toInt()}",
        helpTitle = "最大输出 Token",
        helpText = "控制单次回复允许的最大输出长度，不是模型能回看的对话长度。" +
            "Token 是模型计量文本的单位，不等同于字数。\n\n" +
            "实际发送的上限还会被上下文容量的约 25% 限制，避免输出挤占输入空间。" +
            "调大允许更长回复，也可能增加等待时间和费用；不保证模型一定写满。"
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


@Composable
internal fun WebSearchSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    local: SettingsLocalState,
    darkTheme: Boolean
) {
    SectionTitle("联网搜索")
    SettingsSwitchRow(
        label = "启用联网搜索",
        description = "允许模型在对话中搜索网页、读取网页正文",
        checked = state.webSearchEnabled,
        darkTheme = darkTheme,
        onCheckedChange = viewModel::updateWebSearchEnabled
    )

    SettingsExpandableContent(visible = state.webSearchEnabled) {
        Column {
            ParamLabelWithHelp(
                label = "服务商",
                helpTitle = "联网搜索与服务商",
                helpText = "自动：优先匹配聊天接口的服务商，否则依次选择有 Key 的 DeepSeek、支持匿名的 AnySearch。" +
                    "自动模式调用失败时会尝试下一家；手动指定时不切换。参数错误和用户取消不会触发切换。\n\n" +
                    "DeepSeek 优先使用独立 Key；留空时，会尝试复用「API 配置」中的 Key。" +
                    "AnySearch 留空使用匿名档，有 Key 时使用该 Key。\n\n" +
                    "查询词和网页地址会发送给实际使用的服务商，自动切换也可能将同一请求发送给下一家。" +
                    "服务可能有额度、限速或收费。联网工具与记忆设置相互独立，" +
                    "只需聊天端点支持 function calling；设置修改从下一轮对话生效。"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.meapet.mobile.config.WebSearchProviderId.entries.forEach { provider ->
                    FilterChipLike(
                        label = when (provider) {
                            com.meapet.mobile.config.WebSearchProviderId.AUTO -> "自动"
                            com.meapet.mobile.config.WebSearchProviderId.DEEPSEEK -> "DeepSeek"
                            com.meapet.mobile.config.WebSearchProviderId.ANYSEARCH -> "AnySearch"
                        },
                        selected = state.webSearchProvider == provider,
                        enabled = true,
                        onClick = { viewModel.updateWebSearchProvider(provider) }
                    )
                }
            }

            if (state.webSearchProvider != com.meapet.mobile.config.WebSearchProviderId.ANYSEARCH) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = local.webSearchDeepSeekKey,
                    onValueChange = { local.webSearchDeepSeekKey = it },
                    label = { Text("DeepSeek API Key（可选）") },
                    placeholder = { Text("留空复用「API 配置」中的 Key") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .saveOnFocusChange { viewModel.saveWebSearchDeepSeekKey(local.webSearchDeepSeekKey) },
                    singleLine = true,
                    visualTransformation = if (local.webSearchDeepSeekKeyVisible)
                        VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = {
                            local.webSearchDeepSeekKeyVisible = !local.webSearchDeepSeekKeyVisible
                        }) {
                            Text(if (local.webSearchDeepSeekKeyVisible) "隐藏" else "显示")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }

            if (state.webSearchProvider != com.meapet.mobile.config.WebSearchProviderId.DEEPSEEK) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = local.webSearchAnySearchKey,
                    onValueChange = { local.webSearchAnySearchKey = it },
                    label = { Text("AnySearch API Key（可选）") },
                    placeholder = { Text("留空使用匿名档") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .saveOnFocusChange { viewModel.saveWebSearchAnySearchKey(local.webSearchAnySearchKey) },
                    singleLine = true,
                    visualTransformation = if (local.webSearchAnySearchKeyVisible)
                        VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = {
                            local.webSearchAnySearchKeyVisible = !local.webSearchAnySearchKeyVisible
                        }) {
                            Text(if (local.webSearchAnySearchKeyVisible) "隐藏" else "显示")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "联网与记忆相互独立；联网会发送查询或网址，可能产生费用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
