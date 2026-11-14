package com.meapet.mobile.tts.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnglishBloomIndexTest {

    /** 用与生成脚本相同的位序手工构造位图。 */
    private fun indexOf(vararg words: String): EnglishBloomIndex {
        val bits = ByteArray(EnglishBloomIndex.BYTE_COUNT)
        for (word in words) {
            for (bit in EnglishBloomHash.bitsFor(word)) {
                val byteIndex = bit ushr 3
                val mask = 1 shl (bit and 7)
                bits[byteIndex] = (bits[byteIndex].toInt() or mask).toByte()
            }
        }
        return EnglishBloomIndex.fromBits(bits)
    }

    @Test
    fun `位图常量与生成脚本一致`() {
        assertEquals(4_194_304, EnglishBloomIndex.WORD_BITS)
        assertEquals(524_288, EnglishBloomIndex.BYTE_COUNT)
    }

    @Test
    fun `哈希位索引与生成脚本一致`() {
        // 由 build_lexicon_extras.py 的 bits_for('kotlin') 打印，跨语言钉死同一算法
        assertEquals(listOf(848108, 3856866, 2671320), EnglishBloomHash.bitsFor("kotlin").toList())
    }

    @Test
    fun `已插入词命中`() {
        val index = indexOf("kotlin", "github", "hello")
        assertTrue(index.mightContain("kotlin"))
        assertTrue(index.mightContain("github"))
        assertTrue(index.mightContain("hello"))
    }

    @Test
    fun `明显不存在的词不命中`() {
        val index = indexOf("kotlin", "github", "hello")
        assertFalse(index.mightContain("zzzq"))
        assertFalse(index.mightContain("qqqq"))
    }

    @Test
    fun `EMPTY 恒 true`() {
        assertTrue(EnglishBloomIndex.EMPTY.mightContain("zzzq"))
        assertTrue(EnglishBloomIndex.EMPTY.mightContain(""))
    }

    @Test
    fun `真实资产大小与常见词命中`() {
        // Gradle 单测工作目录是模块目录（app/）；直接读生成产物，防止脚本与 Kotlin 算法漂移
        val asset = File("src/main/assets/tts/en_words.bloom")
        assertTrue("缺少 Bloom 资产：${asset.absolutePath}", asset.isFile)
        assertEquals(EnglishBloomIndex.BYTE_COUNT.toLong(), asset.length())
        val index = EnglishBloomIndex.fromBits(asset.readBytes())
        assertTrue(index.mightContain("kotlin"))
        assertTrue(index.mightContain("github"))
        assertTrue(index.mightContain("hello"))
        assertTrue(index.mightContain("you"))
        assertFalse(index.mightContain("zzzq"))
    }
}
