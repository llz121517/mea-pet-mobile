package com.meapet.mobile.tts.text

import org.junit.Test
import kotlin.test.assertEquals

/**
 * 日文上下文里的阿拉伯数字 → 假名读法。
 *
 * 背景：sherpa 的 `ruleFsts`（phone/date/number.fst）是**中文**数字前端，日文句里的
 * `15 時` 会被读成 shí wǔ shí、`9 月 1 日` 读成 jiǔ yuè yī rì。这里在进 sherpa 前
 * 按上下文把日文句里的数字换成假名数词，覆盖基数、日期、时刻与常见量词；
 * 未覆盖的场景保留原数字交给 ruleFsts 兜底（宁可读成中文，也不要读错或崩）。
 */
class JapaneseNumbersTest {

    /** 用一个空的读音表即可：本层只测数字改写，不涉及汉字读音。 */
    private fun normalizer() = TtsTextNormalizer(mapProvider = { JaReadingMap.EMPTY })

    /**
     * 套一个日文语境：真日文句子几乎必然含假名，判据据此区分中日文同形的数字串
     * （`9月1日` 既可能是中文也可能是日文）。测试里加一个不会干扰断言的假名前缀。
     */
    private fun ja(text: String) = normalizer().rewriteJapaneseNumbers("これは${text}です")
        .removePrefix("これは").removeSuffix("です")

    // ── 基数 ──────────────────────────────────────────

    @Test
    fun `一位数基数`() {
        assertEquals("いち", ja("1"))
        assertEquals("に", ja("2"))
        assertEquals("さん", ja("3"))
        assertEquals("し", ja("4"))
        assertEquals("ご", ja("5"))
        assertEquals("ろく", ja("6"))
        assertEquals("しち", ja("7"))
        assertEquals("はち", ja("8"))
        assertEquals("く", ja("9"))
        assertEquals("じゅう", ja("10"))
    }

    @Test
    fun `十几与几十`() {
        assertEquals("じゅういち", ja("11"))
        assertEquals("じゅうよん", ja("14"))
        assertEquals("じゅうなな", ja("17"))
        assertEquals("じゅうきゅう", ja("19"))
        assertEquals("にじゅう", ja("20"))
        assertEquals("さんじゅうご", ja("35"))
        assertEquals("きゅうじゅうきゅう", ja("99"))
    }

    @Test
    fun `百千与万`() {
        assertEquals("ひゃく", ja("100"))
        assertEquals("さんびゃく", ja("300"))
        assertEquals("ろっぴゃく", ja("600"))
        assertEquals("はっぴゃく", ja("800"))
        assertEquals("せん", ja("1000"))
        assertEquals("さんぜん", ja("3000"))
        assertEquals("はっせん", ja("8000"))
        assertEquals("いちまん", ja("10000"))
    }

    // ── 日期 ──────────────────────────────────────────

    @Test
    fun `日期特殊读法`() {
        assertEquals("ついたち", ja("1日"))
        assertEquals("ふつか", ja("2日"))
        assertEquals("みっか", ja("3日"))
        assertEquals("よっか", ja("4日"))
        assertEquals("いつか", ja("5日"))
        assertEquals("むいか", ja("6日"))
        assertEquals("なのか", ja("7日"))
        assertEquals("ようか", ja("8日"))
        assertEquals("ここのか", ja("9日"))
        assertEquals("とおか", ja("10日"))
        assertEquals("じゅうよっか", ja("14日"))
        assertEquals("はつか", ja("20日"))
        assertEquals("にじゅうよっか", ja("24日"))
        assertEquals("さんじゅういちにち", ja("31日"))
    }

    @Test
    fun `月份特殊读法`() {
        assertEquals("しがつ", ja("4月"))
        assertEquals("しちがつ", ja("7月"))
        assertEquals("くがつ", ja("9月"))
        assertEquals("いちがつ", ja("1月"))
        assertEquals("じゅうにがつ", ja("12月"))
    }

    // ── 时刻 ──────────────────────────────────────────

    @Test
    fun `时刻特殊读法`() {
        assertEquals("いちじ", ja("1時"))
        assertEquals("よじ", ja("4時"))
        assertEquals("しちじ", ja("7時"))
        assertEquals("くじ", ja("9時"))
        assertEquals("じゅうごじ", ja("15時"))
        assertEquals("にじゅうよじ", ja("24時"))
    }

    @Test
    fun `分钟`() {
        assertEquals("さんじゅっぷん", ja("30分"))
        assertEquals("いっぷん", ja("1分"))
        assertEquals("ろっぷん", ja("6分"))
        assertEquals("じゅうごふん", ja("15分"))
    }

    // ── 量词 ──────────────────────────────────────────

    @Test
    fun `常见量词`() {
        assertEquals("ひとり", ja("1人"))
        assertEquals("ふたり", ja("2人"))
        assertEquals("さんにん", ja("3人"))
        assertEquals("いっこ", ja("1個"))
        assertEquals("にほん", ja("2本"))
        assertEquals("いっぱい", ja("1杯"))
        assertEquals("いっさい", ja("1歳"))
        assertEquals("はたち", ja("20歳"))
    }

    // ── 上下文与降级 ──────────────────────────────────

    @Test
    fun `日文句子中的数字按日文读`() {
        // 只断言数字部分：汉字读音由 rewriteJapaneseContext 负责（本层不碰）
        assertEquals("じゅうごじ", ja("15時"))
        assertEquals("くがつ ついたち", ja("9月 1日"))
        assertEquals("にじゅうよじ", ja("24時"))
    }

    @Test
    fun `中文上下文不受影响`() {
        val n = normalizer()
        // 没有假名 → 整段按中文处理，数字保持原样交给中文 ruleFsts
        assertEquals("15时", n.rewriteJapaneseNumbers("15时"))
        assertEquals("9月1日", n.rewriteJapaneseNumbers("9月1日"))
    }

    @Test
    fun `英文与裸数字不受影响`() {
        val n = normalizer()
        assertEquals("i have 3 apples", n.rewriteJapaneseNumbers("i have 3 apples"))
        assertEquals("123", n.rewriteJapaneseNumbers("123"))
    }

    @Test
    fun `复合数字与超长数字保留原样`() {
        assertEquals("3.5", ja("3.5"))
        assertEquals("10:30", ja("10:30"))
        assertEquals("090-1234-5678", ja("090-1234-5678"))
        assertEquals("123456789", ja("123456789"))
    }

    @Test
    fun `未覆盖的量词`() {
        // 未知单位（缶）不在表里：数字仍转成基数词（合法读法），单位字符保留
        assertEquals("さん缶", ja("3缶"))
    }
}
