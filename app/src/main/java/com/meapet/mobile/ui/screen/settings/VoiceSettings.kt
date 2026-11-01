package com.meapet.mobile.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  语音：TTS 开关、语速与模型
// ═══════════════════════════════════════════════════

/** 语音：模型下载管理 + 主/悬浮窗开关 + 默认语音 + 语速。 */
@Composable
internal fun TtsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    val modelReady = state.ttsModelState is com.meapet.mobile.tts.model.TtsModelState.Ready

    // ── 语音模型管理 ──
    TtsModelCard(state, viewModel)

    Spacer(Modifier.height(8.dp))
    SectionTitle("发声")

    // ── 发声开关（模型未就绪时置灰）──
    SettingsSwitchRow(
        label = "主界面语音",
        description = if (modelReady) "对话回复在主界面朗读" else "需先下载语音模型",
        checked = state.ttsMainEnabled && modelReady,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateTtsMainEnabled(it) },
        enabled = modelReady
    )
    SettingsSwitchRow(
        label = "悬浮窗语音",
        description = if (modelReady) "悬浮窗回复朗读" else "需先下载语音模型",
        checked = state.ttsOverlayEnabled && modelReady,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateTtsOverlayEnabled(it) },
        enabled = modelReady
    )

    // ── 语速 ──
    // 内部存的是 length_scale（<1 快、>1 慢）；滑杆显示/拖动的是语速倍率 speed = 1/length_scale
    // （0.5=半速、1.0=原速、2.0=双倍速），方向符合直觉。
    // 用本地 remember 状态拖动（顺滑，不随 DataStore 流回环），松手才落盘。
    val lengthScale = state.ttsLengthScale.toFloat().coerceIn(0.5f, 2.0f)
    var speed by remember {
        mutableStateOf((1f / lengthScale).coerceIn(0.5f, 2.0f))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "语速: ${"%.2f".format(speed)}x",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (modelReady) 1f else 0.5f)
    )
    Slider(
        value = speed,
        onValueChange = { speed = it },
        onValueChangeFinished = {
            // speed（0.5..2.0）→ length_scale = 1/speed；取整到 0.01，避免 1/0.6=1.6667 这类
            // 非 0.1 对齐值，保证下次进页面显示不回跳。
            val lengthScale = kotlin.math.round((1f / speed) * 100) / 100.0
            viewModel.updateTtsLengthScale(lengthScale)
        },
        valueRange = TTS_SPEED_RANGE,
        steps = TTS_SPEED_STEPS,
        enabled = modelReady,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )

    // Debug 专用：任意文本试听，方便真机验证数字/日期/日语/中英混排（release 不显示）
    if (com.meapet.mobile.BuildConfig.DEBUG) {
        Spacer(Modifier.height(8.dp))
        var previewText by remember { mutableStateOf("你好喵，今天天气真好。我今年18岁，电话是13800138000。") }
        var showDialog by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showDialog = true },
            enabled = modelReady,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("试听文本（Debug）") }
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("输入试听文本") },
                text = {
                    OutlinedTextField(
                        value = previewText,
                        onValueChange = { previewText = it },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        viewModel.previewTts(previewText)
                    }) { Text("朗读") }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) { Text("取消") }
                },
            )
        }
    }
}

/** 语音模型下载状态卡：状态显示 + 下载/导入/进度/删除。 */
@Composable
private fun TtsModelCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    // 从本地 zip 资源包手动导入（绕过 GitHub 下载）
    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importTtsModelZip(it) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when (val st = state.ttsModelState) {
                is com.meapet.mobile.tts.model.TtsModelState.Ready -> {
                    Text("语音模型已就绪", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "模型已下载，语音功能可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.deleteTtsModel() }) {
                        Text("删除模型")
                    }
                }
                is com.meapet.mobile.tts.model.TtsModelState.Downloading -> {
                    Text("正在下载语音模型…", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        st.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressLike(progress = st.progress)
                }
                is com.meapet.mobile.tts.model.TtsModelState.Importing -> {
                    Text("正在导入语音模型…", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "正在解压资源包，请稍候",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is com.meapet.mobile.tts.model.TtsModelState.Error -> {
                    Text("下载失败", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        st.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.downloadTtsModel() }) { Text("重试") }
                        OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text("从本地导入")
                        }
                    }
                }
                is com.meapet.mobile.tts.model.TtsModelState.NotDownloaded -> {
                    Text("语音模型未下载", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.ttsModelUrlConfigured)
                            "约 84MB，下载后开放语音功能；网络受限可从本地 zip 导入"
                        else
                            "未配置模型下载地址，可从本地 zip 导入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.downloadTtsModel() },
                            enabled = state.ttsModelUrlConfigured
                        ) { Text("下载模型") }
                        OutlinedButton(
                            onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) { Text("从本地导入") }
                    }
                }
            }
        }
    }
}
