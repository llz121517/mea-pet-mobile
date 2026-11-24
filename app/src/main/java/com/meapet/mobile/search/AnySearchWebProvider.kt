package com.meapet.mobile.search

import com.meapet.mobile.client.JsonHttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class AnySearchWebProvider(
    private val http: JsonHttpClient,
    private val apiKeyProvider: () -> String,
    private val retryDelayMs: Long = 800
) : WebProvider {
    override val id = "anysearch"
    override val endpointHost = ENDPOINT_HOST

    override fun isAvailable(): Boolean = true

    override suspend fun search(query: String, limit: Int): WebOutcome<List<SearchHit>> {
        val count = limit.coerceIn(1, 10)
        val payload = buildJsonObject {
            put("query", query)
            put("max_results", count)
        }
        return http.requestWebData("$BASE_URL/search", payload, apiKeyProvider().trim(), retryDelayMs) { body ->
            val data = body.successData() ?: return@requestWebData null
            (data["results"] as? JsonArray)?.readSearchHits(count)
        }
    }

    override suspend fun fetch(url: String): WebOutcome<FetchedPage> {
        val payload = buildJsonObject { put("url", url) }
        return http.requestWebData(
            "$BASE_URL/extract", payload, apiKeyProvider().trim(), retryDelayMs,
            errorForStatus = { if (it == 422) WebError.NOT_FOUND else webHttpError(it) }
        ) { body ->
            body.successData()?.readFetchedPage()
        }
    }

    private fun JsonObject.successData(): JsonObject? {
        val code = (get("code") as? JsonPrimitive)?.takeUnless { it.isString }
        return if (code?.intOrNull == 0) get("data") as? JsonObject else null
    }

    private companion object {
        const val ENDPOINT_HOST = "api.anysearch.com"
        const val BASE_URL = "https://$ENDPOINT_HOST/v1"
    }
}
