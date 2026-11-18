package com.meapet.mobile.chat.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 清洗模型以文本形式泄漏的工具调用标记（真实样本来自 DeepSeek 的 `<｜｜DSML｜｜ ...>`）。 */
class ToolCallMarkupTest {

    /** 线上实际泄漏到正文的样本（全角竖线版本）。 */
    private val leakedFullwidth = """

        <｜｜DSML｜｜ calls>
        <｜｜DSML｜｜ invoke name="delete_memory">
        <｜｜DSML｜｜ parameter name="id" string="true">mem_ln3glfhc</｜｜DSML｜｜ parameter>
        <｜｜DSML｜｜ parameter name="confirm_permanent" string="false">true</｜｜DSML｜｜ parameter>
        <｜｜DSML｜｜ parameter name="reason" string="true">与mem_39ypzmxj重复，保留信息更完整的一条</｜｜DSML｜｜ parameter>
        </｜｜DSML｜｜ invoke>
        </｜｜DSML｜｜ calls>
    """.trimIndent()

    @Test
    fun `纯标记内容清洗后为空`() {
        assertEquals("", ToolCallMarkup.strip(leakedFullwidth))
    }

    @Test
    fun `保留标记之前的正文`() {
        val text = "删掉喵。$leakedFullwidth"
        assertEquals("删掉喵。", ToolCallMarkup.strip(text))
    }

    @Test
    fun `半角竖线写法也能清洗`() {
        val text = """
            好的喵。
            <||DSML|| calls>
            <||DSML|| invoke name="search_memory">
            <||DSML|| parameter name="query" string="true">论文</||DSML|| parameter>
            </||DSML|| invoke>
            </||DSML|| calls>
        """.trimIndent()
        assertEquals("好的喵。", ToolCallMarkup.strip(text))
    }

    @Test
    fun `只有 invoke 块也清洗`() {
        val text = "查一下。" + """
            <｜｜DSML｜｜ invoke name="search_memory">
            <｜｜DSML｜｜ parameter name="query" string="true">x</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
        """.trimIndent()
        assertEquals("查一下。", ToolCallMarkup.strip(text))
    }

    @Test
    fun `未闭合标记从标记处截断`() {
        val text = "好的喵。\n<｜｜DSML｜｜ calls>\n<｜｜DSML｜｜ invoke name=\"delete_memory\">"
        assertEquals("好的喵。", ToolCallMarkup.strip(text))
    }

    @Test
    fun `普通文本不受影响`() {
        val text = "今天天气不错喵。\n\n要出门吗喵。"
        assertEquals(text, ToolCallMarkup.strip(text))
        assertFalse(ToolCallMarkup.containsMarkup(text))
    }

    @Test
    fun `标记检测`() {
        assertTrue(ToolCallMarkup.containsMarkup(leakedFullwidth))
        assertFalse(ToolCallMarkup.containsMarkup("没有标记"))
    }
}
