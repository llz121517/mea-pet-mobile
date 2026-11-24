package com.meapet.mobile.search

import com.meapet.mobile.client.JsonHttpClient
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DeepSeekWebProvider(
    private val http: JsonHttpClient,
    private val apiKeyProvider: () -> String,
    private val retryDelayMs: Long = 800
) : WebProvider {
    override val id = "deepseek"
    override val endpointHost = ENDPOINT_HOST

    override fun isAvailable(): Boolean = apiKeyProvider().isNotBlank()

    override suspend fun search(query: String, limit: Int): WebOutcome<List<SearchHit>> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) return WebOutcome.Failure(WebError.NOT_CONFIGURED)
        val count = limit.coerceIn(1, 50)
        val payload = buildJsonObject {
            put("query", query)
            put("count", count)
            put("need_content", false)
        }
        return http.requestWebData("$BASE_URL/search", payload, apiKey, retryDelayMs) { body ->
            if ("error" in body) return@requestWebData null
            (body["results"] as? JsonArray)?.readSearchHits(count) {
                readSearchHit()?.copy(siteName = readString("site_name"), publishedAt = readString("date"))
            }
        }
    }

    override suspend fun fetch(url: String): WebOutcome<FetchedPage> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) return WebOutcome.Failure(WebError.NOT_CONFIGURED)
        val payload = buildJsonObject { put("url", url) }
        return http.requestWebData(
            "$BASE_URL/fetch", payload, apiKey, retryDelayMs,
            errorForStatus = { if (it == 403) WebError.ACCESS_DENIED else webHttpError(it) }
        ) { body ->
            if ("error" in body) null else body.readFetchedPage()
        }
    }

    companion object {
        private const val ENDPOINT_HOST = "api.deepseek.com"
        private const val BASE_URL = "https://$ENDPOINT_HOST/v1/tools"

        fun canReuseChatKey(chatBaseUrl: String): Boolean {
            val uri = try {
                URI(chatBaseUrl.trim())
            } catch (_: URISyntaxException) {
                return false
            }
            return ENDPOINT_HOST.equals(uri.host, ignoreCase = true) &&
                "https".equals(uri.scheme, ignoreCase = true) &&
                uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
        }

        /**
         * 是否应复用聊天 Key 作为 DeepSeek Key。
         *
         * 双重条件：用户**显式允许**复用（设置开关，默认开），且聊天端点确实是官方
         * DeepSeek HTTPS 地址（[canReuseChatKey]）。只看地址不够——用户可能把某个
         * 中转/聚合服务的 Key 填在「API 配置」里，直接拿去请求 api.deepseek.com 就
         * 等于把第三方的 Key 发给了 DeepSeek；开关让这件事必须由用户点头。
         */
        fun shouldReuseChatKey(chatBaseUrl: String, userAllowsReuse: Boolean): Boolean =
            userAllowsReuse && canReuseChatKey(chatBaseUrl)
    }
}
