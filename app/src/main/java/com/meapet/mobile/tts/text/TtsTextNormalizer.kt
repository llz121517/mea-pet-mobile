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
        return rewriteJapaneseContext(
            rewriteJapaneseNumbers(convertEnglishNumbers(expandUnknownEnglish(lowercaseLatin(trimmed))))
        )
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

    /**
     * 日文上下文里的阿拉伯数字 → 假名读法。
     *
     * 为什么需要：`ruleFsts` 是**中文**数字前端，日文句里的 `15 時` 会被读成
     * shí wǔ shí、`9 月 1 日` 读成 jiǔ yuè yī rì。这里在进 sherpa 前把日文句里的数字
     * 换成假名数词。
     *
     * ## 判定
     * 单个数字 run **紧邻假名**（前后跳过空格后的最近邻，任一侧为假名）才算日文上下文；
     * 紧邻中文汉字或拉丁字母、或两侧都没有内容时一律不动，交给原有路径：
     * - 中文上下文：保持原数字，由中文 ruleFsts 读；
     * - 英文上下文：已被 [convertEnglishNumbers] 先转成英文数词，此处看不到数字。
     *
     * ## 覆盖范围
     * - 后接**特殊读法**的单位（日期/月份/时刻/量词）→ 用专用表；
     * - 后接普通量词或句读 → 基数词；
     * - 否则降级：**保留原数字**，让 ruleFsts 兜底（宁可读成中文，也不猜错读法）。
     *
     * 数字本身超出 [MAX_JAPANESE_NUMBER] 或 `0` 开头的多位数字不处理（电话、编号等）。
     */
    internal fun rewriteJapaneseNumbers(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c !in '0'..'9') {
                sb.append(c)
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j] in '0'..'9') j++
            val run = text.substring(i, j)
            val reading = japaneseNumberReading(text, i, j, run)
            if (reading == null) {
                sb.append(run)
                i = j
            } else {
                // 读法已包含单位读音时，一并跳过中间的空白与单位字符（`15 時`→`じゅうごじ`）
                sb.append(reading.text)
                i += reading.consumedChars
            }
        }
        return sb.toString()
    }

    /** 一次数字改写的产物：读法文本 + 从数字起点算起被覆盖的字符数。 */
    private data class NumberReading(val text: String, val consumedChars: Int)

    /** [start]..[endExclusive] 是数字 run；返回 null 表示保持原样。 */
    private fun japaneseNumberReading(
        text: String,
        start: Int,
        endExclusive: Int,
        run: String,
    ): NumberReading? {
        val end = endExclusive - 1
        if (!isJapaneseNumberContext(text, start, end)) return null
        if (run.length > 1 && run[0] == '0') return null
        if (isCompoundNumberPart(text, start, end)) return null
        val value = run.toIntOrNull() ?: return null
        if (value > MAX_JAPANESE_NUMBER) return null

        val unitIndex = unitIndexAfter(text, end)
        val unit = unitIndex?.let { text[it] }
        val unitIsJapanese = unit != null && unit in JA_UNIT_CHARS
        if (unitIsJapanese && unitIndex != null) {
            // 单位一律由读法接管：特殊表命中用专用读法，否则「基数 + 单位基准读音」，
            // 不能只替换数字而把单位字符留在原文里（`いち時`）。
            val reading = specialReading(value, unit!!)
                ?: (japaneseCardinal(value)?.let { it + genericUnitReading(value, unit) })
            if (reading != null) return NumberReading(reading, consumedChars = unitIndex + 1 - start)
        }
        val cardinal = japaneseCardinal(value, standalone = true) ?: return null
        return NumberReading(cardinal, consumedChars = endExclusive - start)
    }

    /** 数字后（跳过空白）的单位字符下标。 */
    private fun unitIndexAfter(text: String, end: Int): Int? {
        var i = end + 1
        while (i < text.length && text[i] == ' ') i++
        return i.takeIf { it < text.length }
    }

    /** 表外单位的基准读音（无音变的默认读法）。 */
    private fun genericUnitReading(value: Int, unit: Char): String = when (unit) {
        '日' -> "にち"
        '月' -> "がつ"
        '時' -> "じ"
        '分' -> "ふん"
        '秒' -> "びょう"
        '人' -> "にん"
        '歳' -> "さい"
        '年' -> "ねん"
        '週' -> "しゅう"
        '円' -> "えん"
        '度' -> "ど"
        '点' -> "てん"
        '番' -> "ばん"
        else -> ""
    }

    /**
     * 数字 run 是否处于日文上下文。
     *
     * 两条判据（任一成立即为日文）：
     * 1. 跳过空格后的任一侧紧邻**假名** —— `15時に`；
     * 2. 数字后紧跟**日文单位**，且整段文本里存在假名 —— `9月1日に` 里的 `9月`、`1日`。
     *
     * 第 2 条为什么要求「全段有假名」：`日`/`月`/`時` 这些字中日文同形，单看 `9月1日`
     * 无法区分是中文日期还是日文日期。真日文句子几乎必然含假名（助词、送假名），
     * 而中文文本没有假名——用这个信号做判据，宁可漏转（交给 ruleFsts 读成中文）
     * 也不要在中文文本里硬读成日文。
     */
    private fun isJapaneseNumberContext(text: String, start: Int, end: Int): Boolean {
        var prev = start - 1
        while (prev >= 0 && text[prev] == ' ') prev--
        if (prev >= 0 && isKana(text[prev])) return true
        var next = end + 1
        while (next < text.length && text[next] == ' ') next++
        if (next >= text.length) return false
        if (isKana(text[next])) return true
        return text[next] in JA_UNIT_CHARS && text.any(::isKana)
    }

    /** 特殊读法：日期 / 月份 / 时刻 / 量词；表外返回 null 由调用方退化到基数词。 */
    private fun specialReading(value: Int, unit: Char): String? = when (unit) {
        '日' -> JAPANESE_DAYS[value]
        '月' -> JAPANESE_MONTHS[value]
        '時' -> JAPANESE_HOURS[value]
        '分' -> JAPANESE_MINUTES[value]
        '人' -> JAPANESE_PEOPLE[value]
        '歳' -> JAPANESE_AGES[value]
        '個', '本', '杯', '枚', '冊', '匹', '台', '回', '階', '件' -> japaneseCounter(value, unit)
        else -> null
    }

    /** 基数词：1..9999 的组合；超出范围返回 null（调用方保留原数字）。 */
    internal fun japaneseCardinal(value: Int, standalone: Boolean = false): String? {
        if (value !in 0..MAX_JAPANESE_NUMBER) return null
        if (value == 0) return "ゼロ"
        if (standalone && value < 10) return japaneseDigit(value, standalone = true)
        val sb = StringBuilder()
        var rest = value
        for ((unitValue, unit) in JAPANESE_UNITS) {
            val count = rest / unitValue
            if (count == 0) continue
            rest %= unitValue
            // 音变读法（ろっぴゃく/はっぴゃく 等）整体替换「数字+单位」，不再拼 count
            val irregular = irregularUnitReading(unitValue, count)
            when {
                irregular != null -> sb.append(irregular)
                count == 1 && unitValue in 10..1000 -> sb.append(unit)   // 10/100/1000 前省略「一」，万位不省
                else -> {
                    sb.append(japaneseDigit(count, standalone = false))
                    sb.append(unit)
                }
            }
        }
        if (rest > 0) sb.append(japaneseDigit(rest, standalone = false))
        return sb.toString().ifEmpty { null }
    }

    /**
     * 单个数字的读法。
     *
     * 4/7 有两个读法：单独念时日语习惯 し/しち，但在组合里（14、17、40、70）更常用
     * よん/なな；9 的组合里常用 きゅう（く 只用于 9時/9月 等固定搭配）。
     * 这里按「是否参与组合」选择更自然的读法。
     */
    private fun japaneseDigit(value: Int, standalone: Boolean): String = when {
        standalone -> JAPANESE_DIGITS[value]
        value == 4 -> "よん"
        value == 7 -> "なな"
        value == 9 -> "きゅう"
        else -> JAPANESE_DIGITS[value]
    }

    /**
     * 单位音变的**完整**读法（含数字部分）；无音变返回 null。
     *
     * 特例：3百→さんびゃく、6百→ろっぴゃく、8百→はっぴゃく、3千→さんぜん、8千→はっせん。
     * 这些读法整体替换「数字+单位」，调用方不要再拼 count（否则会得到 `ろくろっぴゃく`）。
     */
    private fun irregularUnitReading(unitValue: Int, count: Int): String? = when (unitValue) {
        100 -> when (count) {
            3 -> "さんびゃく"
            6 -> "ろっぴゃく"
            8 -> "はっぴゃく"
            else -> null
        }
        1000 -> when (count) {
            3 -> "さんぜん"
            8 -> "はっせん"
            else -> null
        }
        else -> null
    }

    private fun japaneseCounter(value: Int, unit: Char): String? {
        val base = japaneseCardinal(value) ?: return null
        val reading = when (unit) {
            '個' -> if (value == 1) "いっこ" else "${base}こ"
            '本' -> when {
                value == 1 -> "いっぽん"
                value == 3 -> "さんぼん"
                value == 6 -> "ろっぽん"
                value == 8 -> "はっぽん"
                value == 10 -> "じゅっぽん"
                else -> "${base}ほん"
            }
            '杯' -> when {
                value == 1 -> "いっぱい"
                value == 3 -> "さんばい"
                value == 6 -> "ろっぱい"
                value == 8 -> "はっぱい"
                value == 10 -> "じゅっぱい"
                else -> "${base}はい"
            }
            '枚' -> "${base}まい"
            '冊' -> if (value == 1) "いっさつ" else "${base}さつ"
            '匹' -> when {
                value == 1 -> "いっぴき"
                value == 3 -> "さんびき"
                value == 6 -> "ろっぴき"
                value == 8 -> "はっぴき"
                value == 10 -> "じゅっぴき"
                else -> "${base}ひき"
            }
            '台' -> "${base}だい"
            '回' -> "${base}かい"
            '階' -> if (value == 3) "さんがい" else "${base}かい"
            '件' -> "${base}けん"
            else -> null
        }
        return reading
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

        // ── 日文数字读法 ──────────────────────────────────────────

        /** 日文数字 run；与 [NUMBER_RUN] 同一模式，分开命名以便两处独立演进。 */
        val JA_NUMBER_RUN = Regex("[0-9]+")

        /** 日文数字改写的上限（万位以内）；更大的数字（编号、里程等）保留原样。 */
        const val MAX_JAPANESE_NUMBER = 99999

        /** 0..9；下标即数值。注意 4=し、7=しち、9=く 是日文数词的标准读法。 */
        val JAPANESE_DIGITS = arrayOf(
            "ゼロ", "いち", "に", "さん", "し", "ご", "ろく", "しち", "はち", "く",
        )

        /** 基数单位：值 → 读法。按从大到小排列，拆位时依次除。 */
        val JAPANESE_UNITS = listOf(
            10000 to "まん",
            1000 to "せん",
            100 to "ひゃく",
            10 to "じゅう",
        )

        /** 1..31 日的特殊读法；表外的日（如 32）退化为「基数 + にち」。 */
        val JAPANESE_DAYS = mapOf(
            1 to "ついたち", 2 to "ふつか", 3 to "みっか", 4 to "よっか", 5 to "いつか",
            6 to "むいか", 7 to "なのか", 8 to "ようか", 9 to "ここのか", 10 to "とおか",
            14 to "じゅうよっか", 20 to "はつか", 24 to "にじゅうよっか",
        )

        /** 月份特殊读法；其余月份「基数 + がつ」。 */
        val JAPANESE_MONTHS = mapOf(
            4 to "しがつ", 7 to "しちがつ", 9 to "くがつ",
        )

        /** 时刻特殊读法；其余「基数 + じ」。14/24 时的 4 读 よ 而非 よん。 */
        val JAPANESE_HOURS = mapOf(
            4 to "よじ", 7 to "しちじ", 9 to "くじ",
            14 to "じゅうよじ", 24 to "にじゅうよじ",
        )

        /** 分钟特殊音变；其余「基数 + ふん」。 */
        val JAPANESE_MINUTES = mapOf(
            1 to "いっぷん", 3 to "さんぷん", 4 to "よんぷん", 6 to "ろっぷん",
            8 to "はっぷん", 10 to "じゅっぷん", 30 to "さんじゅっぷん",
        )

        /** 人数特殊读法；其余「基数 + にん」。 */
        val JAPANESE_PEOPLE = mapOf(
            1 to "ひとり", 2 to "ふたり", 4 to "よにん", 7 to "しちにん",
        )

        /** 年龄特殊读法；其余「基数 + さい」。 */
        val JAPANESE_AGES = mapOf(
            1 to "いっさい", 8 to "はっさい", 10 to "じゅっさい", 20 to "はたち",
        )

        /** 视为日文单位的字符（与假名一起构成日文上下文判据，见 [isJapaneseNumberContext]）。 */
        val JA_UNIT_CHARS = setOf(
            '日', '月', '時', '分', '秒', '人', '歳', '個', '本', '杯', '枚', '冊',
            '匹', '台', '回', '階', '件', '年', '週', '円', '度', '点', '番',
        )

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
