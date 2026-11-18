package com.meapet.mobile.chat.tools

/**
 * 清理模型**以文本形式泄漏**的工具调用标记。
 *
 * ## 背景
 * 部分 OpenAI 兼容模型的内部工具调用标记是 `<｜DSML｜ ...>` 形式的特殊 token。
 * 正常情况下服务端会把它解析成结构化 `tool_calls`；但在某些边界（例如最后一轮
 * `tool_choice="none"` 被强制禁止调用工具、或网关未识别该标记）时，模型会把整段标记
 * **当普通文本**放进 `content`，直接展示给用户就会看到 `<｜｜DSML｜｜ invoke ...>` 这种乱码。
 *
 * 这里做纯文本清洗（不放宽工具协议本身）：
 * 1. 删掉完整的 `calls` / `invoke` 块（含内部参数）；
 * 2. 删掉残留的标记标签；
 * 3. 若仍有未闭合的标记，从第一个标记处截断到结尾；
 * 4. 折叠因删除产生的多余空行。
 */
object ToolCallMarkup {

    /** 标记两侧的竖线可能是半角 `|` 或全角 `｜`，数量 1~3 个都容忍。 */
    private const val BAR = """[|｜]{1,3}"""

    private val callsBlock = Regex(
        """<$BAR\s*DSML\s*$BAR\s*calls>.*?</$BAR\s*DSML\s*$BAR\s*calls>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val invokeBlock = Regex(
        """<$BAR\s*DSML\s*$BAR\s*invoke[^>]*>.*?</$BAR\s*DSML\s*$BAR\s*invoke>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val anyTag = Regex("""</?$BAR\s*DSML[^>]*>""", RegexOption.IGNORE_CASE)

    /** 未闭合时的兜底：从第一个标记开始整段丢弃。 */
    private val danglingTag = Regex("""<$BAR\s*DSML""", RegexOption.IGNORE_CASE)

    private val excessiveBlankLines = Regex("""\n{3,}""")

    /**
     * 清洗文本中的工具调用标记。
     *
     * @return 清洗后的正文；没有标记时原样返回
     */
    fun strip(text: String): String {
        if (!text.contains("DSML", ignoreCase = true)) return text
        var out = text
        out = callsBlock.replace(out, "")
        out = invokeBlock.replace(out, "")
        out = anyTag.replace(out, "")
        // 仍有未闭合标记：从第一个标记处截断（这类内容对用户没有意义）
        danglingTag.find(out)?.let { out = out.substring(0, it.range.first) }
        out = excessiveBlankLines.replace(out.trim(), "\n\n")
        return out.trim()
    }

    /** 是否含工具调用标记（用于判断清洗后是否已无有效正文）。 */
    fun containsMarkup(text: String): Boolean = text.contains("DSML", ignoreCase = true)
}
