package com.meapet.mobile.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutoWebProviderTest {
    @Test
    fun `auto tries matching hosts first then each remaining provider in registration order`() = runTest {
        val calls = mutableListOf<String>()
        val providers = listOf(
            fake("first", calls),
            fake("host-first", calls, host = "chat.example.org"),
            fake("unavailable", calls, available = false),
            fake("host-second", calls, host = "chat.example.org"),
            fake("last", calls, search = { _, _ -> WebOutcome.Ok(hits) })
        )
        val selected = WebProviderResolver(providers).resolve("auto", "https://CHAT.EXAMPLE.ORG:443/v1")!!

        assertEquals(WebOutcome.Ok(hits), selected.search("weather", 3))
        assertEquals(listOf("host-first:search", "host-second:search", "first:search", "last:search"), calls)
    }

    @Test
    fun `auto uses registration order for invalid and nonmatching chat hosts`() = runTest {
        for (url in listOf("not an endpoint", "https://chat.example.org.other/v1", "https://chat.example.org@other.example/v1")) {
            val calls = mutableListOf<String>()
            val selected = WebProviderResolver(listOf(
                fake("first", calls),
                fake("second", calls, host = "chat.example.org", search = { _, _ -> WebOutcome.Ok(hits) })
            )).resolve("auto", url)!!

            assertEquals(WebOutcome.Ok(hits), selected.search("weather", 3))
            assertEquals(listOf("first:search", "second:search"), calls)
        }
    }

    @Test
    fun `auto search passes arguments and advances on every normalized failure except invalid request`() = runTest {
        for (error in WebError.entries.filter { it != WebError.INVALID_REQUEST }) {
            val calls = mutableListOf<String>()
            val selected = WebProviderResolver(listOf(
                fake("first", calls, search = { _, _ -> WebOutcome.Failure(error) }),
                fake("second", calls, search = { query, limit ->
                    assertEquals("weather", query)
                    assertEquals(3, limit)
                    WebOutcome.Ok(hits)
                }),
                fake("unused", calls)
            )).resolve("auto", "https://chat.example.org")!!

            assertEquals(WebOutcome.Ok(hits), selected.search("weather", 3))
            assertEquals(listOf("first:search", "second:search"), calls)
        }
    }

    @Test
    fun `auto fetch advances to the next provider and preserves the URL`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls),
            fake("second", calls, fetch = { url ->
                assertEquals("https://example.org/page", url)
                WebOutcome.Ok(page)
            })
        )).resolve("auto", "")!!

        assertEquals(WebOutcome.Ok(page), selected.fetch("https://example.org/page"))
        assertEquals(listOf("first:fetch", "second:fetch"), calls)
    }

    @Test
    fun `empty search results stop auto without trying another provider`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls, search = { _, _ -> WebOutcome.Ok(emptyList<SearchHit>()) }),
            fake("unused", calls)
        )).resolve("auto", "")!!

        assertEquals(WebOutcome.Ok(emptyList<SearchHit>()), selected.search("weather", 3))
        assertEquals(listOf("first:search"), calls)
    }

    @Test
    fun `all auto failures return the last normalized failure once per candidate`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls, fetch = { WebOutcome.Failure(WebError.UNAUTHORIZED) }),
            fake("last", calls, fetch = { WebOutcome.Failure(WebError.ACCESS_DENIED) })
        )).resolve("auto", "")!!

        assertEquals(WebOutcome.Failure(WebError.ACCESS_DENIED), selected.fetch("https://example.org"))
        assertEquals(listOf("first:fetch", "last:fetch"), calls)
        assertNull(WebProviderResolver(emptyList()).resolve("auto", ""))
    }

    @Test
    fun `invalid request is terminal for both auto operations`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls,
                search = { _, _ -> WebOutcome.Failure(WebError.INVALID_REQUEST) },
                fetch = { WebOutcome.Failure(WebError.INVALID_REQUEST) }),
            fake("unused", calls)
        )).resolve("auto", "")!!

        assertEquals(WebOutcome.Failure(WebError.INVALID_REQUEST), selected.search("weather", 3))
        assertEquals(WebOutcome.Failure(WebError.INVALID_REQUEST), selected.fetch("https://example.org"))
        assertEquals(listOf("first:search", "first:fetch"), calls)
    }

    @Test
    fun `provider cancellation propagates without trying the next candidate`() = runTest {
        val cancellation = CancellationException("cancelled")
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls, search = { _, _ -> throw cancellation }),
            fake("unused", calls)
        )).resolve("auto", "")!!

        val caught = assertIs<CancellationException>(runCatching { selected.search("weather", 3) }.exceptionOrNull())
        assertEquals("cancelled", caught.message)
        assertEquals(listOf("first:search"), calls)
    }

    @Test
    fun `outer tool cancellation never advances to another auto provider`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls, fetch = { awaitCancellation() }),
            fake("unused", calls)
        )).resolve("auto", "")!!

        val result = withTimeoutOrNull(10) {
            WebFetchTool({ selected }).execute("""{"url":"https://example.org"}""")
        }

        assertNull(result)
        assertEquals(listOf("first:fetch"), calls)
    }

    @Test
    fun `timed out provider leaves enough of the tool budget for the next provider`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls, fetch = { awaitCancellation() }),
            fake("second", calls, fetch = { WebOutcome.Ok(page) })
        )).resolve("auto", "")!!

        val result = Json.parseToJsonElement(WebFetchTool({ selected })
            .execute("""{"url":"https://example.org"}""")).jsonObject

        assertEquals("true", result.getValue("ok").jsonPrimitive.content)
        assertEquals("Page", result.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("first:fetch", "second:fetch"), calls)
        assertTrue(testScheduler.currentTime in 1..7_500)
    }

    @Test
    fun `all timed out candidates are attempted within the existing total tool budget`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver((1..3).map { n ->
            fake("provider-$n", calls, search = { _, _ -> awaitCancellation() })
        }).resolve("auto", "")!!

        val result = Json.parseToJsonElement(WebSearchTool({ selected })
            .execute("""{"query":"weather"}""")).jsonObject

        assertEquals("unavailable", result.getValue("error").jsonPrimitive.content)
        assertEquals(listOf("provider-1:search", "provider-2:search", "provider-3:search"), calls)
        assertTrue(testScheduler.currentTime in 1 until 15_000)
    }

    @Test
    fun `unexpected provider exceptions become safe failures and allow failover`() = runTest {
        val calls = mutableListOf<String>()
        val selected = WebProviderResolver(listOf(
            fake("first", calls, search = { _, _ -> error("secret-credential") }),
            fake("second", calls, search = { _, _ -> WebOutcome.Ok(hits) })
        )).resolve("auto", "")!!

        assertEquals(WebOutcome.Ok(hits), selected.search("weather", 3))
        assertEquals(listOf("first:search", "second:search"), calls)
        val allThrow = WebProviderResolver(listOf(
            fake("last", calls, fetch = { error("secret-credential") })
        )).resolve("auto", "")!!
        val raw = WebFetchTool({ allThrow }).execute("""{"url":"https://example.org"}""")
        assertEquals("unavailable", Json.parseToJsonElement(raw).jsonObject.getValue("error").jsonPrimitive.content)
        assertFalse(raw.contains("secret-credential"))
    }

    @Test
    fun `auto starts over on every call without remembering provider failures`() = runTest {
        val calls = mutableListOf<String>()
        var firstRecovered = false
        val selected = WebProviderResolver(listOf(
            fake("first", calls, search = { _, _ ->
                if (firstRecovered) WebOutcome.Ok(hits) else WebOutcome.Failure(WebError.UNAVAILABLE)
            }),
            fake("second", calls, search = { _, _ -> WebOutcome.Ok(emptyList<SearchHit>()) })
        )).resolve("auto", "")!!

        assertEquals(WebOutcome.Ok(emptyList<SearchHit>()), selected.search("weather", 3))
        firstRecovered = true
        assertEquals(WebOutcome.Ok(hits), selected.search("weather", 3))
        assertEquals(listOf("first:search", "second:search", "first:search"), calls)
    }

    @Test
    fun `explicit selections isolate failures and unavailable selections never switch`() = runTest {
        val calls = mutableListOf<String>()
        val explicit = fake("selected", calls, fetch = { WebOutcome.Failure(WebError.UNAUTHORIZED) })
        val resolver = WebProviderResolver(listOf(
            explicit,
            fake("unavailable", calls, available = false),
            fake("unused", calls, fetch = { WebOutcome.Ok(page) })
        ))

        assertSame(explicit, resolver.resolve("selected", "https://unused.example.org"))
        assertEquals(WebOutcome.Failure(WebError.UNAUTHORIZED), resolver.resolve("selected", "")!!.fetch("https://example.org"))
        assertNull(resolver.resolve("unavailable", ""))
        assertNull(resolver.resolve("unknown", ""))
        assertEquals(listOf("selected:fetch"), calls)
    }

    private fun fake(
        id: String,
        calls: MutableList<String>,
        host: String = "$id.example.org",
        available: Boolean = true,
        search: suspend (String, Int) -> WebOutcome<List<SearchHit>> = { _, _ -> WebOutcome.Failure(WebError.UNAVAILABLE) },
        fetch: suspend (String) -> WebOutcome<FetchedPage> = { WebOutcome.Failure(WebError.UNAVAILABLE) }
    ) = object : WebProvider {
        override val id = id
        override val endpointHost = host
        override fun isAvailable() = available
        override suspend fun search(query: String, limit: Int): WebOutcome<List<SearchHit>> {
            calls += "$id:search"
            return search.invoke(query, limit)
        }
        override suspend fun fetch(url: String): WebOutcome<FetchedPage> {
            calls += "$id:fetch"
            return fetch.invoke(url)
        }
    }

    private val hits = listOf(SearchHit("Weather", "https://example.org/weather", "Sunny"))
    private val page = FetchedPage("Example", "Page")
}
