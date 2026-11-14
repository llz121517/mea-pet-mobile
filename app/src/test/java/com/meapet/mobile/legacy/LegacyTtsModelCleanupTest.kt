package com.meapet.mobile.legacy

import com.meapet.mobile.legacy.steps.LegacyTtsModelCleanup
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File

class LegacyTtsModelCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val settingsManager: SettingsManager = mock()
    private val step = LegacyTtsModelCleanup()

    private fun context(dir: File) = LegacyMigrationContext(dir, settingsManager)

    @Test
    fun `旧版四模块模型会被整目录清理并置命中标记`() = runBlocking {
        val dir = tmp.newFolder("tts_model")
        listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx").forEach {
            File(dir, it).writeBytes(ByteArray(8))
        }
        val ctx = context(dir)

        step.migrate(ctx)

        assertFalse(dir.exists())
        assertTrue(ctx.ttsLegacyDetected)
    }

    @Test
    fun `新模型目录不受影响且不置命中标记`() = runBlocking {
        val dir = tmp.newFolder("tts_model")
        File(dir, "model.onnx").writeBytes(ByteArray(8))
        File(dir, "fsts").mkdirs()
        File(dir, "fsts/phone.fst").writeBytes(ByteArray(8))
        val ctx = context(dir)

        step.migrate(ctx)

        assertTrue(File(dir, "model.onnx").exists())
        assertTrue(File(dir, "fsts/phone.fst").exists())
        assertFalse(ctx.ttsLegacyDetected)
    }

    @Test
    fun `迁移幂等 - 第二次执行不抛异常`() = runBlocking {
        val dir = tmp.newFolder("tts_model")
        File(dir, "enc_p.onnx").writeBytes(ByteArray(8))
        val ctx = context(dir)

        step.migrate(ctx)
        step.migrate(ctx)

        assertFalse(dir.exists())
        assertTrue(ctx.ttsLegacyDetected)
    }
}
