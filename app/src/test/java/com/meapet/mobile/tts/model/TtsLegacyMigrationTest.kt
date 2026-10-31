package com.meapet.mobile.tts.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsLegacyMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `旧版四模块模型会被整目录清理`() {
        val dir = tmp.newFolder("tts_model")
        listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx").forEach {
            File(dir, it).writeBytes(ByteArray(8))
        }
        assertTrue(TtsLegacyMigration.hasLegacyModel(dir))
        TtsLegacyMigration.migrate(dir)
        assertFalse(dir.exists())
    }

    @Test
    fun `新模型目录不受迁移影响`() {
        val dir = tmp.newFolder("tts_model")
        File(dir, "model.onnx").writeBytes(ByteArray(8))
        File(dir, "fsts").mkdirs()
        TtsLegacyMigration.migrate(dir)
        assertTrue(File(dir, "model.onnx").exists())
    }
}
