package com.meapet.mobile.search

import java.net.URI
import java.net.URISyntaxException

class WebProviderResolver(private val providers: List<WebProvider>) {
    fun resolve(selectedId: String, chatBaseUrl: String): WebProvider? {
        if (selectedId != "auto") {
            return providers.firstOrNull { it.id == selectedId && it.isAvailable() }
        }
        val host = try {
            URI(chatBaseUrl.trim()).host
        } catch (_: URISyntaxException) {
            null
        }
        val available = providers.filter { it.isAvailable() }
        if (available.isEmpty()) return null
        val (matching, remaining) = available.partition { it.endpointHost.equals(host, ignoreCase = true) }
        return AutoWebProvider(matching + remaining)
    }
}
