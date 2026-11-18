package com.meapet.mobile.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ConversationManager 组装 API 请求的单元测试。
 *
 * 重点是记忆协议块的回贴：历史里存的是剥离过块的可见正文，直接发出去，
 * 模型看到的就是「我过去每轮都没输出这个块」，会照着自己漏掉。
 */
class ConversationManagerTest {

    private fun block(json: String) = "```memory-ops\n$json\n```"

    private fun managerWith(vararg turns: Pair<String, String?>): ConversationManager {
        val mgr = ConversationManager()
        turns.forEachIndexed { i, (reply, ops) ->
            mgr.addMessage(ChatMessage(role = ChatRole.user, content = "问题$i"))
            mgr.addMessage(
                ChatMessage(role = ChatRole.assistant, content = reply, memoryOpsBlock = ops)
            )
        }
        return mgr
    }

    // ── 消息分层（静态前缀 + 尾部易变块）────────────────

    @Test
    fun stableContextGoesInHeadSystemAndTailGoesAfterHistory() {
        val mgr = ConversationManager()
        mgr.addMessage(ChatMessage(role = ChatRole.user, content = "你好"))

        val sent = mgr.buildApiMessages(
            systemPrompt = "你是梅尔",
            stableContext = "【记忆协议】…",
            tailFragments = listOf("【当前时间】现在")
        )

        assertEquals(2, sent.size)
        assertEquals(ChatRole.system, sent[0].role)
        assertEquals("你是梅尔\n\n【记忆协议】…", sent[0].content)
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

    @Test
    fun memoryOpsBlockIsAppendedBackToRecentAssistantTurns() {
        val mgr = managerWith("回复A" to block("[]"), "回复B" to block("""[{"op":"create"}]"""))

        val sent = mgr.buildApiMessages(systemPrompt = "p", memoryOpsEchoTurns = 5)
        val assistants = sent.filter { it.role == ChatRole.assistant }

        assertEquals("回复A\n\n${block("[]")}", assistants[0].content)
        assertEquals("回复B\n\n${block("""[{"op":"create"}]""")}", assistants[1].content)
    }

    @Test
    fun echoIsLimitedToMostRecentTurns() {
        val mgr = managerWith(
            "最旧" to block("[1]"),
            "中间" to block("[2]"),
            "最新" to block("[3]")
        )

        val assistants = mgr.buildApiMessages(systemPrompt = "p", memoryOpsEchoTurns = 2)
            .filter { it.role == ChatRole.assistant }

        assertEquals("最旧", assistants[0].content, "超出回贴条数的旧消息应保持原样")
        assertTrue(assistants[1].content.endsWith(block("[2]")))
        assertTrue(assistants[2].content.endsWith(block("[3]")))
    }

    @Test
    fun echoDisabledLeavesHistoryUntouched() {
        val mgr = managerWith("回复A" to block("[]"))

        val assistant = mgr.buildApiMessages(systemPrompt = "p", memoryOpsEchoTurns = 0)
            .first { it.role == ChatRole.assistant }

        assertEquals("回复A", assistant.content)
    }

    @Test
    fun echoDoesNotMutateStoredHistory() {
        val mgr = managerWith("回复A" to block("[]"))

        mgr.buildApiMessages(systemPrompt = "p", memoryOpsEchoTurns = 5)

        val stored = mgr.getMessages().first { it.role == ChatRole.assistant }
        assertEquals("回复A", stored.content, "回贴只作用于发出去的副本，不能污染会话历史")
        assertFalse(mgr.getMessages().any { it.content.contains("```") })
    }

    @Test
    fun turnsWithoutBlockAreLeftAloneAndDoNotConsumeQuota() {
        // 中间那轮模型漏了块：不该凭空补，也不该占掉回贴名额
        val mgr = managerWith("有块1" to block("[1]"), "漏了" to null, "有块2" to block("[2]"))

        val assistants = mgr.buildApiMessages(systemPrompt = "p", memoryOpsEchoTurns = 2)
            .filter { it.role == ChatRole.assistant }

        assertTrue(assistants[0].content.endsWith(block("[1]")))
        assertEquals("漏了", assistants[1].content)
        assertTrue(assistants[2].content.endsWith(block("[2]")))
    }

    @Test
    fun noSyntheticSeedWhenHistoryHasNoRealBlocks() {
        // 冷启动 / 前几轮全漏：不再注入合成 few-shot，只回贴真实块
        val cold = ConversationManager()
        cold.addMessage(ChatMessage(role = ChatRole.user, content = "你好"))
        val coldSent = cold.buildApiMessages(systemPrompt = "p", memoryOpsEchoTurns = 3)
        assertEquals(2, coldSent.size)
        assertEquals("你好", coldSent[1].content)
        assertFalse(coldSent.any { it.content.contains("```") })

        val leaked = managerWith("漏了1" to null, "漏了2" to null)
        val assistants = leaked.buildApiMessages(systemPrompt = "p", memoryOpsEchoTurns = 3)
            .filter { it.role == ChatRole.assistant }
        assertEquals(listOf("漏了1", "漏了2"), assistants.map { it.content })
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
