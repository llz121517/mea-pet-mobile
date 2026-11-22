package com.meapet.mobile.search

import com.meapet.mobile.chat.tools.Tool
import com.meapet.mobile.client.JsonHttpClient

/**
 * Builds a tool pair with immutable connection settings for one turn.
 *
 * DeepSeek Key 的来源由 [reuseChatKey] 决定：
 * - true（默认）：使用独立 Key；独立 Key 留空时复用「API 配置」里的聊天 Key
 *   （仅当聊天端点确实是官方 DeepSeek 地址，见 [DeepSeekWebProvider.canReuseChatKey]）；
 * - false：只用独立 Key，未填写则该服务商不可用（不回退聊天 Key），
 *   设置页此时会展示单独的 DeepSeek Key 输入框。
 */
class WebToolFactory(private val http: JsonHttpClient) {
    fun create(
        selectedId: String,
        chatBaseUrl: String,
        chatApiKey: String,
        anySearchKey: String,
        deepSeekKey: String = "",
        reuseChatKey: Boolean = true
    ): List<Tool> {
        val resolvedDeepSeekKey = deepSeekKey.trim().ifEmpty {
            chatApiKey.takeIf { DeepSeekWebProvider.shouldReuseChatKey(chatBaseUrl, reuseChatKey) }.orEmpty()
        }
        val resolver = WebProviderResolver(listOf(
            DeepSeekWebProvider(http, apiKeyProvider = { resolvedDeepSeekKey }),
            AnySearchWebProvider(http, apiKeyProvider = { anySearchKey })
        ))
        val provider = resolver.resolve(selectedId, chatBaseUrl)
        return listOf(WebSearchTool({ provider }), WebFetchTool({ provider }))
    }
}
