package com.meapet.mobile.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.client.KtorHttpClientEngine
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.chat.ChatService
import com.meapet.mobile.chat.tools.ChatToolLoop
import com.meapet.mobile.memory.search.BigramMemorySearcher
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import com.meapet.mobile.chat.compaction.ContextCompactor
import com.meapet.mobile.chat.ConversationManager
import com.meapet.mobile.chat.ConversationStore
import com.meapet.mobile.storage.ConversationRepository
import com.meapet.mobile.storage.MetaCompactionStore
import com.meapet.mobile.storage.SqliteConversationRepository
import com.meapet.mobile.storage.SqliteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.core.AppInfo
import com.meapet.mobile.core.LifecycleManager
import com.meapet.mobile.core.WallpaperStore
import com.meapet.mobile.legacy.LegacyMigration
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.storage.SqliteMemoryStore
import com.meapet.mobile.memory.MemoryService
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.TtsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.engine.SherpaTtsEngine
import com.meapet.mobile.tts.engine.TtsEngine
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.text.EnglishBloomIndex
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

    /** 旧版 JSON 会话存储：仅供 legacy 导入读取，不再写入。 */
    val conversationStore: ConversationStore by lazy {
        ConversationStore(context.filesDir, applicationScope)
    }

    /** 会话 + 记忆共用的 SQLite 数据库（惰性打开，首次访问时建表/迁移）。 */
    val database: SqliteDatabase by lazy {
        val dbFile = context.getDatabasePath("meapet.db")
        dbFile.parentFile?.mkdirs()
        SqliteDatabase(AndroidSQLiteDriver(), dbFile.absolutePath)
    }

    /** 会话仓库：完整历史永久保存，UI 分页读取，与发给模型的窗口解耦。 */
    val conversationRepository: ConversationRepository by lazy {
        SqliteConversationRepository(database)
    }

    /** 会话历史管理器（内存窗口 + 有序落库）。 */
    val conversationManager: ConversationManager by lazy {
        ConversationManager(
            maxSize = config.maxHistoryMessages,
            trimBatch = config.historyTrimBatch,
            repository = conversationRepository,
            persistScope = applicationScope
        )
    }

    /** 记忆存储器。 */
    val memoryRepository: MemoryStore by lazy {
        SqliteMemoryStore(database, maxItems = config.maxMemoryItems)
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

    /** 上下文容量提供者（手动值 > 探测缓存 > 兜底）。 */
    val contextCapacity: com.meapet.mobile.client.ContextCapacityProvider by lazy {
        com.meapet.mobile.client.ContextCapacityProvider(
            settingsManager = settingsManager,
            clientFactory = { apiClient },
            scope = applicationScope
        )
    }

    /** 上下文压缩状态存储（与会话/记忆同库，`meta` 表一行 JSON）。 */
    private val compactionStore: MetaCompactionStore by lazy {
        MetaCompactionStore(database)
    }

    /** 上下文压缩器：用当前模型把较早轮次折叠成滚动摘要。 */
    private val contextCompactor: ContextCompactor by lazy {
        ContextCompactor(
            clientProvider = { apiClient },
            modelProvider = { settingsManager.getModel() }
        )
    }

    /** 记忆工具执行器（仅 enableMemoryTools 开启时创建；工具循环与 turn 提交共用同一实例）。 */
    private val memoryToolExecutor: MemoryToolExecutor? by lazy {
        if (!config.enableMemoryTools) {
            null
        } else {
            MemoryToolExecutor(
                search = BigramMemorySearcher(memoryRepository),
                store = memoryRepository
            )
        }
    }

    /** 记忆工具循环（仅 enableMemoryTools 开启时创建）。 */
    private val chatToolLoop: ChatToolLoop? by lazy {
        val executor = memoryToolExecutor ?: return@lazy null
        ChatToolLoop(
            clientProvider = { apiClient },
            executor = executor
        )
    }

    /** 聊天服务（核心业务逻辑）。 */
    val chatService: ChatService by lazy {
        ChatService(
            clientProvider = { apiClient },
            onBeforeSend = { contextCapacity.refreshIfNeeded() },
            conversationManager = conversationManager,
            memoryManager = memoryManager,
            settingsManager = settingsManager,
            postProcessScope = applicationScope,
            config = config,
            compactionStore = compactionStore,
            compactor = contextCompactor,
            budgetProvider = { contextCapacity.budget() },
            toolLoop = chatToolLoop,
            memoryTools = memoryToolExecutor
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

    /**
     * 文本归一化：拉丁小写 + 英文 OOV 逐字母兜底 + 日语上下文改写。
     * 两个资产（ja_reading_map.json / en_words.bloom）都在首次合成（IO 线程）时懒加载。
     */
    private val ttsTextNormalizer: TtsTextNormalizer by lazy {
        val bloom = lazy { EnglishBloomIndex.load(context) }
        TtsTextNormalizer(
            mapProvider = { JaReadingMapLoader.load(context) },
            knownEnglishWord = { word -> bloom.value.mightContain(word) },
        )
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
            // 0. 等 DataStore 内存快照就绪再做任何依赖设置的事。
            //    否则冷启动早期构建的 apiClient 会拿到空 API Key（→ 首次请求 401），
            //    容量探测也会因 401 被负缓存 5 分钟（表现为「读不到上下文窗口」）。
            settingsManager.awaitLoaded()
            // 丢弃可能在快照就绪前用空 Key 建好的客户端，保证首次请求就是正确配置
            reloadClient()

            // 1.9 存储迁移：旧 JSON -> SQLite 必须先于会话/记忆的首次访问同步完成（旧 JSON 文件保留不动）
            com.meapet.mobile.storage.LegacyJsonImporter(database, context.filesDir).importIfNeeded()
            // 首次启动：后台探测上下文容量（失败降级兜底）
            contextCapacity.refreshIfNeeded()
            // Live2D 模型预读独立后台跑：模型加载在 GL 线程同步执行，asset 读取是最大耗时。
            // 它不阻塞首屏历史渲染——若并进 warmUpJob，聊天页要等模型预热完才显示内容（闪黑/闪空）。
            applicationScope.launch(Dispatchers.IO) {
                runCatching {
                    com.meapet.mobile.live2d.Live2dManager.getInstance().prewarmModel(context)
                }.onFailure { Log.w(TAG, "Live2D 模型预热失败", it) }
            }
            listOf(
                launch { memoryRepository.loadFromDisk() },
                launch { conversationManager.restoreFromRepository() },
                // 版本迁移：启动时异步执行（框架长期保留；当前 TTS step 预计 1.11.0 移除，见 docs/legacy-compat.md）
                launch { LegacyMigration.run(context, settingsManager, ttsModelManager, memoryRepository) }
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
        // 保存/切换服务商设置：容量缓存随之失效并后台重新探测
        contextCapacity.refresh()
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
