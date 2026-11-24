package com.meapet.mobile.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏回归测试。
 *
 * 背景：kotlinx.serialization 的解码异常会把原始 JSON 拼进 message
 * （"JSON input: ..."），若直接打印 e.message 或用 Log.e(tag, msg, e) 打异常
 * 对象，就会把对话正文写进 logcat，并随「导出日志」外发。
 */
class LogSafetyTest {

    /** 真实构造一个 JsonDecodingException，确认它确实会把输入拼进 message。 */
    private fun realDecodingException(payload: String): Throwable =
        runCatching { Json.parseToJsonElement(payload) }.exceptionOrNull()
            ?: error("期望解析失败")

    @Test
    fun `序列化异常确实会把原始 JSON 拼进 message（前提校验）`() {
        val secret = "用户的隐私对话正文"
        val malformed = """{"content":"$secret"}""".replace("\"content\"", "content")
        val e = realDecodingException(malformed)

        // 确认这个前提成立：异常消息里带 JSON input，且含正文
        assertTrue(e is SerializationException)
        assertTrue(e.message.orEmpty().contains("JSON input"))
        assertTrue(e.message.orEmpty().contains(secret))
    }

    @Test
    fun `safeLogMessage 截掉 JSON input 之后的内容`() {
        val secret = "用户的隐私对话正文"
        val malformed = """{"content":"$secret"}""".replace("\"content\"", "content")
        val e = realDecodingException(malformed)

        val safe = e.safeLogMessage()

        assertFalse("不得包含正文", safe.contains(secret))
        assertFalse("不得包含 JSON input 段", safe.contains("JSON input"))
        // 排障信息要保留：offset / 结构位置
        assertTrue("应保留 offset", safe.contains("offset"))
    }

    @Test
    fun `safeLogMessage 对普通异常原样返回消息`() {
        val e = IllegalStateException("普通错误：读写失败")
        assertEquals("普通错误：读写失败", e.safeLogMessage())
    }

    @Test
    fun `safeLogMessage 消息为空时退回类名`() {
        val e = RuntimeException()
        assertEquals("RuntimeException", e.safeLogMessage())
    }

    @Test
    fun `safeLogMessage 超长消息会截断`() {
        val e = IllegalStateException("x".repeat(500))
        val safe = e.safeLogMessage(maxLen = 100)
        assertTrue(safe.endsWith("…"))
        assertTrue(safe.length <= 101)
    }

    @Test
    fun `手工构造带 JSON input 标记的异常也会被截断`() {
        val secret = "记忆内容或网页正文"
        val e = IllegalStateException(
            "Unexpected JSON token at offset 3: 某错误 at path: \$\nJSON input: {\"text\":\"$secret\"}"
        )
        val safe = e.safeLogMessage()

        assertFalse(safe.contains(secret))
        assertTrue(safe.contains("offset 3"))
    }
}
