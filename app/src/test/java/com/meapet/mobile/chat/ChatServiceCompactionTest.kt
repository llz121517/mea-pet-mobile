package com.meapet.mobile.chat

import com.meapet.mobile.chat.compaction.ContextCompactor
import com.meapet.mobile.chat.compaction.InMemoryCompactionStore
import com.meapet.mobile.chat.compaction.TokenEstimator
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.settings.ApiConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 压缩与聊天主链路的协作：摘要注入首条 system、已覆盖消息不再逐字发送、手动压缩、清除时复位。 */
class ChatServiceCompactionTest {

    private val summaryMarker = "对话历史摘要助手"

    private fun settings(): SettingsManager = mock<SettingsManager>().also {
        whenever(it.getApiConnection()).thenReturn(ApiConnection("https://example.org/v1", "fixture-key"))
        whenever(it.isMemoryEnabled()).thenReturn(true)
        whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
        whenever(it.getModel()).thenReturn("m")
        whenever(it.getTemperature()).thenReturn(0.7)
        whenever(it.getMaxTokens()).thenReturn(4096)
        // 记忆模式是枚举，mock 默认返回 null 会在 ChatService 解析模式时 NPE。
        // 这些用例只关心压缩链路，固定走文本协议，避免工具分支干扰
        whenever(it.getMemoryMode()).thenReturn(com.meapet.mobile.config.MemoryMode.TEXT)
    }

    /** 摘要请求返回固定摘要，聊天请求返回普通回复。 */
    private fun engine(summary: String = "摘要正文") = FakeHttpClientEngine { req ->
        val body = (req.body as RequestBody.Json).content
        val content = if (body.contains(summaryMarker)) summary else "收到喵"
        HttpResponse(
            statusCode = 200,
            body = """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}"""
                .encodeToByteArray(),
            contentType = "application/json"
        )
    }

    private fun service(
        cm: ConversationManager,
        store: InMemoryCompactionStore,
        engine: FakeHttpClientEngine,
        estimator: TokenEstimator = TokenEstimator(),
        budget: Int = 1_000_000
    ): ChatService {
        val compactor = ContextCompactor(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            modelProvider = { "m" }
        )
        return ChatService(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            conversationManager = cm,
            memoryManager = null,
            settingsManager = settings(),
            postProcessScope = CoroutineScope(Dispatchers.Unconfined),
            compactionStore = store,
            compactor = compactor,
            estimator = estimator,
            budgetProvider = { budget }
        )
    }

    private fun preload(cm: ConversationManager, turns: Int, contentLength: Int = 6) {
        repeat(turns) { i ->
            cm.addMessage(ChatMessage(role = ChatRole.user, content = "问题$i" + "文".repeat(contentLength)))
            cm.addMessage(ChatMessage(role = ChatRole.assistant, content = "回答$i" + "文".repeat(contentLength)))
        }
    }

    @Test
    fun `手动压缩成功后摘要写入存储并在下轮注入首条 system`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12)
        val store = InMemoryCompactionStore()
        val engine = engine()
        val svc = service(cm, store, engine)

        val result = svc.compactContext()

        assertTrue(result.isSuccess, "对话足够长时应压缩成功")
        assertEquals("摘要正文", store.load().summary)
        assertTrue(store.load().coveredCount > 0)

        // 下一轮请求：摘要进首条 system，已被覆盖的旧消息不再逐字发送
        val send = svc.sendMessage("继续聊")
        assertTrue(send.isSuccess)
        val body = (engine.requests.last().body as RequestBody.Json).content
        assertTrue(body.contains("【对话摘要】"), "摘要应注入首条 system: $body")
        assertTrue(body.contains("摘要正文"))
        assertFalse(body.contains("问题1文"), "已覆盖的消息不该再发送: $body")
        assertTrue(body.contains("问题11文"), "最近的轮次应保留原文: $body")
    }

    @Test
    fun `对话太短时手动压缩给出可展示的失败原因且不发请求`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 3)
        val store = InMemoryCompactionStore()
        val engine = engine()
        val svc = service(cm, store, engine)

        val result = svc.compactContext()

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
        assertEquals(0, engine.requests.size, "不该为太短的对话发摘要请求")
    }

    @Test
    fun `上下文逼近预算时自动压缩兜底`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12, contentLength = 300)
        val store = InMemoryCompactionStore()
        val engine = engine()
        // 预算大于小窗口保护阈值、但明显小于当前上下文（≈7.7k token）
        val svc = service(cm, store, engine, budget = 7000)

        val result = svc.sendMessage("再来一句")

        assertTrue(result.isSuccess)
        assertTrue(store.load().hasSummary, "触发硬阈值时应自动压缩")
    }

    @Test
    fun `清除对话同时复位压缩状态`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12)
        val store = InMemoryCompactionStore()
        val engine = engine()
        val svc = service(cm, store, engine)
        svc.compactContext()
        assertTrue(store.load().hasSummary)

        svc.clearHistory()

        assertFalse(store.load().hasSummary)
        assertFalse(svc.compaction().hasSummary)
        assertEquals(0, svc.currentContextTokens())
    }

    @Test
    fun `没有压缩存储时手动压缩返回失败而不崩`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12)
        val engine = engine()
        val svc = ChatService(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            conversationManager = cm,
            memoryManager = null,
            settingsManager = settings(),
            postProcessScope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertTrue(svc.compactContext().isFailure)
    }

    /**
     * 注入侧必须有冲突优先级约定（G15 成因④）：摘要是压缩那一刻的快照，保留窗口里可能已经
     * 完成/推翻它记录的事。不写「以后文为准」，模型面对矛盾结论只能猜。
     */
    @Test
    fun `摘要注入时声明与后文冲突以后文为准`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12)
        val store = InMemoryCompactionStore()
        val engine = engine()
        val svc = service(cm, store, engine)

        svc.compactContext()
        svc.sendMessage("继续聊")

        val body = (engine.requests.last().body as RequestBody.Json).content
        assertTrue(
            body.contains("若与后文原文冲突，以后文原文为准"),
            "摘要头必须声明冲突时的优先级: $body"
        )
    }

    /** 摘要头带上生成日期，模型才能判断这条进度描述是否已经过期。 */
    @Test
    fun `摘要注入时带上截至日期`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12)
        // 固定一个已知时间点，断言日期串出现在摘要头
        val fixedAt = java.time.LocalDate.of(2026, 11, 24)
            .atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val store = InMemoryCompactionStore()
        val engine = engine()
        val svc = service(cm, store, engine)

        svc.compactContext()
        // 把 updatedAt 改成固定值再发下一轮（真实链路里由压缩时间写入）
        store.save(store.load().copy(updatedAt = fixedAt))
        // compactionState 在内存里有缓存，重建 service 让它重新 load
        val svc2 = service(cm, store, engine)
        svc2.sendMessage("继续聊")

        val body = (engine.requests.last().body as RequestBody.Json).content
        assertTrue(body.contains("截至 2026-11-24"), "摘要头应带生成日期: $body")
    }

    /**
     * 重叠区不进入聊天请求只影响摘要请求。
     */
    @Test
    fun `重叠区不进入聊天请求只影响摘要请求`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12)
        val store = InMemoryCompactionStore()
        val engine = engine()
        val svc = service(cm, store, engine)

        svc.compactContext()
        val summaryReqBody = (engine.requests.last().body as RequestBody.Json).content
        svc.sendMessage("继续聊")
        val chatReqBody = (engine.requests.last().body as RequestBody.Json).content

        assertTrue(
            summaryReqBody.contains("用于判断哪些状态已经过期"),
            "摘要请求应带重叠区"
        )
        assertFalse(
            chatReqBody.contains("用于判断哪些状态已经过期"),
            "重叠区只用于摘要请求，不该进入聊天请求: $chatReqBody"
        )
    }

    // ── 压缩互斥（G5）────────────────────────────────────────

    /**
     * 两个手动压缩并发时**必须串行**：第二个要等第一个完整走完才读状态，
     * 否则后写的一方会用旧快照覆盖先写的结果，摘要与覆盖点回退。
     */
    @Test
    fun `两个手动压缩并发时串行执行且不丢摘要`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 14)
        val store = InMemoryCompactionStore()
        val gate = CompletableDeferred<Unit>()
        var summaryCalls = 0
        val engine = FakeHttpClientEngine { req ->
            val body = (req.body as RequestBody.Json).content
            if (body.contains(summaryMarker)) {
                val n = ++summaryCalls
                // 第一个摘要请求挂住，让第二个有机会抢进来
                if (n == 1) gate.await()
                HttpResponse(
                    statusCode = 200,
                    body = """{"choices":[{"message":{"role":"assistant","content":"摘要$n"}}]}"""
                        .encodeToByteArray(),
                    contentType = "application/json"
                )
            } else {
                HttpResponse(
                    statusCode = 200,
                    body = """{"choices":[{"message":{"role":"assistant","content":"收到喵"}}]}"""
                        .encodeToByteArray(),
                    contentType = "application/json"
                )
            }
        }
        val svc = service(cm, store, engine)

        val first = async(Dispatchers.Default) { svc.compactContext() }
        // 等第一个进入摘要请求
        while (summaryCalls == 0) yield()
        val second = async(Dispatchers.Default) { svc.compactContext() }
        // 给第二个足够时间去抢锁（若没互斥，它会并发进入并读到旧状态）
        repeat(20) { yield() }
        assertEquals(1, summaryCalls, "第二个压缩必须等第一个做完才开始")

        gate.complete(Unit)
        val r1 = first.await()
        val r2 = second.await()

        assertTrue(r1.isSuccess, "第一个压缩应成功")
        // 第二个拿到的是第一个折叠后的新状态：剩余轮数不足时会以「对话还不够长」失败，
        // 这是正确行为——关键是它**没有**用旧快照把摘要覆盖回去
        assertTrue(
            r2.isFailure,
            "第一个已折到只剩保留轮数，第二个应报『对话还不够长』而不是再折一次"
        )
        assertEquals(1, summaryCalls, "第二个不该发出摘要请求")
        // 关键：摘要仍是第一个写入的那份，没有被旧快照回退
        val finalState = store.load()
        assertEquals("摘要1", finalState.summary, "摘要不该被并发写入覆盖")
    }

    /**
     * 压缩期间历史被清除：结果必须作废，不能把旧摘要写回刚清空的库（G2 同源）。
     */
    @Test
    fun `压缩期间清除历史则结果作废`() = runTest {
        val cm = ConversationManager()
        preload(cm, turns = 12)
        val store = InMemoryCompactionStore()
        val gate = CompletableDeferred<Unit>()
        var entered = false
        val engine = FakeHttpClientEngine { req ->
            val body = (req.body as RequestBody.Json).content
            if (body.contains(summaryMarker)) {
                entered = true
                gate.await()
                HttpResponse(
                    statusCode = 200,
                    body = """{"choices":[{"message":{"role":"assistant","content":"旧摘要"}}]}"""
                        .encodeToByteArray(),
                    contentType = "application/json"
                )
            } else {
                HttpResponse(
                    statusCode = 200,
                    body = """{"choices":[{"message":{"role":"assistant","content":"收到喵"}}]}"""
                        .encodeToByteArray(),
                    contentType = "application/json"
                )
            }
        }
        val svc = service(cm, store, engine)

        val job = async(Dispatchers.Default) { svc.compactContext() }
        while (!entered) yield()
        // 摘要请求在途时清除历史
        svc.clearHistory()
        gate.complete(Unit)
        val result = job.await()

        assertTrue(result.isFailure, "历史已变，压缩结果应作废")
        assertFalse(store.load().hasSummary, "不能把旧摘要写回清空后的库")
    }

    /**
     * 自动压缩与手动压缩互斥：手动压缩持锁期间触发的自动压缩**直接跳过**而不是排队，
     * 否则本轮聊天会被一整次压缩卡住。
     */
    @Test
    fun `手动压缩进行中时自动压缩跳过硬阈值兜底`() = runTest {
        val cm = ConversationManager()
        // 内容很短，保证预算充足、不触发自动压缩
        preload(cm, turns = 14, contentLength = 400)
        val store = InMemoryCompactionStore()
        val gate = CompletableDeferred<Unit>()
        var summaryCalls = 0
        var autoSkipped = false
        val engine = FakeHttpClientEngine { req ->
            val body = (req.body as RequestBody.Json).content
            if (body.contains(summaryMarker)) {
                summaryCalls++
                gate.await()
                HttpResponse(
                    statusCode = 200,
                    body = """{"choices":[{"message":{"role":"assistant","content":"摘要"}}]}"""
                        .encodeToByteArray(),
                    contentType = "application/json"
                )
            } else {
                HttpResponse(
                    statusCode = 200,
                    body = """{"choices":[{"message":{"role":"assistant","content":"收到喵"}}]}"""
                        .encodeToByteArray(),
                    contentType = "application/json"
                )
            }
        }
        // 预算压到会硬触发自动压缩
        val svc = service(cm, store, engine, budget = 6000)

        val manual = async(Dispatchers.Default) { svc.compactContext() }
        while (summaryCalls == 0) yield()
        // 手动压缩持锁中：这轮发送应跳过自动压缩（不增加摘要请求数），直接窗口丢弃后成功
        val send = svc.sendMessage("继续聊")
        assertTrue(send.isSuccess, "拿不到压缩锁时应退化为窗口丢弃，而不是失败")
        assertEquals(1, summaryCalls, "自动压缩应跳过，不该排队再发一次摘要请求")
        autoSkipped = true

        gate.complete(Unit)
        manual.await()
        assertTrue(autoSkipped)
    }
}
