package com.meapet.mobile.chat

import com.meapet.mobile.chat.tools.ChatToolLoop
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.config.MemoryMode
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.search.MemoryQuery
import com.meapet.mobile.memory.search.MemorySearch
import com.meapet.mobile.memory.tools.MemoryToolExecutor
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.settings.ApiConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工具模式下的 system 内容。
 *
 * 计划核心决策：人设事实（FACTUAL / CORE_TRAIT）**仍全量注入**，只有短期/长期记忆
 * 改由模型自己调用 `search_memory` 检索。工具模式不能走 `buildContext`——那会把文本
 * 协议说明与关键词回忆一起带进来（两套协议打架），但它的人设段必须保留在首条 system。
 */
class ChatServiceToolPersonaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun settings(): SettingsManager = mock<SettingsManager>().also {
        whenever(it.getApiConnection()).thenReturn(ApiConnection("https://example.org/v1", "fixture-key"))
        whenever(it.isMemoryEnabled()).thenReturn(true)
        whenever(it.getSystemPrompt()).thenReturn("你是梅尔")
        whenever(it.getModel()).thenReturn("m")
        whenever(it.getTemperature()).thenReturn(0.7)
        whenever(it.getMaxTokens()).thenReturn(4096)
        whenever(it.getMemoryMode()).thenReturn(MemoryMode.AUTO)
    }

    private fun textResponse(content: String): HttpResponse {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"$content"}}]}
        """.trimIndent()
        return HttpResponse(200, body.encodeToByteArray(), "application/json")
    }

    private fun service(
        engine: FakeHttpClientEngine,
        memoryManager: MemoryManager?,
        maxTokensCap: Int = Int.MAX_VALUE
    ): ChatService {
        val search = object : MemorySearch {
            override suspend fun search(query: MemoryQuery): List<MemoryItem> = emptyList()
        }
        val executor = MemoryToolExecutor(search)
        return ChatService(
            clientProvider = { OpenAiCompatibleClient("sk-test", "https://api.example.com/v1", engine) },
            conversationManager = ConversationManager(),
            memoryManager = memoryManager,
            settingsManager = settings(),
            postProcessScope = CoroutineScope(Dispatchers.Unconfined),
            config = AppConfig.DEFAULT,
            maxTokensCapProvider = { maxTokensCap },
            toolLoopProvider = {
                ChatToolLoop(clientProvider = {
                    OpenAiCompatibleClient("sk-test", "https://api.example.com/v1", engine)
                }, registry = com.meapet.mobile.chat.tools.ToolRegistry(executor.asTools()))
            },
            memoryTools = executor
        )
    }

    private fun systemOf(engine: FakeHttpClientEngine): String {
        val body = (engine.requests.first().body as RequestBody.Json).content
        return json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["role"]!!.jsonPrimitive.content == "system" }["content"]!!.jsonPrimitive.content
    }

    @Test
    fun `工具模式注入人设事实但不注入关键词回忆`() = runTest {
        val engine = FakeHttpClientEngine { textResponse("好喵") }
        val mm = mock<MemoryManager>().also {
            wheneverBlocking { it.buildPersonaSection() }
                .thenReturn("【用户人设】（永久，可引用 id 更新/删除）\n- [mem_1] (事实) 主人叫小明")
        }

        val result = service(engine, mm).sendMessage("你好")

        assertTrue(result.isSuccess)
        val system = systemOf(engine)
        assertTrue(system.contains(ChatService.MEMORY_TOOL_HINT), "工具说明仍应在首条 system")
        assertTrue(system.contains("【用户人设】"), "人设事实必须注入: $system")
        assertTrue(system.contains("主人叫小明"), "人设条目内容必须注入: $system")
    }

    @Test
    fun `工具模式不调用文本协议 buildContext（无用户输入关键词注入）`() = runTest {
        val engine = FakeHttpClientEngine { textResponse("好喵") }
        val mm = mock<MemoryManager>().also {
            wheneverBlocking { it.buildPersonaSection() }.thenReturn("")
            wheneverBlocking { it.buildContext(any()) }
                .thenThrow(AssertionError("工具模式不应构建文本协议上下文"))
        }

        service(engine, mm).sendMessage("今天吃什么")

        verifyBlocking(mm, never()) { buildContext(any()) }
        val system = systemOf(engine)
        assertTrue(system.contains("【记忆工具】"))
        assertFalse(system.contains("【相关回忆】"), "工具模式不注入关键词回忆")
    }

    @Test
    fun `没有人设时只注入工具说明`() = runTest {
        val engine = FakeHttpClientEngine { textResponse("好喵") }

        service(engine, memoryManager = null).sendMessage("你好")

        val system = systemOf(engine)
        assertEquals(
            "你是梅尔\n\n" + ChatService.MEMORY_TOOL_HINT,
            system,
            "无记忆模块时 system 里只有设置的人设 + 固定工具说明"
        )
    }

    @Test
    fun `max_tokens 按容量上界夹住`() = runTest {
        val engine = FakeHttpClientEngine { textResponse("好喵") }

        service(engine, memoryManager = null, maxTokensCap = 1000).sendMessage("你好")

        val body = (engine.requests.first().body as RequestBody.Json).content
        val request = json.parseToJsonElement(body).jsonObject
        assertEquals(
            "1000",
            request["max_tokens"]!!.jsonPrimitive.content,
            "用户配置 4096，容量上界 1000 → 请求应夹到 1000"
        )
    }
}
