package com.meapet.mobile.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.client.KtorHttpClientEngine
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.chat.ChatService
import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.chat.ConversationStore
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.core.AppInfo
import com.meapet.mobile.core.LifecycleManager
import com.meapet.mobile.core.WallpaperStore
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.memory.MemoryService
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.TtsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.engine.SherpaTtsEngine
import com.meapet.mobile.tts.engine.TtsEngine
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.text.JaReadingMapLoader
import com.meapet.mobile.tts.text.TtsTextNormalizer
import com.meapet.mobile.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 应用依赖容器（手动 DI / 服务定位器）。
 *
 * ## 职责
 * - 创建并持有所有子系统实例；
 * - 保证初始化顺序（Settings → Client → Memory → Chat）；
 * - 提供 [reloadClient] 等方法在运行时切换配置（如 API Key 变更）。
 *
 * ## 使用方式
 * ```kotlin
 * // 在 Application.onCreate 中初始化
 * val container = AppContainer(context)
 *
 * // 在 Activity / ViewModel 中获取
 * val chatService = container.chatService
 * ```
 *
 * ## 低耦合设计
 * - 各子系统之间仅通过接口/服务类交互，不直接依赖具体实现；
 * - [ChatService] 依赖 [MemoryManager] 接口，而非 MemoryRepository 具体类；
 * - 容器是唯一知道"谁依赖谁"的地方。
 *
 * @param context Application Context（非 Activity，防止泄漏）
 * @param config 全局配置，不传则使用 [AppConfig.DEFAULT]
 */
class AppContainer(
    private val context: Context,
    val config: AppConfig = AppConfig.fromBuildConfig(
        ttsModelBaseUrl = BuildConfig.TTS_MODEL_BASE_URL,
        appVersion = BuildConfig.VERSION_NAME
    )
) {
    companion object {
        private const val TAG = "AppContainer"
    }
    /** 设置管理器（独立，无依赖） */
    val settingsManager: SettingsManager by lazy {
        SettingsManager(context)
    }

    /** OpenAI 兼容 HTTP 客户端。可通过 [reloadClient] 在运行时重建。 */
    val apiClient: OpenAiCompatibleClient
        get() = _apiClient ?: synchronized(clientLock) {
            _apiClient ?: createClient().also { _apiClient = it }
        }

    /** 会话历史持久化存储。 */
    val conversationStore: ConversationStore by lazy {
        ConversationStore(context.filesDir, applicationScope)
    }

    /** 会话历史管理器。 */
    val conversationManager: ConversationManager by lazy {
        ConversationManager(
            maxSize = config.maxHistoryMessages,
            trimBatch = config.historyTrimBatch,
            store = conversationStore
        )
    }

    /** 记忆存储器。 */
    val memoryRepository: MemoryRepository by lazy {
        MemoryRepository(context.filesDir, maxItems = config.maxMemoryItems)
    }

    /** 记忆业务服务。 */
    val memoryService: MemoryService by lazy {
        MemoryService(
            repository = memoryRepository,
            summarizationClient = { apiClient },
            settingsManager = settingsManager,
            config = config
        )
    }

    /** 记忆管理器（高层面 orchestrator）。 */
    val memoryManager: MemoryManager by lazy {
        MemoryManager(
            service = memoryService,
            repository = memoryRepository,
            settingsManager = settingsManager,
            config = config
        )
    }

    /** 聊天服务（核心业务逻辑）。 */
    val chatService: ChatService by lazy {
        ChatService(
            clientProvider = { apiClient },
            conversationManager = conversationManager,
            memoryManager = memoryManager,
            settingsManager = settingsManager,
            postProcessScope = applicationScope,
            config = config
        )
    }

    /** 应用级协程作用域（用于非 UI 的后台任务）。 */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    /** 版本更新检测（独立 HTTP 引擎，不与用户 API Key 绑定）。 */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(currentVersionProvider = { readAppVersion() })
    }

    /** 主界面背景壁纸导入与清理（相册 URI → filesDir）。 */
    val wallpaperStore: WallpaperStore by lazy {
        WallpaperStore(context)
    }

    /** 生命周期管理器。 */
    val lifecycleManager: LifecycleManager by lazy {
        LifecycleManager(
            trimMemoryCallback = { level ->
                // 只做日志，不在这里调 suspend 函数（系统回调可能在任何线程触发，
                // 且此时各 lazy 组件可能尚未初始化，容易导致崩溃）
                if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                    Log.i(TAG, "系统内存紧张（level=$level），由各组件自行在下次操作时清理")
                }
            }
        )
    }

    // ── TTS 语音 ──────────────────────────────────────

    /** TTS 模型包（model.onnx + lexicon + tokens + fsts）下载与状态管理。 */
    val ttsModelManager: TtsModelManager by lazy {
        TtsModelManager(context)
    }

    /** sherpa-onnx 合成引擎（懒加载 75MB 模型 + 8.9MB lexicon）。 */
    private val sherpaTtsEngine: TtsEngine by lazy {
        SherpaTtsEngine(ttsModelManager)
    }

    /** 文本归一化：拉丁小写 + 日语上下文改写（首次合成时加载 ja_reading_map.json）。 */
    private val ttsTextNormalizer: TtsTextNormalizer by lazy {
        TtsTextNormalizer { JaReadingMapLoader.load(context) }
    }

    /** TTS 门面：开关判断、归一化、整段合成播放。 */
    val ttsManager: TtsManager by lazy {
        TtsManager(
            settingsManager = settingsManager,
            modelManager = ttsModelManager,
            engine = sherpaTtsEngine,
            normalizer = ttsTextNormalizer,
            player = TtsAudioPlayer(),
            scope = applicationScope,
        ).also { wireVoiceMutex() }
    }

    /**
     * 接线触摸语音 ↔ TTS 互斥（经 Live2dDelegate 的进程级 hook，避免包间反向依赖）：
     * - 触摸语音触发时若 TTS 在播，先停 TTS；
     * - TTS 开始播放时停掉未完触摸语音。
     */
    private fun wireVoiceMutex() {
        // 触摸语音触发时若 TTS 在播，先停 TTS
        com.meapet.mobile.live2d.Live2dDelegate.ttsPlayingChecker = { ttsManager.isPlaying.value }
        com.meapet.mobile.live2d.Live2dDelegate.ttsStopper = { ttsManager.stop() }
        // TTS 开始播放时停掉未完的触摸语音
        TtsManager.onPlaybackStart = {
            com.meapet.mobile.live2d.Live2dDelegate.getInstance().stopTouchVoices()
        }
    }

    // ── 启动预热 ──────────────────────────────────────

    /**
     * 启动预热任务。[warmUp] 调用后完成记忆 + 会话历史的磁盘加载；
     * ViewModel 可 `join()` 等待加载完成后刷新 UI。
     * 未调用 warmUp 时为已完成的空 Job（各仓库有惰性加载兜底，不会丢数据）。
     */
    @Volatile
    var warmUpJob: Job = Job().apply { complete() }
        private set

    /** 启动时异步加载持久化的记忆与会话历史（不阻塞主线程）。 */
    fun warmUp() {
        warmUpJob = applicationScope.launch(Dispatchers.IO) {
            listOf(
                launch { memoryRepository.loadFromDisk() },
                launch { conversationManager.restore(conversationStore.load()) },
                // Live2D 模型文件预读到内存缓存：模型加载在 GL 线程同步执行，
                // asset 读取是最大耗时（阻塞主线程 onPause 数秒）。预热后 GL 线程
                // 只做解析 + GL 上传，启动/切前后台不再长时间卡顿。
                launch {
                    runCatching {
                        com.meapet.mobile.live2d.Live2dManager.getInstance()
                            .prewarmModel(context)
                    }.onFailure { Log.w(TAG, "Live2D 模型预热失败", it) }
                }
            ).joinAll()
        }
    }

    /** 从 PackageManager 读取当前 versionName（统一实现见 [AppInfo]）。 */
    fun readAppVersion(): String = AppInfo.readVersion(context)

    // ── 运行时热替换 ──────────────────────────────────

    /**
     * 在 API Key 或 Base URL 变更后重建 HTTP 客户端。
     *
     * 旧客户端引擎会被关闭；[ChatService]、[MemoryService] 通过 provider
     * 在下次请求时拿到新的 [apiClient]，无需重建服务实例。
     */
    fun reloadClient() {
        val old: OpenAiCompatibleClient?
        synchronized(clientLock) {
            old = _apiClient
            _apiClient = null
        }
        old?.close()
        Log.i(TAG, "API client invalidated, will be rebuilt on next request")
    }

    /**
     * 清除所有记忆数据。
     */
    suspend fun clearAllMemories() {
        memoryRepository.clear()
    }

    /**
     * 复位所有状态（清除对话 + 记忆）。
     */
    suspend fun resetAll() {
        conversationManager.clear()
        memoryRepository.clear()
    }

    // ── 内部可变缓存（用于 reload） ──

    private val clientLock = Any()

    @Volatile
    private var _apiClient: OpenAiCompatibleClient? = null

    private fun createClient(): OpenAiCompatibleClient {
        val apiKey = settingsManager.getApiKey()
        val baseUrl = settingsManager.getApiUrl()
        return OpenAiCompatibleClient(
            apiKey = apiKey,
            baseUrl = baseUrl,
            engine = KtorHttpClientEngine()
        )
    }
}
