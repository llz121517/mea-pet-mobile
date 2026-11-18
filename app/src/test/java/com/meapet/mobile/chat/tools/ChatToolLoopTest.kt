package com.meapet.mobile.chat.tools

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.search.MemoryQuery
import com.meapet.mobile.memory.search.MemorySearch
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatToolLoopTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fakeSearch = object : MemorySearch {
        override suspend fun search(query: MemoryQuery): List<MemoryItem> =
            listOf(MemoryItem(id = "mem_1", content = "主人喜欢炸鸡", keywords = listOf("炸鸡")))
    }

    private fun toolCall(id: String, name: String = "search_memory", args: String = """{"query":"炸鸡"}"""): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("arguments", args)
            })
        }

    private fun toolCallResponse(vararg calls: JsonObject): HttpResponse =
        toolCallResponseWithText("", *calls)

    /** 某一轮「同时给正文与工具调用」：正文应立刻回传给 UI，同时进历史。 */
    private fun toolCallResponseWithText(text: String, vararg calls: JsonObject): HttpResponse {
        val content = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", text)
                        put("tool_calls", buildJsonArray { calls.forEach { add(it) } })
                    })
                    put("finish_reason", "tool_calls")
                })
            })
        }.toString()
        return HttpResponse(200, content.encodeToByteArray(), "application/json")
    }

    private fun textResponse(text: String): HttpResponse {
        val body = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", text)
                    })
                    put("finish_reason", "stop")
                })
            })
            put("usage", buildJsonObject { put("prompt_tokens", 123) })
        }.toString()
        return HttpResponse(200, body.encodeToByteArray(), "application/json")
    }

    private fun bodyOf(engine: FakeHttpClientEngine, index: Int): JsonObject =
        json.parseToJsonElement((engine.requests[index].body as RequestBody.Json).content).jsonObject

    @Test
    fun `一轮工具调用后返回最终文本`() = runTest {
        val responses = ArrayDeque(listOf(toolCallResponse(toolCall("call_1")), textResponse("记得喵")))
        val engine = FakeHttpClientEngine { responses.removeFirst() }
        val loop = ChatToolLoop(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            executor = MemoryToolExecutor(fakeSearch)
        )

        val result = loop.run("m", listOf(buildJsonObject { put("role", "user"); put("content", "hi") }), 0.7, 512, turnId = "t1")

        assertEquals("记得喵", result.finalContent)
        assertEquals(2, result.rounds)
        assertEquals(1, result.toolCalls)
        assertEquals(123, result.firstPromptTokens)

        // 第一轮：带 tools + tool_choice=auto
        val first = bodyOf(engine, 0)
        assertTrue(first.containsKey("tools"))
        assertEquals("auto", first["tool_choice"]!!.jsonPrimitive.content)

        // 第二轮：assistant 原样回传 + tool 消息按 tool_call_id 回填
        val second = bodyOf(engine, 1)
        val messages = second["messages"]!!.jsonArray
        val assistant = messages.map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "assistant" }
        assertTrue(assistant.containsKey("tool_calls"), "assistant 应原样回传 tool_calls")
        val toolMsg = messages.map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("call_1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(toolMsg["content"]!!.jsonPrimitive.content.contains("mem_1"))
    }

    @Test
    fun `达到最大轮数时最后一轮用 tool_choice none 并失败`() = runTest {
        val engine = FakeHttpClientEngine { toolCallResponse(toolCall("call_x")) }
        val loop = ChatToolLoop(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            executor = MemoryToolExecutor(fakeSearch),
            maxRounds = 3
        )

        assertFailsWith<IllegalStateException> {
            loop.run("m", listOf(buildJsonObject { put("role", "user") }), 0.7, 512, turnId = "t1")
        }
        assertEquals(3, engine.requests.size, "最多请求 maxRounds 次")
        val last = bodyOf(engine, 2)
        assertEquals("none", last["tool_choice"]!!.jsonPrimitive.content)
        assertTrue(last.containsKey("tools"), "最后一轮仍保留 tools，保持前缀一致")
    }

    @Test
    fun `单轮调用数超过上限时多余调用回填错误信封`() = runTest {
        val calls = (1..10).map { toolCall("call_$it") }
        val responses = ArrayDeque(listOf(toolCallResponse(*calls.toTypedArray()), textResponse("好喵")))
        val engine = FakeHttpClientEngine { responses.removeFirst() }
        val loop = ChatToolLoop(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            executor = MemoryToolExecutor(fakeSearch),
            maxCallsPerRound = 8
        )

        val result = loop.run("m", listOf(buildJsonObject { put("role", "user") }), 0.7, 512, turnId = "t1")

        assertEquals(8, result.toolCalls, "只执行前 8 个")
        val second = bodyOf(engine, 1)
        val toolMessages = second["messages"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(10, toolMessages.size, "每个 tool_call_id 都必须有对应结果")
        assertEquals(
            2,
            toolMessages.count { it["content"]!!.jsonPrimitive.content.contains("too_many_calls") }
        )
    }

    @Test
    fun `两轮工具调用时片段进回调、transcript 记录 assistant 与 tool、finalRaw 非空`() = runTest {
        val responses = ArrayDeque(
            listOf(
                toolCallResponseWithText("我先查一下喵", toolCall("call_1")),
                textResponse("记得喵")
            )
        )
        val engine = FakeHttpClientEngine { responses.removeFirst() }
        val loop = ChatToolLoop(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            executor = MemoryToolExecutor(fakeSearch)
        )
        val visible = mutableListOf<ChatMessage>()
        val transcript = mutableListOf<ChatMessage>()

        val result = loop.run(
            model = "m",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "hi")
            }),
            temperature = 0.7,
            maxTokens = 512,
            turnId = "turn-1",
            onVisibleFragment = { visible += it },
            onTranscript = { transcript += it }
        )

        // 片段：只回调有正文的中间 assistant，且带 turnId
        assertEquals(1, visible.size)
        assertEquals("我先查一下喵", visible.single().content)
        assertEquals("turn-1", visible.single().turnId)

        // transcript：中间 assistant（含原始 JSON）+ tool 结果，顺序固定
        assertEquals(2, transcript.size, "应依次记录 assistant 片段与 tool 结果")
        val assistant = transcript[0]
        assertEquals(ChatRole.assistant, assistant.role)
        assertEquals("turn-1", assistant.turnId)
        assertNotNull(assistant.rawAssistantJson, "中间 assistant 必须带原始 JSON 供下次透传")
        assertTrue(
            assistant.rawAssistantJson!!.contains("tool_calls"),
            "原始 JSON 应包含 tool_calls: ${assistant.rawAssistantJson}"
        )
        val tool = transcript[1]
        assertEquals(ChatRole.tool, tool.role)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search_memory", tool.toolName)
        assertEquals("turn-1", tool.turnId)

        // 最终回复：正文正确，finalRaw 非空（下一轮原样透传）
        assertEquals("记得喵", result.finalContent)
        assertNotNull(result.finalRaw)
        assertTrue(result.finalRaw!!.contains("记得喵"))
    }

    @Test
    fun `响应解析失败时抛异常`() = runTest {
        val engine = FakeHttpClientEngine {
            HttpResponse(200, "{}".encodeToByteArray(), "application/json")
        }
        val loop = ChatToolLoop(
            clientProvider = { OpenAiCompatibleClient("k", "https://x/v1", engine) },
            executor = MemoryToolExecutor(fakeSearch)
        )

        assertFailsWith<IllegalStateException> {
            loop.run("m", listOf(buildJsonObject { put("role", "user") }), 0.7, 512, turnId = "t1")
        }
    }
}
