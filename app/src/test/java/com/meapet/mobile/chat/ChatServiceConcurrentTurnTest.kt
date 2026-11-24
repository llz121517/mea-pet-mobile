package com.meapet.mobile.chat

import com.meapet.mobile.chat.tools.ChatToolLoop
import com.meapet.mobile.chat.tools.ToolRegistry
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.memory.search.MemorySearch
import com.meapet.mobile.memory.search.MemoryQuery
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import com.meapet.mobile.settings.ApiConnection
import com.meapet.mobile.settings.SettingsManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * G6：同一个 [ChatService] 同一时刻只允许一个在途 turn。
 *
 * 主界面与悬浮窗共用同一个 `ChatService` 实例，而一轮 turn 是「读历史 → 请求 →
 * 写历史 / 记忆」的读-改-写。若两个 turn 并行，后结束的一轮会把自己出发时的旧历史
 * 写回去，覆盖另一轮刚提交的片段（甚至让同一轮的消息交错）。
 *
 * 服务层的守卫语义与主界面「插队」一致：取消在途 turn，**等它收尾完成**后再发新的。
 * 这里刻意不经过 ViewModel 的 `cancelAndJoin`（模拟悬浮窗直接调用），验证守卫确实
 * 落在服务层。
 */
class ChatServiceConcurrentTurnTest {

    // ── 脚手架 ────────────────────────────────────────

    private fun settings(
        mode: com.meapet.mobile.config.MemoryMode = com.meapet.mobile.config.MemoryMode.TEXT
    ): SettingsManager = mock<SettingsManager>().also {
        whenever(it.getApiConnection()).thenReturn(ApiConnection("https://example.org/v1", "fixture-key"))
        whenever(it.isMemoryEnabled()).thenReturn(true)
        whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
        whenever(it.getModel()).thenReturn("gpt-4o-mini")
        whenever(it.getTemperature()).thenReturn(0.8)
        whenever(it.getMaxTokens()).thenReturn(1024)
        whenever(it.getMemoryMode()).thenReturn(mode)
    }

    private fun service(
        cm: ConversationManager,
        engine: FakeHttpClientEngine,
        mode: com.meapet.mobile.config.MemoryMode = com.meapet.mobile.config.MemoryMode.TEXT
    ) = ChatService(
        clientProvider = {
            OpenAiCompatibleClient(
                apiKey = "sk-test",
                baseUrl = "https://api.example.com/v1",
                engine = engine
            )
        },
        conversationManager = cm,
        memoryManager = null,
        settingsManager = settings(mode),
        postProcessScope = CoroutineScope(Dispatchers.Unconfined),
        config = com.meapet.mobile.config.AppConfig.DEFAULT
    )

    /** 工具轮服务：第一轮吐「正文 + tool_call」，用于验证取消时只提交可见片段。 */
    private fun toolService(cm: ConversationManager, engine: FakeHttpClientEngine): ChatService {
        val executor = MemoryToolExecutor(
            search = object : MemorySearch {
                override suspend fun search(query: MemoryQuery) = emptyList<com.meapet.mobile.memory.MemoryItem>()
            }
        )
        val client = OpenAiCompatibleClient("sk-test", "https://api.example.com/v1", engine)
        return ChatService(
            clientProvider = { client },
            conversationManager = cm,
            memoryManager = null,
            settingsManager = settings(com.meapet.mobile.config.MemoryMode.AUTO),
            postProcessScope = CoroutineScope(Dispatchers.Unconfined),
            config = com.meapet.mobile.config.AppConfig.DEFAULT,
            toolLoopProvider = { _, _ -> ChatToolLoop({ client }, ToolRegistry(executor.asTools())) },
            memoryTools = executor
        )
    }

    private fun text(content: String) = HttpResponse(
        statusCode = 200,
        body = """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}"""
            .encodeToByteArray(),
        contentType = "application/json"
    )

    /** 一段可见正文 + 一个必须继续执行的 tool_call（第二轮请求才会真正发生）。 */
    private fun fragmentThenToolCall(fragment: String) = HttpResponse(
        statusCode = 200,
        body = """
            {"choices":[{"message":{"role":"assistant","content":"$fragment",
            "tool_calls":[{"id":"call_1","type":"function",
            "function":{"name":"search_memory","arguments":"{\"query\":\"炸鸡\"}"}}]},
            "finish_reason":"tool_calls"}]}
        """.trimIndent().encodeToByteArray(),
        contentType = "application/json"
    )

    // ── G6：并发守卫 ──────────────────────────────────

    /**
     * 旧 turn 卡在请求中时，第二个发送方（模拟悬浮窗，不经过 ViewModel）不得与之并行：
     * 服务层先取消旧轮、等它回滚完孤儿 user，再开始新一轮。
     *
     * 关键断言是「两个 HTTP 请求从未重叠」：若没有守卫，第二个请求会在旧轮仍挂在
     * [firstGate] 上时就发出去（[maxInRequests] 变成 2）。
     */
    @Test
    fun `在途 turn 未结束时再次发送会先取消旧轮并等它收尾`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val inRequests = AtomicInteger()
        val maxInRequests = AtomicInteger()
        val engine = FakeHttpClientEngine {
            val now = inRequests.incrementAndGet()
            maxInRequests.updateAndGet { maxOf(it, now) }
            try {
                if (calls.getAndIncrement() == 0) {
                    firstStarted.complete(Unit)
                    firstGate.await()
                    text("第一条回复")
                } else {
                    text("第二条回复")
                }
            } finally {
                inRequests.decrementAndGet()
            }
        }
        val cm = ConversationManager()
        val svc = service(cm, engine)
        // 旧轮放在独立的 scope 里：它被取消时不能连带取消测试协程
        val turnScope = CoroutineScope(Dispatchers.Default)
        val first = turnScope.launch {
            svc.sendMessage(ChatMessage(role = ChatRole.user, content = "第一条", id = "u1"))
        }
        try {
            withTimeout(5_000) { firstStarted.await() }
            assertTrue("旧轮应处于在途状态", svc.isTurnActive)

            // 第二个发送方：直接调服务（悬浮窗的调用方式）
            val second = svc.sendMessage(ChatMessage(role = ChatRole.user, content = "第二条", id = "u2"))

            assertTrue("插队发送应成功: ${second.exceptionOrNull()}", second.isSuccess)
            assertTrue("旧轮应被取消", first.isCancelled)
            assertEquals(
                "旧轮没有可见片段，应整轮回滚；历史里只能留新一轮: " +
                    cm.getMessages().map { it.content },
                listOf("第二条", "第二条回复"),
                cm.getMessages().map { it.content }
            )
            assertEquals("两个 turn 的请求不得重叠", 1, maxInRequests.get())
            assertFalse("全部结束后不应仍在途", svc.isTurnActive)
        } finally {
            turnScope.cancel()
        }
    }

    // ── G1 的服务端一半：取消 + 等待收尾 ───────────────

    /**
     * [ChatService.cancelActiveTurnAndJoin] 返回时，旧轮的取消分支必须已经跑完
     * （已展示片段入史、未完成 tool 行丢弃）。
     *
     * 清除对话依赖这个语义：只 `cancel()` 就取快照会漏掉这段片段，清库后它还可能
     * 再被写回历史。
     */
    @Test
    fun `cancelActiveTurnAndJoin 返回时旧轮已提交可见片段`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val engine = FakeHttpClientEngine {
            if (calls.getAndIncrement() == 0) {
                fragmentThenToolCall("先查一下")
            } else {
                // 第二轮请求卡住：此刻 turn 仍在途，可见片段已展示但尚未提交
                secondStarted.complete(Unit)
                gate.await()
                text("最终回复")
            }
        }
        val cm = ConversationManager()
        val svc = toolService(cm, engine)
        val turnScope = CoroutineScope(Dispatchers.Default)
        val job = turnScope.launch {
            svc.sendMessage(ChatMessage(role = ChatRole.user, content = "帮我查一下", id = "u1"))
        }
        try {
            withTimeout(5_000) { secondStarted.await() }

            svc.cancelActiveTurnAndJoin()

            // 返回即代表收尾完成：片段已经落史，未完成的 tool 行被丢弃
            assertEquals(
                "取消收尾应在返回前完成: " + cm.getMessages().map { it.content },
                listOf("帮我查一下", "先查一下"),
                cm.getMessages().map { it.content }
            )
            assertTrue(
                "未完成的工具行不得落库: " + cm.getMessages().map { it.role },
                cm.getMessages().none { it.isTool }
            )
            assertFalse("收尾后不应仍在途", svc.isTurnActive)
            withTimeout(5_000) { job.join() }
        } finally {
            turnScope.cancel()
        }
    }
}
