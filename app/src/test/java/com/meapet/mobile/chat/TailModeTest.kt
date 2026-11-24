package com.meapet.mobile.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** tail 并入最后一条 user：只改发出副本、不进历史、不再产生尾部 system。 */
class TailModeTest {

    private fun mgr(): ConversationManager = ConversationManager().also {
        it.addMessage(ChatMessage(role = ChatRole.user, content = "你好"))
        it.addMessage(ChatMessage(role = ChatRole.assistant, content = "嗨"))
        it.addMessage(ChatMessage(role = ChatRole.user, content = "现在几点"))
    }

    @Test
    fun composeTailJoinsNonBlankFragmentsInOrderWithLabel() {
        assertEquals("$TAIL_LABEL\nA\nB", composeTail(listOf(" A ", "", "  ", "B")))
        assertEquals("", composeTail(listOf("", " ")))
    }

    @Test
    fun mergeUserAppendsTailToLastUserOnly() {
        val sent = mgr().buildApiMessages("p", tailFragments = listOf("T"))

        assertEquals(4, sent.size)
        assertEquals(1, sent.count { it.role == ChatRole.system })
        assertEquals("你好", sent[1].content)
        assertEquals("现在几点\n\n$TAIL_LABEL\nT", sent.last().content)
        assertEquals(ChatRole.user, sent.last().role)
    }

    @Test
    fun tailNeverEntersStoredHistory() {
        val m = mgr()
        m.buildApiMessages("p", tailFragments = listOf("T"))

        assertTrue(m.getMessages().none { it.content.contains(TAIL_LABEL) })
        assertEquals("现在几点", m.getMessages().last().content)
    }

    @Test
    fun separateUserAddsLabeledUserMessage() {
        val sent = mgr().buildApiMessages("p", tailFragments = listOf("T"), tailMode = TailMode.SEPARATE_USER)

        assertEquals(5, sent.size)
        assertEquals("现在几点", sent[3].content)
        assertEquals(ChatRole.user, sent[4].role)
        assertEquals("$TAIL_LABEL\nT", sent[4].content)
    }

    @Test
    fun emptyFragmentsLeaveMessagesUntouched() {
        val sent = mgr().buildApiMessages("p", tailFragments = emptyList())
        assertEquals(4, sent.size)
        assertEquals("现在几点", sent.last().content)
    }

    @Test
    fun prefixBeforeLastUserIsByteIdenticalAcrossDifferentTails() {
        val a = mgr().buildApiMessages("p", tailFragments = listOf("10:00"))
        val b = mgr().buildApiMessages("p", tailFragments = listOf("10:01"))
        assertEquals(a.dropLast(1).map { it.role to it.content }, b.dropLast(1).map { it.role to it.content })
    }

    @Test
    fun tailIsSkippedWhenLastMessageIsNotUser() {
        // 工具循环的请求序列里 assistant(tool_calls) 与 tool 必须紧邻：
        // 末条不是 user 时硬插 tail 会把 user 消息夹进工具调用与结果之间（服务端 400）。
        // 这里用「末条是 assistant」的最小场景验证 applyTail 的守卫
        val m = ConversationManager().also {
            it.addMessage(ChatMessage(role = ChatRole.user, content = "你好"))
            it.addMessage(ChatMessage(role = ChatRole.assistant, content = "嗨"))
        }

        val sent = m.buildApiMessages("p", tailFragments = listOf("T"))

        assertEquals(3, sent.size, "不应新增/改写任何消息")
        assertEquals(ChatRole.assistant, sent.last().role)
        assertEquals("嗨", sent.last().content, "tail 不能贴到非 user 消息上")
        assertTrue(m.getMessages().none { it.content.contains(TAIL_LABEL) })
    }
}
