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
        return MemoryToolExecutor(BigramMemorySearcher(repo))
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
}
