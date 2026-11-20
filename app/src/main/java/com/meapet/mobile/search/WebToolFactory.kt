package com.meapet.mobile.search

import com.meapet.mobile.chat.tools.Tool
import com.meapet.mobile.client.JsonHttpClient

/** Builds a tool pair with immutable connection settings for one turn. */
class WebToolFactory(private val http: JsonHttpClient) {
    fun create(
        selectedId: String,
        chatBaseUrl: String,
        chatApiKey: String,
        anySearchKey: String,
        deepSeekKey: String = ""
    ): List<Tool> {
        val resolvedDeepSeekKey = deepSeekKey.trim().ifEmpty {
            chatApiKey.takeIf { DeepSeekWebProvider.canReuseChatKey(chatBaseUrl) }.orEmpty()
        }
        val resolver = WebProviderResolver(listOf(
            DeepSeekWebProvider(http, apiKeyProvider = { resolvedDeepSeekKey }),
            AnySearchWebProvider(http, apiKeyProvider = { anySearchKey })
        ))
        val provider = resolver.resolve(selectedId, chatBaseUrl)
        return listOf(WebSearchTool({ provider }), WebFetchTool({ provider }))
    }
}
