package com.meapet.mobile.chat.compaction

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenEstimatorTest {

    private fun msg(content: String, role: ChatRole = ChatRole.user) =
        ChatMessage(role = role, content = content)

    @Test
    fun `中文按字计、英文按比例计、每条消息有结构开销`() {
        val e = TokenEstimator()
        // 10 个汉字 → 约 10 token，加 4 结构开销
        assertEquals(14, e.rawTokens(msg("一二三四五六七八九十")))
        // 40 个英文字母 → 40 * 0.25 = 10，加 4
        assertEquals(14, e.rawTokens(msg("a".repeat(40))))
    }

    @Test
    fun `未校准前 estimate 等于 raw 之和`() {
        val e = TokenEstimator()
        val messages = listOf(msg("你好"), msg("hello", ChatRole.assistant))
        assertEquals(messages.sumOf { e.rawTokens(it) }, e.estimate(messages))
        assertEquals(1.0, e.ratio, 0.0001)
    }

    @Test
    fun `校准后按比值放大并随新观测平滑移动`() {
        val e = TokenEstimator(smoothing = 0.3)
        e.calibrate(localEstimate = 100, promptTokens = 200)
        // observed = 2.0，夹在上限内；ratio = 1*(1-0.3) + 2*0.3 = 1.3
        assertEquals(1.3, e.ratio, 0.0001)

        val messages = listOf(msg("你好"))
        val raw = messages.sumOf { e.rawTokens(it) }
        assertTrue(e.estimate(messages) > raw, "校准后估算应变大")
    }

    @Test
    fun `校准比值被夹在 0_5 到 2_0 之间`() {
        val e = TokenEstimator(smoothing = 1.0)
        e.calibrate(localEstimate = 100, promptTokens = 10_000)
        assertEquals(2.0, e.ratio, 0.0001)
        e.calibrate(localEstimate = 100, promptTokens = 1)
        assertEquals(0.5, e.ratio, 0.0001)
    }

    @Test
    fun `无效输入不改变比值`() {
        val e = TokenEstimator()
        e.calibrate(localEstimate = 0, promptTokens = 100)
        e.calibrate(localEstimate = 100, promptTokens = 0)
        assertEquals(1.0, e.ratio, 0.0001)
    }
}
