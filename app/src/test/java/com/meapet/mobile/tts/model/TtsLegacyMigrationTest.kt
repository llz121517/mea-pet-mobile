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
    fun `迁移幂等 - 第二次调用目录已不存在也不抛异常`() {
        val dir = tmp.newFolder("tts_model")
        listOf("enc_p.onnx", "dp.onnx", "flow.onnx", "dec.onnx").forEach {
            File(dir, it).writeBytes(ByteArray(8))
        }
        TtsLegacyMigration.migrate(dir)
        assertFalse(dir.exists())

        // 第二次：目录已经没了，应当静默 no-op
        TtsLegacyMigration.migrate(dir)
        assertFalse(dir.exists())
    }

    @Test
    fun `新旧混合目录也会被整目录删除`() {
        val dir = tmp.newFolder("tts_model")
        File(dir, "enc_p.onnx").writeBytes(ByteArray(8))
        File(dir, "model.onnx").writeBytes(ByteArray(8))
        File(dir, "fsts").mkdirs()
        File(dir, "fsts/phone.fst").writeBytes(ByteArray(8))

        TtsLegacyMigration.migrate(dir)

        // 破坏性行为（设计如此）：混合目录整体删除，新包文件也一并丢失，需重新下载/导入
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
