package com.meapet.mobile.ui.component

/*
 * ── TODO(2026-09-07) ────────────────────────────────────────────────
 * 1) 拆分本文件（现约 670 行、混 7 类职责）：计划按
 *    ui/component/markdown/ 拆成 MarkdownText / MarkdownLatex /
 *    MarkdownSegments / MarkdownFactory / MarkdownScrollView，纯搬家、internal 保持。
 *
 * 2) 已知限制：
 *    Markwon 表格 TableRowSpan 是 ReplacementSpan —— 单元格文字不在 TextView 文本里，
 *    而在 span 内部 Cell 的 StaticLayout 中，且 cells 无公共取回口。
 *    后果：长按选中表格只能按行选、复制内容为空。
 *    彻底解决需弃用 ext-tables 改自绘表格（格内才谈得上真实文本选择），属大改；
 *    若要低成本可用方案：把每个表格拆成独立 TextView，复制时兜底为
 *    “整张表可粘贴纯文本（行=换行、列=制表符）”。
 * ──────────────────────────────────────────────────────────────────
 */

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlin.math.roundToInt

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
 * 表格斑马底色：中性低透明叠加色，浅/深色各一套。
 *
 * 表头行稍实（约 12%）、数据行极淡交替（5% / 透明）——让表格块与普通正文可辨，
 * 又不抢戏。颜色不随气泡透明度衰减（与边框色同理，避免滑杆拖动时重建 Markwon），
 * 想调整对比度改这三处即可。
 */
private fun tableHeaderRowBg(dark: Boolean): Int = if (dark) 0x1FFFFFFF else 0x1F000000
private fun tableEvenRowBg(dark: Boolean): Int = if (dark) 0x0DFFFFFF else 0x0D000000
private fun tableOddRowBg(dark: Boolean): Int = 0x00000000

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
                    .tableHeaderRowBackgroundColor(tableHeaderRowBg(dark))
                    .tableEvenRowBackgroundColor(tableEvenRowBg(dark))
                    .tableOddRowBackgroundColor(tableOddRowBg(dark))
                    .tableCellPadding(
                        (TABLE_CELL_PADDING.value * context.resources.displayMetrics.density).roundToInt()
                    )
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

/**
 * 单元格文字距格线/表边的内边距（dp，四边统一）。
 * Markwon 默认 0 → 首列文字会贴它左边那根竖线；稍加一点更透气。
 */
private val TABLE_CELL_PADDING = 3.dp

/** 表格与相邻段落之间的间距（dp）。拆成多个 TextView 后需手工补上原本的段间距。 */
private val SEGMENT_SPACING = 8.dp

/** 宽表格滚动窗内、首/末列距窗缘的内边距（dp）：最左列文字不贴窗缘，留点呼吸。 */
private val TABLE_SCROLL_EDGE_INSET = 2.dp

/** 底部横向缩略条的胶囊高度（dp）。 */
private val SCROLL_INDICATOR_HEIGHT = 3.dp

/** 表格末行之下为缩略条预留的空白区（dp），胶囊纵向居中于此，避免盖住末行。 */
private val SCROLL_INDICATOR_RESERVE = 12.dp

/** 胶囊透明度（乘到主题 primary 上，取淡）。 */
private const val SCROLL_INDICATOR_ALPHA = 0.45f

/** 胶囊最小长度（dp），超宽表格内容再多也保留可拖的视觉块。 */
private val SCROLL_INDICATOR_MIN_WIDTH = 8.dp

/** 拖过头(overscroll)时胶囊最大拉长量（dp）：超过后封顶，松手弹回。 */
private val TABLE_OVERSCROLL_MAX = 18.dp

/** 拖过头量累积时的阻尼（0..1）。 */
private const val TABLE_OVERSCROLL_DAMPING = 0.5f

/** 胶囊随 overscroll 拉长的放大系数（1.0 = 拉长量与过头量 1:1）。 */
private const val PILL_OVERSCROLL_SCALE = 1.0f

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
 * 宽表格 → 固定内缩的可横向滚动窗，底部附主题色胶囊缩略条。
 *
 * 横向滚动窗整体在气泡内左右固定内缩 [horizontalPadding]（用 Compose `padding` 施加），
 * 气泡底色在表格左右**恒定透出**——这是真正"可见"的留白。内容只在窗内滚动、不滑进边距，
 * 因此不需要 `clipToPadding=false`。
 *
 * Markwon 的表格没有横向滚动能力：`TableRowSpan` 把行宽均分成列宽，而它拿到的宽度就是
 * TextView 的内容宽度（`SpanUtils.width` → `textView.width - 左右 padding`）。所以必须由
 * 外部把 TextView 撑到 [contentWidth] 再套进滚动容器——但**不能靠 `layoutParams.width`**，
 * 见 [FixedContentWidthScrollView]。
 *
 * 胶囊缩略条画在 Compose 层（下方给表格预留 [SCROLL_INDICATOR_RESERVE] 空白），
 * 固定在窗内、只按滚动**比例**滑动，绝不会被画进内容里跟着整表平移。
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
    val contentWidthPx = with(density) { contentWidth.roundToPx() }
    val edgeInsetPx = with(density) { TABLE_SCROLL_EDGE_INSET.roundToPx() }
    val indicatorReservePx = with(density) { SCROLL_INDICATOR_RESERVE.roundToPx() }
    val indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = SCROLL_INDICATOR_ALPHA)
    // 滚动比例（0..1），驱动胶囊位置；在 Canvas 的 draw 里读它 → 只触发重绘、不整树重组
    var fraction by remember { mutableFloatStateOf(0f) }
    // 拖过头量（px），驱动胶囊被拉长；同样在 draw 里读
    var overscrollPx by remember { mutableFloatStateOf(0f) }
    val parsed = remember(markwon, source) { markwon.toMarkdown(source) }

    BoxWithConstraints(modifier = Modifier.padding(horizontal = horizontalPadding)) {
        // 内缩后的窗宽即可视宽；表格超宽时才滚动
        val viewportPx = with(density) { maxWidth.roundToPx() }
        val hasOverflow = contentWidthPx > viewportPx

        Box {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    FixedContentWidthScrollView(ctx).apply {
                        isHorizontalScrollBarEnabled = false
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
                    scroll.contentWidth = contentWidthPx
                    // 底部预留 = 胶囊区（末行不延伸其下）；左右各留 [TABLE_SCROLL_EDGE_INSET]，
                    // 最左列文字不贴窗缘
                    scroll.setPadding(edgeInsetPx, 0, edgeInsetPx, indicatorReservePx)
                    val tv = scroll.getChildAt(0) as TextView
                    tv.setTextColor(textColor.toArgb())
                    tv.setHintTextColor(codeBg.toArgb())
                    tv.setLinkTextColor(ColorStateList.valueOf(textColor.toArgb()))
                    markwon.setParsedMarkdown(tv, parsed)
                    if (contentWidthPx > 0) {
                        scroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
                            val range = (contentWidthPx - scroll.width).coerceAtLeast(1)
                            fraction = (scrollX.toFloat() / range).coerceIn(0f, 1f)
                        }
                    }
                    scroll.onOverscroll = { px -> overscrollPx = px }
                }
            )

            // 胶囊缩略条：固定在窗内下沿预留区，长度 = 可见比例，位置 = 滚动比例
            if (hasOverflow) {
                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(SCROLL_INDICATOR_RESERVE)
                ) {
                    val vp = size.width
                    if (contentWidthPx <= vp) return@Canvas
                    val barH = with(density) { SCROLL_INDICATOR_HEIGHT.toPx() }
                    val minW = with(density) { SCROLL_INDICATOR_MIN_WIDTH.toPx() }
                    val baseW = (vp * vp / contentWidthPx).coerceAtLeast(minW)
                    var w = baseW
                    var l = (vp - baseW) * fraction
                    val over = overscrollPx * PILL_OVERSCROLL_SCALE
                    if (over > 0f) {
                        if (fraction > 0.5f) {
                            // 右端被拉过头：右缘钉住，往左拉长
                            w = (baseW + over).coerceAtMost(vp)
                            l = (vp - w).coerceAtLeast(0f)
                        } else {
                            // 左端被拉过头：左缘钉住，往右拉长
                            w = (baseW + over).coerceAtMost(vp)
                        }
                    }
                    val barY = (size.height - barH) / 2f
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(l, barY),
                        size = Size(w, barH),
                        cornerRadius = CornerRadius(barH / 2f)
                    )
                }
            }
        }
    }
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

    /** 拖过头(overscroll)量回调（px>0；松手动画归零）。驱动胶囊拉长。 */
    var onOverscroll: ((Float) -> Unit)? = null

    private var downX = 0f
    private var overPx = 0f
    private var springAnim: ValueAnimator? = null

    private fun scrollRange(): Int =
        (contentWidth - (width - paddingLeft - paddingRight)).coerceAtLeast(0)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                springAnim?.cancel()
                springAnim = null
                overPx = 0f
                downX = event.x
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                downX = event.x
                val range = scrollRange()
                // 已到右端还往左拖 / 已到左端还往右拖 → 过头量
                val atRightOver = range > 0 && scrollX >= range - 1 && dx < 0f
                val atLeftOver = range > 0 && scrollX <= 1 && dx > 0f
                val gain = when {
                    atRightOver -> -dx
                    atLeftOver -> dx
                    else -> 0f
                }
                if (gain > 0f) {
                    val maxPx = TABLE_OVERSCROLL_MAX.value * resources.displayMetrics.density
                    overPx = (overPx + gain * TABLE_OVERSCROLL_DAMPING).coerceAtMost(maxPx)
                    onOverscroll?.invoke(overPx)
                } else if (overPx > 0f) {
                    // 往回拖 = 释放：过头量随之减小，更跟手
                    val releasing = if (scrollX >= range - 1) dx > 0f else dx < 0f
                    if (releasing) {
                        val back = if (dx > 0f) dx else -dx
                        overPx = (overPx - back * TABLE_OVERSCROLL_DAMPING).coerceAtLeast(0f)
                        onOverscroll?.invoke(overPx)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springBack()
        }
        return super.onTouchEvent(event)
    }

    private fun springBack() {
        if (overPx <= 0f) return
        val from = overPx
        springAnim?.cancel()
        springAnim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                overPx = anim.animatedValue as Float
                onOverscroll?.invoke(overPx)
            }
            start()
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
