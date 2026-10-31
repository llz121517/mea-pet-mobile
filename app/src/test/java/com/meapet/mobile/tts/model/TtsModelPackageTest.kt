package com.meapet.mobile.tts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsModelPackageTest {

    @Test
    fun `必需文件清单与大小表一一对应`() {
        val files = TtsModelManager.REQUIRED_MODEL_FILES
        assertEquals(6, files.size)
        assertEquals(
            listOf("model.onnx", "tokens.txt", "lexicon.txt", "fsts/phone.fst", "fsts/date.fst", "fsts/number.fst"),
            files,
        )
        assertTrue(files.all { TtsModelManager.EXPECTED_SIZES[it]?.let { size -> size > 0 } == true })
    }

    @Test
    fun `根级平铺 zip 可解析`() {
        val entries = TtsModelManager.REQUIRED_MODEL_FILES
        val mapping = resolveModelEntries(entries)!!
        assertEquals("model.onnx", mapping["model.onnx"])
        assertEquals("fsts/phone.fst", mapping["fsts/phone.fst"])
    }

    @Test
    fun `zip 内嵌一级目录可解析`() {
        val entries = TtsModelManager.REQUIRED_MODEL_FILES.map { "tts-model-v1/$it" } + "readme.txt"
        val mapping = resolveModelEntries(entries)!!
        assertEquals("tts-model-v1/model.onnx", mapping["model.onnx"])
        assertEquals("tts-model-v1/fsts/number.fst", mapping["fsts/number.fst"])
    }

    @Test
    fun `缺任一文件返回 null`() {
        val entries = TtsModelManager.REQUIRED_MODEL_FILES - "fsts/date.fst"
        assertNull(resolveModelEntries(entries))
    }
}
