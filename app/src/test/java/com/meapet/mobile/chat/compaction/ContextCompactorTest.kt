package com.meapet.mobile.chat.compaction

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextCompactorTest {

    private fun turn(i: Int): List<ChatMessage> = listOf(
        ChatMessage(role = ChatRole.user, content = "问题$i", id = "u$i"),
        ChatMessage(role = ChatRole.assistant, content = "回答$i", id = "a$i")
    )

    private fun messages(turns: Int): List<ChatMessage> = (1..turns).flatMap { turn(it) }

    @Test
    fun `切点按轮对齐且至少保留最近 N 轮`() {
        val msgs = messages(10)
        // 10 轮、保留 4 轮 → 折叠前 6 轮 = 12 条
        assertEquals(12, foldCountFor(msgs, minKeepTurns = 4))
        // 折叠点落在 assistant 结束处（下一条是 user）
        assertEquals(ChatRole.assistant, msgs[foldCountFor(msgs, 4) - 1].role)
    }

    @Test
    fun `轮数不足时不折叠`() {
        assertEquals(0, foldCountFor(messages(4), minKeepTurns = 4))
        assertEquals(0, foldCountFor(emptyList(), minKeepTurns = 4))
    }

    @Test
    fun `窗口丢弃只按轮粒度且不会切开发言`() {
        val msgs = messages(6)
        val estimator = TokenEstimator()
        // 预算刚好只够最后一轮（2 条 × 7 token = 14）：丢到最后一轮起点
        assertEquals(10, dropCountToFit(msgs, estimator, budget = 20))

        // 预算小于任何一轮：全部丢弃（极小窗口兜底）
        assertEquals(msgs.size, dropCountToFit(msgs, estimator, budget = 1))

        val generous = dropCountToFit(msgs, estimator, budget = Int.MAX_VALUE)
        assertEquals(0, generous, "预算充足时不该丢弃")
    }

    @Test
    fun `切点不会落在工具 turn 中间`() {
        // 每轮：user → assistant(tool_calls) → tool → assistant(最终)。共 4 条/轮
        fun toolTurn(i: Int): List<ChatMessage> = listOf(
            ChatMessage(role = ChatRole.user, content = "问题$i", id = "u$i"),
            ChatMessage(
                role = ChatRole.assistant, content = "", id = "a$i",
                rawAssistantJson = """{"role":"assistant","tool_calls":[{"id":"c$i"}]}"""
            ),
            ChatMessage(role = ChatRole.tool, content = """{"ok":true}""", id = "t$i", toolCallId = "c$i"),
            ChatMessage(role = ChatRole.assistant, content = "回答$i", id = "f$i")
        )
        val msgs = (1..10).flatMap { toolTurn(it) }

        // 保留最近 4 轮 → 折叠前 6 轮 = 24 条；切点必须落在 user 消息上
        val fold = foldCountFor(msgs, minKeepTurns = 4)
        assertEquals(24, fold)
        assertEquals(ChatRole.user, msgs[fold].role, "切点（下一条保留消息）必须是 user")
        assertEquals(ChatRole.assistant, msgs[fold - 1].role, "折叠末尾应是上一轮的最终 assistant")

        // 按预算丢弃时同样只能丢整轮：预算刚好够最近 3 轮（12 条）
        val estimator = TokenEstimator()
        val budget = estimator.estimate(msgs.takeLast(12))
        val drop = dropCountToFit(msgs, estimator, budget)
        assertEquals(28, drop)
        assertEquals(ChatRole.user, msgs[drop].role)
    }

    @Test
    fun `压缩成功返回摘要正文并带上旧摘要与新内容`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"  旧摘要+新事实  "}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )

        val summary = compactor.compact(messages(6), previousSummary = "旧摘要", foldCount = 4)

        assertEquals("旧摘要+新事实", summary, "应去掉首尾空白")
        assertTrue(seenBody.contains("旧摘要"), "旧摘要应进入提示词")
        assertTrue(seenBody.contains("问题1"), "被折叠的消息应进入提示词")
    }

    @Test
    fun `请求失败时返回 null 由调用方兜底`() = runTest {
        val engine = FakeHttpClientEngine {
            HttpResponse(500, """{"error":{"message":"boom"}}""".encodeToByteArray(), "application/json")
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )

        assertNull(compactor.compact(messages(6), previousSummary = "", foldCount = 4))
    }

    @Test
    fun `foldCount 为 0 时直接返回 null 不发请求`() = runTest {
        val engine = FakeHttpClientEngine {
            HttpResponse(200, "{}".encodeToByteArray(), "application/json")
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )

        assertNull(compactor.compact(messages(3), "", 0))
        assertEquals(0, engine.requests.size)
    }
}
