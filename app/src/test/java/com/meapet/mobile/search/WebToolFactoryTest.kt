package com.meapet.mobile.search

import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.JsonHttpClient
import com.meapet.mobile.client.test.FakeHttpClientEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebToolFactoryTest {
    @Test
    fun `third party chat credentials are never sent to a selected DeepSeek service`() = runTest {
        val engine = FakeHttpClientEngine { error("Third party key must not be reused") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "deepseek", "https://chat.example.org/v1", "private-chat-key", ""
        )
        val raw = tools.single { it.name == "web_search" }.execute("""{"query":"weather"}""")
        assertEquals("not_configured", Json.parseToJsonElement(raw).jsonObject.getValue("error").jsonPrimitive.content)
        assertTrue(engine.requests.isEmpty())
    }

    /**
     * 用户关掉「复用聊天 Key」后，即使聊天地址是官方 DeepSeek 也不能再拿聊天 Key 顶替：
     * 复用是用户显式同意的行为，不能让它在用户不知情时发生。
     */
    @Test
    fun `reuse switch off never falls back to the chat key`() = runTest {
        val engine = FakeHttpClientEngine { error("Chat key must not be reused when switch is off") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "deepseek", "https://api.deepseek.com/v1", "official-chat-key", "",
            deepSeekKey = "", reuseChatKey = false
        )
        val raw = tools.single { it.name == "web_search" }.execute("""{"query":"weather"}""")
        assertEquals("not_configured", Json.parseToJsonElement(raw).jsonObject.getValue("error").jsonPrimitive.content)
        assertTrue(engine.requests.isEmpty())
    }

    /** 关掉复用但单独填了 DeepSeek Key：照常可用，且请求头用的是独立 Key。 */
    @Test
    fun `reuse switch off still uses a separately supplied DeepSeek key`() = runTest {
        val engine = FakeHttpClientEngine { response("""{"results":[]}""") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "deepseek", "https://api.deepseek.com/v1", "official-chat-key", "",
            deepSeekKey = "own-deepseek-key", reuseChatKey = false
        )
        tools.single { it.name == "web_search" }.execute("""{"query":"weather"}""")
        assertEquals("Bearer own-deepseek-key", engine.requests.single().headers["Authorization"])
    }

    /** 复用开关默认开启时，官方地址仍然行为不变（保持既有兼容）。 */
    @Test
    fun `reuse switch on keeps reusing the official chat key`() = runTest {
        val engine = FakeHttpClientEngine { response("""{"results":[]}""") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "deepseek", "https://api.deepseek.com/v1", "official-chat-key", ""
        )
        tools.single { it.name == "web_search" }.execute("""{"query":"weather"}""")
        assertEquals("Bearer official-chat-key", engine.requests.single().headers["Authorization"])
    }

    @Test
    fun `auto uses official credentials for both search and fetch`() = runTest {
        val engine = FakeHttpClientEngine {
            if (it.url.endsWith("/search")) response("""{"results":[]}""")
            else response("""{"title":"Example","content":"Page"}""")
        }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "auto", "https://api.deepseek.com/v1", "official-chat-key", "separate-search-key"
        )
        val search = Json.parseToJsonElement(tools.single { it.name == "web_search" }
            .execute("""{"query":"weather"}""")).jsonObject
        assertEquals("true", search.getValue("ok").jsonPrimitive.content)
        assertEquals("0", search.getValue("returned").jsonPrimitive.content)
        assertPage(tools.single { it.name == "web_fetch" }.execute("""{"url":"https://example.org"}"""))
        assertEquals(listOf("https://api.deepseek.com/v1/tools/search", "https://api.deepseek.com/v1/tools/fetch"), engine.requests.map { it.url })
        assertEquals(listOf("Bearer official-chat-key", "Bearer official-chat-key"), engine.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun `auto on another host uses anonymous AnySearch without the chat key`() = runTest {
        val engine = FakeHttpClientEngine { response("""{"code":0,"data":{"results":[]}}""") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "auto", "https://chat.example.org/v1", "private-chat-key", ""
        )
        tools.single { it.name == "web_search" }.execute("""{"query":"weather"}""")
        assertEquals("https://api.anysearch.com/v1/search", engine.requests.single().url)
        assertEquals(null, engine.requests.single().headers["Authorization"])
    }

    @Test
    fun `explicit AnySearch uses only its own key even on official chat`() = runTest {
        val engine = FakeHttpClientEngine { response("""{"code":0,"data":{"title":"Example","content":"Page"}}""") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "anysearch", "https://api.deepseek.com/v1", "official-chat-key", "separate-search-key"
        )
        assertPage(tools.single { it.name == "web_fetch" }.execute("""{"url":"https://example.org"}"""))
        assertEquals("https://api.anysearch.com/v1/extract", engine.requests.single().url)
        assertEquals("Bearer separate-search-key", engine.requests.single().headers["Authorization"])
    }

    @Test
    fun `explicit DeepSeek key overrides the official chat key`() = runTest {
        val engine = FakeHttpClientEngine { response("""{"title":"Example","content":"Page"}""") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "deepseek", "https://api.deepseek.com/v1", "chat-key", "",
            deepSeekKey = "  dedicated-web-key  "
        )
        assertPage(tools.single { it.name == "web_fetch" }.execute("""{"url":"https://example.org"}"""))
        assertEquals("Bearer dedicated-web-key", engine.requests.single().headers["Authorization"])
    }

    @Test
    fun `dedicated DeepSeek key supports auto and explicit selection with a third party chat`() = runTest {
        val engine = FakeHttpClientEngine {
            if (it.url.endsWith("/search")) response("""{"results":[]}""")
            else response("""{"title":"Example","content":"Page"}""")
        }
        for (selectedId in listOf("auto", "deepseek")) {
            val tools = WebToolFactory(JsonHttpClient(engine)).create(
                selectedId, "https://chat.example.org/v1", "private-chat-key", "",
                deepSeekKey = "dedicated-web-key"
            )
            val search = Json.parseToJsonElement(tools.single { it.name == "web_search" }
                .execute("""{"query":"weather"}""")).jsonObject
            assertEquals("true", search.getValue("ok").jsonPrimitive.content)
            assertPage(tools.single { it.name == "web_fetch" }.execute("""{"url":"https://example.org"}"""))
        }
        assertEquals(List(2) { listOf("https://api.deepseek.com/v1/tools/search", "https://api.deepseek.com/v1/tools/fetch") }.flatten(), engine.requests.map { it.url })
        assertEquals(List(4) { "Bearer dedicated-web-key" }, engine.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun `auto retries the provider HTTP request before falling through with isolated keys`() = runTest {
        val engine = FakeHttpClientEngine {
            if (it.url.startsWith("https://api.deepseek.com/")) {
                HttpResponse(503, "private upstream diagnostic".encodeToByteArray(), "text/plain")
            } else {
                response("""{"code":0,"data":{"results":[{"title":"Weather","url":"https://example.org/weather","snippet":"Sunny"}]}}""")
            }
        }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "auto", "https://api.deepseek.com/v1", "official-chat-key", "separate-search-key",
            deepSeekKey = "dedicated-web-key"
        )

        val result = Json.parseToJsonElement(tools.single { it.name == "web_search" }
            .execute("""{"query":"weather"}""")).jsonObject

        assertEquals("true", result.getValue("ok").jsonPrimitive.content)
        assertEquals("1", result.getValue("returned").jsonPrimitive.content)
        assertEquals(listOf(
            "https://api.deepseek.com/v1/tools/search",
            "https://api.deepseek.com/v1/tools/search",
            "https://api.anysearch.com/v1/search"
        ), engine.requests.map { it.url })
        assertEquals(listOf("Bearer dedicated-web-key", "Bearer dedicated-web-key", "Bearer separate-search-key"),
            engine.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun `auto fetch starts with the matching host then falls back using only the dedicated key`() = runTest {
        val engine = FakeHttpClientEngine {
            if (it.url.startsWith("https://api.anysearch.com/")) {
                HttpResponse(401, "secret failed key".encodeToByteArray(), "text/plain")
            } else {
                response("""{"title":"Example","content":"Page"}""")
            }
        }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "auto", "https://api.anysearch.com/v1", "private-chat-key", "anysearch-key",
            deepSeekKey = "dedicated-web-key"
        )

        assertPage(tools.single { it.name == "web_fetch" }.execute("""{"url":"https://example.org"}"""))
        assertEquals(listOf("https://api.anysearch.com/v1/extract", "https://api.deepseek.com/v1/tools/fetch"),
            engine.requests.map { it.url })
        assertEquals(listOf("Bearer anysearch-key", "Bearer dedicated-web-key"),
            engine.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun `auto fallback to anonymous AnySearch never receives the official chat key`() = runTest {
        val engine = FakeHttpClientEngine {
            if (it.url.startsWith("https://api.deepseek.com/")) {
                HttpResponse(402, "quota diagnostic".encodeToByteArray(), "text/plain")
            } else {
                response("""{"code":0,"data":{"title":"Example","content":"Page"}}""")
            }
        }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "auto", "https://api.deepseek.com/v1", "official-chat-key", ""
        )

        assertPage(tools.single { it.name == "web_fetch" }.execute("""{"url":"https://example.org"}"""))
        assertEquals(listOf("Bearer official-chat-key", null), engine.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun `explicit failed providers do not call another configured provider`() = runTest {
        for (selection in listOf("deepseek", "anysearch")) {
            val engine = FakeHttpClientEngine {
                HttpResponse(401, "private diagnostic".encodeToByteArray(), "text/plain")
            }
            val tools = WebToolFactory(JsonHttpClient(engine)).create(
                selection, "https://api.deepseek.com/v1", "official-chat-key", "anysearch-key"
            )

            for ((name, args) in listOf("web_search" to """{"query":"weather"}""",
                "web_fetch" to """{"url":"https://example.org"}""")) {
                val result = Json.parseToJsonElement(tools.single { it.name == name }.execute(args)).jsonObject
                assertEquals("unauthorized", result.getValue("error").jsonPrimitive.content)
            }
            assertEquals(2, engine.requests.size)
            assertTrue(engine.requests.all { it.url.startsWith("https://api.$selection.com/") })
        }
    }

    @Test
    fun `blank explicit DeepSeek choice never falls back to configured AnySearch`() = runTest {
        val engine = FakeHttpClientEngine { error("Explicit unavailable choice must not switch") }
        val tools = WebToolFactory(JsonHttpClient(engine)).create(
            "deepseek", "https://third-party.example/v1", "private-chat-key", "available-anysearch-key"
        )

        for ((name, args) in listOf("web_search" to """{"query":"weather"}""",
            "web_fetch" to """{"url":"https://example.org"}""")) {
            val result = Json.parseToJsonElement(tools.single { it.name == name }.execute(args)).jsonObject
            assertEquals("not_configured", result.getValue("error").jsonPrimitive.content)
        }
        assertTrue(engine.requests.isEmpty())
    }

    private fun assertPage(raw: String) {
        val page = Json.parseToJsonElement(raw).jsonObject
        assertEquals("true", page.getValue("ok").jsonPrimitive.content)
        assertEquals("Example", page.getValue("title").jsonPrimitive.content)
        assertEquals("Page", page.getValue("content").jsonPrimitive.content)
        assertEquals("false", page.getValue("truncated").jsonPrimitive.content)
    }

    private fun response(body: String) = HttpResponse(statusCode = 200, body = body.encodeToByteArray(), contentType = "application/json")
}
