package com.meapet.mobile.tts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // 逐项钉死字节数：改错/漏项都会失败
        assertEquals(
            mapOf(
                "model.onnx" to 74_864_089L,
                "tokens.txt" to 439L,
                "lexicon.txt" to 8_915_316L,
                "fsts/phone.fst" to 88_630L,
                "fsts/date.fst" to 59_154L,
                "fsts/number.fst" to 64_482L,
            ),
            TtsModelManager.EXPECTED_SIZES,
        )
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
    fun `嵌套 model_onnx 排在前面时回退到根级平铺包`() {
        // 干扰项：嵌套目录里先出现一个孤零零的 model.onnx，真正完整的包在根级平铺
        val entries = listOf("nested/model.onnx") + TtsModelManager.REQUIRED_MODEL_FILES
        val mapping = resolveModelEntries(entries)!!
        assertEquals("model.onnx", mapping["model.onnx"])
        assertEquals("fsts/phone.fst", mapping["fsts/phone.fst"])
        assertEquals("fsts/date.fst", mapping["fsts/date.fst"])
    }

    @Test
    fun `缺任一文件返回 null`() {
        val entries = TtsModelManager.REQUIRED_MODEL_FILES - "fsts/date.fst"
        assertNull(resolveModelEntries(entries))
    }
}
