package com.meapet.mobile.tts.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * [TtsAudioPlayer] 的纯 JVM 单测：只覆盖不依赖 AudioTrack 的 PCM 转换。
 *
 * [floatToPcm16] 之所以抽成顶层函数，就是为了让这段数值逻辑能在 JVM 上直接测
 * （Android 的 AudioTrack 在单测里是 stub，无法验证波形）。
 */
class TtsAudioPlayerTest {

    @Test
    fun `浮点波形转 PCM16 并裁剪越界值`() {
        // 0.5 * 32767 = 16383.5，Kotlin toInt() 截断 → 16383；±2 先裁剪到 ±1
        val pcm = floatToPcm16(floatArrayOf(0f, 0.5f, -0.5f, 2f, -2f))
        assertArrayEquals(shortArrayOf(0, 16383, -16383, 32767, -32767), pcm)
    }

    @Test
    fun `空波形返回空数组`() {
        assertArrayEquals(shortArrayOf(), floatToPcm16(FloatArray(0)))
    }

    @Test
    fun `端点值映射到 PCM16 满量程`() {
        assertArrayEquals(shortArrayOf(32767, -32767), floatToPcm16(floatArrayOf(1f, -1f)))
    }
}
