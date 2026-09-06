package com.meapet.mobile.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [markdownTableColumns] 列数识别。
 *
 * 列数决定宽表格要不要横向滚动：Markwon 把可用宽度按列均分，列数一多每列就只剩
 * 几十 dp、单元格逐字换行（表头 `列1`~`列5` 会被折成「列 / 数字」两行）。
 */
class MarkdownTableColumnsTest {

    @Test
    fun `五列表格返回 5`() {
        val md = """
            |列1|列2|列3|列4|列5|
            |---|---|---|---|---|
            |A|B|C|D|E|
        """.trimIndent()
        assertEquals(5, markdownTableColumns(md))
    }

    @Test
    fun `对齐冒号不影响列数`() {
        val md = """
            | 左 | 中 | 右 |
            |:---|:---:|---:|
            | a | b | c |
        """.trimIndent()
        assertEquals(3, markdownTableColumns(md))
    }

    @Test
    fun `无表格返回 0`() {
        assertEquals(0, markdownTableColumns("普通一段话，没有表格。\n还有第二行。"))
    }

    @Test
    fun `分割线不算表格`() {
        assertEquals(0, markdownTableColumns("上文\n\n---\n\n下文"))
        assertEquals(0, markdownTableColumns("上文\n\n***\n\n下文"))
    }

    @Test
    fun `多个表格取最大列数`() {
        val md = """
            |a|b|
            |---|---|
            |1|2|

            文字间隔

            |c|d|e|f|
            |---|---|---|---|
            |3|4|5|6|
        """.trimIndent()
        assertEquals(4, markdownTableColumns(md))
    }

    @Test
    fun `省略首尾竖线也能识别`() {
        val md = """
            列1 | 列2 | 列3
            --- | --- | ---
            a | b | c
        """.trimIndent()
        assertEquals(3, markdownTableColumns(md))
    }
}

/**
 * [splitMarkdownTables] 分段。
 *
 * 表格要横向滚动就得把 TextView 撑得比气泡宽，而同一个 TextView 里的普通段落会跟着
 * 按那个宽度换行、横向溢出气泡（真机上表现为正文右侧被截断，得左右拖才能读完）。
 * 所以必须先按表格块切开，各用自己的宽度渲染。
 */
class SplitMarkdownTablesTest {

    @Test
    fun `无表格时只有一段文本`() {
        val segments = splitMarkdownTables("就是一段普通的话。\n第二行。")
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MarkdownSegment.Text)
    }

    @Test
    fun `表格前后的文字各自成段`() {
        val md = """
            好嘞～5列表格这就来喵！

            | 序号 | 日期 | 星期 |
            |---|---|---|
            | 1 | 2026-09-06 | 星期日 |

            如果想换成其他内容，尽管告诉我。
        """.trimIndent()

        val segments = splitMarkdownTables(md)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MarkdownSegment.Text)
        assertTrue(segments[1] is MarkdownSegment.Table)
        assertTrue(segments[2] is MarkdownSegment.Text)
        assertEquals(3, (segments[1] as MarkdownSegment.Table).columns)
        assertTrue((segments[0] as MarkdownSegment.Text).content.contains("好嘞"))
        assertTrue((segments[2] as MarkdownSegment.Text).content.contains("尽管告诉我"))
        // 表格段不能夹带正文
        assertFalse((segments[1] as MarkdownSegment.Table).content.contains("好嘞"))
    }

    @Test
    fun `围栏代码块里的分隔行不算表格`() {
        val md = """
            看这段代码：

            ```
            | a | b |
            |---|---|
            ```

            就这样。
        """.trimIndent()

        val segments = splitMarkdownTables(md)
        assertTrue("代码块内容不应被切成表格段", segments.none { it is MarkdownSegment.Table })
    }

    @Test
    fun `连续两个表格各自成段`() {
        val md = """
            | a | b |
            |---|---|
            | 1 | 2 |

            | c | d | e |
            |---|---|---|
            | 3 | 4 | 5 |
        """.trimIndent()

        val tables = splitMarkdownTables(md).filterIsInstance<MarkdownSegment.Table>()
        assertEquals(2, tables.size)
        assertEquals(2, tables[0].columns)
        assertEquals(3, tables[1].columns)
    }

    @Test
    fun `表格紧接文字没有空行也能切开`() {
        val md = """
            | a | b |
            |---|---|
            | 1 | 2 |
            紧接着的说明文字
        """.trimIndent()

        val segments = splitMarkdownTables(md)
        assertEquals(2, segments.size)
        assertTrue(segments[0] is MarkdownSegment.Table)
        assertEquals("紧接着的说明文字", (segments[1] as MarkdownSegment.Text).content)
    }
}
