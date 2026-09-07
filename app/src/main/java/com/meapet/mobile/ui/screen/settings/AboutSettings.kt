package com.meapet.mobile.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.core.AppInfo
import com.meapet.mobile.ui.component.LinkItem
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel

// ═══════════════════════════════════════════════════
//  关于：应用信息、版本更新、隐私政策与数据授权
// ═══════════════════════════════════════════════════

/** 关于卡片中的 Live2D 模型来源链接。 */
private const val LIVE2D_MODEL_SOURCE_URL = "https://www.bilibili.com/video/BV1AoX7BXEaN"

/**
 * 应用信息：名称/简介/版本、致谢、外部链接与技术栈。
 *
 * 原先是主界面「更多」菜单里的独立对话框，现并入关于页——版本、更新、隐私政策
 * 同属一个信息域，分散两处会让用户记两个入口。
 */
@Composable
internal fun AppInfoSection() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val appVersion = remember(context) { AppInfo.readVersion(context) }
    val linkStyle = MaterialTheme.typography.bodySmall

    SectionTitle("应用信息")

    Text("MeaPet —— 梅尔桌宠", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(2.dp))
    Text(
        "一只基于 Live2D 的 AI 梅尔 非常不完善 但是初版花了我 0.14B Tokens",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "版本 $appVersion",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(Modifier.height(10.dp))

    Text(
        "Say my name when a tree susurrates\nOnce and again telling a story lost in time",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(3.dp))
    LinkItem("Live2D 模型来源", LIVE2D_MODEL_SOURCE_URL, uriHandler, linkStyle)
    Spacer(Modifier.height(3.dp))
    LinkItem("GitHub 仓库", AppInfo.gitRepoUrl, uriHandler, linkStyle)
    Spacer(Modifier.height(3.dp))
    LinkItem("交流 QQ 群", AppInfo.qqGroupUrl, uriHandler, linkStyle)

    Spacer(Modifier.height(6.dp))
    Text(
        "技术栈：Live2D Cubism · Jetpack Compose · Ktor · Markwon · Coroutines · GLSurfaceView",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/** 更新：启动自动检查开关 + 手动检查。 */
@Composable
internal fun UpdateSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    val uriHandler = LocalUriHandler.current

    SectionTitle("更新")

    SettingsSwitchRow(
        label = "启动时自动检查更新",
        description = "打开 App 时静默检测新版本，发现更新才会提示；关闭后仅可在此手动检查",
        checked = state.enableAutoUpdateCheck,
        darkTheme = darkTheme,
        onCheckedChange = { viewModel.updateEnableAutoUpdateCheck(it) }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { viewModel.checkForUpdate() },
            enabled = !state.isCheckingUpdate
        ) {
            if (state.isCheckingUpdate) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.isCheckingUpdate) "检测中…" else "立即检查更新")
        }
    }

    // 手动检测结果：有更新时额外给一条发布页链接
    state.updateMessage?.let { message ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        state.updateReleaseUrl?.let { url ->
            Spacer(Modifier.height(2.dp))
            LinkItem("打开更新页面", url, uriHandler, MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** 隐私与数据：查看隐私政策 + 友盟采集授权管理。 */
@Composable
internal fun PrivacySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenPrivacyPolicy: () -> Unit,
    onExitApp: () -> Unit
) {
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

    // 导出日志（拉起系统分享，发给开发者排查问题）
    val exportingLog by viewModel.exportingLog.collectAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !exportingLog) { viewModel.exportLog() },
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
                text = if (exportingLog) "正在导出日志…" else "导出日志",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (exportingLog) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
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
    }

    Spacer(Modifier.height(8.dp))
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
            if (!BuildConfig.UMENG_ENABLED) {
                // 无统计 SDK 构建：无采集、无授权可管，仅提示构建状态
                Text(
                    text = "该构建未包含统计 SDK，不会采集任何数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
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

/** 图标来源署名：MDI 是 Apache 2.0，署名非强制但属良好实践。 */
@Composable
internal fun AttributionSection() {
    SectionTitle("开源图标")
    Text(
        text = "界面图标来自 Material Design Icons (Pictogrammers)，" +
            "依据 Apache License 2.0 使用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_FAINT_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

// ═══════════════════════════════════════════════════
//  通用子组件
// ═══════════════════════════════════════════════════
