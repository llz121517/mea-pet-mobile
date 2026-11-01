package com.meapet.mobile.viewmodel

import com.meapet.mobile.app.AppContainer
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.TtsManager
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * [SettingsViewModel] 交互测试。
 *
 * 静态依赖（PrivacyConsentManager / AppInfo）经 [FakeSettingsViewModel]
 * 覆写的 protected 方法隔离（避免单测触碰 DataStore / PackageManager）；
 * `application` mock 为 [MeaPetApplication]，让 `MeaPetApplication.from` 真实执行，
 * `container` 经 doReturn 注入。验证「值有变化才落盘」与输入校验逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    /** 屏蔽静态依赖的测试替身。 */
    private class FakeSettingsViewModel(application: MeaPetApplication) : SettingsViewModel(application) {
        override fun privacyAgreedFlow() = MutableStateFlow(false)
        override fun isPrivacyAgreed() = false
        override fun readAppVersion() = "1.0.0"
    }

    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var application: MeaPetApplication
    private lateinit var container: AppContainer
    private lateinit var settingsManager: SettingsManager
    private lateinit var ttsModelManager: TtsModelManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = mock()
        container = mock()
        settingsManager = mock()
        ttsModelManager = mock()

        // container 经 doReturn 注入（by lazy 属性）
        Mockito.doReturn(container).`when`(application).container
        Mockito.doReturn(settingsManager).`when`(container).settingsManager
        Mockito.doReturn(ttsModelManager).`when`(container).ttsModelManager
        Mockito.doReturn(AppConfig.DEFAULT).`when`(container).config

        // 设置 getter（initialState 同步读取）
        whenever(settingsManager.getApiKey()).thenReturn("")
        whenever(settingsManager.getApiUrl()).thenReturn("https://api.openai.com/v1")
        whenever(settingsManager.getModel()).thenReturn("gpt-4o-mini")
        whenever(settingsManager.getTemperature()).thenReturn(0.7)
        whenever(settingsManager.getMaxTokens()).thenReturn(1024)
        whenever(settingsManager.getSystemPrompt()).thenReturn("")
        whenever(settingsManager.isMemoryEnabled()).thenReturn(true)
        whenever(settingsManager.isAutoSummaryEnabled()).thenReturn(true)
        whenever(settingsManager.getSummaryInterval()).thenReturn(10)
        whenever(settingsManager.isTtsMainEnabled()).thenReturn(false)
        whenever(settingsManager.isTtsOverlayEnabled()).thenReturn(false)
        whenever(settingsManager.getTtsLengthScale()).thenReturn(1.0)
        whenever(settingsManager.getChatBubbleAlpha()).thenReturn(1.0)
        whenever(settingsManager.isAutoUpdateCheckEnabled()).thenReturn(true)
        whenever(settingsManager.getWallpaperPath()).thenReturn("")
        whenever(settingsManager.getWallpaperBlur()).thenReturn(0.0)

        // TTS 模型管理器（state 流）
        whenever(ttsModelManager.state).thenReturn(MutableStateFlow(TtsModelState.NotDownloaded))

        // 全部设置 Flow（init 订阅）
        whenever(settingsManager.apiKeyFlow).thenReturn(MutableStateFlow(""))
        whenever(settingsManager.apiUrlFlow).thenReturn(MutableStateFlow("https://api.openai.com/v1"))
        whenever(settingsManager.modelFlow).thenReturn(MutableStateFlow("gpt-4o-mini"))
        whenever(settingsManager.temperatureFlow).thenReturn(MutableStateFlow(0.7))
        whenever(settingsManager.maxTokensFlow).thenReturn(MutableStateFlow(1024))
        whenever(settingsManager.systemPromptFlow).thenReturn(MutableStateFlow(""))
        whenever(settingsManager.enableMemoryFlow).thenReturn(MutableStateFlow(true))
        whenever(settingsManager.enableAutoSummaryFlow).thenReturn(MutableStateFlow(true))
        whenever(settingsManager.summaryIntervalFlow).thenReturn(MutableStateFlow(10))
        whenever(settingsManager.themeModeFlow).thenReturn(MutableStateFlow("system"))
        whenever(settingsManager.enableDynamicColorFlow).thenReturn(MutableStateFlow(true))
        whenever(settingsManager.colorPresetFlow).thenReturn(MutableStateFlow("default"))
        whenever(settingsManager.ttsMainEnabledFlow).thenReturn(MutableStateFlow(false))
        whenever(settingsManager.ttsOverlayEnabledFlow).thenReturn(MutableStateFlow(false))
        whenever(settingsManager.ttsLengthScaleFlow).thenReturn(MutableStateFlow(1.0))
        whenever(settingsManager.chatBubbleAlphaFlow).thenReturn(MutableStateFlow(1.0))
        whenever(settingsManager.enableAutoUpdateCheckFlow).thenReturn(MutableStateFlow(true))
        whenever(settingsManager.wallpaperPathFlow).thenReturn(MutableStateFlow(""))
        whenever(settingsManager.wallpaperBlurFlow).thenReturn(MutableStateFlow(0.0))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `保存不同 Key 落盘并重建客户端`() = runTest(dispatcher.scheduler) {
        val vm = FakeSettingsViewModel(application)
        vm.saveApiKey("new-key")
        dispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(settingsManager) { setApiKey("new-key") }
        verify(container).reloadClient()
    }

    @Test
    fun `相同 Key 不重复落盘`() = runTest(dispatcher.scheduler) {
        whenever(settingsManager.getApiKey()).thenReturn("same")
        val vm = FakeSettingsViewModel(application)

        vm.saveApiKey("same")
        dispatcher.scheduler.advanceUntilIdle()

        verifyBlocking(settingsManager, never()) { setApiKey(any()) }
    }

    @Test
    fun `空 API 地址拉取模型报错`() = runTest(dispatcher.scheduler) {
        val vm = FakeSettingsViewModel(application)

        vm.fetchModels("key", "")

        assertEquals("请先填写 API 地址", vm.state.value.modelsError)
    }

    /**
     * 删除模型：`markMutating()` 必须早于 `releaseEngine()`。
     *
     * 若顺序反了，releaseEngine 等待在途合成的窗口里 state 仍是 Ready，新回复会排队合成，
     * 并在模型目录删除期间重建 OfflineTts（静默加载失败/播到已删文件）。
     * 断言方式：记录 releaseEngine 被调用那一刻的 state —— 必须是 Importing（非 Ready）。
     */
    @Test
    fun `删除模型先关 speak 门禁再释放引擎`() = runTest(dispatcher.scheduler) {
        val ttsManager: TtsManager = mock()
        Mockito.doReturn(ttsManager).`when`(container).ttsManager

        val modelState = MutableStateFlow<TtsModelState>(TtsModelState.Ready)
        whenever(ttsModelManager.state).thenReturn(modelState)
        whenever(ttsModelManager.markMutating()).thenAnswer {
            modelState.value = TtsModelState.Importing
        }
        var stateWhenReleased: TtsModelState? = null
        wheneverBlocking { ttsManager.releaseEngine() }.thenAnswer {
            stateWhenReleased = modelState.value
        }

        val vm = FakeSettingsViewModel(application)
        vm.deleteTtsModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(TtsModelState.Importing, stateWhenReleased)
        verify(ttsModelManager).markMutating()
        verifyBlocking(ttsManager) { releaseEngine() }
        verifyBlocking(ttsModelManager) { deleteModel() }
    }

    /** 下载前同样要先置非就绪再释放旧引擎（模型文件会被逐个替换）。 */
    @Test
    fun `下载模型前先关 speak 门禁并释放引擎`() = runTest(dispatcher.scheduler) {
        val ttsManager: TtsManager = mock()
        Mockito.doReturn(ttsManager).`when`(container).ttsManager

        val modelState = MutableStateFlow<TtsModelState>(TtsModelState.Ready)
        whenever(ttsModelManager.state).thenReturn(modelState)
        whenever(ttsModelManager.markMutating()).thenAnswer {
            modelState.value = TtsModelState.Importing
        }
        var stateWhenReleased: TtsModelState? = null
        wheneverBlocking { ttsManager.releaseEngine() }.thenAnswer {
            stateWhenReleased = modelState.value
        }

        val vm = FakeSettingsViewModel(application)
        vm.downloadTtsModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(TtsModelState.Importing, stateWhenReleased)
        verify(ttsModelManager).markMutating()
        verifyBlocking(ttsManager) { releaseEngine() }
        verifyBlocking(ttsModelManager) { download(any()) }
    }
}
