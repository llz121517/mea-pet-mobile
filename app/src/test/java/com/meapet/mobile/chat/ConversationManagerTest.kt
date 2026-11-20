package com.meapet.mobile.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ConversationManager 组装 API 请求的单元测试。
 *
 * 覆盖稳定前缀、请求尾部只读信息、窗口裁剪与工具历史的发送副本清洗。
 */
class ConversationManagerTest {

    // ── 消息分层（静态前缀 + 尾部易变块）────────────────

    @Test
    fun stableContextGoesInHeadSystemAndTailGoesAfterHistory() {
        val mgr = ConversationManager()
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "你好"))

        val sent = mgr.buildApiMessages(
            systemPrompt = "你是梅尔",
            stableContext = "【只读记忆】…",
            tailFragments = listOf("【当前时间】现在")
        )

        assertEquals(2, sent.size)
        assertEquals(ChatRole.system, sent[0].role)
        assertEquals("你是梅尔\n\n【只读记忆】…", sent[0].content)
        assertEquals(ChatRole.user, sent[1].role)
        // 每轮都变的内容必须排在历史之后，否则它之后的一切都无法命中 prefix cache
        assertEquals(1, sent.count { it.role == ChatRole.system })
        assertEquals("你好\n\n$TAIL_LABEL\n【当前时间】现在", sent[1].content)
    }

    @Test
    fun blankTailProducesNoTrailingMessage() {
        val mgr = ConversationManager()
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "你好"))

        val sent = mgr.buildApiMessages(systemPrompt = "你是梅尔", tailFragments = listOf("  "))

        assertEquals(2, sent.size)
        assertEquals(ChatRole.user, sent.last().role)
    }

    @Test
    fun blankSectionsAreSkippedInSystemMessage() {
        val mgr = ConversationManager()
        val system = mgr.buildApiMessages(systemPrompt = "你是梅尔").first()
        assertEquals("你是梅尔", system.content)
    }

    // ── 窗口裁剪 ──────────────────────────────────────

    @Test
    fun trimRemovesABatchSoThePrefixStaysStableForSeveralTurns() {
        val mgr = ConversationManager(maxSize = 10, trimBatch = 4)
        repeat(10) { mgr.addMessage(ChatMessage(role = ChatRole.user, content = "m$it")) }
        assertEquals(10, mgr.size)

        // 第 11 条触发裁剪：一次砍到 maxSize-trimBatch = 6，而不是只砍 1 条
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "m10"))
        assertEquals(6, mgr.size)
        assertEquals("m5", mgr.getMessages().first().content, "应从最老的开始丢")

        // 之后连续几轮不再裁剪，前缀保持不变
        val prefixAfterTrim = mgr.getMessages().map { it.content }
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "m11"))
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "m12"))
        assertEquals(8, mgr.size)
        assertEquals(prefixAfterTrim, mgr.getMessages().take(prefixAfterTrim.size).map { it.content })
    }

    // ── 工具历史窗口 ──────────────────────────────────

    /** 一个完整的工具 turn：user → assistant(tool_calls) → tool → assistant(最终)。 */
    private fun toolTurn(turnId: String = "turn-1"): List<ChatMessage> {
        val raw = """{"role":"assistant","content":"","tool_calls":[""" +
            """{"id":"call_1","type":"function","function":{"name":"search_memory","arguments":"{}"}}]}"""
        return listOf(
            ChatMessage(role = ChatRole.user, content = "问", turnId = turnId),
            ChatMessage(
                role = ChatRole.assistant, content = "", turnId = turnId,
                rawAssistantJson = raw
            ),
            ChatMessage(
                role = ChatRole.tool, content = """{"ok":true}""", turnId = turnId,
                toolCallId = "call_1", toolName = "search_memory"
            ),
            ChatMessage(
                role = ChatRole.assistant, content = "答", turnId = turnId,
                rawAssistantJson = """{"role":"assistant","content":"答"}"""
            )
        )
    }

    @Test
    fun includeToolHistoryKeepsToolMessagesAndRawJson() {
        val mgr = ConversationManager()
        mgr.addMessages(toolTurn())

        val sent = mgr.buildApiMessages(systemPrompt = "p", includeToolHistory = true)

        assertEquals(
            listOf(ChatRole.system, ChatRole.user, ChatRole.assistant, ChatRole.tool, ChatRole.assistant),
            sent.map { it.role }
        )
        assertTrue(sent[2].rawAssistantJson!!.contains("tool_calls"))
        assertEquals("call_1", sent[3].toolCallId)
    }

    @Test
    fun excludeToolHistoryFiltersToolMessagesAndRawJson() {
        val mgr = ConversationManager()
        mgr.addMessages(toolTurn())

        val sent = mgr.buildApiMessages(systemPrompt = "p", includeToolHistory = false)

        // 纯 tool_calls 的空 assistant 与 tool 消息都被丢弃，只留正文
        assertEquals(listOf(ChatRole.system, ChatRole.user, ChatRole.assistant), sent.map { it.role })
        assertEquals("答", sent[2].content)
        assertEquals(null, sent[2].rawAssistantJson, "文本协议不能带 assistant 原始 JSON")
    }

    @Test
    fun orphanToolMessageAtWindowStartIsDropped() {
        val mgr = ConversationManager()
        val raw = """{"role":"assistant","content":"","tool_calls":[""" +
            """{"id":"call_1","type":"function","function":{"name":"search_memory","arguments":"{}"}}]}"""
        // 窗口被裁到 turn 中间：开头是一条找不到声明的 tool 消息
        mgr.addMessages(
            listOf(
                ChatMessage(role = ChatRole.tool, content = "{}", toolCallId = "call_orphan"),
                ChatMessage(
                    role = ChatRole.assistant, content = "",
                    rawAssistantJson = raw
                ),
                ChatMessage(role = ChatRole.tool, content = """{"ok":true}""", toolCallId = "call_1"),
                ChatMessage(role = ChatRole.user, content = "继续")
            )
        )

        val sent = mgr.buildApiMessages(systemPrompt = "p", includeToolHistory = true)

        // 孤立 tool 被清洗掉；有声明且 id 匹配的 tool 保留
        assertEquals(listOf(ChatRole.system, ChatRole.assistant, ChatRole.tool, ChatRole.user), sent.map { it.role })
        assertEquals("call_1", sent[2].toolCallId)
    }

    @Test
    fun toolMessageWhoseDeclarationIsMissingIsDropped() {
        val mgr = ConversationManager()
        mgr.addMessages(
            listOf(
                ChatMessage(role = ChatRole.user, content = "问"),
                ChatMessage(role = ChatRole.tool, content = "{}", toolCallId = "call_x"),
                ChatMessage(role = ChatRole.user, content = "再问")
            )
        )

        val sent = mgr.buildApiMessages(systemPrompt = "p", includeToolHistory = true)

        assertEquals(listOf(ChatRole.system, ChatRole.user, ChatRole.user), sent.map { it.role })
    }

    // ── 撤销：整体替换历史 ────────────────────────────

    @Test
    fun replaceAllRestoresSnapshotVerbatim() {
        val mgr = ConversationManager()
        val snapshot = listOf(
            ChatMessage(role = ChatRole.user, content = "旧问题", timestamp = 1_000L),
            ChatMessage(role = ChatRole.assistant, content = "旧回答", timestamp = 2_000L),
        )

        mgr.replaceAll(snapshot)

        // 撤销要的是「回到清空前的精确状态」：顺序、条数、内容都不能被合并策略改动
        assertEquals(snapshot, mgr.getMessages())
    }

    @Test
    fun replaceAllDiscardsCurrentMessages() {
        val mgr = ConversationManager()
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "清空后残留"))

        mgr.replaceAll(emptyList())

        assertTrue(mgr.getMessages().isEmpty())
    }

    @Test
    fun replaceAllOverwritesInsteadOfAppending() {
        val mgr = ConversationManager()
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "残留"))
        val snapshot = listOf(ChatMessage(role = ChatRole.user, content = "恢复"))

        mgr.replaceAll(snapshot)

        assertEquals(snapshot, mgr.getMessages())
    }
}
