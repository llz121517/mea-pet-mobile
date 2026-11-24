package com.meapet.mobile.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI 可见性过滤：tool 与「纯 tool_calls 的 assistant」必须被挡在列表外，其余保留。 */
class ChatMessageTest {

    @Test
    fun `tool 消息不可见`() {
        val tool = ChatMessage(
            role = ChatRole.tool,
            content = """{"ok":true}""",
            toolCallId = "call_1",
            toolName = "search_memory"
        )
        assertTrue(tool.isTool)
        assertFalse(tool.isVisibleInUi)
    }

    @Test
    fun `content 为空的 assistant 不可见`() {
        val pureToolCalls = ChatMessage(
            role = ChatRole.assistant,
            content = "",
            rawAssistantJson = """{"role":"assistant","content":null,"tool_calls":[]}"""
        )
        assertFalse(pureToolCalls.isVisibleInUi)

        // 只有空白也属于空气泡
        assertFalse(ChatMessage(role = ChatRole.assistant, content = "  \n").isVisibleInUi)
    }

    @Test
    fun `system user 与有正文的 assistant 可见`() {
        assertTrue(ChatMessage(role = ChatRole.system, content = "系统提示").isVisibleInUi)
        assertTrue(ChatMessage(role = ChatRole.user, content = "你好").isVisibleInUi)
        assertTrue(ChatMessage(role = ChatRole.assistant, content = "喵").isVisibleInUi)
    }

    @Test
    fun `forUi 过滤 tool 与空 assistant 且保持原顺序`() {
        val user = ChatMessage(role = ChatRole.user, content = "问")
        val toolCallAssistant = ChatMessage(role = ChatRole.assistant, content = "")
        val tool = ChatMessage(role = ChatRole.tool, content = "{}", toolCallId = "c1")
        val fragment = ChatMessage(role = ChatRole.assistant, content = "片段")
        val final = ChatMessage(role = ChatRole.assistant, content = "最终")

        val visible = listOf(user, toolCallAssistant, tool, fragment, final).forUi()

        assertEquals(listOf(user, fragment, final), visible)
    }
}
