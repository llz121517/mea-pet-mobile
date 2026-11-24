package com.meapet.mobile.search

internal const val DEFAULT_WEB_TIMEOUT_MS = 15_000L

interface WebProvider {
    val id: String
    val endpointHost: String
    fun isAvailable(): Boolean
    suspend fun search(query: String, limit: Int): WebOutcome<List<SearchHit>>
    suspend fun fetch(url: String): WebOutcome<FetchedPage>
}

sealed interface WebOutcome<out T> {
    data class Ok<T>(val value: T) : WebOutcome<T>
    data class Failure(val error: WebError) : WebOutcome<Nothing>
}

enum class WebError {
    INVALID_REQUEST,
    UNAUTHORIZED,
    QUOTA_EXCEEDED,
    RATE_LIMITED,
    UNAVAILABLE,
    NOT_CONFIGURED,
    NOT_FOUND,
    ACCESS_DENIED
}

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val siteName: String? = null,
    val publishedAt: String? = null
)

data class FetchedPage(val title: String, val content: String)
