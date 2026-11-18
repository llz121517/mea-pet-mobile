package com.meapet.mobile.client

import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextCapacityProviderTest {

    private fun settings(url: String = "https://api.example.com/v1", manual: Int? = null): SettingsManager =
        mock<SettingsManager>().also {
            whenever(it.getApiUrl()).thenReturn(url)
            whenever(it.getModel()).thenReturn("m")
            whenever(it.getMaxTokens()).thenReturn(4096)
            whenever(it.getContextWindow()).thenReturn(manual)
        }

    private fun provider(sm: SettingsManager, now: () -> Long = { 0L }) = ContextCapacityProvider(
        settingsManager = sm,
        clientFactory = {
            OpenAiCompatibleClient("k", "https://api.example.com/v1", FakeHttpClientEngine {
                HttpResponse(200, """{"data":[{"id":"m","context_length":65536}]}""".encodeToByteArray(), "application/json")
            })
        },
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = now
    )

    @Test
    fun fallbackWhenNothingProbed() {
        val c = provider(settings()).current()
        assertEquals(CapacitySource.FALLBACK, c.source)
        assertEquals(32768, c.tokens)
    }

    @Test
    fun loopbackFallbackIs32768() {
        assertEquals(32768, provider(settings(url = "http://localhost:11434/v1")).current().tokens)
    }

    @Test
    fun manualValueWinsOverCache() {
        val p = provider(settings(manual = 20000))
        p.onModelsBody("""{"data":[{"id":"m","context_length":65536}]}""")
        val c = p.current()
        assertEquals(CapacitySource.MANUAL, c.source)
        assertEquals(20000, c.tokens)
    }

    @Test
    fun modelsBodyFillsCacheAndRefreshIfNeededSkipsUntilExpiry() = runBlocking {
        var t = 0L
        val p = provider(settings(), { t })
        assertTrue(p.needsRefresh())
        p.onModelsBody("""{"data":[{"id":"m","context_length":65536}]}""")
        assertEquals(CapacitySource.PROBED, p.current().source)
        assertEquals(65536, p.current().tokens)
        assertFalse(p.needsRefresh())
        t = CAPACITY_CACHE_TTL_MS + 1
        assertTrue(p.needsRefresh())
    }

    @Test
    fun refreshProbesViaModelsEndpoint() = runBlocking {
        val p = provider(settings())
        p.probeNow()
        assertEquals(65536, p.current().tokens)
    }
}
