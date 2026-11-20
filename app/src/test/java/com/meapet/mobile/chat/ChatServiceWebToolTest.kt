package com.meapet.mobile.chat

import com.meapet.mobile.chat.tools.ChatToolLoop
import com.meapet.mobile.chat.tools.ToolRegistry
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.JsonHttpClient
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.config.MemoryMode
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import com.meapet.mobile.search.AnySearchWebProvider
import com.meapet.mobile.search.WebFetchTool
import com.meapet.mobile.search.WebSearchTool
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatServiceWebToolTest {
    @Test
    fun `turn snapshots opt in and fetch failure stays inside the tool loop`() = runTest {
        var enabled = true
        var reply = 0
        val chat = FakeHttpClientEngine {
            when (++reply) {
                1 -> {
                    enabled = false
                    response("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_fetch","arguments":"{\"url\":\"https://example.org/article\"}"}}]}}]}""")
                }
                else -> response("""{"choices":[{"message":{"role":"assistant","content":"正文"}}]}""")
            }
        }
        val web = FakeHttpClientEngine { response("""{"code":-1,"message":"private diagnostics"}""", 422) }
        val provider = AnySearchWebProvider(JsonHttpClient(web), { "" })
        val client = OpenAiCompatibleClient("fake-key", "https://chat.example.org/v1", chat)
        val memory = MemoryToolExecutor(search = object : com.meapet.mobile.memory.search.MemorySearch {
            override suspend fun search(query: com.meapet.mobile.memory.search.MemoryQuery) =
                emptyList<com.meapet.mobile.memory.MemoryItem>()
        })
        val memoryLoop = ChatToolLoop({ client }, ToolRegistry(memory.asTools()))
        val webLoop = ChatToolLoop({ client }, ToolRegistry(memory.asTools() + listOf(
            WebSearchTool({ provider }), WebFetchTool({ provider })
        )))
        val settings = mock<SettingsManager>().also {
            whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
            whenever(it.getModel()).thenReturn("model")
            whenever(it.getMemoryMode()).thenReturn(MemoryMode.AUTO)
            whenever(it.getTemperature()).thenReturn(0.7)
            whenever(it.getMaxTokens()).thenReturn(4096)
        }
        val service = ChatService(
            clientProvider = { client }, conversationManager = ConversationManager(), memoryManager = null,
            settingsManager = settings, postProcessScope = backgroundScope, memoryTools = memory,
            toolLoopProvider = { if (enabled) webLoop else memoryLoop }
        )

        assertTrue(service.sendMessage("读取网页").isSuccess)
        assertTrue(service.sendMessage("下一轮").isSuccess)
        assertEquals(1, web.requests.size)
        val requests = chat.requests.map { Json.parseToJsonElement((it.body as RequestBody.Json).content).jsonObject }
        assertEquals(3, requests.size)
        val webNames = toolNames(requests[0])
        assertEquals(listOf("web_search", "web_fetch"), webNames.takeLast(2))
        assertEquals(requests[0]["tools"].toString(), requests[1]["tools"].toString())
        val result = requests[1].getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(result.contains("\"ok\":false"))
        assertFalse(result.contains("private diagnostics"))
        assertEquals(listOf("search_memory", "create_memory", "update_memory", "delete_memory"), toolNames(requests[2]))
    }

    @Test
    fun `text and off modes keep the existing non tool request`() = runTest {
        for (mode in listOf(MemoryMode.TEXT, MemoryMode.OFF)) {
            val chat = FakeHttpClientEngine { response("""{"choices":[{"message":{"role":"assistant","content":"正常回复"}}]}""") }
            val client = OpenAiCompatibleClient("fake-key", "https://chat.example.org/v1", chat)
            val settings = mock<SettingsManager>().also {
                whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
                whenever(it.getModel()).thenReturn("model")
                whenever(it.getMemoryMode()).thenReturn(mode)
                whenever(it.getTemperature()).thenReturn(0.7)
                whenever(it.getMaxTokens()).thenReturn(4096)
            }
            val service = ChatService(
                clientProvider = { client }, conversationManager = ConversationManager(), memoryManager = null,
                settingsManager = settings, postProcessScope = backgroundScope,
                toolLoopProvider = { error("Deferred S4 modes must not activate web tools") }
            )
            assertTrue(service.sendMessage("你好").isSuccess)
            val request = Json.parseToJsonElement((chat.requests.single().body as RequestBody.Json).content).jsonObject
            assertFalse(request.containsKey("tools"))
        }
    }

    private fun toolNames(request: JsonObject) = request.getValue("tools").jsonArray.map {
        it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
    }

    private fun response(body: String, status: Int = 200) = HttpResponse(status, body.encodeToByteArray(), "application/json")
}
