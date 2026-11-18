package com.meapet.mobile.memory.search

import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.memory.MemoryType
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigramMemorySearcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): MemoryRepository = MemoryRepository(tmp.newFolder())

    private fun mem(id: String, content: String, keywords: List<String>) =
        MemoryItem(id = id, content = content, type = MemoryType.SHORT_TERM, keywords = keywords)

    private suspend fun seed(repo: MemoryRepository) {
        repo.saveAll(
            listOf(
                MemoryItem(
                    id = "mem_thesis",
                    content = "主人在准备毕业论文",
                    type = MemoryType.SHORT_TERM,
                    importance = 0.8f,
                    keywords = listOf("论文", "毕业")
                ),
                MemoryItem(
                    id = "mem_chicken",
                    content = "主人喜欢炸鸡",
                    type = MemoryType.LONG_TERM,
                    importance = 0.6f,
                    keywords = listOf("炸鸡", "食物")
                ),
                MemoryItem(
                    id = "mem_name",
                    content = "主人叫小明",
                    type = MemoryType.FACTUAL,
                    importance = 0.9f,
                    keywords = listOf("小明", "名字")
                )
            )
        )
    }

    @Test
    fun `关键词命中可召回`() = runTest {
        val repo = store()
        seed(repo)
        val searcher = BigramMemorySearcher(repo)

        val results = searcher.search(MemoryQuery(text = "论文"))

        // 新语义：不过滤零分条目，命中项排最前，未命中条目补足候选池
        assertEquals(listOf("mem_thesis", "mem_chicken"), results.map { it.id })
    }

    @Test
    fun `用户换个说法也能召回（双向包含）`() = runTest {
        val repo = store()
        seed(repo)
        val searcher = BigramMemorySearcher(repo)

        // 「那个论文怎么样了」包含关键词「论文」
        val results = searcher.search(MemoryQuery(text = "那个论文怎么样了"))
        assertEquals("mem_thesis", results.first().id)
    }

    @Test
    fun `中文内容二元组可召回未出现在关键词里的说法`() = runTest {
        val repo = store()
        seed(repo)
        val searcher = BigramMemorySearcher(repo)

        // 只靠内容 bigram（「毕业」在内容里），keywords 命中「毕业」也算；用更偏的查询验证内容兜底
        val results = searcher.search(MemoryQuery(text = "毕业论文进度"))
        assertEquals("mem_thesis", results.first().id)
    }

    @Test
    fun `默认不检索人设类`() = runTest {
        val repo = store()
        seed(repo)
        val searcher = BigramMemorySearcher(repo)

        val defaults = searcher.search(MemoryQuery(text = "小明"))
        assertTrue(defaults.none { it.id == "mem_name" }, "FACTUAL 默认全量注入，不参与检索")
        assertEquals(2, defaults.size, "非人设类候选仍按候选池全部返回")

        val withFactual = searcher.search(
            MemoryQuery(
                text = "小明",
                types = setOf(MemoryType.FACTUAL)
            )
        )
        assertEquals(listOf("mem_name"), withFactual.map { it.id })
    }

    @Test
    fun `按重要度排序返回全部候选`() = runTest {
        val repo = store()
        seed(repo)
        val searcher = BigramMemorySearcher(repo)

        val results = searcher.search(
            MemoryQuery(text = "论文", sort = MemorySort.IMPORTANCE, limit = 10)
        )
        assertEquals(listOf("mem_thesis", "mem_chicken"), results.map { it.id })
    }

    @Test
    fun `空查询且按相关度排序时返回空`() = runTest {
        val repo = store()
        seed(repo)
        val searcher = BigramMemorySearcher(repo)

        assertTrue(searcher.search(MemoryQuery(text = "   ")).isEmpty())
    }

    @Test
    fun `命中一条时返回候选池前 limit 条且命中项排第一`() = runTest {
        val repo = store()
        repo.saveAll(
            listOf(
                mem("mem_hit", "主人在准备毕业论文", listOf("论文")),
                mem("mem_cat", "主人喜欢猫", listOf("猫")),
                mem("mem_hike", "主人周末去爬山", listOf("爬山"))
            )
        )
        val searcher = BigramMemorySearcher(repo)

        val results = searcher.search(MemoryQuery(text = "论文", limit = 5))

        assertEquals(3, results.size, "返回条数 = min(limit, 候选数)")
        assertEquals("mem_hit", results.first().id, "命中项排第一")
    }

    @Test
    fun `limit 只返回前 limit 条`() = runTest {
        val repo = store()
        repo.saveAll(
            listOf(
                mem("mem_hit", "主人在准备毕业论文", listOf("论文")),
                mem("mem_cat", "主人喜欢猫", listOf("猫")),
                mem("mem_hike", "主人周末去爬山", listOf("爬山"))
            )
        )
        val searcher = BigramMemorySearcher(repo)

        val results = searcher.search(MemoryQuery(text = "论文", limit = 2))

        assertEquals(2, results.size)
        assertEquals("mem_hit", results.first().id)
    }

    @Test
    fun `candidates 覆盖 store 作为候选池`() = runTest {
        val emptyRepo = store()
        val searcher = BigramMemorySearcher(emptyRepo)
        val candidates = listOf(
            mem("mem_a", "主人在准备毕业论文", listOf("论文")),
            mem("mem_b", "主人喜欢猫", listOf("猫"))
        )

        val results = searcher.search(MemoryQuery(text = "论文", candidates = candidates))

        assertEquals(listOf("mem_a", "mem_b"), results.map { it.id })
    }

    @Test
    fun `切词生成中文二元组与英文整词`() {
        val searcher = BigramMemorySearcher(store())
        val tokens = searcher.tokenize("毕业论文 OpenAI")
        assertTrue("毕业" in tokens)
        assertTrue("论文" in tokens)
        assertTrue("openai" in tokens)
    }
}
