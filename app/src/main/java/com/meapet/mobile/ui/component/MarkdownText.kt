package com.meapet.mobile.ui.component

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import ru.noties.jlatexmath.JLatexMathAndroid
import org.scilab.forge.jlatexmath.TeXFormula
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * LaTeX 初始化（一次性）。
 *
 * jlatexmath-android 的符号表 TeXSymbols.xml（\infty \sum \alpha 等 621 个符号）
 * 在 JLatexMathAndroid.init(context) 之后才会在类初始化时从 assets 自动加载。
 * Markwon 插件自身不调 init（库虽有自动初始化 Provider，为防 manifest 合并失效，
 * 这里显式调用并预热）。
 */
@Volatile
private var latexInitialized = false
private val latexInitLock = Any()

private fun ensureLatexInit(context: Context) {
    if (latexInitialized) return
    synchronized(latexInitLock) {
        if (latexInitialized) return
        JLatexMathAndroid.init(context.applicationContext)
        try {
            // 预热：触发 TeXFormula/SymbolAtom 静态初始化（加载符号表 + 字体配置）
            TeXFormula("x")
        } catch (t: Throwable) {
            Log.w("MarkdownText", "LaTeX 预热失败，公式可能无法渲染", t)
        }
        latexInitialized = true
    }
}

/**
 * 定界符归一化。
 *
 * Markwon 4.6.2 的 LaTeX 定界符统一是 $$...$$（行内正则 (\${2})([\s\S]+?)\1，
 * 块级也是 $$），**单个 $ 根本不被识别**。而 AI 模型常输出四种写法：
 *   \[...\]（显示公式）、\(...\)（行内）、$$...$$、$...$
 * 这里统一收敛到 $$...$$，否则整段公式按原文显示（上个版本就死在把 \(...\) 转成单 $）。
 */
internal fun normalizeLatexDelimiters(src: String): String {
    var s = src
    // \[ ... \] 显示公式 → $$ ... $$
    s = Regex("""\\\[([\s\S]+?)\\\]""").replace(s) { "\$\$${it.groupValues[1]}\$\$" }
    // \( ... \) 行内公式 → $$ ... $$
    s = Regex("""\\\(([\s\S]+?)\\\)""").replace(s) { "\$\$${it.groupValues[1]}\$\$" }
    // 单美元行内 $ ... $ → $$ ... $$。仅当内容含 LaTeX 信号（\ ^ _ { }）才转，
    // 避免把 "价格 $5 和 $10" 这类货币成对误判为公式。
    s = Regex("""(?<![\\$])\$(?!\$)([^\n$]*[\\^_{}][^\n$]*)(?<![\\$])\$(?!\$)""")
        .replace(s) { "\$\$${it.groupValues[1]}\$\$" }
    return s
}

/**
 * Markwon 缓存键。参与构造的全部参数都必须进键，否则同 dark 不同参数
 * （如系统字体缩放导致 textSizePx 变化）会命中旧实例、公式字号错乱。
 * 颜色不进键：文本/代码背景在 TextView 层逐帧设置，跟随主题与气泡透明度。
 */
private data class MarkwonKey(val dark: Boolean, val textSizePx: Float, val tableBorder: Int)

/**
 * Markdown 渲染工厂。
 *
 * Markwon 实例按 [MarkwonKey] 缓存（公式字体等插件初始化开销较大，避免逐条消息重建）。
 */
private val markwonCache = ConcurrentHashMap<MarkwonKey, Markwon>()

private fun obtainMarkwon(context: Context, dark: Boolean, textSizePx: Float, tableBorder: Int): Markwon =
    markwonCache.getOrPut(MarkwonKey(dark, textSizePx, tableBorder)) {
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            // 行内解析器：JLatexMathPlugin 的行内公式处理器需注册到它上面
            .usePlugin(MarkwonInlineParserPlugin.create())
            // ~~删除线~~（GFM）
            .usePlugin(StrikethroughPlugin.create())
            // 表格（边框色按主题定，不随气泡透明度）
            .usePlugin(TablePlugin.create(
                TableTheme.Builder()
                    .tableBorderColor(tableBorder)
                    .build()
            ))
            // LaTeX 公式：块级默认开启；显式打开行内（Markwon 4.6.2 行内默认关闭）。
            // 行内与块级均用 $$...$$ 定界。
            .usePlugin(JLatexMathPlugin.create(textSizePx) { builder ->
                builder.inlinesEnabled(true)
                builder.blocksEnabled(true)
            })
            // 裸 URL 自动识别为链接
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

/**
 * 流式输出时未闭合的 ``` 围栏自动补全，
 * 防止"半截代码块"在渲染器里闪烁成普通文本。
 */
internal fun closeUnclosedFences(src: String): String {
    val fenceCount = src.lineSequence().count { it.trimStart().startsWith("```") }
    return if (fenceCount % 2 == 1) "$src\n```" else src
}

/**
 * GFM 表格的分隔行（`|---|:---:|---:|`）。
 *
 * 至少两列才匹配：单列表格不存在挤压问题，而单独一行 `---` 是分割线、不能误判成表格。
 */
private val TABLE_DELIMITER_ROW =
    Regex("""^\s*\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)+\|?\s*$""")

/**
 * 数出 Markdown 里表格的最大列数（无表格返回 0）。
 *
 * 只认分隔行——commonmark 的 GFM 表格必须有它，据此拿列数比解析整棵语法树便宜得多。
 */
internal fun markdownTableColumns(src: String): Int =
    src.lineSequence()
        .filter { TABLE_DELIMITER_ROW.matches(it) }
        .map { line -> line.trim().trim('|').split('|').size }
        .maxOrNull() ?: 0

/** 表格每列的最小宽度（dp）。约 6 个汉字，低于此值单元格就会逐字换行。 */
private const val MIN_TABLE_COLUMN_WIDTH_DP = 88

/** 表格与相邻段落之间的间距（dp）。拆成多个 TextView 后需手工补上原本的段间距。 */
private val SEGMENT_SPACING = 8.dp

/**
 * 消息正文的分段：表格与普通文本必须分开渲染。
 *
 * 表格要横向滚动就得把 TextView 撑到比气泡更宽（Markwon 按 canvas 宽度均分列宽），
 * 但同一个 TextView 里的普通段落也会跟着按那个宽度换行——正文于是横向溢出气泡，
 * 得左右拖才能读完。所以按表格块切开，各用自己的宽度渲染。
 */
internal sealed interface MarkdownSegment {
    /** 普通文本（含代码块、公式、列表等一切非表格内容）。 */
    data class Text(val content: String) : MarkdownSegment

    /** 单个 GFM 表格块。[columns] 为列数。 */
    data class Table(val content: String, val columns: Int) : MarkdownSegment
}

/**
 * 把 Markdown 按 GFM 表格块切成若干段。
 *
 * 表格块的判据是「含 `|` 的行 + 紧随其后的分隔行」，随后连续含 `|` 的行都算表格内容。
 * 围栏代码块内的内容一律当文本，避免代码里的 `|---|` 被误判成表格。
 */
internal fun splitMarkdownTables(src: String): List<MarkdownSegment> {
    val lines = src.lines()
    val segments = mutableListOf<MarkdownSegment>()
    val buffer = StringBuilder()
    var inFence = false
    var i = 0

    fun flushText() {
        val text = buffer.toString().trim('\n')
        if (text.isNotBlank()) segments += MarkdownSegment.Text(text)
        buffer.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            buffer.appendLine(line)
            i++
            continue
        }
        val isTableStart = !inFence &&
            line.contains('|') &&
            i + 1 < lines.size &&
            TABLE_DELIMITER_ROW.matches(lines[i + 1])
        if (!isTableStart) {
            buffer.appendLine(line)
            i++
            continue
        }
        flushText()
        val table = StringBuilder()
        while (i < lines.size && lines[i].contains('|')) {
            table.appendLine(lines[i])
            i++
        }
        val content = table.toString().trim('\n')
        segments += MarkdownSegment.Table(content, markdownTableColumns(content))
    }
    flushText()
    return segments
}

/** 创建承载 Markdown 的 TextView（选择/链接/行距等一次性配置）。 */
private fun createMarkdownTextView(ctx: Context): TextView = TextView(ctx).apply {
    // 原生文字选择：长按弹出系统选择菜单（复制 / 全选）。
    // 先开 selectable（系统会把 movementMethod 置为 ArrowKeyMovementMethod），
    // 再覆盖回 LinkMovementMethod——长按选择由 textIsSelectable 标志驱动，
    // 与 movementMethod 无关，因此选择与链接点击可以并存。
    setTextIsSelectable(true)
    movementMethod = LinkMovementMethod.getInstance()
    typeface = Typeface.SANS_SERIF
    setLineSpacing(0f, 1.2f)
    // 选中高亮：半透明主题蓝，选择时可见（不再用全透明，否则选区看不见）
    setHighlightColor(0x553F51B5)
}

/**
 * 助手消息气泡的 Markdown 渲染组件。
 *
 * 用 Markwon(TextView) 渲染：代码块（等宽+背景色）、行内/块级 LaTeX 公式、
 * 表格、删除线、链接可点。颜色/透明度在 update 阶段桥接 MaterialTheme，
 * 主题切换与气泡透明度滑杆实时生效。
 *
 * @param markdown 消息正文
 * @param color 正文颜色（调用方已按气泡透明度处理）
 * @param alpha 气泡透明度（影响代码块背景）
 * @param isStreaming 流式中：未闭合代码围栏自动补全
 * @param availableWidth 气泡留给正文的宽度（已扣除 [horizontalPadding]）。用于判断
 *   表格是否需要横向滚动；[Dp.Unspecified] 表示调用方未提供，此时一律不滚动
 * @param horizontalPadding 正文左右内边距。**由本组件而非气泡施加**：横向滚动的表格
 *   要一路滑到气泡边缘才不会显得"被气泡边缘吃掉一截"，所以这段内边距对表格是画在
 *   TextView 内部（跟着内容滚），对普通段落才是外部 padding
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color,
    alpha: Float = 1f,
    isStreaming: Boolean = false,
    availableWidth: Dp = Dp.Unspecified,
    horizontalPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val textSizePx = with(density) { 14.sp.toPx() }
    val tableBorder = if (dark) 0xFF49454F.toInt() else 0xFFCAC4D0.toInt()
    // 首次渲染前初始化 LaTeX 符号表（幂等）
    remember { ensureLatexInit(context); true }
    val markwon = remember(dark, textSizePx, tableBorder) { obtainMarkwon(context, dark, textSizePx, tableBorder) }

    // 渲染前处理：定界符归一化 + 流式中未闭合代码围栏补全
    val safe = remember(markdown, isStreaming) {
        val normalized = normalizeLatexDelimiters(markdown)
        if (isStreaming) closeUnclosedFences(normalized) else normalized
    }

    // 代码块背景：主题基准色叠加气泡透明度（Markwon CodeBlockSpan 取 hint color）
    val codeBgBase = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val codeBg = codeBgBase.copy(alpha = codeBgBase.alpha * alpha)
    val textColor = color.copy(alpha = alpha)

    val segments = remember(safe) { splitMarkdownTables(safe) }
    val scrollableTables = availableWidth != Dp.Unspecified && segments.any {
        it is MarkdownSegment.Table && (it.columns * MIN_TABLE_COLUMN_WIDTH_DP).dp > availableWidth
    }

    // 没有需要滚动的表格：单个 TextView 渲染整段，与拆分前完全一致
    if (!scrollableTables) {
        MarkdownBody(
            markwon = markwon,
            source = safe,
            textColor = textColor,
            codeBg = codeBg,
            modifier = modifier.padding(horizontal = horizontalPadding)
        )
        return
    }

    Column(modifier = modifier) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) Spacer(modifier = Modifier.height(SEGMENT_SPACING))
            val tableWidth = if (segment is MarkdownSegment.Table) {
                (segment.columns * MIN_TABLE_COLUMN_WIDTH_DP).dp
            } else {
                Dp.Unspecified
            }
            if (tableWidth != Dp.Unspecified && tableWidth > availableWidth) {
                ScrollableMarkdownBody(
                    markwon = markwon,
                    source = segment.contentText(),
                    textColor = textColor,
                    codeBg = codeBg,
                    contentWidth = tableWidth,
                    horizontalPadding = horizontalPadding
                )
            } else {
                MarkdownBody(
                    markwon = markwon,
                    source = segment.contentText(),
                    textColor = textColor,
                    codeBg = codeBg,
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                )
            }
        }
    }
}

private fun MarkdownSegment.contentText(): String = when (this) {
    is MarkdownSegment.Text -> content
    is MarkdownSegment.Table -> content
}

/** 一段 Markdown → 一个 TextView。宽度由 Compose 约束决定，正常换行。 */
@Composable
private fun MarkdownBody(
    markwon: Markwon,
    source: String,
    textColor: Color,
    codeBg: Color,
    modifier: Modifier = Modifier,
) {
    // 解析结果按 (markwon, source) 缓存：主题/气泡透明度变化只走 update 改色，
    // 不再触发昂贵的 Markdown 重新解析（流式期间 source 每帧变，仍会逐帧解析）
    val parsed = remember(markwon, source) { markwon.toMarkdown(source) }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> createMarkdownTextView(ctx) },
        update = { tv ->
            tv.setTextColor(textColor.toArgb())
            tv.setHintTextColor(codeBg.toArgb())
            tv.setLinkTextColor(ColorStateList.valueOf(textColor.toArgb()))
            markwon.setParsedMarkdown(tv, parsed)
        }
    )
}

/**
 * 宽表格 → 可横向滚动的 TextView。
 *
 * Markwon 的表格没有横向滚动能力：`TableRowSpan` 把行宽均分成列宽，而它拿到的宽度就是
 * TextView 的内容宽度（`SpanUtils.width` → `textView.width - 左右 padding`）。所以必须由
 * 外部把 TextView 撑到 [contentWidth] 再套进滚动容器——但**不能靠 `layoutParams.width`**，
 * 见 [FixedContentWidthScrollView]。
 *
 * [horizontalPadding] 加在滚动容器上并配 `clipToPadding=false`：静止时表格首/末列距气泡
 * 边缘留出内边距，滚动过程中内容却能画进内边距区域，不会在离气泡边缘还有一段的位置
 * 凭空消失（那正是"气泡边缘遮住文字"的观感来源）。
 */
@Composable
private fun ScrollableMarkdownBody(
    markwon: Markwon,
    source: String,
    textColor: Color,
    codeBg: Color,
    contentWidth: Dp,
    horizontalPadding: Dp,
) {
    val density = LocalDensity.current
    val paddingPx = with(density) { horizontalPadding.roundToPx() }
    val contentWidthPx = with(density) { contentWidth.roundToPx() }
    val parsed = remember(markwon, source) { markwon.toMarkdown(source) }
    AndroidView(
        factory = { ctx ->
            FixedContentWidthScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(
                    createMarkdownTextView(ctx),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        },
        update = { scroll ->
            scroll.setPadding(paddingPx, 0, paddingPx, 0)
            scroll.contentWidth = contentWidthPx
            val tv = scroll.getChildAt(0) as TextView
            tv.setTextColor(textColor.toArgb())
            tv.setHintTextColor(codeBg.toArgb())
            tv.setLinkTextColor(ColorStateList.valueOf(textColor.toArgb()))
            markwon.setParsedMarkdown(tv, parsed)
        }
    )
}

/**
 * 以固定内容宽度承载子 View 的 [HorizontalScrollView]。
 *
 * [HorizontalScrollView] 重写了 `measureChild`/`measureChildWithMargins`，一律用
 * `UNSPECIFIED` 规格量子 View 的宽度、**完全忽略 `layoutParams.width`**——横向滚动容器
 * 的本意就是"子 View 想多宽给多宽"。于是"给 TextView 设个固定宽度"这条路根本不通，
 * TextView 只会退回按内容算宽度，而这对 Markwon 表格是致命的：
 *
 * - 表格独占 TextView 时，`TableRowSpan.getSize()` 在首次 draw 前返回的宽度是 0，
 *   TextView 于是量出宽 0、永远画不出来 → **整个表格消失**。
 * - 表格与正文同处一个 TextView 时，宽度变成"最长段落不换行时的宽度"，正文因此彻底
 *   不换行、横向溢出气泡 → **正文被截断**。
 *
 * 所以宽度必须由容器用 `EXACTLY` 规格下发。
 */
private class FixedContentWidthScrollView(context: Context) : HorizontalScrollView(context) {

    /** 子 View 的目标宽度（px）。0 表示回退到默认的按内容测量。 */
    var contentWidth: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        child.measure(
            childWidthSpec(0),
            getChildMeasureSpec(
                parentHeightMeasureSpec,
                paddingTop + paddingBottom,
                child.layoutParams.height
            )
        )
    }

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int
    ) {
        val lp = child.layoutParams as MarginLayoutParams
        child.measure(
            childWidthSpec(lp.leftMargin + lp.rightMargin),
            getChildMeasureSpec(
                parentHeightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
                lp.height
            )
        )
    }

    private fun childWidthSpec(margins: Int): Int = if (contentWidth > 0) {
        MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY)
    } else {
        MeasureSpec.makeMeasureSpec(margins, MeasureSpec.UNSPECIFIED)
    }
}
