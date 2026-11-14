package com.meapet.mobile.tts.text

import android.content.Context
import android.util.Log

/**
 * 英文词表 Bloom 索引：判定一个小写拉丁词是否在模型 lexicon 的词表内。
 *
 * 背景：sherpa 的 CharacterLexicon 对词表外的英文词会**整词丢弃**（`Ignore OOV 'kotlin'`），
 * 不会自动逐字母拼读。App 侧在归一化时用本索引判定「是否已知」，未知的多字母词才拆成
 * 字母串交给 sherpa（`kotlin` → `k o t l i n`）。
 *
 * 资产 `assets/tts/en_words.bloom` 由 `.tools/tts-spike/scripts/build_lexicon_extras.py`
 * 生成：裸 bitset，`1 shl 22` bits = 524,288 字节，无文件头，k=3，FNV-1a 双种子，
 * 位序为小端（bit b → byte b/8，mask 1 shl (b % 8)）。生成脚本与 [EnglishBloomHash] 必须同步。
 *
 * 加载失败或长度不符时退化为 [EMPTY]：[EMPTY.mightContain] 恒为 true，宁可保留原词
 * （sherpa 仍会整词丢弃）也不把已知词拆成字母乱读。
 */
class EnglishBloomIndex private constructor(private val bits: ByteArray) {

    /** 词是否**可能**在词表内；false 一定不在，true 有小概率误判（约 0.06%）。 */
    fun mightContain(word: String): Boolean {
        if (bits.isEmpty()) return true
        if (word.isEmpty()) return false
        for (bit in EnglishBloomHash.bitsFor(word)) {
            val byteIndex = bit ushr 3
            val mask = 1 shl (bit and 7)
            if (bits[byteIndex].toInt() and mask == 0) return false
        }
        return true
    }

    companion object {
        /** 位图总位数，必须与生成脚本的 `BLOOM_BITS` 一致。 */
        const val WORD_BITS = 1 shl 22

        /** 位图字节数（无文件头）。 */
        const val BYTE_COUNT = WORD_BITS / 8

        private const val ASSET = "tts/en_words.bloom"
        private const val TAG = "EnglishBloomIndex"

        /** 退化实例：不提供任何否定信息，调用方按「已知词」处理。 */
        val EMPTY = EnglishBloomIndex(ByteArray(0))

        /** 从 APK 资产读取 Bloom 位图；IO 轻量（512KB），调用方应在首次合成时懒加载。 */
        fun load(context: Context): EnglishBloomIndex = try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            if (bytes.size != BYTE_COUNT) {
                Log.w(TAG, "Bloom 资产长度异常：${bytes.size} != $BYTE_COUNT，退化为不兜底")
                EMPTY
            } else {
                EnglishBloomIndex(bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 Bloom 资产失败，退化为不兜底", e)
            EMPTY
        }

        /** 测试用：从已构造的位图创建索引（生产只走 [load]）。 */
        internal fun fromBits(bits: ByteArray): EnglishBloomIndex = EnglishBloomIndex(bits)
    }
}

/**
 * 与生成脚本完全一致的哈希/位运算：
 * h1/h2 = FNV-1a 32-bit（种子 0x811C9DC5 / 0x01000193），第 i 位 = (h1 + i*h2) mod 2^22。
 */
internal object EnglishBloomHash {
    private const val FNV_PRIME = 0x01000193
    private val SEED_1 = 0x811C9DC5u.toInt()
    private const val SEED_2 = 0x01000193
    private const val K = 3

    fun bitsFor(word: String): IntArray {
        val bytes = word.toByteArray(Charsets.UTF_8)
        val h1 = fnv1a(bytes, SEED_1)
        val h2 = fnv1a(bytes, SEED_2)
        val mask = EnglishBloomIndex.WORD_BITS - 1
        return IntArray(K) { i -> (h1 + i * h2) and mask }
    }

    private fun fnv1a(bytes: ByteArray, seed: Int): Int {
        var h = seed
        for (b in bytes) {
            h = h xor (b.toInt() and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }
}
