package com.meapet.mobile.chat

import com.meapet.mobile.chat.tools.ChatToolLoop
import com.meapet.mobile.chat.tools.ToolRegistry
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.config.MemoryMode
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.memory.MemoryService
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.memory.search.BigramMemorySearcher
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.settings.ApiConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceReadOnlyMemoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `text mode appends keyword hits only to request copies and never mutates memories`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        repository.save(MemoryItem(id = "remembered", content = "用户喜欢炸鸡", type = MemoryType.SHORT_TERM, keywords = listOf("炸鸡")))
        val before = repository.getAll()
        val settings = settings(MemoryMode.TEXT)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        val history = ConversationManager()
        val engine = FakeHttpClientEngine { text("可以聊聊炸鸡") }
        val service = service(engine, settings, manager, history, backgroundScope)

        assertTrue(service.sendMessage("炸鸡怎么样").isSuccess)
        runCurrent()

        val messages = request(engine, 0).getValue("messages").jsonArray.map { it.jsonObject }
        val system = messages.first().getValue("content").jsonPrimitive.content
        val outgoingUser = messages.last().getValue("content").jsonPrimitive.content
        assertFalse(system.contains("memory-ops"))
        assertTrue(outgoingUser.contains("【相关回忆】"))
        assertTrue(outgoingUser.contains("用户喜欢炸鸡"))
        assertEquals("炸鸡怎么样", history.getMessages().first { it.isUser }.content)
        assertEquals(before, repository.getAll())
        assertEquals(0, repository.getExchangeCount())
    }

    @Test
    fun `legacy fenced text stays ordinary reply content and cannot create memories`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.TEXT)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        val history = ConversationManager()
        val raw = "这是一个示例\n```memory-ops\n[{\"op\":\"create\",\"content\":\"不应写入\",\"type\":\"SHORT_TERM\",\"keywords\":[\"示例\"]}]\n```"
        val service = service(FakeHttpClientEngine { text(raw) }, settings, manager, history, backgroundScope)

        val result = service.sendMessage("解释这个代码示例")
        runCurrent()

        assertTrue(result.isSuccess)
        assertEquals(raw, result.getOrThrow().second.content)
        assertTrue(repository.getAll().isEmpty())
        assertEquals(0, repository.getExchangeCount())
    }

    @Test
    fun `unsupported tools fall back to readonly context without replaying tool history`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        repository.save(MemoryItem(id = "remembered", content = "用户喜欢炸鸡", type = MemoryType.SHORT_TERM, keywords = listOf("炸鸡")))
        val settings = settings(MemoryMode.AUTO)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        val history = ConversationManager()
        history.addMessages(listOf(
            ChatMessage(role = ChatRole.user, content = "以前的问题", id = "old-user", turnId = "old"),
            ChatMessage(role = ChatRole.assistant, content = "查一下", id = "old-assistant", turnId = "old", rawAssistantJson = """{"role":"assistant","content":"查一下","tool_calls":[{"id":"old-call","type":"function","function":{"name":"search_memory","arguments":"{}"}}]}"""),
            ChatMessage(role = ChatRole.tool, content = "{}", id = "old-result", turnId = "old", toolCallId = "old-call", toolName = "search_memory"),
            ChatMessage(role = ChatRole.assistant, content = "以前的回复", id = "old-final", turnId = "old")
        ))
        var calls = 0
        val engine = FakeHttpClientEngine { if (calls++ == 0) rejectedTools() else text("普通回复") }
        val service = service(engine, settings, manager, history, backgroundScope, repository)

        assertTrue(service.sendMessage("再聊炸鸡").isSuccess)
        runCurrent()

        assertTrue(request(engine, 0).containsKey("tools"))
        val fallback = request(engine, 1)
        assertFalse(fallback.containsKey("tools"))
        val messages = fallback.getValue("messages").jsonArray.map { it.jsonObject }
        assertTrue(messages.none { it["role"]?.jsonPrimitive?.content == "tool" || it.containsKey("tool_calls") })
        assertFalse(messages.first().getValue("content").jsonPrimitive.content.contains("memory-ops"))
        assertTrue(messages.last().getValue("content").jsonPrimitive.content.contains("用户喜欢炸鸡"))
        assertEquals(1, history.getMessages().count { it.isTool })
        assertEquals(0, repository.getExchangeCount())
    }

    @Test
    fun `successful memory tools still commit and count an exchange`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        var calls = 0
        val engine = FakeHttpClientEngine {
            if (calls++ == 0) response(buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("id", "create-1")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "create_memory")
                        put("arguments", """{"content":"用户喜欢炸鸡","type":"SHORT_TERM","importance":0.5,"keywords":["炸鸡"]}""")
                    })
                }) })
            }) else text("记住了")
        }
        val service = service(engine, settings, manager, ConversationManager(), backgroundScope, repository)

        assertTrue(service.sendMessage("我喜欢炸鸡").isSuccess)
        runCurrent()

        assertEquals(listOf("用户喜欢炸鸡"), repository.getAll().map { it.content })
        assertEquals(1, repository.getExchangeCount())
    }

    @Test
    fun `changing model endpoint or key retries tools after a rejection`() = runTest {
        for (changed in listOf("model", "url", "key")) {
            val repository = MemoryRepository(temp.newFolder())
            val settings = settings(MemoryMode.AUTO)
            whenever(settings.getApiConnection())
                .thenReturn(ApiConnection("https://example.org/v1", "first-key"))
            val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
            var calls = 0
            val engine = FakeHttpClientEngine { if (calls++ == 0) rejectedTools() else text("回复") }
            val service = service(engine, settings, manager, ConversationManager(), backgroundScope, repository)
            assertTrue(service.sendMessage("第一轮").isSuccess)
            assertTrue(service.sendMessage("同一配置").isSuccess)
            assertFalse(request(engine, 2).containsKey("tools"))
            // 端点快照读的是 getApiConnection 一次取回的 url/key
            when (changed) {
                "model" -> whenever(settings.getModel()).thenReturn("other-model")
                "url" -> whenever(settings.getApiConnection())
                    .thenReturn(ApiConnection("https://other.example/v1", "first-key"))
                "key" -> whenever(settings.getApiConnection())
                    .thenReturn(ApiConnection("https://example.org/v1", "second-key"))
            }
            assertTrue(service.sendMessage("切换配置").isSuccess)
            assertTrue(request(engine, 3).containsKey("tools"), changed)
        }
    }

    /**
     * 实测：历史里已有工具轮记录后，来回切换「支持工具」与「不支持工具」的模型。
     *
     * 关注三点：
     * 1. 切到不支持工具的模型时，旧工具历史必须被过滤成纯文本（否则服务端 400）；
     * 2. 切回支持工具的模型时，必须重新尝试工具（负缓存不能跨模型生效）；
     * 3. 来回切换不应让历史里的工具记录丢失或被改写。
     */
    @Test
    fun `已有工具历史时来回切换支持与不支持工具的模型`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO)
        whenever(settings.getApiConnection())
            .thenReturn(ApiConnection("https://example.org/v1", "key"))
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        val history = ConversationManager()
        // 预置一轮真实的工具历史（assistant 带 tool_calls + tool 结果）
        history.addMessages(toolHistoryTurn())

        var calls = 0
        val engine = FakeHttpClientEngine {
            val body = (it.body as RequestBody.Json).content
            val model = Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content
            calls++
            if (model == "bad-model" && body.contains("\"tools\"")) rejectedTools() else text("回复$calls")
        }
        val service = service(engine, settings, manager, history, backgroundScope, repository)

        whenever(settings.getModel()).thenReturn("good-model")
        assertTrue(service.sendMessage("第一轮").isSuccess)

        whenever(settings.getModel()).thenReturn("bad-model")
        val badTurn = service.sendMessage("第二轮")
        assertTrue(badTurn.isSuccess, "不支持工具的模型也应成功回复")

        whenever(settings.getModel()).thenReturn("good-model")
        assertTrue(service.sendMessage("第三轮").isSuccess)

        val requests = engine.requests.map {
            Json.parseToJsonElement((it.body as RequestBody.Json).content).jsonObject
        }
        val trace = requests.map { req ->
            val m = req.getValue("model").jsonPrimitive.content
            val tools = req.containsKey("tools")
            val toolRows = req.getValue("messages").jsonArray.count {
                it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
            }
            "$m|tools=$tools|toolRows=$toolRows"
        }
        println("SWITCH_TRACE: " + trace.joinToString(" -> "))

        assertTrue(requests.last().containsKey("tools"), "切回后请求应重新包含 tools")
    }

    /** 一轮带 tool_calls 与 tool 结果的合法历史。 */
    private fun toolHistoryTurn() = listOf(
        ChatMessage(role = ChatRole.user, content = "查一下", id = "h-u", turnId = "h"),
        ChatMessage(
            role = ChatRole.assistant,
            content = "我查查",
            id = "h-a1",
            turnId = "h",
            rawAssistantJson = """{"role":"assistant","content":"我查查","tool_calls":[{"id":"h-c1","type":"function","function":{"name":"search_memory","arguments":"{}"}}]}"""
        ),
        ChatMessage(
            role = ChatRole.tool,
            content = """{"ok":true}""",
            id = "h-t1",
            turnId = "h",
            toolCallId = "h-c1",
            toolName = "search_memory"
        ),
        ChatMessage(role = ChatRole.assistant, content = "查到了", id = "h-a2", turnId = "h")
    )

    @Test
    fun `ordinary HTTP errors do not disable tools or retry silently`() = runTest {
        for (status in listOf(400, 404, 422, 501)) {
            val repository = MemoryRepository(temp.newFolder())
            val settings = settings(MemoryMode.AUTO)
            val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
            var calls = 0
            val engine = FakeHttpClientEngine {
                if (calls++ == 0) HttpResponse(status, """{"error":{"message":"Invalid model or context length"}}""".encodeToByteArray(), "application/json") else text("回复")
            }
            val service = service(engine, settings, manager, ConversationManager(), backgroundScope, repository)
            assertTrue(service.sendMessage("第一轮").isFailure)
            assertEquals(1, engine.requests.size)
            assertTrue(service.sendMessage("重试").isSuccess)
            assertTrue(request(engine, 1).containsKey("tools"))
        }
    }

    @Test
    fun `disabled memory never advertises tools or advances summary count`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO)
        whenever(settings.isMemoryEnabled()).thenReturn(false)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, ConversationManager(), backgroundScope, repository)
        assertTrue(service.sendMessage("你好").isSuccess)
        runCurrent()
        assertFalse(request(engine, 0).containsKey("tools"))
        assertEquals(0, repository.getExchangeCount())
    }

    @Test
    fun `HTTP rejection after a memory write aborts the turn without committing changes`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        val history = ConversationManager()
        var calls = 0
        val engine = FakeHttpClientEngine {
            when (calls++) {
                0 -> response(Json.parseToJsonElement("""{"role":"assistant","content":"","tool_calls":[{"id":"write","type":"function","function":{"name":"create_memory","arguments":"{\"content\":\"不应落库\"}"}}]}""").jsonObject)
                1 -> rejectedTools()
                else -> text("重试成功")
            }
        }
        val service = service(engine, settings, manager, history, backgroundScope, repository)
        assertTrue(service.sendMessage("记一下").isFailure)
        assertTrue(history.getMessages().isEmpty())
        assertTrue(repository.getAll().isEmpty())
        assertEquals(2, engine.requests.size)
        assertTrue(service.sendMessage("再试").isSuccess)
        assertTrue(request(engine, 2).containsKey("tools"))
    }

    @Test
    fun `unsupported top level tools parameter falls back and caches rejection`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        var calls = 0
        val engine = FakeHttpClientEngine {
            if (calls++ == 0) HttpResponse(422, """{"error":{"message":"This model does not support parameters: ['tools']"}}""".encodeToByteArray(), "application/json") else text("回复")
        }
        val service = service(engine, settings, manager, ConversationManager(), backgroundScope, repository)
        assertTrue(service.sendMessage("第一轮").isSuccess)
        assertTrue(service.sendMessage("下一轮").isSuccess)
        assertEquals(3, engine.requests.size)
        assertTrue(request(engine, 0).containsKey("tools"))
        assertFalse(request(engine, 1).containsKey("tools"))
        assertFalse(request(engine, 2).containsKey("tools"))
    }

    @Test
    fun `rejecting another parameter while mentioning tools does not disable tools`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO)
        val manager = MemoryManager(MemoryService(repository, { null }, settings), repository, settings)
        var calls = 0
        val engine = FakeHttpClientEngine {
            if (calls++ == 0) HttpResponse(400, """{"error":{"message":"Unsupported parameter: temperature when tools are enabled"}}""".encodeToByteArray(), "application/json") else text("回复")
        }
        val service = service(engine, settings, manager, ConversationManager(), backgroundScope, repository)
        assertTrue(service.sendMessage("第一轮").isFailure)
        assertEquals(1, engine.requests.size)
        assertTrue(service.sendMessage("下一轮").isSuccess)
        assertTrue(request(engine, 1).containsKey("tools"))
    }

    private fun rejectedTools() = HttpResponse(400, """{"error":{"message":"This model does not support tools"}}""".encodeToByteArray(), "application/json")

    private fun service(engine: FakeHttpClientEngine, settings: SettingsManager, manager: MemoryManager, history: ConversationManager, scope: CoroutineScope, toolStore: MemoryRepository? = null): ChatService {
        val client = OpenAiCompatibleClient("fixture", "https://example.org/v1", engine)
        val executor = toolStore?.let { MemoryToolExecutor(BigramMemorySearcher(it), it, commit = manager::applyToolChanges) }
        return ChatService(clientProvider = { client }, conversationManager = history, memoryManager = manager,
            settingsManager = settings, postProcessScope = scope,
            toolLoopProvider = { _, _ -> executor?.let { ChatToolLoop({ client }, ToolRegistry(it.asTools())) } }, memoryTools = executor)
    }

    private fun settings(mode: MemoryMode) = mock<SettingsManager>().also {
        whenever(it.getApiConnection()).thenReturn(ApiConnection("https://example.org/v1", "fixture-key"))
        whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
        whenever(it.getModel()).thenReturn("model")
        whenever(it.getTemperature()).thenReturn(0.7)
        whenever(it.getMaxTokens()).thenReturn(1024)
        whenever(it.getMemoryMode()).thenReturn(mode)
        whenever(it.isMemoryEnabled()).thenReturn(true)
        whenever(it.isAutoSummaryEnabled()).thenReturn(false)
        whenever(it.getSummaryInterval()).thenReturn(10)
    }

    private fun text(content: String) = response(buildJsonObject { put("role", "assistant"); put("content", content) })
    private fun response(message: JsonObject) = HttpResponse(200, buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("message", message) }) }) }.toString().encodeToByteArray(), "application/json")
    private fun request(engine: FakeHttpClientEngine, index: Int) = Json.parseToJsonElement((engine.requests[index].body as RequestBody.Json).content).jsonObject
}
