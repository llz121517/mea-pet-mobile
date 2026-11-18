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
}
