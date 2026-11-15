package com.meapet.mobile.tts.text

/**
 * 合成前文本归一化：拉丁转小写 + 英文 OOV 逐字母兜底 + 英文数字转数词 + 日语汉字连段改写为假名。
 *
 * 规则：
 * 1. 拉丁 run 转小写；
 * 2. **英文 OOV 兜底**：多字母拉丁 run 若不在英文词表内（[knownEnglishWord] 为 false），
 *    按字母用单个空格拆开（`kotlin` → `k o t l i n`，`don't` → `d o n t`，非字母字符丢弃）；
 *    单字母与已知词原样保留。词表由 assets/tts/en_words.bloom 提供；
 * 3. **英文数字**：紧邻拉丁词的整数 run 转英文数词（`3` → `three`、`12` → `twelve`），
 *    见 [convertEnglishNumbers]。sherpa 的 ruleFsts 是中文数字前端，不预转换会被读成 sān / shí èr；
 * 4. 扫描「连续汉字段」；仅当该段紧挨假名（前或后）才视为日语片段；
 * 5. 片段内先用 [JaReadingMap.word] 做最长匹配（≤[MAX_WORD_LEN] 字）；
 * 6. 未命中的单字：后跟假名且有词干读音→词干；否则整字读音；再否则保留原字；
 * 7. 非日语片段完全不动 → 中文永不被日文词条抢占。
 *
 * 日语改写部分与 `.tools/tts-spike/scripts/test_final_design.py::rewrite` 完全一致，
 * 改逻辑必须两边同步（英文兜底与英文数字是 App 侧新增层，Python 参考实现的 `rewrite` 不含它们）。
 */
class TtsTextNormalizer(
    private val mapProvider: () -> JaReadingMap,
    private val knownEnglishWord: (String) -> Boolean = { true },
) {

    @Volatile
    private var cachedMap: JaReadingMap? = null

    private fun map(): JaReadingMap =
        cachedMap ?: synchronized(this) { cachedMap ?: mapProvider().also { cachedMap = it } }

    /** 文本 → 归一化文本；空白输入返回空串。 */
    fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return rewriteJapaneseContext(convertEnglishNumbers(expandUnknownEnglish(lowercaseLatin(trimmed))))
    }

    internal fun lowercaseLatin(text: String): String =
        LATIN_RUN.replace(text) { it.value.lowercase() }

    /** 词表外的多字母拉丁词按字母拆开；单字母与已知词保持原样。 */
    internal fun expandUnknownEnglish(text: String): String =
        ENGLISH_RUN.replace(text) { match ->
            val run = match.value
            if (run.length > 1 && !knownEnglishWord(run)) spellOut(run) else run
        }

    /** 丢弃非字母字符，用单个空格连接小写字母：`don't` → `d o n t`。 */
    private fun spellOut(run: String): String = buildString(run.length * 2) {
        var first = true
        for (c in run) {
            if (c in 'a'..'z') {
                if (!first) append(' ')
                append(c)
                first = false
            }
        }
    }

    /**
     * 英文上下文的阿拉伯数字 → 英文数词：`I have 3 apples` → `i have three apples`。
     *
     * 为什么需要：数字不经过 lexicon 查表（lexicon 无阿拉伯数字条目），而是由 sherpa 的
     * `ruleFsts`（phone/date/number.fst，**中文**数字前端）在查表前统一转成中文数词；
     * 英文句里的数字因此被读成 sān / shí èr。这里在进 sherpa 前按上下文预转换。
     *
     * 判定只影响「整数 run」，其余场景一律保持原样交给 ruleFsts：
     * - 跳过空格后的最近邻字符中**任一侧是拉丁字母** → 视为英文上下文，转换；
     * - 相邻的是 CJK/假名 → 中文/日文上下文，不转换（中文 #20 的 sān、日文数字保持现状）；
     * - 复合数字不转换：紧邻 `.` `:` `/` `%` `-` `+` 且再往外是数字（`3.5`、`10:30`、`3/4`、`-5`）；
     * - 超过 [MAX_ENGLISH_NUMBER_DIGITS] 位（电话等长数字留给 phone.fst）或 `0` 开头的多位数不转换。
     */
    internal fun convertEnglishNumbers(text: String): String =
        NUMBER_RUN.replace(text) { match ->
            val run = match.value
            val convertible = run.length <= MAX_ENGLISH_NUMBER_DIGITS &&
                !(run.length > 1 && run[0] == '0') &&
                !isCompoundNumberPart(text, match.range.first, match.range.last) &&
                (neighborKind(text, match.range.first, -1) == NEIGHBOR_LATIN ||
                    neighborKind(text, match.range.last, +1) == NEIGHBOR_LATIN)
            if (!convertible) run else toEnglishNumber(run.toLong()) ?: run
        }

    /** 复合数字的一部分（小数 / 时间 / 分数 / 百分比 / 负数）→ 不转英文。 */
    private fun isCompoundNumberPart(text: String, start: Int, end: Int): Boolean {
        val prev = text.getOrNull(start - 1)
        val prevPrev = text.getOrNull(start - 2)
        val next = text.getOrNull(end + 1)
        val nextNext = text.getOrNull(end + 2)
        val prevJoins = prev != null && prev in NUMBER_CONNECTORS && prevPrev?.isDigit() == true
        val nextJoins = next != null && next in NUMBER_CONNECTORS && nextNext?.isDigit() == true
        return prevJoins || nextJoins
    }

    /** 跳过空格后，[index] 向 [step] 方向的最近邻字符类别。 */
    private fun neighborKind(text: String, index: Int, step: Int): Int {
        var i = index + step
        while (i in text.indices && text[i] == ' ') i += step
        return when {
            i !in text.indices -> NEIGHBOR_NONE
            text[i] in 'a'..'z' -> NEIGHBOR_LATIN
            text[i].isDigit() -> NEIGHBOR_DIGIT
            else -> NEIGHBOR_OTHER
        }
    }

    /** 0..999999 → 英文基数词；超出范围返回 null（调用方保留原数字）。 */
    private fun toEnglishNumber(value: Long): String? {
        if (value !in 0..MAX_ENGLISH_NUMBER) return null
        if (value == 0L) return "zero"
        val parts = mutableListOf<String>()
        val thousands = (value / 1000).toInt()
        val remainder = (value % 1000).toInt()
        if (thousands > 0) {
            parts += toEnglishBelowThousand(thousands)
            parts += "thousand"
        }
        if (remainder > 0) parts += toEnglishBelowThousand(remainder)
        return parts.joinToString(" ")
    }

    private fun toEnglishBelowThousand(value: Int): String {
        val parts = mutableListOf<String>()
        val hundreds = value / 100
        val rest = value % 100
        if (hundreds > 0) {
            parts += ONES[hundreds]
            parts += "hundred"
        }
        if (rest > 0) parts += toEnglishBelowHundred(rest)
        return parts.joinToString(" ")
    }

    private fun toEnglishBelowHundred(value: Int): String {
        if (value < 20) return ONES[value]
        val tens = TENS[value / 10]
        val ones = value % 10
        return if (ones == 0) tens else "$tens ${ONES[ones]}"
    }

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

        /**
         * 英文 OOV 判定的词边界：字母开头，可含撇号/连字符，**不含句点**。
         * 句点作为标点保留，否则 `you.` 会查不到 `you` 而被拆成字母并丢掉句号。
         */
        val ENGLISH_RUN = Regex("[A-Za-z][A-Za-z'\\-]*")
        const val MAX_WORD_LEN = 8

        /** 阿拉伯数字 run；英文上下文的整数在进 sherpa 前转英文数词。 */
        val NUMBER_RUN = Regex("[0-9]+")

        /** 最多 6 位（≤999,999）；电话等更长数字交给 phone.fst 逐位处理。 */
        const val MAX_ENGLISH_NUMBER_DIGITS = 6
        const val MAX_ENGLISH_NUMBER = 999_999L

        /** 紧邻这些字符且再往外是数字 → 复合数字的一部分，不转英文。 */
        const val NUMBER_CONNECTORS = ".,:/%-+"

        const val NEIGHBOR_NONE = 0
        const val NEIGHBOR_LATIN = 1
        const val NEIGHBOR_DIGIT = 2
        const val NEIGHBOR_OTHER = 3

        /** 0..19；下标即数值。 */
        val ONES = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen",
        )

        /** 0..9；下标为十位数字（0/1 不用）。 */
        val TENS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        )

        fun isKanji(c: Char) = c in '\u4e00'..'\u9fff'
        fun isKana(c: Char) = c in '\u3040'..'\u30ff'
        fun isHira(c: Char) = c in '\u3040'..'\u309f'
    }
}
