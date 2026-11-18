package com.meapet.mobile.memory.search

import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.memory.MemoryType
import kotlin.math.min

/** 检索排序方式，对应 `search_memory` 工具的 `sort` 参数。 */
enum class MemorySort { RELEVANCE, RECENT, IMPORTANCE }

/**
 * 一条检索请求。
 *
 * @param text 检索词或短句（可空格分隔多个关键词）
 * @param types 限定类型；null = 短期 + 长期（人设类默认不参与检索，它们每轮全量注入）
 * @param limit 最多返回条数（1..10）
 * @param sort 排序方式，默认相关度
 * @param candidates 显式指定候选池；非 null 时实现必须用它代替 `store.getAll()`。
 *   调用方（如 `MemoryToolExecutor`）可借此传入「已落库 + 本 turn 暂存」的合并视图，
 *   使同一 turn 内刚创建/更新的记忆也能被检索到。null 时回退到 `store.getAll()`。
 */
data class MemoryQuery(
    val text: String,
    val types: Set<MemoryType>? = null,
    val limit: Int = 5,
    val sort: MemorySort = MemorySort.RELEVANCE,
    val candidates: List<MemoryItem>? = null
)

/** 记忆检索抽象。对外契约固定，底层实现可替换（内存打分 / 未来的 FTS）。 */
interface MemorySearch {
    suspend fun search(query: MemoryQuery): List<MemoryItem>
}

/**
 * 内存打分检索（默认实现，不依赖 FTS / 向量）。
 *
 * ## 为什么不用 FTS
 * 记忆规模很小（短期+长期上限 500 条，永久类同量级），全量扫描是毫秒级；
 * 而 Android 框架 SQLite 只有 FTS4 且中文分词基本无效，引入 FTS 的复杂度换不来收益。
 *
 * ## 打分（权重为起始值，可按实测调整）
 * - 关键词命中 0.3：命中 keywords 的比例
 * - 内容二元组重叠 0.1：内容与检索词的字符二元组（中文适用）重叠率
 * - 重要度 0.1：模型给出的 importance
 * - 新近度 0.1：按 lastAccessedAt 衰减（越久未用越低）
 * - 双向命中加权：query 含某词、或某词含 query 片段都算命中（解决「那个论文」vs「毕业论文」）
 *
 * 相关度排序时对候选池**全部**条目打分：得分高的在前，命中不足 limit 时用低分
 * （未命中）条目补齐，因此调用方总会拿到候选池的前 limit 条。按 RECENT / IMPORTANCE
 * 排序时返回全部候选的前 limit 条。
 */
class BigramMemorySearcher(
    private val store: MemoryStore,
    private val clock: () -> Long = System::currentTimeMillis
) : MemorySearch {

    companion object {
        /** 默认检索类型：人设类（FACTUAL/CORE_TRAIT）每轮全量注入，不参与检索。 */
        val DEFAULT_TYPES: Set<MemoryType> = setOf(MemoryType.SHORT_TERM, MemoryType.LONG_TERM)

        private const val W_KEYWORD = 0.3
        private const val W_BIGRAM = 0.1
        private const val W_IMPORTANCE = 0.1
        private const val W_RECENCY = 0.1
        private const val RECENCY_HALF_LIFE_DAYS = 30.0
    }

    /**
     * 检索记忆。
     *
     * 候选池优先取 [MemoryQuery.candidates]（调用方传入的合并视图），为 null 时取
     * [store] 的全量数据；随后按 [MemoryQuery.types] 过滤。
     *
     * RELEVANCE：对候选池全部条目打分，按
     * `(得分降序, importance 降序, lastAccessedAt 降序, createdAt 降序, id 升序)`
     * 排序后取前 limit 条——**不过滤零分条目**，命中不足时由未命中条目补齐；
     * tokenize 结果为空（空/空白 query）时直接返回空列表。
     * RECENT / IMPORTANCE：保持原有排序语义。
     */
    override suspend fun search(query: MemoryQuery): List<MemoryItem> {
        val wantedTypes = query.types ?: DEFAULT_TYPES
        val pool = (query.candidates ?: store.getAll()).filter { it.type in wantedTypes }
        if (pool.isEmpty()) return emptyList()
        val limit = query.limit.coerceIn(1, 10)
        val queryTokens = tokenize(query.text)
        if (queryTokens.isEmpty() && query.sort == MemorySort.RELEVANCE) return emptyList()

        val ranked = when (query.sort) {
            MemorySort.RECENT -> pool.sortedByDescending { it.lastAccessedAt }
            MemorySort.IMPORTANCE -> pool.sortedByDescending { it.importance }
            MemorySort.RELEVANCE -> pool
                .map { item -> item to score(item, query.text, queryTokens) }
                .sortedWith(
                    compareByDescending<Pair<MemoryItem, Double>> { it.second }
                        .thenByDescending { it.first.importance }
                        .thenByDescending { it.first.lastAccessedAt }
                        .thenByDescending { it.first.createdAt }
                        .thenBy { it.first.id }
                )
                .map { it.first }
        }
        return ranked.take(limit)
    }

    private fun score(item: MemoryItem, rawQuery: String, queryTokens: Set<String>): Double {
        val lowerQuery = rawQuery.lowercase()

        // 关键词命中：双向包含（query 含关键词，或关键词含 query 的某个词）
        val nonBlankKeywords = item.keywords.filter { it.isNotBlank() }
        val matchedKeywords = nonBlankKeywords.count { kw ->
            val k = kw.lowercase()
            k.isNotEmpty() && (lowerQuery.contains(k) || queryTokens.any { t -> k.contains(t) || t.contains(k) })
        }
        val keywordScore = if (nonBlankKeywords.isEmpty()) 0.0 else matchedKeywords.toDouble() / nonBlankKeywords.size

        // 内容二元组重叠
        val contentTokens = tokenize(item.content)
        val overlap = if (contentTokens.isEmpty() || queryTokens.isEmpty()) 0.0 else {
            val common = queryTokens.count { it in contentTokens }
            common.toDouble() / min(queryTokens.size, contentTokens.size).coerceAtLeast(1)
        }

        // 新近度衰减
        val days = ((clock() - item.lastAccessedAt).coerceAtLeast(0L)) / 86_400_000.0
        val recency = 1.0 / (1.0 + days / RECENCY_HALF_LIFE_DAYS)

        return keywordScore * W_KEYWORD +
            overlap * W_BIGRAM +
            item.importance.toDouble() * W_IMPORTANCE * if (matchedKeywords > 0 || overlap > 0) 1.0 else 0.0 +
            recency * W_RECENCY * if (matchedKeywords > 0 || overlap > 0) 1.0 else 0.0
    }

    /**
     * 切词：拉丁/数字按连续段整体作为一个词；CJK 连续段生成字符二元组（单字时保留单字）。
     * 全部小写。用于中英文都能命中的检索。
     */
    internal fun tokenize(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val tokens = HashSet<String>()
        val lower = text.lowercase()
        var i = 0
        while (i < lower.length) {
            val ch = lower[i]
            when {
                isCjk(ch) -> {
                    var j = i
                    while (j < lower.length && isCjk(lower[j])) j++
                    val run = lower.substring(i, j)
                    if (run.length == 1) {
                        tokens.add(run)
                    } else {
                        for (k in 0 until run.length - 1) tokens.add(run.substring(k, k + 2))
                    }
                    i = j
                }
                ch.isLetterOrDigit() -> {
                    var j = i
                    while (j < lower.length && lower[j].isLetterOrDigit() && !isCjk(lower[j])) j++
                    tokens.add(lower.substring(i, j))
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF ||
            c in 0x3040..0x30FF || c in 0xAC00..0xD7AF || c in 0xF900..0xFAFF
    }
}
