package com.meapet.mobile.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.backup.BackupCategory
import com.meapet.mobile.backup.BackupCollector
import com.meapet.mobile.backup.BackupContentReader
import com.meapet.mobile.backup.BackupContents
import com.meapet.mobile.backup.BackupCounter
import com.meapet.mobile.backup.BackupExporter
import com.meapet.mobile.backup.BackupImporter
import com.meapet.mobile.backup.BackupInspector
import com.meapet.mobile.backup.BackupService
import com.meapet.mobile.backup.ImportCount
import com.meapet.mobile.backup.ImportOutcome
import com.meapet.mobile.backup.ManifestRejectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 「设置 → 数据与备份」页面状态。
 *
 * **密码永远不在这个状态里**：它只作为 [BackupViewModel.runExport] /
 * [BackupViewModel.inspectPackage] 的方法参数存在，用后立即用 `'\u0000'` 覆写；
 * UI 侧也只在 `remember` 中暂存（见 `DataSettings`）。状态里只放页面可渲染的信息。
 *
 * @property busy 导出 / 读包 / 导入进行中（按钮禁用 + 进度指示）
 * @property available 本机**有内容的**类别（决定「有内容 / 本机这一类还没有东西」文案）
 * @property exportSelection 导出勾选（默认全选；Key 由 [includeApiKeys] 单独控制）
 * @property includeApiKeys 是否把可选 API Key 三键写进 settings 条目
 * @property staged 已通过校验的待导入包（只有元信息，不含 zip 句柄与密码）
 * @property stagedFileName 待导入包的显示名（SAF 显示名，取不到时为 null）
 * @property counts 按条数计的类别（对话 / 记忆）包内条数 vs 本机条数，显示在类别行副标题
 * @property importSelection 导入勾选（默认只勾包内实际存在的类别）
 * @property receipt 操作回执（成功 / 失败原因 / 取消；null = 不显示）
 */
data class BackupUiState(
    val busy: Boolean = false,
    val available: Set<BackupCategory> = emptySet(),
    val exportSelection: Set<BackupCategory> = BackupCategory.entries.toSet(),
    val includeApiKeys: Boolean = false,
    val staged: BackupContents? = null,
    val stagedFileName: String? = null,
    val counts: Map<BackupCategory, ImportCount> = emptyMap(),
    val importSelection: Set<BackupCategory> = emptySet(),
    val receipt: String? = null,
)

/**
 * 数据与备份页面 ViewModel：导出 / 读包校验 / 按类别导入的状态机。
 *
 * 密码生命周期（PRD §7）：
 * - 密码只作为方法参数传入，**不进 [BackupUiState]**、不写日志、不落盘；
 * - [runExport] / [inspectPackage] 在 `finally` 里 `password.fill('\u0000')`；
 * - 已打开包的读取器（含 zip 句柄与密码）存私有字段，替换 / 清空 / onCleared 时关闭。
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val container = MeaPetApplication.from(application)

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** 已校验包的读取器：持解密后的 zip 句柄，**不放进 UI 状态**，用完必须 close。 */
    private var reader: BackupContentReader? = null

    // ── 页面生命周期 ─────────────────────────────────

    /**
     * 进入「数据与备份」页：
     * 1. 清扫 `cacheDir/backup` 里上次异常退出（或旧版本）残留的暂存包；
     * 2. 刷新本机各类别可用性（采集器口径，与导出实际内容一致）。
     */
    fun onPageOpened() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { container.backupService.sweepStaleStaging() }
                refreshAvailability()
            }
        }
    }

    /** 在本机有内容的类别标记里刷新 [BackupUiState.available]。 */
    private suspend fun refreshAvailability() {
        runCatching { BackupCollector(container.backupSources).availableCategories() }
            .onSuccess { available -> _state.update { it.copy(available = available) } }
    }

    // ── 导出 ─────────────────────────────────────────

    /**
     * 切换导出的类别。
     *
     * API Key 写在 `settings` 条目里，因此取消勾选「设置」时**同时关闭** [includeApiKeys]：
     * 否则界面上会留着一个勾不上也不生效的 Key 选项，下次再勾「设置」时又会意外带上 Key。
     */
    fun toggleExportCategory(category: BackupCategory) {
        _state.update { current ->
            val next = if (category in current.exportSelection) {
                current.exportSelection - category
            } else {
                current.exportSelection + category
            }
            val keysStillMeaningful = BackupCategory.SETTINGS in next
            current.copy(
                exportSelection = next,
                includeApiKeys = current.includeApiKeys && keysStillMeaningful,
            )
        }
    }

    fun setIncludeApiKeys(enabled: Boolean) {
        _state.update { current ->
            // 「设置」没勾选时 Key 无落地位置：忽略这次勾选（UI 上该项也是置灰的）
            if (enabled && BackupCategory.SETTINGS !in current.exportSelection) return@update current
            current.copy(includeApiKeys = enabled)
        }
    }

    /**
     * 写出加密备份包到 SAF 选中的 [uri]。
     *
     * @param uri CreateDocument 返回的目标位置
     * @param password 密码；本方法负责用后清零（成功 / 失败 / 提前返回都会清零）
     */
    fun runExport(uri: Uri, password: CharArray) {
        val snapshot = _state.value
        if (snapshot.busy) {
            password.fill('\u0000')
            return
        }
        if (snapshot.exportSelection.isEmpty()) {
            password.fill('\u0000')
            _state.update { it.copy(receipt = "请勾选要导出的数据") }
            return
        }
        val selection = snapshot.exportSelection
        val includeApiKeys = snapshot.includeApiKeys
        _state.update { it.copy(busy = true, receipt = null) }
        val job = viewModelScope.launch {
            try {
                val (displayName, size) = withContext(Dispatchers.IO) {
                    val collected = BackupCollector(container.backupSources)
                        .collect(selection, includeApiKeys)
                    // exporter 负责关闭 sink（SAF 输出流）；明文不落盘，文本走内存、壁纸直读源文件
                    val sink = container.backupService.openWriteSink(uri)
                    BackupExporter.export(collected.items, collected.manifest, password, sink)
                    val name = runCatching { container.backupService.displayName(uri) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: BackupExporter.defaultFileName(BackupService.utcCompactNow())
                    // 优先读目标文件的真实大小；取不到时用采集条目的未压缩字节数兜底
                    name to (targetSize(uri) ?: collected.items.sumOf { it.bytes })
                }
                _state.update {
                    it.copy(busy = false, receipt = "已导出 $displayName（${formatSize(size)}）")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, receipt = "导出失败：${exportFailureReason(e)}")
                }
            } finally {
                password.fill('\u0000')
            }
        }
        // ViewModel 先被清理、协程尚未启动就被取消时 finally 不会执行，这里兜底清零
        job.invokeOnCompletion { password.fill('\u0000') }
    }

    /** 用户在系统保存面板点了取消：没有写出任何文件。 */
    fun reportExportCancelled() {
        _state.update { it.copy(busy = false, receipt = CANCELLED_RECEIPT) }
    }

    // ── 读包（校验）───────────────────────────────────

    /**
     * 把 SAF 选中的包暂存到 `cacheDir` 并校验（密码 / manifest / 版本 / 路径）。
     *
     * 成功后 [BackupUiState.staged] 才有值；读取器留在私有字段供 [runImport] 使用。
     * 失败只展示可读原因：密码错误与包损坏共用「密码错误或文件已损坏」，不泄露包内信息。
     *
     * @param password 密码；本方法负责用后清零
     */
    fun inspectPackage(uri: Uri, password: CharArray) {
        if (_state.value.busy) {
            password.fill('\u0000')
            return
        }
        // 开始读新包：旧包内容立即作废（读取器在 IO 里关闭）
        _state.update {
            it.copy(
                busy = true,
                receipt = null,
                staged = null,
                stagedFileName = null,
                counts = emptyMap(),
                importSelection = emptySet(),
            )
        }
        val job = viewModelScope.launch {
            try {
                val (opened, fileName, counts) = withContext(Dispatchers.IO) {
                    closeReader()
                    val stageFile = container.backupService.stageForRead(uri)
                    val newReader = BackupInspector.open(stageFile, password)
                    // 读包成功后立刻算条数对比（对话 / 记忆）：确认弹窗要用它，
                    // 提前算好也避免确认那一刻再解一次包。
                    val compared = BackupCounter.compare(newReader, container.backupTarget)
                    Triple(newReader, runCatching { container.backupService.displayName(uri) }.getOrNull(), compared)
                }
                reader = opened
                val contents = opened.contents
                _state.update {
                    it.copy(
                        busy = false,
                        staged = contents,
                        stagedFileName = fileName,
                        counts = counts,
                        // 默认勾选包内全部类别（用户可再取消）
                        importSelection = contents.categories,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ManifestRejectException) {
                // 密码错误 / 结构不完整 / 版本不支持：直接展示校验器给的可读文案
                Log.w(TAG, "读包被拒：${e.message}", e)
                _state.update {
                    it.copy(busy = false, receipt = e.message ?: "无法读取这个备份包")
                }
            } catch (e: Exception) {
                Log.w(TAG, "读包失败", e)
                _state.update {
                    it.copy(busy = false, receipt = "读取失败，请确认文件完整")
                }
            } finally {
                password.fill('\u0000')
            }
        }
        job.invokeOnCompletion { password.fill('\u0000') }
    }

    fun toggleImportCategory(category: BackupCategory) {
        _state.update { current ->
            val next = if (category in current.importSelection) {
                current.importSelection - category
            } else {
                current.importSelection + category
            }
            current.copy(importSelection = next)
        }
    }

    /** 放弃待导入包：关闭读取器、删除暂存文件并清掉清单（返回上一步 / 重新选包）。 */
    fun clearStaged() {
        closeReader()
        _state.update {
            it.copy(
                staged = null,
                stagedFileName = null,
                counts = emptyMap(),
                importSelection = emptySet(),
            )
        }
    }

    // ── 导入 ─────────────────────────────────────────

    /**
     * 把已校验包里勾选的类别整体覆盖到本机。
     *
     * 落地后刷新内存会话窗口（否则 UI / 模型上下文仍停在导入前的消息上）；
     * 设置类别成功时重建 HTTP 客户端，让新的 api_url / key / model 立即生效。
     * 这两个刷新都是「尽力而为」：失败只可能让页面显示滞后，不能反过来把导入报告成失败。
     */
    fun runImport() {
        if (_state.value.busy) return
        val activeReader = reader
        if (activeReader == null) {
            _state.update { it.copy(receipt = "请先选包并读取") }
            return
        }
        val selection = _state.value.importSelection
        if (selection.isEmpty()) {
            _state.update { it.copy(receipt = "请勾选要恢复的类别") }
            return
        }
        _state.update { it.copy(busy = true, receipt = null) }
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val result = BackupImporter.import(activeReader, selection, container.backupTarget)
                    // 1) ChatService 侧缓存整体重载：内存窗口 + 压缩状态 + token 估算。
                    //    导入直接写库、绕过了 ChatService，不重载会让下一轮请求仍用旧历史/旧摘要。
                    runCatching { container.chatService.reloadAfterExternalImport() }
                        .onFailure { Log.w(TAG, "ChatService 重载失败（导入已生效）", it) }
                    // 2) 设置整类导入成功：URL / Key / Model 可能已变，重建客户端（容量缓存随之刷新）
                    if (BackupCategory.SETTINGS in result.succeeded) {
                        runCatching { container.reloadClient() }
                    }
                    // 3) 广播「数据已整批替换」：主页消息列表等各自持有的副本需要自行 reload
                    //    （设置页返回主页不会走 ON_RESUME，光靠生命周期钩子刷不到）
                    container.notifyDataReplaced()
                    // 本机数据已变，刷新可用性标记，避免再导出时显示旧状态
                    refreshAvailability()
                    result
                }
                _state.update { it.copy(busy = false, receipt = importReceipt(outcome)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, receipt = "导入失败：${importFailureReason(e)}")
                }
            }
        }
    }

    /** 用户在系统文件选择面板点了取消：本机数据没有任何改动。 */
    fun reportImportCancelled() {
        _state.update { it.copy(busy = false, receipt = CANCELLED_RECEIPT) }
    }

    // ── 回执 / 工具 ──────────────────────────────────

    fun dismissReceipt() {
        _state.update { it.copy(receipt = null) }
    }

    override fun onCleared() {
        closeReader()
        super.onCleared()
    }

    /** 关闭并丢弃当前读取器（zip 句柄 + 其内部持有的密码副本）。 */
    private fun closeReader() {
        val current = reader ?: return
        reader = null
        runCatching { current.close() }
    }

    /** 导入回执：按类别列出成功 / 失败（Snackbar 一行显示，保持简短）。 */
    private fun importReceipt(outcome: ImportOutcome): String {
        val ordered = BackupCategory.entries
        val succeeded = ordered.filter { it in outcome.succeeded }
        val failed = ordered.filter { it in outcome.failed }
        return buildString {
            when {
                succeeded.isNotEmpty() -> {
                    append("已恢复 ")
                    append(succeeded.joinToString("、") { it.label })
                }
                failed.isNotEmpty() -> append("导入失败")
                else -> append("没有需要恢复的类别")
            }
            if (failed.isNotEmpty()) {
                append("；失败：")
                append(
                    failed.joinToString("、") { category ->
                        "${category.label}（${outcome.failed[category]}）"
                    }
                )
            }
        }
    }

    private fun exportFailureReason(error: Throwable): String = when (error) {
        // SAF 写入失败最常见原因是目标位置空间不足 / 存储不可用
        is IOException -> "写入失败，请检查可用空间"
        is ManifestRejectException -> error.message ?: "备份包校验失败"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"
    }

    private fun importFailureReason(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"

    /** 读目标 SAF 文件大小；取不到（或为负数）返回 null。 */
    private fun targetSize(uri: Uri): Long? = runCatching {
        getApplication<Application>().contentResolver
            .openFileDescriptor(uri, "r")
            ?.use { it.statSize }
            ?.takeIf { it >= 0L }
    }.getOrNull()

    private fun formatSize(bytes: Long): String =
        android.text.format.Formatter.formatShortFileSize(
            getApplication<Application>(),
            bytes.coerceAtLeast(0L)
        )

    private companion object {
        private const val TAG = "BackupViewModel"

        /** PRD §4.3 / LLD §5：系统面板取消的统一回执。 */
        const val CANCELLED_RECEIPT = "已取消，未改动任何数据"
    }
}
