package com.meapet.mobile.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.R
import com.meapet.mobile.backup.BackupCategory
import com.meapet.mobile.backup.BackupExporter
import com.meapet.mobile.backup.BackupService
import com.meapet.mobile.viewmodel.BackupUiState
import com.meapet.mobile.viewmodel.BackupViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ═══════════════════════════════════════════════════
//  数据与备份：导出（加密 zip）、导入（按类别覆盖恢复）
// ═══════════════════════════════════════════════════

/**
 * 「数据与备份」页内容。
 *
 * 由 [SettingsScreen] 的滚动 Column 承载，外包一层 `SettingsCard`，因此本函数
 * **不套 Scaffold**、不自己滚动；「导出」「导入」两个可折叠块共用顶部的回执区。
 *
 * 口令生命周期：只存在本函数的 `remember` 里（不 saveable、不进 ViewModel 状态）；
 * 导出时跨 SAF 往返用的 [CharArray] 在结果回调里立刻清零，ViewModel 内部也会清零。
 */
@Composable
internal fun DataSettings(viewModel: BackupViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    // 本页没有 SettingsUiState，按当前 MaterialTheme 的实际明暗给开关配色
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 进页：清扫残留暂存包 + 刷新本机各类别可用性
    LaunchedEffect(Unit) { viewModel.onPageOpened() }

    Text(
        text = "把本机的设置、对话、记忆等数据打包成口令加密的 zip，换机或重装后按类别恢复。" +
            "备份只写到你自己选择的位置，不经过网络。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )

    if (state.busy) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "处理中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 共享回执区：导出 / 导入的成功、失败、取消都落在这里
    ReceiptBlock(receipt = state.receipt, onDismiss = viewModel::dismissReceipt)

    ExportBlock(state = state, viewModel = viewModel, darkTheme = darkTheme)

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ALPHA_DIVIDER)
    )

    ImportBlock(state = state, viewModel = viewModel)
}

// ── 共享：回执 ─────────────────────────────────────

@Composable
private fun ReceiptBlock(receipt: String?, onDismiss: () -> Unit) {
    if (receipt == null) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = receipt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp)
            )
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

// ── 导出 ───────────────────────────────────────────

@Composable
private fun ExportBlock(
    state: BackupUiState,
    viewModel: BackupViewModel,
    darkTheme: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var showNotice by remember { mutableStateOf(false) }

    /** 跨 SAF 往返暂存的口令；回调里交给 ViewModel 后立即清零。 */
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }

    val passwordMismatch = passwordConfirm.isNotEmpty() && password != passwordConfirm
    val passwordTooShort = password.isNotEmpty() && password.length < 8
    val canExport = !state.busy &&
        state.exportSelection.isNotEmpty() &&
        password.isNotEmpty() &&
        password == passwordConfirm

    // CreateDocument：系统保存面板，不需要存储权限
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val carried = pendingPassword
        pendingPassword = null
        // 无论写出还是取消，口令都不再留在界面里
        password = ""
        passwordConfirm = ""
        if (uri == null || carried == null) {
            carried?.fill('\u0000')
            viewModel.reportExportCancelled()
        } else {
            // ViewModel 内负责用后清零
            viewModel.runExport(uri, carried)
        }
    }

    ExpandableHeader(
        title = "导出",
        subtitle = "把勾选的数据打包成加密 zip",
        expanded = expanded,
        enabled = !state.busy,
        onToggle = { expanded = !expanded }
    )

    SettingsExpandableContent(visible = expanded) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "选择要写进备份包的数据；未勾选的类别不会出现在包里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.padding(bottom = 4.dp)
            )

            BackupCategory.entries.forEach { category ->
                val available = category in state.available
                CategoryCheckRow(
                    label = category.label,
                    status = if (available) "有内容" else "本机这一类还没有东西",
                    statusColor = if (available) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = ALPHA_MUTED_TEXT)
                    },
                    checked = category in state.exportSelection,
                    enabled = !state.busy,
                    onCheckedChange = { viewModel.toggleExportCategory(category) }
                )
            }

            Spacer(Modifier.height(4.dp))

            // API Key 默认不进包；勾选后随设置条目一起写入
            SettingsSwitchRow(
                label = "包含 API Key",
                description = "默认不包含。勾选后聊天 / 联网服务的 Key 会写进设置条目。",
                checked = state.includeApiKeys,
                darkTheme = darkTheme,
                onCheckedChange = { viewModel.setIncludeApiKeys(it) },
                enabled = !state.busy
            )

            Spacer(Modifier.height(8.dp))
            SectionTitle("备份口令")

            Text(
                text = "口令用于加密备份包，至少 8 位；忘记口令将无法再打开这个包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = "口令",
                enabled = !state.busy,
                isError = false,
                supportingText = null
            )

            Spacer(Modifier.height(8.dp))

            PasswordField(
                value = passwordConfirm,
                onValueChange = { passwordConfirm = it },
                label = "再次输入口令",
                enabled = !state.busy,
                isError = passwordMismatch,
                supportingText = if (passwordMismatch) "两次输入的口令不一致" else null
            )

            if (passwordTooShort) {
                Text(
                    text = "口令短于 8 位，更容易被猜到（仍可继续）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showNotice = true },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出…")
            }
            Text(
                text = "导出前会先提醒包里有什么，确认后才打开系统保存面板。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_FAINT_TEXT),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    if (showNotice) {
        ExportNoticeDialog(
            selection = state.exportSelection,
            includeApiKeys = state.includeApiKeys,
            onDismiss = { showNotice = false },
            onConfirm = {
                showNotice = false
                // 口令转为 CharArray 暂存，跨 SAF 往返后交给 ViewModel（其内部用后清零）
                pendingPassword = password.toCharArray()
                createDocument.launch(
                    BackupExporter.defaultFileName(BackupService.utcCompactNow())
                )
            }
        )
    }
}

/** 「包里有什么」告知弹窗：列出勾选类别与「口令遗忘无法找回」。 */
@Composable
private fun ExportNoticeDialog(
    selection: Set<BackupCategory>,
    includeApiKeys: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("包里有什么") },
        text = {
            Column {
                Text("将把以下数据加密写进备份包：")
                Spacer(Modifier.height(8.dp))
                val chosen = BackupCategory.entries.filter { it in selection }
                if (chosen.isEmpty()) {
                    Text("（没有勾选任何类别）")
                } else {
                    chosen.forEach { Text("· ${it.label}") }
                }
                if (includeApiKeys) {
                    Spacer(Modifier.height(8.dp))
                    Text("· API Key")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "备份包用你设置的口令加密。口令遗忘无法找回，包将无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "备份文件会写到你在下一步选择的位置，应用不保留副本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("继续导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 导入 ───────────────────────────────────────────

@Composable
private fun ImportBlock(
    state: BackupUiState,
    viewModel: BackupViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    val staged = state.staged
    val context = LocalContext.current

    // 读取成功后包已解密打开，口令不再需要留在界面里
    LaunchedEffect(staged) {
        if (staged != null) password = ""
    }

    // 文件选择面板：mime 放宽到 */*，避免不同文件管理器给的 mime 不一致
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            viewModel.reportImportCancelled()
        } else {
            // 换了新包：旧包（可能已解密打开）作废并关闭读取器
            viewModel.clearStaged()
            pickedUri = uri
            password = ""
        }
    }

    ExpandableHeader(
        title = "导入",
        subtitle = "从备份包按类别整体恢复",
        expanded = expanded,
        enabled = !state.busy,
        onToggle = { expanded = !expanded }
    )

    SettingsExpandableContent(visible = expanded) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "先选择备份包并输入口令读取，确认包里有什么之后再恢复。" +
                    "恢复会按类别整体覆盖本机现有数据，无法撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedButton(
                onClick = {
                    openDocument.launch(
                        arrayOf("application/zip", "application/octet-stream", "*/*")
                    )
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (pickedUri == null) "选择备份包…" else "重新选择备份包…")
            }

            if (pickedUri != null && staged == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已选择备份包。输入口令后点「读取备份」校验包内容（口令错误不会透露包内信息）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "备份口令",
                    enabled = !state.busy,
                    isError = false,
                    supportingText = null
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val uri = pickedUri
                        if (uri != null) {
                            // 转为 CharArray 交给 ViewModel；其内部用后清零
                            viewModel.inspectPackage(uri, password.toCharArray())
                        }
                    },
                    enabled = !state.busy && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("读取备份")
                }
            }

            if (staged != null) {
                Spacer(Modifier.height(12.dp))
                SectionTitle("备份包内容")

                Text(
                    text = "创建时间：${formatCreatedAt(staged.manifest.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.stagedFileName?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(
                        text = "文件：$name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "只显示包里实际存在的类别；勾选要恢复到本机的类别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = ALPHA_FAINT_TEXT),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )

                BackupCategory.entries
                    .filter { it in staged.categories }
                    .forEach { category ->
                        val entry = staged.entries[category]
                        CategoryCheckRow(
                            label = category.label,
                            status = entry?.let {
                                "共 " + android.text.format.Formatter.formatShortFileSize(
                                    context, it.bytes
                                )
                            } ?: "已包含",
                            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = ALPHA_MUTED_TEXT),
                            checked = category in state.importSelection,
                            enabled = !state.busy,
                            onCheckedChange = { viewModel.toggleImportCategory(category) }
                        )
                    }

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showConfirm = true },
                        enabled = !state.busy && state.importSelection.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导入所选类别")
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearStaged() },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }

    if (showConfirm && staged != null) {
        ImportConfirmDialog(
            selection = state.importSelection,
            available = staged.categories,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                viewModel.runImport()
            }
        )
    }
}

/** 导入前的强制确认弹窗：逐项点名「将覆盖本机的 X」。 */
@Composable
private fun ImportConfirmDialog(
    selection: Set<BackupCategory>,
    available: Set<BackupCategory>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val chosen = BackupCategory.entries.filter { it in selection && it in available }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认恢复数据") },
        text = {
            Column {
                Text("以下类别的本机现有数据将被备份包中的内容整体覆盖，且无法撤销：")
                Spacer(Modifier.height(8.dp))
                if (chosen.isEmpty()) {
                    Text("（没有勾选任何类别）")
                } else {
                    chosen.forEach { Text("· 将覆盖本机的${it.label}") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "恢复后部分设置（如外观）可能需要重新进入设置页才完全生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 公共小件 ───────────────────────────────────────

/** 可折叠块标题：标题 + 说明 + 右侧箭头（展开时旋转 90°）。 */
@Composable
private fun ExpandableHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_FAINT_TEXT)
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = ALPHA_MUTED_TEXT),
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 90f else 0f)
        )
    }
}

/** 类别勾选行：复选框 + 类别名 + 「有内容 / 本机这一类还没有东西」（或包内体积）。 */
@Composable
private fun CategoryCheckRow(
    label: String,
    status: String,
    statusColor: Color,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            enabled = enabled
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
        }
    }
}

/** 口令输入框（掩码显示；不提供明文切换，减少口令在屏幕上停留的机会）。 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isError: Boolean,
    supportingText: String?
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = if (supportingText != null) {
            { Text(supportingText) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** manifest.createdAt（ISO-8601 UTC）→ 本机时区 `yyyy-MM-dd HH:mm`；解析失败原样展示。 */
private fun formatCreatedAt(iso: String): String {
    if (iso.isBlank()) return "未知"
    return runCatching {
        Instant.parse(iso)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(iso)
}
