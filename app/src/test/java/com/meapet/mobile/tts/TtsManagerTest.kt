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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
 * [TtsManager] 的纯 JVM 单测：引擎用 [TtsEngine] 假实现（sherpa 的 `GeneratedAudio`
 * 只是数据类，可在 JVM 上直接构造），播放器用 mockito 打桩。
 *
 * 覆盖流式播放的关键契约：逐句回调即写入播放器（首块才点亮/停触摸语音）、代际令牌透传、
 * 中止传播（回调返回 false 后不再写入）、采样率与语速透传、各道闸门不进入合成、
 * [TtsManager.releaseEngine] 与在途合成串行。
 */
class TtsManagerTest {

    private val settings = mock<SettingsManager>()
    private val modelManager = mock<TtsModelManager>()
    private val player = mock<TtsAudioPlayer>()
    private val normalizer = TtsTextNormalizer(mapProvider = { JaReadingMap.EMPTY })

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

    private fun message(text: String) = ChatMessage(ChatRole.assistant, text)

    /**
     * 流式引擎假实现：按 [chunks] 逐块回调。
     *
     * @param beforeEachChunk 每块回调前的钩子（模拟"合成中途被打断"时先阻塞再出块）
     * @param afterAllChunks 全部分块回调后的钩子（可用来等 manager 收尾）
     */
    private class FakeStreamingEngine(
        private val sampleRate: Int = 22050,
        private val chunks: List<FloatArray> = listOf(FloatArray(10) { 0.1f }),
        private val beforeEachChunk: ((Int) -> Unit)? = null,
        private val afterAllChunks: (() -> Unit)? = null,
    ) : TtsEngine {
        val speeds = mutableListOf<Float>()
        val releaseCalls = AtomicBoolean(false)
        var finished = false
        var aborted = false
        var called = false

        override fun synthesizeStreaming(
            text: String,
            speed: Float,
            onChunk: (GeneratedAudio) -> Boolean,
        ): Boolean {
            called = true
            speeds += speed
            chunks.forEachIndexed { index, chunk ->
                beforeEachChunk?.invoke(index)
                if (!onChunk(GeneratedAudio(chunk, sampleRate))) {
                    aborted = true
                    return false
                }
            }
            finished = true
            afterAllChunks?.invoke()
            return true
        }

        override fun release() {
            releaseCalls.set(true)
        }
    }

    @Test
    fun `流式合成按句写入播放器并收尾`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        whenever(player.startStreaming(22050)).thenReturn(42)
        whenever(player.writeStreaming(any(), any())).thenReturn(true)

        val engine = FakeStreamingEngine(
            chunks = listOf(FloatArray(10) { 0.5f }, FloatArray(20) { 0.25f }, FloatArray(30) { -0.5f }),
        )
        manager(engine).speak(message("今天天气很好。我们出去散步吧。记得带伞。"), TtsManager.Source.MAIN)

        verify(player, timeout(3000)).startStreaming(22050)
        val pcm = argumentCaptor<ShortArray>()
        verify(player, timeout(3000).times(3)).writeStreaming(eq(42), pcm.capture())
        assertEquals(listOf(10, 20, 30), pcm.allValues.map { it.size })
        assertArrayEquals(ShortArray(10) { 16383 }, pcm.allValues[0])
        verify(player, timeout(3000)).finishStreaming(42)
        assertTrue("引擎应跑到自然结束", engine.finished)
    }

    @Test
    fun `语速按 lengthScale 换算传给引擎`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(2.0)
        readyModel()
        whenever(player.startStreaming(any())).thenReturn(1)

        val engine = FakeStreamingEngine()
        manager(engine).speak(message("今天天气真好。"), TtsManager.Source.MAIN)

        verify(player, timeout(3000)).startStreaming(22050)
        assertEquals(0.5f, engine.speeds.single(), 1e-6f)
    }

    @Test
    fun `采样率取自首个分块并原样透传`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        whenever(player.startStreaming(24000)).thenReturn(7)
        whenever(player.writeStreaming(any(), any())).thenReturn(true)

        val engine = FakeStreamingEngine(sampleRate = 24000, chunks = listOf(FloatArray(10), FloatArray(10)))
        manager(engine).speak(message("こんにちは"), TtsManager.Source.MAIN)

        verify(player, timeout(3000)).startStreaming(24000)
        verify(player, timeout(3000)).finishStreaming(eq(7))
    }

    @Test
    fun `有音频时才点亮播放并触发 onPlaybackStart`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        whenever(player.startStreaming(any())).thenReturn(1)
        whenever(player.writeStreaming(any(), any())).thenReturn(true)

        val playbackStarted = AtomicBoolean(false)
        TtsManager.onPlaybackStart = { playbackStarted.set(true) }
        try {
            val mgr = manager(FakeStreamingEngine())
            mgr.speak(message("你好"), TtsManager.Source.MAIN)

            verify(player, timeout(3000)).startStreaming(22050)
            assertTrue("首块到达应触发 onPlaybackStart", playbackStarted.get())
            assertTrue("首块到达应点亮播放中标记", mgr.isPlaying.value)
        } finally {
            TtsManager.onPlaybackStart = null
        }
    }

    @Test
    fun `合成无分块时不开始播放`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()

        val done = CountDownLatch(1)
        val mgr = manager(FakeStreamingEngine(chunks = emptyList(), afterAllChunks = { done.countDown() }))
        mgr.speak(message("你好"), TtsManager.Source.MAIN)

        assertTrue(done.await(3, TimeUnit.SECONDS))
        delay(100)
        assertFalse("没有音频不应点亮播放中标记", mgr.isPlaying.value)
        verify(player, never()).startStreaming(any())
        verify(player, never()).finishStreaming(any())
    }

    @Test
    fun `stop 后迟到的分块不再开始播放`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()

        val entered = CountDownLatch(1)
        val allowChunk = CountDownLatch(1)
        val engine = FakeStreamingEngine(
            beforeEachChunk = {
                entered.countDown()
                allowChunk.await(5, TimeUnit.SECONDS)
            },
        )
        val mgr = manager(engine)
        mgr.speak(message("你好"), TtsManager.Source.MAIN)
        assertTrue("合成应已开始", entered.await(3, TimeUnit.SECONDS))

        mgr.stop()
        allowChunk.countDown()

        verify(player, timeout(3000).atLeastOnce()).stop()
        delay(300)
        verify(player, never()).startStreaming(any())
        assertTrue("过期分块应中止合成", engine.aborted)
    }

    @Test
    fun `空白内容不进入合成`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        val engine = FakeStreamingEngine()

        manager(engine).speak(message("   "), TtsManager.Source.MAIN)
        delay(300)
        assertFalse(engine.called)
        verify(player, never()).startStreaming(any())
    }

    @Test
    fun `归一化后为空不合成也不播放`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        val engine = FakeStreamingEngine()
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

        mgr.speak(message("raw text"), TtsManager.Source.MAIN)
        delay(300)
        assertFalse(engine.called)
        verify(player, never()).startStreaming(any())
    }

    @Test
    fun `模型未就绪不合成`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(modelManager.state).thenReturn(MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded))
        val engine = FakeStreamingEngine()

        manager(engine).speak(message("你好"), TtsManager.Source.MAIN)
        delay(300)
        assertFalse(engine.called)
        verify(player, never()).startStreaming(any())
    }

    @Test
    fun `会话静音不合成`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        readyModel()
        val engine = FakeStreamingEngine()
        val mgr = manager(engine)
        mgr.toggleSessionMute()

        mgr.speak(message("你好"), TtsManager.Source.MAIN)
        delay(300)
        assertFalse(engine.called)
        verify(player, never()).startStreaming(any())
    }

    @Test
    fun `主开关关闭不合成`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(false)
        readyModel()
        val engine = FakeStreamingEngine()

        manager(engine).speak(message("你好"), TtsManager.Source.MAIN)
        delay(300)
        assertFalse(engine.called)
        verify(player, never()).startStreaming(any())
    }

    @Test
    fun `试听跳过来源开关但仍要求模型就绪与会话未静音`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(false)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()
        whenever(player.startStreaming(any())).thenReturn(1)
        val engine = FakeStreamingEngine()

        manager(engine).speakPreview("你好")
        verify(player, timeout(3000)).startStreaming(22050)
    }

    @Test
    fun `releaseEngine 等完在途合成才释放引擎`() = runBlocking<Unit> {
        whenever(settings.isTtsMainEnabled()).thenReturn(true)
        whenever(settings.getTtsLengthScale()).thenReturn(1.0)
        readyModel()

        val synthEntered = CountDownLatch(1)
        val allowSynthFinish = CountDownLatch(1)
        val engine = FakeStreamingEngine(beforeEachChunk = {
            synthEntered.countDown()
            allowSynthFinish.await(5, TimeUnit.SECONDS)
        })
        val mgr = manager(engine)
        mgr.speak(message("你好"), TtsManager.Source.MAIN)
        assertTrue("合成应已开始", synthEntered.await(3, TimeUnit.SECONDS))

        val released = CompletableDeferred<Unit>()
        launch(Dispatchers.IO) {
            mgr.releaseEngine()
            released.complete(Unit)
        }
        delay(200)
        assertFalse("在途合成未结束不应释放引擎", engine.releaseCalls.get())

        allowSynthFinish.countDown()
        withTimeout(3000) { released.await() }
        assertTrue("合成结束后应释放引擎", engine.releaseCalls.get())
    }
}
