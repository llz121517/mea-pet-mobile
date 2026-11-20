package com.meapet.mobile.search

import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpRequest
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.JsonHttpClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.test.FakeHttpClientEngine
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebProviderTest {
    @Test
    fun deepSeekSearchSendsAuthenticatedRequestAndMapsResults() = runTest {
        val engine = FakeHttpClientEngine {
            response(200, """{
                "results":[
                    {"rerank_score":2,"title":"Article","url":"https://example.org/a","snippet":"Summary","summary":"Longer summary","content":"","site_name":"Example","site_icon":"","date":"2026-11-19T01:00:00Z","authority":0},
                    {"url":"https://example.org/b","title":null,"snippet":null},
                    {"title":"No source"}
                ]
            }""")
        }
        val outcome = DeepSeekWebProvider(JsonHttpClient(engine), { " key " }).search("weather", 3)

        assertRequest(
            engine.requests.single(), "https://api.deepseek.com/v1/tools/search", "Bearer key",
            """{"query":"weather","count":3,"need_content":false}"""
        )
        assertEquals(WebOutcome.Ok(listOf(
            SearchHit("Article", "https://example.org/a", "Summary", "Example", "2026-11-19T01:00:00Z"),
            SearchHit("", "https://example.org/b", "")
        )), outcome)
    }

    @Test
    fun deepSeekFetchPostsToOfficialHostAndMapsMarkdown() = runTest {
        val engine = FakeHttpClientEngine {
            response(200, """{"title":"","content":"# Markdown page"}""")
        }
        val outcome = DeepSeekWebProvider(JsonHttpClient(engine), { "key" }).fetch("https://example.org/a")

        assertRequest(
            engine.requests.single(), "https://api.deepseek.com/v1/tools/fetch", "Bearer key",
            """{"url":"https://example.org/a"}"""
        )
        assertEquals(WebOutcome.Ok(FetchedPage("", "# Markdown page")), outcome)
    }

    @Test
    fun deepSeekWithBlankKeyMakesNoRequests() = runTest {
        val engine = FakeHttpClientEngine { error("Do not request without credentials") }
        val provider = DeepSeekWebProvider(JsonHttpClient(engine), { "   " })

        assertFalse(provider.isAvailable())
        assertEquals(WebOutcome.Failure(WebError.NOT_CONFIGURED), provider.search("test", 1))
        assertEquals(WebOutcome.Failure(WebError.NOT_CONFIGURED), provider.fetch("https://example.org"))
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun anySearchCapsRequestAndReturnedHitCount() = runTest {
        val hits = (1..11).joinToString { n -> """{"title":"Hit $n","url":"https://example.org/$n"}""" }
        val engine = FakeHttpClientEngine {
            response(200, """{"code":0,"message":"success","request_id":"search-id","data":{"results":[$hits],"metadata":{"total_results":11,"search_time_ms":300}}}""")
        }
        val provider = AnySearchWebProvider(JsonHttpClient(engine), { "  " })
        val outcome = provider.search("weather", 50)

        assertTrue(provider.isAvailable())
        assertRequest(
            engine.requests.single(), "https://api.anysearch.com/v1/search", null,
            """{"query":"weather","max_results":10}"""
        )
        assertEquals(WebOutcome.Ok((1..10).map { n -> SearchHit("Hit $n", "https://example.org/$n", "") }), outcome)
    }

    @Test
    fun anySearchFetchReadsDataEnvelopeInBothAuthModes() = runTest {
        var key = ""
        val engine = FakeHttpClientEngine {
            response(200, """{"code":0,"message":"success","request_id":"extract-id","data":{"url":"https://example.org/a","title":"Example Article","content":"Extracted page content..."}}""")
        }
        val provider = AnySearchWebProvider(JsonHttpClient(engine), { key })
        val expected = WebOutcome.Ok(FetchedPage("Example Article", "Extracted page content..."))

        assertEquals(expected, provider.fetch("https://example.org/a"))
        key = " as_sk_test "
        assertEquals(expected, provider.fetch("https://example.org/a"))
        assertEquals(2, engine.requests.size)
        listOf(null, "Bearer as_sk_test").forEachIndexed { index, auth ->
            assertRequest(
                engine.requests[index], "https://api.anysearch.com/v1/extract", auth,
                """{"url":"https://example.org/a"}"""
            )
        }
    }

    @Test
    fun documentedEmptyResultArraysAreSuccessful() = runTest {
        val deepSeek = DeepSeekWebProvider(JsonHttpClient(FakeHttpClientEngine {
            response(200, """{"results":[]}""")
        }), { "key" })
        val anySearch = AnySearchWebProvider(JsonHttpClient(FakeHttpClientEngine {
            response(200, """{"code":0,"message":"success","request_id":"empty","data":{"results":[],"metadata":{"total_results":0,"search_time_ms":100}}}""")
        }), { "" })

        assertEquals(WebOutcome.Ok(emptyList<SearchHit>()), deepSeek.search("test", 1))
        assertEquals(WebOutcome.Ok(emptyList<SearchHit>()), anySearch.search("test", 1))
    }

    @Test
    fun extractErrorsUseOnlySafeCodes() = runTest {
        val cases = listOf(
            Triple(400, WebError.INVALID_REQUEST, WebError.INVALID_REQUEST),
            Triple(401, WebError.UNAUTHORIZED, WebError.UNAUTHORIZED),
            Triple(402, WebError.QUOTA_EXCEEDED, WebError.QUOTA_EXCEEDED),
            Triple(403, WebError.ACCESS_DENIED, WebError.UNAUTHORIZED),
            Triple(404, WebError.NOT_FOUND, WebError.NOT_FOUND),
            Triple(408, WebError.UNAVAILABLE, WebError.UNAVAILABLE),
            Triple(422, WebError.INVALID_REQUEST, WebError.NOT_FOUND),
            Triple(429, WebError.RATE_LIMITED, WebError.RATE_LIMITED),
            Triple(503, WebError.UNAVAILABLE, WebError.UNAVAILABLE)
        )
        for ((status, deepSeekError, anySearchError) in cases) {
            val engine = FakeHttpClientEngine {
                response(status, """{"code":-1,"message":"password=fake; api_key=fake","error_code":"extract_failed"}""")
            }
            val http = JsonHttpClient(engine)
            val url = "https://example.org/a"
            assertEquals(WebOutcome.Failure(deepSeekError), DeepSeekWebProvider(http, { "key" }, 0).fetch(url))
            assertEquals(WebOutcome.Failure(anySearchError), AnySearchWebProvider(http, { "" }, 0).fetch(url))
            val requestsPerProvider = if (status == 429 || status in 500..599) 2 else 1
            assertEquals(2 * requestsPerProvider, engine.requests.size)
        }
    }

    @Test
    fun unauthorizedAnySearchDoesNotFallBackToAnonymous() = runTest {
        val engine = FakeHttpClientEngine { response(401, """{"code":-1,"message":"Invalid key"}""") }
        val outcome = AnySearchWebProvider(JsonHttpClient(engine), { "bad-key" }, 0).search("test", 1)

        assertEquals(WebOutcome.Failure(WebError.UNAUTHORIZED), outcome)
        assertEquals("Bearer bad-key", engine.requests.single().headers["Authorization"])
    }

    @Test
    fun rateLimitedSearchRetriesSameRequestOnceAndReturnsSuccess() = runTest {
        val responses = ArrayDeque(listOf(response(429, "{}"), response(200, """{"results":[]}""")))
        val engine = FakeHttpClientEngine { responses.removeFirst() }
        val outcome = DeepSeekWebProvider(JsonHttpClient(engine), { "key" }, 1).search("test", 1)

        assertEquals(WebOutcome.Ok(emptyList<SearchHit>()), outcome)
        assertEquals(2, engine.requests.size)
        assertEquals(engine.requests.first(), engine.requests.last())
    }

    @Test
    fun errorEnvelopeAndMalformedSuccessAreUnavailableWithoutRetry() = runTest {
        val bodies = listOf(
            "not JSON",
            """{"code":-1,"message":"password=fake","data":{"title":"secret","content":"secret"}}""",
            """{"code":0,"data":{"title":"Example","content":{}}}"""
        )
        for (body in bodies) {
            val engine = FakeHttpClientEngine { response(200, body) }
            val outcome = AnySearchWebProvider(JsonHttpClient(engine), { "" }, 0).fetch("https://example.org/a")

            assertEquals(WebOutcome.Failure(WebError.UNAVAILABLE), outcome)
            assertEquals(1, engine.requests.size)
        }
    }

    @Test
    fun networkErrorsDoNotRetry() = runTest {
        val engine = FakeHttpClientEngine { throw IOException("private diagnostic") }
        val outcome = DeepSeekWebProvider(JsonHttpClient(engine), { "key" }, 0).search("test", 1)

        assertEquals(WebOutcome.Failure(WebError.UNAVAILABLE), outcome)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun cancellationFromNetworkPropagates() = runTest {
        val cancellation = CancellationException("cancelled")
        val engine = FakeHttpClientEngine { throw cancellation }
        val provider = AnySearchWebProvider(JsonHttpClient(engine), { "" }, 0)

        val caught = runCatching { provider.search("test", 1) }.exceptionOrNull()

        assertSame(cancellation, caught)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun deepSeekChatKeyReuseRequiresOfficialHttpsOrigin() {
        val eligible = listOf("https://api.deepseek.com/v1", "https://API.DEEPSEEK.COM:443/v1")
        val ineligible = listOf(
            "https://example.org/v1",
            "https://api.deepseek.com.example.org/v1",
            "http://api.deepseek.com/v1",
            "https://api.deepseek.com:444/v1",
            "https://account@api.deepseek.com/v1",
            "not an endpoint"
        )

        eligible.forEach { assertTrue(it, DeepSeekWebProvider.canReuseChatKey(it)) }
        ineligible.forEach { assertFalse(it, DeepSeekWebProvider.canReuseChatKey(it)) }
    }

    private fun response(status: Int, body: String) = HttpResponse(status, body.encodeToByteArray(), "application/json")

    private fun assertRequest(request: HttpRequest, endpoint: String, auth: String?, body: String) {
        assertEquals(HttpMethod.POST, request.method)
        assertEquals(endpoint, request.url)
        assertEquals(auth, request.headers["Authorization"])
        assertEquals(
            Json.parseToJsonElement(body) as JsonObject,
            Json.parseToJsonElement((request.body as RequestBody.Json).content)
        )
    }
}
