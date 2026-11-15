package com.meapet.mobile.legacy

import com.meapet.mobile.legacy.steps.LegacyTtsNativeLibCleanup
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File

class LegacyTtsNativeLibCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settingsManager: SettingsManager = mock()
    private val step = LegacyTtsNativeLibCleanup()

    private fun context(dir: File) = LegacyMigrationContext(dir, settingsManager)

    @Test
    fun `旧版 native 库目录会被删除并置命中标记`() = runBlocking {
        val dir = tmp.newFolder("tts_model")
        val abiDir = File(dir, "lib/x86").apply { mkdirs() }
        File(abiDir, "libonnxruntime.so").writeBytes(ByteArray(8))
        val ctx = context(dir)

        step.migrate(ctx)

        assertFalse(File(dir, "lib").exists())
        assertTrue(ctx.ttsLegacyDetected)
    }

    @Test
    fun `没有 lib 目录时不置命中标记`() = runBlocking {
        val dir = tmp.newFolder("tts_model")
        File(dir, "model.onnx").writeBytes(ByteArray(8))
        val ctx = context(dir)

        step.migrate(ctx)

        assertFalse(ctx.ttsLegacyDetected)
        assertTrue(File(dir, "model.onnx").exists())
    }
}
