package com.meapet.mobile.tts

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.audio.TtsAudioPlayer
import com.meapet.mobile.tts.engine.TtsEngine
import com.meapet.mobile.tts.model.TtsModelManager
import com.meapet.mobile.tts.model.TtsModelState
import com.meapet.mobile.tts.text.JaReadingMap
import com.meapet.mobile.tts.text.TtsTextNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [TtsManager] 的纯 JVM 单测：引擎用 [TtsEngine] 匿名实现（sherpa 的 `GeneratedAudio`
 * 只是数据类，可在 JVM 上直接构造），播放器用 mockito 打桩。
 *
 * 覆盖改造后的关键契约：采样率透传、语速 = 1/lengthScale、各道闸门（开关 / 静音 / 模型就绪 /
 * 空白文本）不进入合成、试听入口跳过来源开关、[TtsManager.releaseEngine] 与在途合成串行。
 */
class TtsManagerTest {

    private val settings = mock<SettingsManager>()
    private val modelManager = mock<TtsModelManager>()
    private val player = mock<TtsAudioPlayer>()
    private val normalizer = TtsTextNormalizer { JaReadingMap.EMPTY }

    private fun manager(engine: TtsEngine) = TtsManager(
        settingsManager = settings,
        modelManager = modelManager,
        engine = engine,
        normalizer = normalizer,
        player = player,
        scope = CoroutineScope(SupervisorJob()),
    )

    private fun readyModel() {
        whenever(modelManager.state).thenReturn(MutableStateFlow<TtsModelState>(TtsModelState.Ready))
    }

    @Test
    fun `播放取合成结果采样率并按语速换算`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(2.0)
        readyModel()

        val speedRef = AtomicReference<Float>(0f)
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                speedRef.set(speed)
                return GeneratedAudio(FloatArray(100) { 0.1f }, 24000)
            }

            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "今天天气真好。"), TtsManager.Source.MAIN)

        verify(player, timeout(3000)).play(any(), eq(24000))
        // lengthScale=2.0 → speed = 0.5（语速更慢）
        assertEquals(0.5f, speedRef.get(), 1e-6f)
    }

    @Test
    fun `采样率原样透传给播放器`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()

        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float) = GeneratedAudio(FloatArray(10), 22050)
            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "こんにちは"), TtsManager.Source.MAIN)

        verify(player, timeout(3000)).play(any(), eq(22050))
    }

    @Test
    fun `语速随 lengthScale 收敛到设置范围`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(9.0)   // 越界，应裁剪为 2.0
        readyModel()

        val speedRef = AtomicReference<Float>(0f)
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                speedRef.set(speed)
                return GeneratedAudio(FloatArray(10), 22050)
            }

            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "你好"), TtsManager.Source.MAIN)

        verify(player, timeout(3000)).play(any(), eq(22050))
        assertEquals(0.5f, speedRef.get(), 1e-6f)
    }

    @Test
    fun `空白内容不进入合成`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        var called = false
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                called = true
                return GeneratedAudio(FloatArray(1), 22050)
            }

            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "   "), TtsManager.Source.MAIN)
        Thread.sleep(300)
        assertFalse(called)
        // 注意：TtsManager.init 会给 player.onPlaybackComplete 赋值（算一次交互），
        // 所以只能断言 play() 从未被调用，不能用 verifyNoInteractions。
        verify(player, never()).play(any(), any())
    }

    @Test
    fun `归一化后为空不合成也不播放`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        var called = false
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                called = true
                return GeneratedAudio(FloatArray(1), 22050)
            }

            override fun release() {}
        }
        // 模拟「改写后没有可读文本」：归一化结果为空，必须在合成前静默放弃
        val emptyNormalizer = mock<TtsTextNormalizer>()
        whenever(emptyNormalizer.normalize(any())).thenReturn("")
        val mgr = TtsManager(
            settingsManager = settings,
            modelManager = modelManager,
            engine = engine,
            normalizer = emptyNormalizer,
            player = player,
            scope = CoroutineScope(SupervisorJob()),
        )

        mgr.speak(ChatMessage(ChatRole.assistant, "raw text"), TtsManager.Source.MAIN)
        Thread.sleep(300)
        assertFalse(called)
        verify(player, never()).play(any(), any())
    }

    @Test
    fun `模型未就绪不合成`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(modelManager.state).thenReturn(MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded))
        var called = false
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                called = true
                return GeneratedAudio(FloatArray(1), 22050)
            }

            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "你好"), TtsManager.Source.MAIN)
        Thread.sleep(300)
        assertFalse(called)
        verify(player, never()).play(any(), any())
    }

    @Test
    fun `会话静音不合成`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        readyModel()
        var called = false
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                called = true
                return GeneratedAudio(FloatArray(1), 22050)
            }

            override fun release() {}
        }
        val mgr = manager(engine)
        mgr.toggleSessionMute()   // 开启本次会话静音

        mgr.speak(ChatMessage(ChatRole.assistant, "你好"), TtsManager.Source.MAIN)
        Thread.sleep(300)
        assertFalse(called)
        verify(player, never()).play(any(), any())
    }

    @Test
    fun `主开关关闭不合成`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(false)
        readyModel()
        var called = false
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                called = true
                return GeneratedAudio(FloatArray(1), 22050)
            }

            override fun release() {}
        }

        manager(engine).speak(ChatMessage(ChatRole.assistant, "你好"), TtsManager.Source.MAIN)
        Thread.sleep(300)
        assertFalse(called)
        verify(player, never()).play(any(), any())
    }

    @Test
    fun `试听跳过来源开关但仍要求模型就绪与会话未静音`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(false)   // 主开关关着，试听照样出声
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()

        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float) = GeneratedAudio(FloatArray(10), 22050)
            override fun release() {}
        }

        manager(engine).speakPreview("你好")
        verify(player, timeout(3000)).play(any(), eq(22050))
    }

    @Test
    fun `releaseEngine 等完在途合成才释放引擎`() = runBlocking {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()

        val synthEntered = CountDownLatch(1)
        val allowSynthFinish = CountDownLatch(1)
        val releaseCalled = AtomicBoolean(false)
        val engine = object : TtsEngine {
            override fun synthesize(text: String, speed: Float): GeneratedAudio {
                synthEntered.countDown()
                allowSynthFinish.await(5, TimeUnit.SECONDS)
                return GeneratedAudio(FloatArray(10), 22050)
            }

            override fun release() {
                releaseCalled.set(true)
            }
        }
        val mgr = manager(engine)
        mgr.speak(ChatMessage(ChatRole.assistant, "你好"), TtsManager.Source.MAIN)
        assertTrue("合成应已开始", synthEntered.await(3, TimeUnit.SECONDS))

        val released = CompletableDeferred<Unit>()
        launch(Dispatchers.IO) {
            mgr.releaseEngine()
            released.complete(Unit)
        }
        delay(200)
        // 合成仍在 native 里跑，release 必须被同一把 Mutex 挡住（否则是 use-after-free）
        assertFalse("在途合成未结束不应释放引擎", releaseCalled.get())

        allowSynthFinish.countDown()
        withTimeout(3000) { released.await() }
        assertTrue("合成结束后应释放引擎", releaseCalled.get())
    }
}
