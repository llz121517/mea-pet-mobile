package com.meapet.mobile.tts.engine

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** 证明 sherpa-onnx AAR 已进入单测 classpath，且关键数据类签名与计划一致。 */
class SherpaDependencyTest {

    @Test
    fun `sherpa 配置类可构造且字段可读`() {
        val vits = OfflineTtsVitsModelConfig(
            model = "/tmp/model.onnx",
            lexicon = "/tmp/lexicon.txt",
            tokens = "/tmp/tokens.txt",
            noiseScale = 0.55f,
            noiseScaleW = 0.7f,
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"),
            ruleFsts = "/tmp/phone.fst,/tmp/date.fst,/tmp/number.fst",
            maxNumSentences = 1,
        )

        assertEquals("/tmp/model.onnx", config.model.vits.model)
        assertEquals(0.55f, config.model.vits.noiseScale, 1e-6f)
        assertEquals("/tmp/phone.fst,/tmp/date.fst,/tmp/number.fst", config.ruleFsts)
        assertEquals(1, config.maxNumSentences)
    }
}
