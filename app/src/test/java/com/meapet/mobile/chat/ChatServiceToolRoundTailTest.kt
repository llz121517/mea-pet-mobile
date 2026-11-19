package com.meapet.mobile.chat

import com.meapet.mobile.chat.tools.ChatToolLoop
import com.meapet.mobile.chat.tools.MAX_TOOL_ROUNDS
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.config.MemoryMode
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.search.MemoryQuery
import com.meapet.mobile.memory.search.MemorySearch
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 工具模式的 tail 里带「工具轮次 已用/上限」计数。
 *
 * 与执行端拦截（ADR 0001）互补：计数器让模型自己看到消耗，接近上限时主动收尾；
 * 计数与上限来自 `ChatToolLoop` 的文件级常量，两处不各写数字。
 */
class ChatServiceToolRoundTailTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun settings(): SettingsManager = mock<SettingsManager>().also {
        whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
        whenever(it.getModel()).thenReturn("gpt-4o-mini")
        whenever(it.getTemperature()).thenReturn(0.8)
        whenever(it.getMaxTokens()).thenReturn(1024)
        whenever(it.getMemoryMode()).thenReturn(MemoryMode.AUTO)
    }

    private fun client(engine: FakeHttpClientEngine) = OpenAiCompatibleClient(
        apiKey = "sk-test",
        baseUrl = "https://api.example.com/v1",
        engine = engine
    )

    private fun textResponse(content: String): HttpResponse {
        val body = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                    })
                    put("finish_reason", "stop")
                })
            })
        }.toString()
        return HttpResponse(200, body.encodeToByteArray(), "application/json")
    }

    private fun toolCallResponse(): HttpResponse {
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call_1")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "search_memory")
                        put("arguments", """{"query":"炸鸡"}""")
                    })
                })
            })
        }
        val body = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("message", message); put("finish_reason", "tool_calls") })
            })
        }.toString()
        return HttpResponse(200, body.encodeToByteArray(), "application/json")
    }

    private fun service(cm: ConversationManager, engine: FakeHttpClientEngine): ChatService {
        val search = object : MemorySearch {
            override suspend fun search(query: MemoryQuery): List<MemoryItem> = emptyList()
        }
        val executor = MemoryToolExecutor(search)
        val loop = ChatToolLoop(clientProvider = { client(engine) }, executor = executor)
        return ChatService(
            clientProvider = { client(engine) },
            conversationManager = cm,
            memoryManager = null,
            settingsManager = settings(),
            postProcessScope = CoroutineScope(Dispatchers.Unconfined),
            config = AppConfig.DEFAULT,
            toolLoop = loop,
            memoryTools = executor
        )
    }

    /** 请求体里最后一条 user 的 content（tail 已并入副本）。 */
    private fun lastUserContent(engine: FakeHttpClientEngine, index: Int): String {
        val body = (engine.requests[index].body as RequestBody.Json).content
        val messages = json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
        val userMessages = messages.filter { it["role"]?.jsonPrimitive?.content == "user" }
        assertEquals("请求里只应有一条 user（tail 并入副本）", 1, userMessages.size)
        return userMessages.single()["content"]!!.jsonPrimitive.content
    }

    @Test
    fun `工具模式每轮 tail 带轮次计数`() = runTest {
        val responses = ArrayDeque(listOf(toolCallResponse(), textResponse("好喵")))
        val engine = FakeHttpClientEngine { responses.removeFirst() }
        val cm = ConversationManager()

        val result = service(cm, engine).sendMessage("帮我查一下")

        assertTrue(result.isSuccess)
        assertTrue(
            "第一轮 tail 应带 0/上限: ${lastUserContent(engine, 0)}",
            lastUserContent(engine, 0).contains("工具调用轮次 0/$MAX_TOOL_ROUNDS")
        )
        assertTrue(
            "第二轮 tail 应带 1/上限: ${lastUserContent(engine, 1)}",
            lastUserContent(engine, 1).contains("工具调用轮次 1/$MAX_TOOL_ROUNDS")
        )
        // 计数器只进请求副本：入库的 user 消息仍是原始正文
        assertEquals("帮我查一下", cm.getMessages().first { it.isUser }.content)
    }
}
