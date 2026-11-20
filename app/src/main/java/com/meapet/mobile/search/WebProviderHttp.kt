package com.meapet.mobile.search

import com.meapet.mobile.client.JsonHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal suspend fun <T : Any> JsonHttpClient.requestWebData(
    endpoint: String,
    payload: JsonObject,
    apiKey: String,
    retryDelayMs: Long,
    errorForStatus: (Int) -> WebError = ::webHttpError,
    decode: (JsonObject) -> T?
): WebOutcome<T> = try {
    val headers = if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
    var response = postJson(endpoint, payload, headers)
    if (response.statusCode == 429 || response.statusCode in 500..599) {
        delay(retryDelayMs)
        response = postJson(endpoint, payload, headers)
    }
    if (response.statusCode !in 200..299) {
        // Quota errors may contain credentials. Never decode or expose an error body.
        WebOutcome.Failure(errorForStatus(response.statusCode))
    } else {
        val data = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        val result = data?.let(decode)
        if (result == null) WebOutcome.Failure(WebError.UNAVAILABLE) else WebOutcome.Ok(result)
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    WebOutcome.Failure(WebError.UNAVAILABLE)
}

internal fun webHttpError(status: Int): WebError = when (status) {
    400, 413, 415, 422 -> WebError.INVALID_REQUEST
    401, 403 -> WebError.UNAUTHORIZED
    402 -> WebError.QUOTA_EXCEEDED
    404 -> WebError.NOT_FOUND
    429 -> WebError.RATE_LIMITED
    else -> WebError.UNAVAILABLE
}

internal fun JsonObject.readString(key: String): String? = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

internal fun JsonObject.readFetchedPage(): FetchedPage? {
    val content = readString("content") ?: return null
    return FetchedPage(readString("title").orEmpty(), content)
}

internal fun JsonObject.readSearchHit(): SearchHit? {
    val url = readString("url")?.takeUnless { it.isBlank() } ?: return null
    return SearchHit(readString("title").orEmpty(), url, readString("snippet").orEmpty())
}

internal fun JsonArray.readSearchHits(
    limit: Int,
    decode: JsonObject.() -> SearchHit? = JsonObject::readSearchHit
): List<SearchHit> = asSequence().filterIsInstance<JsonObject>().mapNotNull(decode).take(limit).toList()
