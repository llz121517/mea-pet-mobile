package com.meapet.mobile.tts.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextNormalizerTest {

    // 小型映射：只保留测试用条目，避免单测依赖 515KB 资产
    private val map = JaReadingMap(
        word = mapOf("今日" to "きょう", "一緒" to "いっしょ", "天気" to "てんき"),
        charFull = mapOf("私" to "わたし", "何" to "なに"),
        charStem = mapOf("知" to "し"),
    )

    private fun normalizer() = TtsTextNormalizer { map }

    @Test
    fun `中文不被动`() {
        val text = "重庆的银行行长说，重要的会议要重新安排。"
        assertEquals(text, normalizer().normalize(text))
    }

    @Test
    fun `日语单字按整字或词干改写`() {
        assertEquals("わたしはなにもしらない。", normalizer().normalize("私は何も知らない。"))
    }

    @Test
    fun `日语词条优先最长匹配`() {
        assertEquals("こんにちは、きょうはいいてんきですね。", normalizer().normalize("こんにちは、今日はいい天気ですね。"))
        assertEquals("いっしょにいこう", normalizer().normalize("一緒にいこう"))
    }

    @Test
    fun `中英混排只改日语片段且拉丁转小写`() {
        assertEquals(
            "今天天气不错，いっしょにいこう，let's go！",
            normalizer().normalize("今天天气不错，一緒にいこう，LET'S GO！"),
        )
    }

    @Test
    fun `紧挨假名的汉字才进入日语分支`() {
        // 「今天」后面是中文标点：整段保持中文
        assertEquals("今天天气真好。", normalizer().normalize("今天天气真好。"))
    }

    @Test
    fun `映射缺失时保留原字`() {
        assertEquals("麒麟です", normalizer().normalize("麒麟です"))
    }

    @Test
    fun `空白输入返回空串`() {
        assertEquals("", normalizer().normalize("   "))
    }
}
