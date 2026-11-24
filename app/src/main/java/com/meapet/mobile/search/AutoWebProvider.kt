package com.meapet.mobile.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** One ordered, call-local pass through the configured AUTO candidates. */
internal class AutoWebProvider(private val candidates: List<WebProvider>) : WebProvider {
    override val id = "auto"
    override val endpointHost = ""
    override fun isAvailable() = candidates.isNotEmpty()

    override suspend fun search(query: String, limit: Int) = attempt { search(query, limit) }
    override suspend fun fetch(url: String) = attempt { fetch(url) }

    private suspend fun <T> attempt(request: suspend WebProvider.() -> WebOutcome<T>): WebOutcome<T> {
        var lastFailure = WebOutcome.Failure(WebError.NOT_CONFIGURED)
        if (candidates.isEmpty()) return lastFailure
        // Leave room for resolution and result encoding inside the tool's outer deadline.
        val attemptTimeoutMs = ((DEFAULT_WEB_TIMEOUT_MS - TIMEOUT_SLACK_MS) / candidates.size).coerceAtLeast(1)
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val outcome = try {
                withTimeoutOrNull(attemptTimeoutMs) { candidate.request() }
                    ?: WebOutcome.Failure(WebError.UNAVAILABLE)
            } catch (e: CancellationException) {
                // Only this attempt's own timeout is recoverable. Caller cancellation always escapes.
                throw e
            } catch (_: Exception) {
                WebOutcome.Failure(WebError.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            when (outcome) {
                is WebOutcome.Ok -> return outcome
                is WebOutcome.Failure -> {
                    if (outcome.error == WebError.INVALID_REQUEST) return outcome
                    lastFailure = outcome
                }
            }
        }
        return lastFailure
    }

    private companion object {
        const val TIMEOUT_SLACK_MS = 250L
    }
}
