package com.meapet.mobile.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.exception.ApiException
import com.meapet.mobile.client.model.ApiResponse
import com.meapet.mobile.core.AppInfo
import com.meapet.mobile.core.PrivacyConsentManager
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import com.meapet.mobile.update.UpdateCheckResult
import com.meapet.mobile.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置界面 ViewModel。
 *
 * @property apiKey API Key
 * @property apiUrl 完整 API 根地址（含版本路径，如 `/v1`、`/v4`）
 * @property model 模型名
 * @property availableModels 从 API 拉取的模型 id 列表
 * @property isLoadingModels 是否正在拉取模型列表
 * @property modelsError 拉取失败时的错误信息
 */
data class SettingsUiState(
    val apiKey: String = "",
    val apiKeyMasked: String = "",
    val apiUrl: String = SettingsKeys.Defaults.API_URL,
    val model: String = SettingsKeys.Defaults.MODEL,
    val temperature: Double = SettingsKeys.Defaults.TEMPERATURE,
    val maxTokens: Int = SettingsKeys.Defaults.MAX_TOKENS,
    val systemPrompt: String = SettingsKeys.Defaults.SYSTEM_PROMPT,
    val enableMemory: Boolean = SettingsKeys.Defaults.ENABLE_MEMORY,
    val enableAutoSummary: Boolean = SettingsKeys.Defaults.ENABLE_AUTO_SUMMARY,
    val summaryInterval: Int = SettingsKeys.Defaults.SUMMARY_INTERVAL,
    val themeMode: String = SettingsKeys.Defaults.THEME_MODE,
    val enableDynamicColor: Boolean = SettingsKeys.Defaults.ENABLE_DYNAMIC_COLOR,
    val colorPreset: String = SettingsKeys.Defaults.COLOR_PRESET,
    val privacyAgreed: Boolean = false,
    val appVersion: String = "",
    val availableModels: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    val modelsError: String? = null,
    // ── TTS 语音 ──
    val ttsMainEnabled: Boolean = SettingsKeys.Defaults.TTS_MAIN_ENABLED,
    val ttsOverlayEnabled: Boolean = SettingsKeys.Defaults.TTS_OVERLAY_ENABLED,
    val ttsLengthScale: Double = SettingsKeys.Defaults.TTS_LENGTH_SCALE,
    /** 模型下载状态（来自 TtsModelManager.state）。 */
    val ttsModelState: TtsModelState = TtsModelState.NotDownloaded,
    /** 模型下载地址是否已配置（BuildConfig 注入）；未配置时下载入口提示。 */
    val ttsModelUrlConfigured: Boolean = false,
    // ── 界面外观 ──
    val chatBubbleAlpha: Double = SettingsKeys.Defaults.CHAT_BUBBLE_ALPHA,
    // ── 更新 ──
    val enableAutoUpdateCheck: Boolean = SettingsKeys.Defaults.ENABLE_AUTO_UPDATE_CHECK,
    val isCheckingUpdate: Boolean = false,
    val updateMessage: String? = null,
    val updateReleaseUrl: String? = null,
    // ── 背景壁纸 ──
    /** 主界面背景壁纸文件绝对路径（空串 = 默认纯色）。 */
    val wallpaperPath: String = SettingsKeys.Defaults.WALLPAPER_PATH,
    /** 主界面背景壁纸模糊强度（0~1，0 = 不模糊）。 */
    val wallpaperBlur: Double = SettingsKeys.Defaults.WALLPAPER_BLUR
)

open class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = MeaPetApplication.from(application)
    private val settingsManager: SettingsManager = container.settingsManager
    private val ttsModelManager: TtsModelManager = container.ttsModelManager
    private val updateChecker: UpdateChecker = container.updateChecker

    /** 手动检测更新的在途任务，重复点击时取消上一次。 */
    private var checkUpdateJob: Job? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** 从内存快照同步读取初始值，保证首帧就是已存设置（供 UI 本地编辑状态取初值）。 */
    private fun initialState(): SettingsUiState {
        val key = settingsManager.getApiKey()
        return SettingsUiState(
            apiKey = key,
            apiKeyMasked = maskApiKey(key),
            apiUrl = settingsManager.getApiUrl(),
            model = settingsManager.getModel(),
            temperature = settingsManager.getTemperature(),
            maxTokens = settingsManager.getMaxTokens(),
            systemPrompt = settingsManager.getSystemPrompt(),
            enableMemory = settingsManager.isMemoryEnabled(),
            enableAutoSummary = settingsManager.isAutoSummaryEnabled(),
            summaryInterval = settingsManager.getSummaryInterval(),
            privacyAgreed = isPrivacyAgreed(),
            ttsMainEnabled = settingsManager.isTtsMainEnabled(),
            ttsOverlayEnabled = settingsManager.isTtsOverlayEnabled(),
            ttsLengthScale = settingsManager.getTtsLengthScale(),
            ttsModelState = ttsModelManager.state.value,
            ttsModelUrlConfigured = container.config.ttsModelBaseUrl.isNotBlank(),
            chatBubbleAlpha = settingsManager.getChatBubbleAlpha(),
            enableAutoUpdateCheck = settingsManager.isAutoUpdateCheckEnabled(),
            wallpaperPath = settingsManager.getWallpaperPath(),
            wallpaperBlur = settingsManager.getWallpaperBlur()
        )
    }

    init {
        // 订阅所有设置流（统一经 subscribe 辅助，避免逐块手写 collect 样板）
        subscribe(settingsManager.apiKeyFlow) { s, key ->
            s.copy(apiKey = key, apiKeyMasked = maskApiKey(key))
        }
        subscribe(settingsManager.apiUrlFlow) { s, url -> s.copy(apiUrl = url) }
        subscribe(settingsManager.modelFlow) { s, m -> s.copy(model = m) }
        subscribe(settingsManager.temperatureFlow) { s, t -> s.copy(temperature = t) }
        subscribe(settingsManager.maxTokensFlow) { s, t -> s.copy(maxTokens = t) }
        subscribe(settingsManager.systemPromptFlow) { s, p -> s.copy(systemPrompt = p) }
        subscribe(settingsManager.enableMemoryFlow) { s, e -> s.copy(enableMemory = e) }
        subscribe(settingsManager.enableAutoSummaryFlow) { s, e -> s.copy(enableAutoSummary = e) }
        subscribe(settingsManager.summaryIntervalFlow) { s, i -> s.copy(summaryInterval = i) }
        subscribe(settingsManager.themeModeFlow) { s, m -> s.copy(themeMode = m) }
        subscribe(settingsManager.enableDynamicColorFlow) { s, e -> s.copy(enableDynamicColor = e) }
        subscribe(settingsManager.colorPresetFlow) { s, p -> s.copy(colorPreset = p) }

        // TTS 语音
        subscribe(settingsManager.ttsMainEnabledFlow) { s, e -> s.copy(ttsMainEnabled = e) }
        subscribe(settingsManager.ttsOverlayEnabledFlow) { s, e -> s.copy(ttsOverlayEnabled = e) }
        subscribe(settingsManager.ttsLengthScaleFlow) { s, v -> s.copy(ttsLengthScale = v) }
        subscribe(ttsModelManager.state) { s, st -> s.copy(ttsModelState = st) }

        // 界面外观
        subscribe(settingsManager.chatBubbleAlphaFlow) { s, v -> s.copy(chatBubbleAlpha = v) }

        // 更新
        subscribe(settingsManager.enableAutoUpdateCheckFlow) { s, e -> s.copy(enableAutoUpdateCheck = e) }

        // 背景壁纸
        subscribe(settingsManager.wallpaperPathFlow) { s, p -> s.copy(wallpaperPath = p) }
        subscribe(settingsManager.wallpaperBlurFlow) { s, v -> s.copy(wallpaperBlur = v) }

        // 隐私授权状态（响应式订阅：同意/撤销后 UI 即时反映）
        subscribe(privacyAgreedFlow()) { s, agreed ->
            s.copy(privacyAgreed = agreed)
        }

        // 从 PackageManager 读取版本号（统一实现见 core.AppInfo）
        _state.update { it.copy(appVersion = readAppVersion()) }
    }

    /**
     * 订阅单个 Flow 并把最新值合入 [SettingsUiState]。
     *
     * 统一收起原本 12 个手写 `viewModelScope.launch { flow.collect { _state.update { ... } } }`
     * 的重复样板。
     */
    private fun <T> subscribe(flow: Flow<T>, reducer: (SettingsUiState, T) -> SettingsUiState) {
        viewModelScope.launch {
            flow.collect { value -> _state.update { reducer(it, value) } }
        }
    }

    // ── 静态依赖（可被测试 override，避免单测依赖 DataStore / PackageManager） ──

    /** 隐私授权状态流（测试 override 返回假流）。 */
    protected open fun privacyAgreedFlow(): Flow<Boolean> = PrivacyConsentManager.agreedFlow(getApplication())

    /** 用户当前是否已授权友盟采集（测试 override 返回假值）。 */
    protected open fun isPrivacyAgreed(): Boolean = PrivacyConsentManager.isAgreed(getApplication())

    /** 当前版本号（测试 override 返回假值）。 */
    protected open fun readAppVersion(): String = AppInfo.readVersion(getApplication())

    // ── 更新方法 ──────────────────────────────────────

    /** 保存 API Key（失焦/离开页面时调用）；值有变化才落盘并重建客户端。 */
    fun saveApiKey(key: String) {
        viewModelScope.launch {
            if (key == settingsManager.getApiKey()) return@launch
            settingsManager.setApiKey(key)
            container.reloadClient()
        }
    }

    /** 保存 API URL（失焦/离开页面时调用）；值有变化才落盘并重建客户端。 */
    fun saveApiUrl(url: String) {
        viewModelScope.launch {
            if (url == settingsManager.getApiUrl()) return@launch
            settingsManager.setApiUrl(url)
            container.reloadClient()
        }
    }

    /** 保存模型名（失焦/离开页面时调用）；值有变化才落盘。 */
    fun saveModel(model: String) {
        viewModelScope.launch {
            if (model == settingsManager.getModel()) return@launch
            settingsManager.setModel(model)
        }
    }

    /** 保存 System Prompt（失焦/离开页面时调用）；值有变化才落盘。 */
    fun saveSystemPrompt(prompt: String) {
        viewModelScope.launch {
            if (prompt == settingsManager.getSystemPrompt()) return@launch
            settingsManager.setSystemPrompt(prompt)
        }
    }

    /** 将 System Prompt 恢复为默认值并持久化。 */
    fun resetSystemPrompt() {
        viewModelScope.launch {
            settingsManager.setSystemPrompt(SettingsKeys.Defaults.SYSTEM_PROMPT)
        }
    }

    fun updateTemperature(temp: Double) {
        viewModelScope.launch { settingsManager.setTemperature(temp) }
    }

    fun updateMaxTokens(tokens: Int) {
        viewModelScope.launch { settingsManager.setMaxTokens(tokens) }
    }

    fun updateEnableMemory(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEnableMemory(enabled) }
    }

    fun updateEnableAutoSummary(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEnableAutoSummary(enabled) }
    }

    fun updateSummaryInterval(interval: Int) {
        viewModelScope.launch { settingsManager.setSummaryInterval(interval) }
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { settingsManager.setThemeMode(mode) }
    }

    fun updateEnableDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEnableDynamicColor(enabled) }
    }

    fun updateColorPreset(preset: String) {
        viewModelScope.launch { settingsManager.setColorPreset(preset) }
    }

    // ── TTS 语音 ──────────────────────────────────────

    /** 模型是否就绪（未就绪时开关应置灰）。 */
    fun isTtsModelReady(): Boolean = ttsModelManager.state.value == TtsModelState.Ready

    fun updateTtsMainEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setTtsMainEnabled(enabled) }
    }

    fun updateTtsOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setTtsOverlayEnabled(enabled) }
    }

    /** 切换默认语音（当前仅中文，保留接口以便后续扩展）。 */
    fun updateTtsLengthScale(scale: Double) {
        viewModelScope.launch { settingsManager.setTtsLengthScale(scale) }
    }

    /** 设置页试听：直接把给定文本交给 TTS（跳过来源开关，仅要求模型就绪）。 */
    fun previewTts(text: String) {
        container.ttsManager.speakPreview(text)
    }

    // ── 界面外观 ──────────────────────────────────────

    /** 更新主页聊天气泡透明度（0.2~1.0）。 */
    fun updateChatBubbleAlpha(alpha: Double) {
        viewModelScope.launch { settingsManager.setChatBubbleAlpha(alpha) }
    }

    /** 切换启动自动检查更新（默认开启）。 */
    fun updateEnableAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setEnableAutoUpdateCheck(enabled) }
    }

    /**
     * 关于页手动检测更新：始终反馈结果（有更新 / 已是最新 / 失败）。
     *
     * 与 ChatViewModel 的「启动静默检测」不同：后者只在有新版本时弹 Snackbar、
     * 失败不打扰；这里是用户主动点击，任何结果都要给回执。
     */
    fun checkForUpdate() {
        if (_state.value.isCheckingUpdate) return
        checkUpdateJob?.cancel()
        _state.update {
            it.copy(isCheckingUpdate = true, updateMessage = null, updateReleaseUrl = null)
        }
        checkUpdateJob = viewModelScope.launch {
            val (message, url) = when (val result = updateChecker.check()) {
                is UpdateCheckResult.UpdateAvailable ->
                    "发现新版本 v${result.release.versionName}" to result.release.htmlUrl
                is UpdateCheckResult.UpToDate ->
                    "当前已是最新版本（v${result.currentVersion}）" to null
                is UpdateCheckResult.Failed ->
                    result.message to null
            }
            _state.update {
                it.copy(isCheckingUpdate = false, updateMessage = message, updateReleaseUrl = url)
            }
        }
    }

    /** 清掉手动检测结果文案（离开关于页时调用，避免下次进入残留）。 */
    fun dismissUpdateMessage() {
        _state.update { it.copy(updateMessage = null, updateReleaseUrl = null) }
    }

    // ── 背景壁纸 ──────────────────────────────────────

    /**
     * 从相册 URI 导入壁纸：复制到 filesDir 并持久化路径。
     * 导入成功会经 wallpaperPathFlow 自动刷新 UI 与主界面渲染。
     */
    fun importWallpaper(uri: android.net.Uri) {
        viewModelScope.launch {
            val path = container.wallpaperStore.importFromContentUri(uri) ?: return@launch
            settingsManager.setWallpaperPath(path)
        }
    }

    /** 恢复默认纯色背景：清空壁纸文件并置空路径。 */
    fun clearWallpaper() {
        viewModelScope.launch {
            container.wallpaperStore.clearAll()
            settingsManager.setWallpaperPath("")
        }
    }

    /** 更新背景壁纸模糊强度（0~1，0 = 不模糊）。 */
    fun updateWallpaperBlur(blur: Double) {
        viewModelScope.launch { settingsManager.setWallpaperBlur(blur) }
    }

    /** 下载 TTS 模型（6 个文件）。地址未配置则进入 Error 提示。 */
    fun downloadTtsModel() {
        viewModelScope.launch {
            val files = ttsModelManager.buildDownloadFiles(container.config.ttsModelBaseUrl)
            // 已有旧引擎时先置非就绪再释放：避免释放等待在途合成期间新回复继续合成，
            // 在模型文件被逐个替换的过程中重建 OfflineTts。无模型时等价空操作。
            ttsModelManager.markMutating()
            container.ttsManager.releaseEngine()
            ttsModelManager.download(files)
        }
    }

    /**
     * 从本地 zip 资源包手动导入 TTS 模型（绕过 GitHub 下载）。
     * 导入前先置非就绪并释放引擎，避免旧 session 占用即将被覆盖的模型文件。
     */
    fun importTtsModelZip(uri: android.net.Uri) {
        viewModelScope.launch {
            // 顺序不可换：先关 speak 门禁，再等 releaseEngine 结束在途合成，
            // 否则等待窗口内新回复仍会重建引擎并读到正在被替换的模型目录
            ttsModelManager.markMutating()
            container.ttsManager.releaseEngine()
            ttsModelManager.importFromZip(uri)
        }
    }

    /** 删除模型，并强制关闭两个语音开关。 */
    fun deleteTtsModel() {
        viewModelScope.launch {
            // 先置非就绪（门禁失效）+ 停播并关闭 OfflineTts session（75MB 模型 + 8.9MB lexicon 的
            // native 内存），再删磁盘文件，避免 releaseEngine 等待窗口内有新合成重建引擎
            ttsModelManager.markMutating()
            container.ttsManager.releaseEngine()
            ttsModelManager.deleteModel()
            settingsManager.setTtsMainEnabled(false)
            settingsManager.setTtsOverlayEnabled(false)
        }
    }

    /**
     * 用当前表单里的 Key / URL 拉取模型列表。
     *
     * 会先把 Key、URL 落盘并重建客户端（与后续聊天共用同一配置），
     * 再用临时客户端请求 `{base}/models`，避免依赖 lazy 初始化时机。
     */
    fun fetchModels(apiKey: String, apiUrl: String) {
        // 本地模型（Ollama / LM Studio 等）不需要 API Key，允许留空直接拉取
        if (apiUrl.isBlank()) {
            _state.update { it.copy(modelsError = "请先填写 API 地址") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(isLoadingModels = true, modelsError = null)
            }
            // 先落盘当前表单值，保证后续聊天与拉取用同一配置
            if (apiKey != settingsManager.getApiKey()) {
                settingsManager.setApiKey(apiKey)
            }
            if (apiUrl != settingsManager.getApiUrl()) {
                settingsManager.setApiUrl(apiUrl)
            }
            container.reloadClient()

            val result = withContext(Dispatchers.IO) {
                val client = OpenAiCompatibleClient(apiKey = apiKey, baseUrl = apiUrl)
                try {
                    Result.success(ApiResponse.modelIds(client.listModels()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    client.close()
                }
            }

            result.fold(
                onSuccess = { ids ->
                    if (ids.isEmpty()) {
                        _state.update {
                            it.copy(
                                availableModels = emptyList(),
                                isLoadingModels = false,
                                modelsError = "接口未返回任何模型"
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                availableModels = ids,
                                isLoadingModels = false,
                                modelsError = null
                            )
                        }
                    }
                },
                onFailure = { e ->
                    val message = when (e) {
                        // ApiException.message 已是友好提示（401 → 提示填 Key）
                        is ApiException -> e.message
                        else -> e.message?.takeIf { it.isNotBlank() } ?: "获取模型列表失败"
                    }
                    _state.update {
                        it.copy(
                            isLoadingModels = false,
                            modelsError = message
                        )
                    }
                }
            )
        }
    }

    /** 从拉取结果中选中一个模型，写回本地设置。 */
    fun selectModel(model: String) {
        viewModelScope.launch {
            if (model == settingsManager.getModel()) return@launch
            settingsManager.setModel(model)
        }
    }

    fun dismissModelsError() {
        _state.update { it.copy(modelsError = null) }
    }

    // ── 日志导出 ──────────────────────────────────────

    /** 是否正在导出日志（驱动按钮 loading/禁用）。 */
    private val _exportingLog = MutableStateFlow(false)
    val exportingLog: StateFlow<Boolean> = _exportingLog.asStateFlow()

    /** 导出本次启动以来的应用日志并拉起系统分享。 */
    fun exportLog() {
        if (_exportingLog.value) return
        viewModelScope.launch {
            _exportingLog.value = true
            try {
                com.meapet.mobile.core.LogExporter.exportAndShare(getApplication())
            } finally {
                _exportingLog.value = false
            }
        }
    }

    // ── 隐私合规 ──────────────────────────────────────
    // 授权状态已并入 [SettingsUiState.privacyAgreed]（init 订阅 agreedFlow 响应式维护）

    /** 撤销友盟数据采集授权（同步落盘停止上报；App 随界面流程退出）。 */
    fun revokePrivacyConsent() {
        PrivacyConsentManager.setAgreed(getApplication(), false)
    }

    // ── 工具 ──────────────────────────────────────────

    private fun maskApiKey(key: String): String {
        if (key.length <= 8) return "****"
        return "${key.take(4)}****${key.takeLast(4)}"
    }
}
