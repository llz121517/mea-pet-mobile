package com.meapet.mobile.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.config.WebSearchProviderId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebSearchSettingsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `existing settings without search preferences default to disabled auto and anonymous`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("defaults.preferences_pb")
        }
        store.edit { it[stringPreferencesKey("api_key")] = "chat-fixture-key" }
        val settings = SettingsManager(store, backgroundScope)
        settings.awaitLoaded()

        assertFalse(settings.getWebSearchEnabled())
        assertEquals(WebSearchProviderId.AUTO, settings.getWebSearchProvider())
        assertEquals("", settings.getWebSearchAnySearchKey())
        assertFalse(settings.webSearchEnabledFlow.first())
        assertEquals(WebSearchProviderId.AUTO, settings.webSearchProviderFlow.first())
        assertEquals("", settings.webSearchAnySearchKeyFlow.first())
        assertEquals("chat-fixture-key", settings.getApiKey())
    }

    @Test
    fun `saved search preferences are read by getters and flows without altering the chat key`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("saved.preferences_pb")
        }
        store.edit { it[stringPreferencesKey("api_key")] = "chat-fixture-key" }
        val settings = SettingsManager(store, backgroundScope)
        settings.awaitLoaded()

        settings.setWebSearchEnabled(true)
        settings.setWebSearchProvider(WebSearchProviderId.ANYSEARCH)
        settings.setWebSearchAnySearchKey("  search-fixture-key  ")
        val reopened = SettingsManager(store, backgroundScope)
        reopened.awaitLoaded()

        assertTrue(reopened.getWebSearchEnabled())
        assertEquals("anysearch", reopened.getWebSearchProvider().id)
        assertEquals("search-fixture-key", reopened.getWebSearchAnySearchKey())
        assertTrue(reopened.webSearchEnabledFlow.first())
        assertEquals("anysearch", reopened.webSearchProviderFlow.first().id)
        assertEquals("search-fixture-key", reopened.webSearchAnySearchKeyFlow.first())
        assertEquals("chat-fixture-key", reopened.getApiKey())
    }

    @Test
    fun `clearing the AnySearch key restores anonymous access without disabling search`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("anonymous.preferences_pb")
        }
        val settings = SettingsManager(store, backgroundScope)
        settings.awaitLoaded()
        settings.setWebSearchEnabled(true)
        settings.setWebSearchProvider(WebSearchProviderId.ANYSEARCH)
        settings.setWebSearchAnySearchKey("search-fixture-key")

        settings.setWebSearchAnySearchKey("   ")

        assertEquals("", settings.getWebSearchAnySearchKey())
        assertEquals("", settings.webSearchAnySearchKeyFlow.first())
        assertEquals(WebSearchProviderId.ANYSEARCH, settings.getWebSearchProvider())
        assertTrue(settings.getWebSearchEnabled())
    }

    @Test
    fun `chat connection pairs its URL with its key without printing credentials`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("connection.preferences_pb")
        }
        val url = "https://chat.example/v1"
        val key = "chat-fixture-key"
        store.edit {
            it[stringPreferencesKey("api_url")] = url
            it[stringPreferencesKey("api_key")] = key
        }
        val settings = SettingsManager(store, backgroundScope)
        settings.awaitLoaded()

        val connection = settings.getApiConnection()

        assertEquals(url, connection.url)
        assertEquals(key, connection.key)
        assertFalse(connection.toString().contains(key))
    }

    @Test
    fun `dedicated DeepSeek key is persisted trimmed and can be cleared without changing other credentials`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("deepseek-key.preferences_pb")
        }
        store.edit { it[stringPreferencesKey("api_key")] = "chat-fixture-key" }
        val settings = SettingsManager(store, backgroundScope)
        settings.awaitLoaded()
        assertEquals("", settings.getWebSearchDeepSeekKey())
        settings.setWebSearchAnySearchKey("anysearch-fixture-key")
        settings.setWebSearchDeepSeekKey("  deepseek-web-fixture-key  ")
        val reopened = SettingsManager(store, backgroundScope)
        reopened.awaitLoaded()
        assertEquals("deepseek-web-fixture-key", reopened.getWebSearchDeepSeekKey())
        assertEquals("deepseek-web-fixture-key", reopened.webSearchDeepSeekKeyFlow.first())
        assertEquals("chat-fixture-key", reopened.getApiKey())
        assertEquals("anysearch-fixture-key", reopened.getWebSearchAnySearchKey())
        reopened.setWebSearchDeepSeekKey("   ")
        assertEquals("", reopened.getWebSearchDeepSeekKey())
        assertEquals("", reopened.webSearchDeepSeekKeyFlow.first())
        assertEquals("chat-fixture-key", reopened.getApiKey())
    }

    @Test
    fun `stored provider IDs are stable and unknown values fall back to auto`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("providers.preferences_pb")
        }
        val providerKey = stringPreferencesKey("web_search_provider")
        val settings = SettingsManager(store, backgroundScope)
        settings.awaitLoaded()

        settings.setWebSearchProvider(WebSearchProviderId.DEEPSEEK)
        assertEquals("deepseek", store.data.first()[providerKey])
        store.edit { it[providerKey] = "removed-provider" }

        assertEquals("auto", settings.webSearchProviderFlow.first().id)
    }
}
