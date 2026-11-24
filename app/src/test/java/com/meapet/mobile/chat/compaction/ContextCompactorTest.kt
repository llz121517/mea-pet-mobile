package com.meapet.mobile.chat.compaction

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * 工具结果是机器 JSON（单条可达 1500 字符），进入摘要提示时必须截断：
     * 原样塞进去既贵又会淹没人话，摘要只需要「查了什么、大致得到什么」。
     */
    @Test
    fun `摘要输入里工具结果被截断为概览`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )
        val longToolResult = "X".repeat(800)
        val msgs = listOf(
            ChatMessage(role = ChatRole.user, content = "查一下", id = "u1"),
            ChatMessage(
                role = ChatRole.tool, content = longToolResult, id = "t1",
                toolCallId = "c1", toolName = "search_memory"
            )
        )

        compactor.compact(msgs, previousSummary = "", foldCount = 2)

        assertTrue(seenBody.contains("工具结果："), "工具消息应有可读角色标签")
        assertTrue(seenBody.contains("X".repeat(200) + "…"), "应保留前 200 字并加省略号")
        assertTrue(!seenBody.contains("X".repeat(300)), "不应把整段工具结果塞进摘要请求")
    }

    /**
     * 自动压缩按 `TARGET_RATIO` 选折叠量：只要能折到目标以下，就**尽量少折**，
     * 保留更多原文细节（旧实现一律折到只剩最近 4 轮）。
     */
    @Test
    fun `目标比例下选择能达标的最小折叠量`() {
        val msgs = messages(10)
        val estimator = TokenEstimator()
        val target = estimator.estimate(msgs.takeLast(16))

        val fold = foldCountFor(msgs, minKeepTurns = 4, targetTokens = target, estimator = estimator)

        assertEquals(4, fold, "折 2 轮（4 条）即可达标，不该折到 12 条")
        assertEquals(ChatRole.user, msgs[fold].role)
    }

    @Test
    fun `目标无法达成时折叠到保留轮数下限`() {
        val msgs = messages(10)
        val estimator = TokenEstimator()

        val fold = foldCountFor(msgs, minKeepTurns = 4, targetTokens = 1, estimator = estimator)

        assertEquals(12, fold, "折到底仍不达标 → 按上限折（保留最近 4 轮）")
    }

    // ── 摘要与保留窗口的断层（G15）────────────────────────────

    /**
     * 复现用户反馈的现象：折叠段里有「待办：买牛奶」，之后在保留窗口里已经「买好了」。
     *
     * 修复前摘要请求只含折叠段，模型压根看不到「买好了」，摘要里会一直留着「未完成」，
     * 而保留窗口的原文又说做完了 —— 模型看到自相矛盾的上下文。
     */
    @Test
    fun `摘要请求带上保留窗口的重叠区以判断状态是否过期`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )
        val msgs = listOf(
            ChatMessage(role = ChatRole.user, content = "帮我记一下待办：买牛奶", id = "u1"),
            ChatMessage(role = ChatRole.assistant, content = "记下啦", id = "a1"),
            ChatMessage(role = ChatRole.user, content = "牛奶买好了", id = "u2"),
            ChatMessage(role = ChatRole.assistant, content = "好耶", id = "a2")
        )

        // 折叠前 2 条（第 1 轮），保留窗口里是第 2 轮
        compactor.compact(msgs, previousSummary = "", foldCount = 2)

        assertTrue(seenBody.contains("【需要并入摘要的对话】"), "应仍有折叠段")
        assertTrue(seenBody.contains("牛奶买好了"), "保留窗口里已完成的事实必须进入摘要请求: $seenBody")
        assertTrue(
            seenBody.contains("用于判断哪些状态已经过期"),
            "重叠区应标明只用于判断状态，不写进摘要正文"
        )
        assertTrue(seenBody.contains("不要写进摘要正文"), "需明确禁止把重叠区内容写进摘要")
    }

    /** 折叠段已覆盖全部消息时没有重叠区，不应凭空造出这一段。 */
    @Test
    fun `折叠段覆盖全部消息时不附加重叠区`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )

        compactor.compact(messages(2), previousSummary = "", foldCount = 4)

        assertFalse(seenBody.contains("用于判断哪些状态已经过期"), "没有保留窗口就不该有重叠区")
    }

    /**
     * **回归**：完成事件落在保留窗口靠前的位置（不在最近几条里）也必须被带进摘要请求。
     *
     * 最初的实现按「最近 6 条」取重叠区，只能覆盖 1~3 轮；保留窗口有 4 轮以上时，
     * 第 1 轮的完成事件就被漏掉，等于没修好断层。
     */
    @Test
    fun `完成事件在保留窗口靠前位置时也能被看到`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )
        // 10 轮：第 1 轮留待办；第 2 轮（折叠点后第 1 轮）就完成了；
        // 折叠前 2 条 → 保留窗口是第 2~10 轮共 18 条，完成事件在窗口最前面，
        // 按「最近 6 条」取根本取不到它。
        val msgs = listOf(
            ChatMessage(role = ChatRole.user, content = "帮我记个待办：买牛奶", id = "u1"),
            ChatMessage(role = ChatRole.assistant, content = "记下啦", id = "a1"),
            ChatMessage(role = ChatRole.user, content = "牛奶已经买好了", id = "u2"),
            ChatMessage(role = ChatRole.assistant, content = "好，那就算完成", id = "a2")
        ) + (3..10).flatMap { i ->
            listOf(
                ChatMessage(role = ChatRole.user, content = "话题$i", id = "u$i"),
                ChatMessage(role = ChatRole.assistant, content = "回答$i", id = "a$i")
            )
        }

        compactor.compact(msgs, previousSummary = "", foldCount = 2)

        assertTrue(
            seenBody.contains("牛奶已经买好了"),
            "完成事件在第 2 轮，必须出现在重叠区里: $seenBody"
        )
    }

    /**
     * **回归**：重叠区不做任何截断。折叠点后第一轮的状态变化（「做完了」）在重叠区里是
     * 最旧的一条，按条数或字符截断丢弃最旧，恰好会把修复要抓的东西扔掉。
     */
    @Test
    fun `重叠区完整保留保留窗口不截断`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )
        // 折叠段 1 条；保留窗口 100 条、每条 300 字（合计 3 万字），远超任何合理字符上限
        val msgs = listOf(
            ChatMessage(role = ChatRole.user, content = "折叠段", id = "u0"),
        ) + (1..100).map {
            ChatMessage(role = ChatRole.user, content = "#$it " + "旧".repeat(300), id = "u$it")
        }

        compactor.compact(msgs, previousSummary = "", foldCount = 1)

        assertFalse(seenBody.contains("已省略"), "不该省略重叠区内容")
        assertTrue(seenBody.contains("#1 "), "折叠点后第一条（最旧的重叠区消息）必须保留: 未找到")
        assertTrue(seenBody.contains("#100 "), "最新一条也必须保留")
    }

    /**
     * 用户反馈的场景：待办在折叠段，完成事件落在保留窗口**最前面**。
     * 这是「按条数取最近 N 条」会漏掉的位置。
     */
    @Test
    fun `完成事件在保留窗口最前面时也能被看到`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )
        val msgs = listOf(
            ChatMessage(role = ChatRole.user, content = "帮我记个待办：买牛奶", id = "u1"),
            ChatMessage(role = ChatRole.assistant, content = "记下啦", id = "a1"),
            ChatMessage(role = ChatRole.user, content = "牛奶已经买好了", id = "u2"),
            ChatMessage(role = ChatRole.assistant, content = "好，那就算完成", id = "a2")
        ) + (3..12).flatMap { i ->
            listOf(
                ChatMessage(role = ChatRole.user, content = "话题$i", id = "u$i"),
                ChatMessage(role = ChatRole.assistant, content = "回答$i", id = "a$i")
            )
        }

        compactor.compact(msgs, previousSummary = "", foldCount = 2)

        assertTrue(
            seenBody.contains("牛奶已经买好了"),
            "完成事件在折叠点后第一轮，必须出现在重叠区里: $seenBody"
        )
    }

    /**
     * 修复前的 prompt 明确要求保留「进行中的话题」，这正是「未完成」类描述被写进摘要的来源。
     * 现在改成：不记进度状态，重要时须带时间锚。
     */
    @Test
    fun `摘要 prompt 不鼓励记录无时间锚的进度状态`() = runTest {
        var seenBody = ""
        val engine = FakeHttpClientEngine { req ->
            seenBody = (req.body as com.meapet.mobile.client.RequestBody.Json).content
            HttpResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                    .encodeToByteArray(),
                contentType = "application/json"
            )
        }
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )
        val fixed = ZonedDateTime.of(2026, 11, 24, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

        compactor.compact(messages(6), previousSummary = "", foldCount = 4, now = fixed)

        assertFalse(
            seenBody.contains("进行中的话题"),
            "不应再主动要求摘要记录「进行中的话题」——那正是过期状态的来源"
        )
        assertTrue(seenBody.contains("不记录「正在做 / 待办 / 尚未完成」"), "应禁止无时间锚的进度描述")
        assertTrue(seenBody.contains("2026-11-24"), "进度类描述应要求带截至日期: $seenBody")
    }
}
