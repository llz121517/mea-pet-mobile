package com.meapet.mobile.chat

import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 发送失败后的会话历史回滚。
 *
 * [ChatService.sendMessage] 在请求**之前**就把用户消息写进 [ConversationManager]，
 * 而失败路径（异常 / 响应无有效内容）都直接 `Result.failure` 返回，不做回滚。
 * 于是每一次失败都在历史里留下一条从未成功发出的"幽灵消息"，并被持久化。
 *
 * 后果有两个：污染后续所有请求的上下文；以及与 ViewModel 的乐观消息形成同内容
 * 不同 id 的两份副本，在 `reloadHistory()` 合并时并列成两条。
 */
class ChatServiceFailureRollbackTest {

    private fun settings(): SettingsManager = mock<SettingsManager>().also {
        whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
        whenever(it.getModel()).thenReturn("gpt-4o-mini")
        whenever(it.getTemperature()).thenReturn(0.8)
        whenever(it.getMaxTokens()).thenReturn(1024)
        // 枚举 mock 默认 null → ChatService 解析模式时 NPE；这些用例只验证单次请求链路
        whenever(it.getMemoryMode()).thenReturn(com.meapet.mobile.config.MemoryMode.TEXT)
    }

    private fun service(
        cm: ConversationManager,
        engine: FakeHttpClientEngine,
        config: com.meapet.mobile.config.AppConfig = com.meapet.mobile.config.AppConfig.DEFAULT
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
        settingsManager = settings(),
        postProcessScope = CoroutineScope(Dispatchers.Unconfined),
        config = config
    )

    private fun errorResponse() = HttpResponse(
        statusCode = 429,
        body = """{"error":{"message":"rate limit reached"}}""".encodeToByteArray(),
        contentType = "application/json"
    )

    private fun okResponse() = HttpResponse(
        statusCode = 200,
        body = """{"choices":[{"message":{"role":"assistant","content":"收到"}}]}""".encodeToByteArray(),
        contentType = "application/json"
    )

    @Test
    fun `请求失败后用户消息不应残留在会话历史`() = runTest {
        val engine = FakeHttpClientEngine { errorResponse() }
        val cm = ConversationManager()

        val result = service(cm, engine).sendMessage("这条发送会失败")

        assertTrue("请求应当失败", result.isFailure)
        assertEquals(
            "失败后历史里不该留下用户消息，实际残留: " + cm.getMessages().map { it.content },
            emptyList<String>(),
            cm.getMessages().map { it.content }
        )
    }

    @Test
    fun `失败残留的消息不应污染后续请求的上下文`() = runTest {
        var fail = true
        val engine = FakeHttpClientEngine { if (fail) errorResponse() else okResponse() }
        val cm = ConversationManager()
        val svc = service(cm, engine)

        svc.sendMessage("这条发送会失败")
        fail = false
        svc.sendMessage("这条是新问题")

        val lastBody = (engine.requests.last().body as RequestBody.Json).content
        assertFalse(
            "失败的消息不该出现在后续请求体里: $lastBody",
            lastBody.contains("这条发送会失败")
        )
    }

    @Test
    fun `重试目标已被回滚时应重发该消息而不是历史里上一条 user`() = runTest {
        var fail = false
        val engine = FakeHttpClientEngine { if (fail) errorResponse() else okResponse() }
        val cm = ConversationManager()
        val svc = service(cm, engine)

        // 先成功发一条，历史里留下 [老消息, 回复]
        svc.sendMessage("老消息")
        // 再发一条并失败 → 被回滚出历史
        fail = true
        val failed = ChatMessage(role = ChatRole.user, content = "新消息")
        svc.sendMessage(failed)
        assertFalse("失败的消息应已回滚", cm.getMessages().any { it.id == failed.id })

        // 重试：目标不在历史里，也必须重发「新消息」本身
        fail = false
        val result = svc.retryMessage(failed)

        assertTrue("重试应成功", result.isSuccess)
        assertEquals("重试返回的应是原消息", failed.id, result.getOrNull()?.first?.id)
        val retryBody = (engine.requests.last().body as RequestBody.Json).content
        assertTrue("请求体应包含被重试的消息: $retryBody", retryBody.contains("新消息"))
        assertEquals(
            "「老消息」在历史里只能有一条，重试不得把它重发或复制: " +
                cm.getMessages().map { it.content },
            1,
            cm.getMessages().count { it.content == "老消息" }
        )
    }

    @Test
    fun `重试目标仍在历史里时不会留下两份`() = runTest {
        val engine = FakeHttpClientEngine { okResponse() }
        val cm = ConversationManager()
        val svc = service(cm, engine)

        val msg = ChatMessage(role = ChatRole.user, content = "同一条消息")
        svc.sendMessage(msg)
        svc.retryMessage(msg)

        assertEquals(
            "重试后该消息只能有一条: " + cm.getMessages().map { it.content },
            1,
            cm.getMessages().count { it.id == msg.id }
        )
    }

    /**
     * 重试是**整轮语义**：该 user 及其之后的全部消息（中间片段、tool 结果）一起移除再重发，
     * 否则旧轮的半成品会和重试后的新轮并存，形成无法配对的 tool_calls。
     */
    @Test
    fun `重试会移除该 user 及其后的整轮残留再重发`() = runTest {
        val engine = FakeHttpClientEngine { okResponse() }
        val cm = ConversationManager()
        val svc = service(cm, engine)

        // 先成功发两轮（历史：[u1, a1, u2, a2]）
        val user1 = ChatMessage(role = ChatRole.user, content = "老消息")
        val user2 = ChatMessage(role = ChatRole.user, content = "新消息")
        svc.sendMessage(user1)
        svc.sendMessage(user2)

        // 人为模拟被中断轮留下的 turn 记录（同一 user 之后的 assistant/tool）
        cm.addMessages(
            listOf(
                ChatMessage(role = ChatRole.assistant, content = "半截回复", turnId = "old-turn"),
                ChatMessage(
                    role = ChatRole.tool, content = """{"ok":true}""",
                    toolCallId = "call_1", toolName = "search_memory", turnId = "old-turn"
                )
            )
        )

        val result = svc.retryMessage(user2)

        assertTrue(result.isSuccess)
        val history = cm.getMessages()
        assertFalse(
            "重试后不该残留旧轮的 tool 消息: " + history.map { it.role },
            history.any { it.isTool }
        )
        assertEquals(1, history.count { it.id == user2.id })
        assertEquals("重试后应只剩 u1/a1/u2/新回复", 4, history.size)
        assertEquals(ChatRole.user, history[2].role)
        assertEquals(ChatRole.assistant, history[3].role)
    }

    /**
     * 总超时按**失败**处理（不是取消）：回滚本轮用户消息、返回可展示的超时文案。
     * 工具循环最坏是多次请求叠加，没有总超时时用户只能强杀。
     */
    @Test
    fun `总超时后回滚用户消息并返回超时失败`() = runTest {
        val engine = FakeHttpClientEngine {
            kotlinx.coroutines.delay(2_000)
            okResponse()
        }
        val cm = ConversationManager()
        val svc = service(
            cm,
            engine,
            config = com.meapet.mobile.config.AppConfig.DEFAULT.copy(turnTimeoutMs = 50)
        )

        val result = svc.sendMessage("这条会超时")

        assertTrue("超时应按失败返回", result.isFailure)
        assertTrue(
            "超时文案应可展示: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("请求超时") == true
        )
        assertTrue(
            "超时后历史里不该留下用户消息: " + cm.getMessages().map { it.content },
            cm.getMessages().isEmpty()
        )
        assertFalse("超时结束后不应仍处于在途状态", svc.isTurnActive)
    }
}
