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
import com.meapet.mobile.memory.tools.MemoryToolSchemas
import com.meapet.mobile.search.WebFetchTool
import com.meapet.mobile.search.WebSearchTool
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

/**
 * S4：联网工具独立于记忆模式与记忆总开关。
 *
 * 关注点是「每轮实际下发了哪些工具、注入了什么上下文、产生了什么记忆副作用」，
 * 不涉及具体搜索服务商（web 工具用真实 schema，但 provider 恒为 null，不会发请求）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceToolEligibilityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `web tools are available when the memory switch is off`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO, memoryOn = false, webOn = true)
        val manager = manager(repository, settings)
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, backgroundScope, repository)

        assertTrue(service.sendMessage("你好").isSuccess)
        runCurrent()

        assertEquals(WEB_TOOL_NAMES, toolNames(engine, 0))
        assertFalse(system(engine).contains(MemoryToolSchemas.HINT))
        assertEquals(0, repository.getExchangeCount(), "web-only 轮次不能推进记忆摘要")
    }

    @Test
    fun `web tools are available when the memory mode is off`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        repository.save(
            MemoryItem(
                id = "remembered",
                content = "用户喜欢炸鸡",
                type = MemoryType.SHORT_TERM,
                keywords = listOf("炸鸡")
            )
        )
        val settings = settings(MemoryMode.OFF, memoryOn = true, webOn = true)
        val manager = manager(repository, settings)
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, backgroundScope, repository)

        assertTrue(service.sendMessage("炸鸡").isSuccess)

        assertEquals(WEB_TOOL_NAMES, toolNames(engine, 0))
        assertFalse(lastUser(engine).contains("【相关回忆】"), "OFF 不注入只读回忆")
        assertEquals(0, repository.getExchangeCount())
    }

    @Test
    fun `text mode keeps readonly context while web tools are used`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        repository.save(
            MemoryItem(
                id = "remembered",
                content = "用户喜欢炸鸡",
                type = MemoryType.SHORT_TERM,
                keywords = listOf("炸鸡")
            )
        )
        val settings = settings(MemoryMode.TEXT, memoryOn = true, webOn = true)
        val manager = manager(repository, settings)
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, backgroundScope, repository)

        assertTrue(service.sendMessage("炸鸡好吃吗").isSuccess)
        runCurrent()

        assertEquals(WEB_TOOL_NAMES, toolNames(engine, 0), "TEXT 模式不提供记忆工具")
        assertTrue(system(engine).contains("【记忆只读】"), "仍应声明记忆为只读")
        assertFalse(system(engine).contains(MemoryToolSchemas.HINT), "不应声明记忆工具")
        assertTrue(lastUser(engine).contains("【相关回忆】"))
        assertTrue(lastUser(engine).contains("用户喜欢炸鸡"))
        assertEquals(0, repository.getExchangeCount(), "只读轮次不推进摘要")
    }

    @Test
    fun `memory and web tools coexist in auto mode`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO, memoryOn = true, webOn = true)
        val manager = manager(repository, settings)
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, backgroundScope, repository)

        assertTrue(service.sendMessage("你好").isSuccess)

        assertEquals(MEMORY_TOOL_NAMES + WEB_TOOL_NAMES, toolNames(engine, 0))
        assertTrue(system(engine).contains(MemoryToolSchemas.HINT))
    }

    @Test
    fun `web switch off keeps the memory-only tool set`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO, memoryOn = true, webOn = false)
        val manager = manager(repository, settings)
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, backgroundScope, repository)

        assertTrue(service.sendMessage("你好").isSuccess)

        assertEquals(MEMORY_TOOL_NAMES, toolNames(engine, 0))
        assertTrue(system(engine).contains(MemoryToolSchemas.HINT))
    }

    @Test
    fun `no tools are advertised when memory and web are both unavailable`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.OFF, memoryOn = false, webOn = false)
        val manager = manager(repository, settings)
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, backgroundScope, repository)

        assertTrue(service.sendMessage("你好").isSuccess)

        assertFalse(request(engine, 0).containsKey("tools"))
    }

    @Test
    fun `tool schema errors are ordinary failures and keep tools enabled`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.AUTO, memoryOn = true, webOn = false)
        val manager = manager(repository, settings)
        var calls = 0
        val engine = FakeHttpClientEngine {
            if (calls++ == 0) {
                HttpResponse(
                    400,
                    (
                        """{"error":{"message":"Invalid schema for function 'search_memory': """ +
                            """tools[0].function.parameters: unsupported schema type"}}"""
                        ).encodeToByteArray(),
                    "application/json"
                )
            } else {
                text("回复")
            }
        }
        val service = service(engine, settings, manager, backgroundScope, repository)

        assertTrue(service.sendMessage("第一轮").isFailure)
        assertEquals(1, engine.requests.size, "schema 错误不应触发自动降级重发")
        assertTrue(service.sendMessage("重试").isSuccess)
        assertTrue(request(engine, 1).containsKey("tools"), "schema 错误不能缓存成「端点不支持工具」")
    }

    @Test
    fun `mode and web switch matrix decides tools and readonly injection`() = runTest {
        data class Case(
            val mode: MemoryMode,
            val memoryOn: Boolean,
            val webOn: Boolean,
            val memoryTools: Boolean,
            val webTools: Boolean,
            val readonly: Boolean
        )

        val cases = listOf(
            Case(MemoryMode.AUTO, true, false, memoryTools = true, webTools = false, readonly = false),
            Case(MemoryMode.AUTO, true, true, memoryTools = true, webTools = true, readonly = false),
            Case(MemoryMode.TOOLS, true, false, memoryTools = true, webTools = false, readonly = false),
            Case(MemoryMode.TOOLS, true, true, memoryTools = true, webTools = true, readonly = false),
            Case(MemoryMode.TEXT, true, false, memoryTools = false, webTools = false, readonly = true),
            Case(MemoryMode.TEXT, true, true, memoryTools = false, webTools = true, readonly = true),
            Case(MemoryMode.OFF, true, false, memoryTools = false, webTools = false, readonly = false),
            Case(MemoryMode.OFF, true, true, memoryTools = false, webTools = true, readonly = false),
            Case(MemoryMode.AUTO, false, true, memoryTools = false, webTools = true, readonly = false),
            Case(MemoryMode.TEXT, false, true, memoryTools = false, webTools = true, readonly = false)
        )

        for (case in cases) {
            val label = "mode=${case.mode} memory=${case.memoryOn} web=${case.webOn}"
            val repository = MemoryRepository(temp.newFolder())
            repository.save(
                MemoryItem(
                    id = "remembered",
                    content = "用户喜欢炸鸡",
                    type = MemoryType.SHORT_TERM,
                    keywords = listOf("炸鸡")
                )
            )
            val settings = settings(case.mode, case.memoryOn, case.webOn)
            val manager = manager(repository, settings)
            val engine = FakeHttpClientEngine { text("回复") }
            val service = service(engine, settings, manager, backgroundScope, repository)

            assertTrue(service.sendMessage("炸鸡").isSuccess, label)

            val expected = buildList {
                if (case.memoryTools) addAll(MEMORY_TOOL_NAMES)
                if (case.webTools) addAll(WEB_TOOL_NAMES)
            }
            val request = request(engine, 0)
            if (expected.isEmpty()) {
                assertFalse(request.containsKey("tools"), label)
            } else {
                assertEquals(expected, toolNames(engine, 0), label)
            }
            assertEquals(case.memoryTools, system(engine).contains(MemoryToolSchemas.HINT), label)
            assertEquals(case.readonly, system(engine).contains("【记忆只读】"), label)
            assertEquals(case.readonly, lastUser(engine).contains("【相关回忆】"), label)
            // 摘要计数在后台 launch 里推进，先把已排队的后台任务走完再断言
            runCurrent()
            // 记忆工具模式的成功轮次按既有语义计入摘要周期；web-only / 只读 / 关闭都不计
            assertEquals(if (case.memoryTools) 1 else 0, repository.getExchangeCount(), label)
        }
    }

    @Test
    fun `text mode with web keeps replaying the old tool history`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        val settings = settings(MemoryMode.TEXT, memoryOn = true, webOn = true)
        val manager = manager(repository, settings)
        val history = ConversationManager()
        history.addMessages(oldToolTurn())
        val engine = FakeHttpClientEngine { text("回复") }
        val service = service(engine, settings, manager, backgroundScope, repository, history)

        assertTrue(service.sendMessage("看看网页").isSuccess)

        assertEquals(WEB_TOOL_NAMES, toolNames(engine, 0))
        val messages = messages(engine, 0)
        assertTrue(messages.any { it["role"]?.jsonPrimitive?.content == "tool" }, "工具历史应原样回放")
        assertTrue(messages.any { it.containsKey("tool_calls") }, "assistant raw 应原样回放")
    }

    @Test
    fun `web only rejection falls back to plain text without memory side effects`() = runTest {
        val repository = MemoryRepository(temp.newFolder())
        repository.save(
            MemoryItem(
                id = "remembered",
                content = "用户喜欢炸鸡",
                type = MemoryType.SHORT_TERM,
                keywords = listOf("炸鸡")
            )
        )
        val settings = settings(MemoryMode.AUTO, memoryOn = false, webOn = true)
        val manager = manager(repository, settings)
        val history = ConversationManager()
        history.addMessages(oldToolTurn())
        var calls = 0
        val engine = FakeHttpClientEngine {
            if (calls++ == 0) {
                HttpResponse(
                    400,
                    """{"error":{"message":"This model does not support tools"}}""".encodeToByteArray(),
                    "application/json"
                )
            } else {
                text("纯文本回复")
            }
        }
        val service = service(engine, settings, manager, backgroundScope, repository, history)

        assertTrue(service.sendMessage("炸鸡").isSuccess)
        runCurrent()

        // 第一次带 web 工具被拒，降级请求既不带工具也不回放结构化工具历史
        val fallback = request(engine, 1)
        assertFalse(fallback.containsKey("tools"))
        val sent = messages(engine, 1)
        assertTrue(sent.none { it["role"]?.jsonPrimitive?.content == "tool" || it.containsKey("tool_calls") })
        // 记忆关闭：不注入只读回忆，也不产生任何记忆副作用
        assertFalse(sent.last().getValue("content").jsonPrimitive.content.contains("【相关回忆】"))
        assertTrue(repository.getAll().map { it.content } == listOf("用户喜欢炸鸡"))
        assertEquals(0, repository.getExchangeCount())
        assertEquals(1, history.getMessages().count { it.isTool }, "库内原始工具记录不被改写")
    }

    // ── 辅助 ──────────────────────────────────────────

    private fun oldToolTurn() = listOf(
        ChatMessage(role = ChatRole.user, content = "以前的问题", id = "old-user", turnId = "old"),
        ChatMessage(
            role = ChatRole.assistant,
            content = "查一下",
            id = "old-assistant",
            turnId = "old",
            rawAssistantJson = """{"role":"assistant","content":"查一下","tool_calls":[{"id":"old-call","type":"function","function":{"name":"web_search","arguments":"{}"}}]}"""
        ),
        ChatMessage(
            role = ChatRole.tool,
            content = """{"ok":true}""",
            id = "old-result",
            turnId = "old",
            toolCallId = "old-call",
            toolName = "web_search"
        ),
        ChatMessage(role = ChatRole.assistant, content = "以前的回复", id = "old-final", turnId = "old")
    )

    private fun settings(mode: MemoryMode, memoryOn: Boolean, webOn: Boolean) =
        mock<SettingsManager>().also {
            whenever(it.getApiConnection()).thenReturn(ApiConnection("https://example.org/v1", "fixture-key"))
            whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
            whenever(it.getModel()).thenReturn("model")
            whenever(it.getApiUrl()).thenReturn("https://example.org/v1")
            whenever(it.getApiKey()).thenReturn("fixture-key")
            whenever(it.getTemperature()).thenReturn(0.7)
            whenever(it.getMaxTokens()).thenReturn(1024)
            whenever(it.getMemoryMode()).thenReturn(mode)
            whenever(it.isMemoryEnabled()).thenReturn(memoryOn)
            whenever(it.isAutoSummaryEnabled()).thenReturn(false)
            whenever(it.getSummaryInterval()).thenReturn(10)
            whenever(it.getWebSearchEnabled()).thenReturn(webOn)
        }

    private fun manager(repository: MemoryRepository, settings: SettingsManager) =
        MemoryManager(MemoryService(repository, { null }, settings), repository, settings)

    private fun service(
        engine: FakeHttpClientEngine,
        settings: SettingsManager,
        manager: MemoryManager,
        scope: CoroutineScope,
        repository: MemoryRepository,
        history: ConversationManager = ConversationManager()
    ): ChatService {
        val client = OpenAiCompatibleClient("fixture", "https://example.org/v1", engine)
        val executor = MemoryToolExecutor(
            search = BigramMemorySearcher(repository),
            store = repository,
            commit = manager::applyToolChanges
        )
        val webTools = listOf(WebSearchTool({ null }), WebFetchTool({ null }))
        return ChatService(
            clientProvider = { client },
            conversationManager = history,
            memoryManager = manager,
            settingsManager = settings,
            postProcessScope = scope,
            toolLoopProvider = { memoryTools, webToolsEnabled ->
                val tools = buildList {
                    if (memoryTools) addAll(executor.asTools())
                    if (webToolsEnabled) addAll(webTools)
                }
                if (tools.isEmpty()) null else ChatToolLoop({ client }, ToolRegistry(tools))
            },
            memoryTools = executor
        )
    }

    private fun text(content: String): HttpResponse = HttpResponse(
        200,
        buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject {
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", content)
                        })
                    })
                }
            )
        }.toString().encodeToByteArray(),
        "application/json"
    )

    private fun request(engine: FakeHttpClientEngine, index: Int): JsonObject =
        Json.parseToJsonElement((engine.requests[index].body as RequestBody.Json).content).jsonObject

    private fun messages(engine: FakeHttpClientEngine, index: Int): List<JsonObject> =
        request(engine, index).getValue("messages").jsonArray.map { it.jsonObject }

    private fun toolNames(engine: FakeHttpClientEngine, index: Int): List<String> =
        request(engine, index).getValue("tools").jsonArray.map {
            it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
        }

    private fun system(engine: FakeHttpClientEngine): String =
        request(engine, 0).getValue("messages").jsonArray.map { it.jsonObject }
            .first { it["role"]?.jsonPrimitive?.content == "system" }
            .getValue("content").jsonPrimitive.content

    private fun lastUser(engine: FakeHttpClientEngine): String =
        request(engine, 0).getValue("messages").jsonArray.map { it.jsonObject }
            .last { it["role"]?.jsonPrimitive?.content == "user" }
            .getValue("content").jsonPrimitive.content

    private companion object {
        val MEMORY_TOOL_NAMES = listOf(
            "search_memory", "create_memory", "update_memory", "delete_memory"
        )
        val WEB_TOOL_NAMES = listOf("web_search", "web_fetch")
    }
}
