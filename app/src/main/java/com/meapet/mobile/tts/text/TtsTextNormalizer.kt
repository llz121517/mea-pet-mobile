package com.meapet.mobile.tts.text

/**
 * 合成前文本归一化：拉丁转小写 + 日语汉字连段改写为假名。
 *
 * 规则（与 `.tools/tts-spike/scripts/test_final_design.py::rewrite` 完全一致，改逻辑必须两边同步）：
 * 1. 扫描「连续汉字段」；仅当该段紧挨假名（前或后）才视为日语片段；
 * 2. 片段内先用 [JaReadingMap.word] 做最长匹配（≤[MAX_WORD_LEN] 字）；
 * 3. 未命中的单字：后跟假名且有词干读音→词干；否则整字读音；再否则保留原字；
 * 4. 非日语片段完全不动 → 中文永不被日文词条抢占。
 */
class TtsTextNormalizer(private val mapProvider: () -> JaReadingMap) {

    @Volatile
    private var cachedMap: JaReadingMap? = null

    private fun map(): JaReadingMap =
        cachedMap ?: synchronized(this) { cachedMap ?: mapProvider().also { cachedMap = it } }

    /** 文本 → 归一化文本；空白输入返回空串。 */
    fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return rewriteJapaneseContext(lowercaseLatin(trimmed))
    }

    internal fun lowercaseLatin(text: String): String =
        LATIN_RUN.replace(text) { it.value.lowercase() }

    internal fun rewriteJapaneseContext(text: String): String {
        val ja = map()
        val sb = StringBuilder(text.length)
        val n = text.length
        var i = 0
        while (i < n) {
            if (!isKanji(text[i])) {
                sb.append(text[i])
                i++
                continue
            }
            var j = i
            while (j < n && isKanji(text[j])) j++
            val run = text.substring(i, j)
            val prevKana = i > 0 && isKana(text[i - 1])
            val nextKana = j < n && isKana(text[j])

            if (!prevKana && !nextKana) {
                sb.append(run)   // 纯中文片段：不动
                i = j
                continue
            }

            var k = 0
            while (k < run.length) {
                var hitLen = 0
                var hitReading: String? = null
                for (len in minOf(MAX_WORD_LEN, run.length - k) downTo 2) {
                    val reading = ja.word[run.substring(k, k + len)]
                    if (reading != null) {
                        hitLen = len
                        hitReading = reading
                        break
                    }
                }
                if (hitReading != null) {
                    sb.append(hitReading)
                    k += hitLen
                    continue
                }
                val ch = run[k].toString()
                val followKana = (k + 1 < run.length && isHira(run[k + 1])) ||
                    (k + 1 == run.length && nextKana)
                val reading = when {
                    followKana && ja.charStem.containsKey(ch) -> ja.charStem[ch]
                    ja.charFull.containsKey(ch) -> ja.charFull[ch]
                    ja.charStem.containsKey(ch) -> ja.charStem[ch]
                    else -> null
                }
                sb.append(reading ?: ch)
                k++
            }
            i = j
        }
        return sb.toString()
    }

    private companion object {
        /** 与 Python `re.sub(r"[A-Za-z][A-Za-z'\-\.]*", lower)` 等价。 */
        val LATIN_RUN = Regex("[A-Za-z][A-Za-z'\\-.]*")
        const val MAX_WORD_LEN = 8

        fun isKanji(c: Char) = c in '\u4e00'..'\u9fff'
        fun isKana(c: Char) = c in '\u3040'..'\u30ff'
        fun isHira(c: Char) = c in '\u3040'..'\u309f'
    }
}
