package com.meapet.mobile.config

/** Stored values for the web search provider preference. */
enum class WebSearchProviderId(val id: String) {
    AUTO("auto"),
    DEEPSEEK("deepseek"),
    ANYSEARCH("anysearch");

    companion object {
        fun parse(raw: String?): WebSearchProviderId =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: AUTO
    }
}
