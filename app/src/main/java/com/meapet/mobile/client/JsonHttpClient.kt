package com.meapet.mobile.client

import kotlinx.serialization.json.JsonObject

/** Sends JSON and leaves HTTP status handling to the caller. Never logs request or response data. */
class JsonHttpClient(private val engine: HttpClientEngine) {
    suspend fun postJson(
        url: String,
        json: JsonObject,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse = engine.execute(
        HttpRequest(
            method = HttpMethod.POST,
            url = url,
            headers = headers,
            body = RequestBody.Json(json.toString())
        )
    )
}
