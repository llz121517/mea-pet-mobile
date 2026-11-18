package com.meapet.mobile.legacy

import com.meapet.mobile.legacy.steps.LegacyExchangeCountMigration
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import kotlin.test.assertEquals

/** 旧 DataStore 计数 -> SQLite meta：迁入、不覆盖已有关、删旧键、幂等。 */
class LegacyExchangeCountMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val step = LegacyExchangeCountMigration()

    private fun ctx(legacyValue: Int?, stored: Int = 0): Pair<LegacyMigrationContext, MemoryStore> {
        val settings: SettingsManager = mock()
        val store: MemoryStore = mock()
        whenever(settings.getLegacyExchangeCount()).thenReturn(legacyValue)
        wheneverBlocking { store.getExchangeCount() }.thenReturn(stored)
        return LegacyMigrationContext(tmp.newFolder(), settings, store) to store
    }

    @Test
    fun `旧值迁入并删除旧键`() = runBlocking {
        val (context, store) = ctx(legacyValue = 7)

        step.migrate(context)

        verifyBlocking(store) { setExchangeCount(7) }
        verifyBlocking(context.settingsManager) { clearLegacyExchangeCount() }
    }

    @Test
    fun `库中已有计数时不覆盖但仍删旧键`() = runBlocking {
        val (context, store) = ctx(legacyValue = 7, stored = 3)

        step.migrate(context)

        verifyBlocking(store, never()) { setExchangeCount(3) }
        verifyBlocking(context.settingsManager) { clearLegacyExchangeCount() }
    }

    @Test
    fun `没有旧值时什么都不做`() = runBlocking {
        val (context, store) = ctx(legacyValue = null)

        step.migrate(context)

        verifyBlocking(store, never()) { setExchangeCount(0) }
        verifyBlocking(context.settingsManager, never()) { clearLegacyExchangeCount() }
    }

    @Test
    fun `旧值为零时不写入但仍删旧键`() = runBlocking {
        val (context, store) = ctx(legacyValue = 0)

        step.migrate(context)

        verifyBlocking(store, never()) { setExchangeCount(0) }
        verifyBlocking(context.settingsManager) { clearLegacyExchangeCount() }
        assertEquals(0, store.getExchangeCount())
    }
}
