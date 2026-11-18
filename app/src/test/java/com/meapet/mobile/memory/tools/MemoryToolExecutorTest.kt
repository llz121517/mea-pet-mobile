package com.meapet.mobile.memory.tools

import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.memory.search.BigramMemorySearcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryToolExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun executorWith(items: List<MemoryItem>): MemoryToolExecutor {
        val repo = MemoryRepository(tmp.newFolder())
        repo.saveAll(items)
        return MemoryToolExecutor(BigramMemorySearcher(repo), store = repo)
    }

    private fun item(id: String, content: String, keywords: List<String>, type: MemoryType = MemoryType.SHORT_TERM) =
        MemoryItem(id = id, content = content, type = type, keywords = keywords)

    @Test
    fun `检索成功返回统一信封`() = runTest {
        val executor = executorWith(
            listOf(item("mem_a", "主人在准备毕业论文", listOf("论文", "毕业")))
        )

        val raw = executor.execute("search_memory", """{"query":"论文","limit":5}""")
        val obj = json.parseToJsonElement(raw).jsonObject

        assertEquals(true, obj["ok"]!!.jsonPrimitive.content.toBoolean())
        val results = obj["results"]!!.jsonArray
        assertEquals(1, results.size)
        val first = results[0].jsonObject
        assertEquals("mem_a", first["id"]!!.jsonPrimitive.content)
        assertTrue(first.containsKey("age_days"), "用 age_days 代替绝对时间戳")
    }

    @Test
    fun `未知工具返回错误信封而不是抛异常`() = runTest {
        val executor = executorWith(emptyList())
        val obj = json.parseToJsonElement(executor.execute("no_such_tool", "{}")).jsonObject
        assertEquals("false", obj["ok"]!!.jsonPrimitive.content)
        assertEquals("unknown_tool", obj["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `参数非法或缺少 query 返回 invalid_arguments`() = runTest {
        val executor = executorWith(emptyList())

        val bad = json.parseToJsonElement(executor.execute("search_memory", "not json")).jsonObject
        assertEquals("invalid_arguments", bad["error"]!!.jsonPrimitive.content)

        val missing = json.parseToJsonElement(executor.execute("search_memory", "{}")).jsonObject
        assertEquals("invalid_arguments", missing["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `limit 会被夹到范围内且结果按相关度返回`() = runTest {
        val executor = executorWith(
            listOf(
                item("mem_1", "毕业论文初稿", listOf("论文")),
                item("mem_2", "论文选题", listOf("论文"))
            )
        )

        val obj = json.parseToJsonElement(
            executor.execute("search_memory", """{"query":"论文","limit":99}""")
        ).jsonObject
        val results = obj["results"]!!.jsonArray
        assertTrue(results.size <= 2)
        assertEquals(results.size, obj["returned"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `库中多条记忆时返回全部候选且命中项排最前`() = runTest {
        val executor = executorWith(
            listOf(
                item("mem_a", "主人在准备毕业论文", listOf("论文", "毕业")),
                item("mem_b", "主人喜欢炸鸡", listOf("炸鸡")),
                item("mem_c", "主人周末去爬山", listOf("爬山"))
            )
        )

        val obj = json.parseToJsonElement(
            executor.execute("search_memory", """{"query":"论文","limit":5}""")
        ).jsonObject
        val results = obj["results"]!!.jsonArray

        assertEquals(3, results.size, "命中不足 limit 时用未命中条目补足候选池")
        assertEquals("mem_a", results[0].jsonObject["id"]!!.jsonPrimitive.content, "命中项排第一")
        assertEquals("3", obj["total_matched"]!!.jsonPrimitive.content, "total_matched = 候选池条数")
    }

    @Test
    fun `同一 turn 内新建的记忆可被检索到`() = runTest {
        val executor = executorWith(emptyList())

        val created = json.parseToJsonElement(
            executor.execute("create_memory", """{"content":"主人养了一只叫团子的猫","keywords":["猫","团子"]}""")
        ).jsonObject
        assertEquals("true", created["ok"]!!.jsonPrimitive.content)
        assertEquals("queued", created["status"]!!.jsonPrimitive.content)

        // 该条目尚未落库（turn 成功后才 applyChanges），检索必须能看到暂存视图
        val obj = json.parseToJsonElement(
            executor.execute("search_memory", """{"query":"猫","limit":5}""")
        ).jsonObject
        val results = obj["results"]!!.jsonArray

        assertEquals(1, results.size)
        val content = results[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("团子"), "检索结果应包含本 turn 刚创建的暂存条目")
    }
}
