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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 工具轮次计数拼在**发给模型的 tool 返回消息**里。
 *
 * 工具循环里最后一条消息是 tool，没有 user 可以挂 tail，所以计数只能随工具结果下发；
 * 它属于每轮动态内容：只进请求副本，**不入历史**。
 */
class ChatServiceToolRoundCounterTest {

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

    private fun toolCallResponse(id: String = "call_1"): HttpResponse {
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", id)
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
        val loop = ChatToolLoop(clientProvider = { client(engine) }, registry = com.meapet.mobile.chat.tools.ToolRegistry(executor.asTools()))
        return ChatService(
            clientProvider = { client(engine) },
            conversationManager = cm,
            memoryManager = null,
            settingsManager = settings(),
            postProcessScope = CoroutineScope(Dispatchers.Unconfined),
            config = AppConfig.DEFAULT,
            toolLoopProvider = { loop },
            memoryTools = executor
        )
    }

    private fun messagesOf(engine: FakeHttpClientEngine, index: Int): List<JsonObject> {
        val body = (engine.requests[index].body as RequestBody.Json).content
        return json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `工具轮次计数随工具返回消息发给模型但不入历史`() = runTest {
        val responses = ArrayDeque(listOf(toolCallResponse(), textResponse("好喵")))
        val engine = FakeHttpClientEngine { responses.removeFirst() }
        val cm = ConversationManager()

        val result = service(cm, engine).sendMessage("帮我查一下")

        assertTrue(result.isSuccess)

        // 第一次请求还没有工具结果；第二次请求里的 tool 消息应带轮次计数 1/上限
        val toolMsg = messagesOf(engine, 1)
            .single { it["role"]?.jsonPrimitive?.content == "tool" }
        val toolContent = toolMsg["content"]!!.jsonPrimitive.content
        assertTrue(
            "tool 返回内容应带轮次计数: $toolContent",
            toolContent.contains("【工具调用轮次】1/$MAX_TOOL_ROUNDS")
        )

        // tail 里不再放计数（只有时间等）；历史里也只保存原始工具结果
        val userContent = messagesOf(engine, 1)
            .last { it["role"]?.jsonPrimitive?.content == "user" }["content"]!!.jsonPrimitive.content
        assertFalse("user 消息不再挂轮次计数: $userContent", userContent.contains("工具调用轮次"))
        val historyTool = cm.getMessages().single { it.isTool }
        assertFalse(
            "计数属于每轮动态内容，不应写进历史: ${historyTool.content}",
            historyTool.content.contains("工具调用轮次")
        )
        assertEquals("原始检索结果仍保留在历史里", true, historyTool.content.contains("ok"))
    }
}
