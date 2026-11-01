package com.meapet.mobile.tts.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SherpaTtsEngineConfigTest {

    @Test
    fun `配置路径为绝对路径且采样率无关`() {
        val dir = File("C:/tmp/tts_model")
        val config = buildSherpaTtsConfig(dir)
        val vits = config.model.vits

        assertEquals(File(dir, "model.onnx").absolutePath, vits.model)
        assertEquals(File(dir, "lexicon.txt").absolutePath, vits.lexicon)
        assertEquals(File(dir, "tokens.txt").absolutePath, vits.tokens)
        assertEquals(0.55f, vits.noiseScale, 1e-6f)
        assertEquals(0.7f, vits.noiseScaleW, 1e-6f)
        assertEquals(1, config.maxNumSentences)
        assertEquals(2, config.model.numThreads)
        assertEquals("cpu", config.model.provider)
    }

    @Test
    fun `ruleFsts 顺序固定 phone date number`() {
        val dir = File("C:/tmp/tts_model")
        val paths = buildSherpaTtsConfig(dir).ruleFsts.split(",")
        assertEquals(3, paths.size)
        assertTrue(paths[0].endsWith("fsts\\phone.fst") || paths[0].endsWith("fsts/phone.fst"))
        assertTrue(paths[1].endsWith("date.fst"))
        assertTrue(paths[2].endsWith("number.fst"))
    }
}
