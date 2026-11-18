package com.meapet.mobile.legacy

import com.meapet.mobile.legacy.steps.LegacyTtsSwitchReset
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import java.io.File

class LegacyTtsSwitchResetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settingsManager: SettingsManager = mock()
    private val memoryStore: MemoryStore = mock()
    private val step = LegacyTtsSwitchReset()

    private fun context(legacyDetected: Boolean): LegacyMigrationContext {
        val dir = tmp.newFolder()
        return LegacyMigrationContext(dir, settingsManager, memoryStore).apply {
            ttsLegacyDetected = legacyDetected
        }
    }

    @Test
    fun `命中旧版残留时两个语音开关都置为 false`() = runBlocking {
        step.migrate(context(legacyDetected = true))

        verifyBlocking(settingsManager) { setTtsMainEnabled(false) }
        verifyBlocking(settingsManager) { setTtsOverlayEnabled(false) }
    }

    @Test
    fun `未命中旧版残留时不动任何开关`() = runBlocking {
        step.migrate(context(legacyDetected = false))

        verifyBlocking(settingsManager, never()) { setTtsMainEnabled(false) }
        verifyBlocking(settingsManager, never()) { setTtsOverlayEnabled(false) }
    }
}
