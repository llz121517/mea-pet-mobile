package com.meapet.mobile.client.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 工具调用响应解析：content 可为空、arguments 是 JSON 字符串、原始 message 保留。 */
class ApiResponseToolCallTest {

    @Test
    fun `解析 tool_calls 与 finish_reason`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"","tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"search_memory","arguments":"{\"query\":\"论文\"}"}},
              {"id":"call_2","type":"function","function":{"name":"search_memory","arguments":"{\"query\":\"炸鸡\"}"}}
            ],"reasoning_content":"思考中"},"finish_reason":"tool_calls"}]}
        """.trimIndent()

        val parsed = ApiResponse.chatCompletionMessage(body)!!

        assertEquals("", parsed.content)
        assertEquals("tool_calls", parsed.finishReason)
        assertEquals(listOf("call_1", "call_2"), parsed.toolCalls.map { it.id })
        assertEquals("search_memory", parsed.toolCalls[0].name)
        assertEquals("""{"query":"论文"}""", parsed.toolCalls[0].arguments)
        // 原始 message 必须保留 reasoning_content，供下一轮原样回传
        assertTrue(parsed.raw.containsKey("reasoning_content"))
        assertTrue(parsed.raw.containsKey("tool_calls"))
    }

    @Test
    fun `arguments 直接给对象时也能解析成字符串`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"search_memory","arguments":{"query":"论文"}}}
            ]}}]}
        """.trimIndent()

        val parsed = ApiResponse.chatCompletionMessage(body)!!
        assertNull(parsed.content)
        assertTrue(parsed.toolCalls.single().arguments.contains("论文"))
    }

    @Test
    fun `普通文本响应解析为无工具调用`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"你好喵"},"finish_reason":"stop"}]}"""

        val parsed = ApiResponse.chatCompletionMessage(body)!!
        assertEquals("你好喵", parsed.content)
        assertTrue(parsed.toolCalls.isEmpty())
        assertEquals("stop", parsed.finishReason)
    }

    @Test
    fun `结构不符返回 null`() {
        assertNull(ApiResponse.chatCompletionMessage("{}"))
        assertNull(ApiResponse.chatCompletionMessage("not json"))
    }
}
