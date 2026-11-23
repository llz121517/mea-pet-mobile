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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.SnackbarHostState
import com.meapet.mobile.R
import com.meapet.mobile.backup.BackupCategory
import com.meapet.mobile.backup.BackupCounter
import com.meapet.mobile.backup.BackupExporter
import com.meapet.mobile.backup.BackupService
import com.meapet.mobile.backup.ImportCount
import com.meapet.mobile.ui.component.showDismissableSnackbar
import com.meapet.mobile.viewmodel.BackupUiState
import com.meapet.mobile.viewmodel.BackupViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ═══════════════════════════════════════════════════
//  数据与备份：导出（加密 zip）、导入（按类别覆盖恢复）
// ═══════════════════════════════════════════════════

/** 弱密码提示阈值：短于该长度只提示不拦截（用户自担风险，见 PRD §2 / §8）。 */
private const val MIN_PASSWORD_LENGTH = 6

/**
 * 「数据与备份」页内容。
 *
 * 由 [SettingsScreen] 的滚动 Column 承载，外包一层 `SettingsCard`，因此本函数
 * **不套 Scaffold**、不自己滚动；导出 / 导入的回执经 [snackbarHostState] 以 Snackbar 呈现。
 *
 * 密码生命周期：只存在本函数的 `remember` 里（不 saveable、不进 ViewModel 状态）；
 * 导出时跨 SAF 往返用的 [CharArray] 在结果回调里立刻清零，ViewModel 内部也会清零。
 */
@Composable
internal fun DataSettings(
    snackbarHostState: SnackbarHostState,
    viewModel: BackupViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    // 进页：清扫残留暂存包 + 刷新本机各类别可用性
    LaunchedEffect(Unit) { viewModel.onPageOpened() }

    // 回执统一走 Snackbar：不占用页面纵向空间，也不跟折叠块抢位置。
    // 用带 ✕ 的可关闭样式：回执看完即关，不用等它自己消失。
    // showSnackbar 是挂起调用，必须等它结束（自动消失 / 点 ✕）再清回执——先清会让
    // LaunchedEffect 以 key 变化重启并取消正在显示的 Snackbar。
    LaunchedEffect(state.receipt) {
        val message = state.receipt ?: return@LaunchedEffect
        snackbarHostState.showDismissableSnackbar(message)
        viewModel.dismissReceipt()
    }

    ExportBlock(state = state, viewModel = viewModel)

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ALPHA_DIVIDER)
    )

    ImportBlock(state = state, viewModel = viewModel)
}

// ── 导出 ───────────────────────────────────────────

@Composable
private fun ExportBlock(
    state: BackupUiState,
    viewModel: BackupViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var showNotice by remember { mutableStateOf(false) }

    /** 跨 SAF 往返暂存的密码；回调里交给 ViewModel 后立即清零。 */
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }

    val passwordMismatch = passwordConfirm.isNotEmpty() && password != passwordConfirm
    val passwordTooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
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
        // 无论写出还是取消，密码都不再留在界面里
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
        subtitle = "把选择的数据打包成加密 zip",
        expanded = expanded,
        enabled = !state.busy,
        onToggle = { expanded = !expanded }
    )

    SettingsExpandableContent(visible = expanded) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "选择要写入备份包的数据；未勾选的类别不会进入包内。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // API Key 存放在 settings 条目内，所以紧贴「设置」行展示（见下方 forEach 内的分支）；
            // 「设置」未勾选时它没有落地位置 → 置灰并说明原因，避免误以为单独勾 Key 就会进包。
            val settingsExported = BackupCategory.SETTINGS in state.exportSelection

            BackupCategory.entries.forEach { category ->
                val available = category in state.available
                CategoryCheckRow(
                    label = category.label,
                    status = if (available) "有内容" else "本机这类还没有东西",
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
                // 「设置」的可选内容：API Key（与类别同样的勾选条目）
                if (category == BackupCategory.SETTINGS) {
                    CategoryCheckRow(
                        label = "API Key",
                        status = when {
                            !settingsExported -> "需先勾选「设置」"
                            state.includeApiKeys -> "随设置写入"
                            else -> "默认不包含"
                        },
                        statusColor = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = ALPHA_MUTED_TEXT),
                        checked = state.includeApiKeys,
                        enabled = !state.busy && settingsExported,
                        onCheckedChange = { viewModel.setIncludeApiKeys(!state.includeApiKeys) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("备份密码")

            Text(
                text = "密码用于加密备份包；忘记密码将无法再打开这个包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = ALPHA_MUTED_TEXT),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                enabled = !state.busy,
                isError = false,
                supportingText = null
            )

            Spacer(Modifier.height(8.dp))

            PasswordField(
                value = passwordConfirm,
                onValueChange = { passwordConfirm = it },
                label = "再次输入密码",
                enabled = !state.busy,
                isError = passwordMismatch,
                supportingText = if (passwordMismatch) "两次输入的密码不一致" else null
            )

            if (passwordTooShort) {
                Text(
                    text = "密码短于 $MIN_PASSWORD_LENGTH 位，更容易被猜到。",
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
        }
    }

    if (showNotice) {
        ExportNoticeDialog(
            selection = state.exportSelection,
            includeApiKeys = state.includeApiKeys,
            onDismiss = { showNotice = false },
            onConfirm = {
                showNotice = false
                // 密码转为 CharArray 暂存，跨 SAF 往返后交给 ViewModel（其内部用后清零）
                pendingPassword = password.toCharArray()
                createDocument.launch(
                    BackupExporter.defaultFileName(BackupService.utcCompactNow())
                )
            }
        )
    }
}

/** 「确认导出数据」弹窗：列出勾选类别与「密码遗忘无法找回」。 */
@Composable
private fun ExportNoticeDialog(
    selection: Set<BackupCategory>,
    includeApiKeys: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导出数据") },
        text = {
            Column {
                Text("将把以下数据加密写入备份包：")
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
                    text = "密码遗忘无法找回，备份包将无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "备份文件写到你在下一步选择的位置，应用不保留副本。",
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

    // 读取成功后包已解密打开，密码不再需要留在界面里
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
                text = "选择备份包并输入密码读取，确认内容后再恢复；" +
                    "恢复会按类别整体覆盖本机数据，无法撤销。",
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
                    text = "已选择备份包。输入密码后点「读取备份」校验内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
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
                    text = "只显示包内实际存在的类别；勾选要恢复的类别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = ALPHA_FAINT_TEXT),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )

                BackupCategory.entries
                    .filter { it in staged.categories }
                    .forEach { category ->
                        val entry = staged.entries[category]
                        val count = state.counts[category]
                        CategoryCheckRow(
                            label = category.label,
                            // 统一显示包内大小；对话 / 记忆再补「本机 → 包内」条数
                            status = buildString {
                                append(
                                    entry?.let {
                                        "共 " + android.text.format.Formatter
                                            .formatShortFileSize(context, it.bytes)
                                    } ?: "已包含"
                                )
                                if (count != null) {
                                    append(" · ${count.local} → ${count.inPackage} 条")
                                }
                            },
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
            counts = state.counts,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                viewModel.runImport()
            }
        )
    }
}

/** 导入前的强制确认弹窗：逐项点名「将覆盖本机的 X」，对话 / 记忆另给条数对比。 */
@Composable
private fun ImportConfirmDialog(
    selection: Set<BackupCategory>,
    available: Set<BackupCategory>,
    counts: Map<BackupCategory, ImportCount>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val chosen = BackupCategory.entries.filter { it in selection && it in available }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认恢复数据") },
        text = {
            Column {
                Text("以下类别的本机数据将被备份包内容整体覆盖，无法撤销：")
                Spacer(Modifier.height(8.dp))
                if (chosen.isEmpty()) {
                    Text("（没有勾选任何类别）")
                } else {
                    chosen.forEach { Text("· 将覆盖本机的${it.label}") }
                }
                // 条数对比：对话 / 记忆按条数计，明确写出「本机 → 包内」以免误覆盖
                val compared = BackupCounter.countedCategories.mapNotNull { category ->
                    if (category !in chosen) return@mapNotNull null
                    val count = counts[category] ?: return@mapNotNull null
                    category to count
                }
                if (compared.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "条数对比（本机 → 包内）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    compared.forEach { (category, count) ->
                        Text(
                            text = "· ${category.label}：${count.local} → ${count.inPackage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "部分设置（如外观）可能需重新进入设置页才生效。",
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

/** 密码输入框（掩码显示；不提供明文切换，减少密码在屏幕上停留的机会）。 */
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
