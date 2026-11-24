package com.meapet.mobile.search

import com.meapet.mobile.client.exception.ApiException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebToolsTest {
    @Test
    fun `search supplies a default limit and preserves the full result URL`() = runTest {
        val url = "https://example.org/article?ref=" + "x".repeat(310)
        val provider = TestProvider(onSearch = { query, limit ->
            assertEquals("天气", query)
            assertEquals(5, limit)
            WebOutcome.Ok(listOf(SearchHit("天气预报", url, "今日晴")))
        })

        val result = json(WebSearchTool({ provider }).execute("""{"query":" 天气 "}"""))

        assertEquals("1", result.getValue("returned").jsonPrimitive.content)
        assertEquals(url, result.getValue("results").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `search bounds serialized output even when snippets need escaping`() = runTest {
        val provider = TestProvider(onSearch = { _, limit ->
            assertEquals(10, limit)
            WebOutcome.Ok(List(10) { SearchHit("标题", "https://example.org/$it", "\n\t".repeat(120)) })
        })

        val raw = WebSearchTool({ provider }).execute("""{"query":"天气","limit":100}""")
        val result = json(raw)
        val count = result.getValue("results").jsonArray.size
        assertTrue(raw.length <= 1500)
        assertTrue(count in 1..9)
        assertEquals(count.toString(), result.getValue("returned").jsonPrimitive.content)
    }

    @Test
    fun `fetch rejects unsupported schemes and embedded credentials before calling an adapter`() = runTest {
        val tool = WebFetchTool({ error("Invalid URL must not reach a provider") })
        for (url in listOf("file:///sdcard/test", "https://user:pass@example.org/", "https:///no-host")) {
            assertEquals("invalid_request", errorOf(tool.execute("""{"url":"$url"}""")))
        }
    }

    @Test
    fun `fetch returns a bounded excerpt and marks truncation`() = runTest {
        val provider = TestProvider(onFetch = { url ->
            assertEquals("https://example.org/post", url)
            WebOutcome.Ok(FetchedPage("示例", "\n正文🙂".repeat(4000)))
        })
        val raw = WebFetchTool({ provider }).execute("""{"url":"https://example.org/post"}""")
        val result = json(raw)
        val content = result.getValue("content").jsonPrimitive.content
        assertEquals("true", result.getValue("truncated").jsonPrimitive.content)
        assertTrue(content.startsWith("\n正文🙂"))
        assertFalse(content.last().isHighSurrogate())
        assertTrue(raw.length <= 8000)
    }

    @Test
    fun `provider exceptions return safe errors and never carry raw payload`() = runTest {
        val provider = TestProvider(onFetch = { throw ApiException(404, "secret-provider-credential", "provider request failed") })
        val raw = WebFetchTool({ provider }).execute("""{"url":"https://example.org/post"}""")
        assertEquals("unavailable", errorOf(raw))
        assertFalse(raw.contains("secret-provider-credential"))
    }

    @Test
    fun `tool timeout returns an error while enclosing cancellation propagates`() = runTest {
        val provider = TestProvider(onFetch = { awaitCancellation() })
        val args = """{"url":"https://example.org/post"}"""
        assertEquals("unavailable", errorOf(WebFetchTool({ provider }, timeoutMs = 5).execute(args)))
        val result = withTimeoutOrNull(5) { WebFetchTool({ provider }, timeoutMs = 100).execute(args) }
        assertEquals(null, result)
    }

    @Test
    fun `malformed arguments never reach a provider`() = runTest {
        val search = WebSearchTool({ error("Invalid arguments must not resolve a provider") })
        for (args in listOf("[]", "{", "{}", """{"query":7}""", """{"query":"   "}""",
            """{"query":"ok","limit":"3"}""", """{"query":"ok","limit":2.5}""")) {
            assertEquals("invalid_request", errorOf(search.execute(args)))
        }
        assertEquals("invalid_request", errorOf(search.execute("""{"query":"${"字".repeat(129)}"}""")))
    }

    @Test
    fun `unavailable selection reports a safe configuration error`() = runTest {
        assertEquals("not_configured", errorOf(WebSearchTool({ null }).execute("""{"query":"weather"}""")))
        assertEquals("not_configured", errorOf(WebFetchTool({ null }).execute("""{"url":"https://example.org"}""")))
    }

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun errorOf(raw: String) = json(raw).getValue("error").jsonPrimitive.content

    private class TestProvider(
        private val onSearch: suspend (String, Int) -> WebOutcome<List<SearchHit>> = { _, _ -> error("unexpected search") },
        private val onFetch: suspend (String) -> WebOutcome<FetchedPage> = { error("unexpected fetch") }
    ) : WebProvider {
        override val id = "test"
        override val endpointHost = "example.org"
        override fun isAvailable() = true
        override suspend fun search(query: String, limit: Int) = onSearch(query, limit)
        override suspend fun fetch(url: String) = onFetch(url)
    }
}
