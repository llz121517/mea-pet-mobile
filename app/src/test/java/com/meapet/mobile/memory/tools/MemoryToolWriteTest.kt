package com.meapet.mobile.memory.tools

import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryRepository
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.memory.search.BigramMemorySearcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 写类工具：暂存语义、去重、错误信封、护栏与批量提交。 */
class MemoryToolWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun setup(): Pair<MemoryToolExecutor, MemoryRepository> {
        val repo = MemoryRepository(tmp.newFolder())
        val executor = MemoryToolExecutor(
            search = BigramMemorySearcher(repo),
            store = repo
        )
        return executor to repo
    }

    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject
    private fun str(raw: String, key: String) = obj(raw)[key]!!.jsonPrimitive.content

    @Test
    fun `关闭记忆时所有工具返回禁用且不读取存储`() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        var searches = 0
        val executor = MemoryToolExecutor(
            search = object : com.meapet.mobile.memory.search.MemorySearch {
                override suspend fun search(query: com.meapet.mobile.memory.search.MemoryQuery): List<MemoryItem> {
                    searches++
                    return listOf(MemoryItem(id = "secret", content = "不能读取", type = MemoryType.SHORT_TERM))
                }
            },
            store = repo,
            isMemoryEnabled = { false }
        )
        for ((name, args) in listOf(
            "search_memory" to """{"query":"秘密"}""",
            "create_memory" to """{"content":"不能创建"}""",
            "update_memory" to """{"id":"missing","content":"不能修改"}""",
            "delete_memory" to """{"id":"missing"}"""
        )) {
            val result = executor.execute(name, args)
            assertEquals("false", str(result, "ok"))
            assertEquals("memory_disabled", str(result, "error"))
        }
        assertEquals(0, searches)
        assertTrue(executor.drainPending().isEmpty)
        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun `暂存后关闭记忆则成功轮次也不落库且重新开启不泄漏暂存`() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        var enabled = true
        val executor = MemoryToolExecutor(
            search = BigramMemorySearcher(repo), store = repo,
            commit = { repo.applyChanges(it.upserts, it.deletes) },
            isMemoryEnabled = { enabled }
        )
        executor.onTurnStart()
        assertEquals("true", str(executor.execute("create_memory", """{"content":"不能提交"}"""), "ok"))
        enabled = false
        executor.onTurnSuccess()
        assertTrue(repo.getAll().isEmpty())
        enabled = true
        executor.onTurnSuccess()
        assertTrue(repo.getAll().isEmpty())
        assertEquals("true", str(executor.execute("create_memory", """{"content":"重新开启后允许"}"""), "ok"))
        executor.onTurnSuccess()
        assertEquals(listOf("重新开启后允许"), repo.getAll().map { it.content })
    }

    @Test
    fun `create 只暂存，commit 前不落库`() = runTest {
        val (executor, repo) = setup()

        val raw = executor.execute(
            "create_memory",
            """{"type":"SHORT_TERM","content":"主人说明天要面试","importance":0.8,"keywords":["面试","明天"]}"""
        )

        assertEquals("true", str(raw, "ok"))
        assertEquals("queued", str(raw, "status"))
        assertTrue(str(raw, "id").startsWith("mem_"))
        assertTrue(repo.getAll().isEmpty(), "turn 成功前不应落库")

        val pending = executor.drainPending()
        assertEquals(1, pending.upserts.size)
        assertEquals("主人说明天要面试", pending.upserts.single().content)
        assertEquals(listOf("面试", "明天"), pending.upserts.single().keywords)
        assertEquals(0.8f, pending.upserts.single().importance)
    }

    @Test
    fun `create 重复内容返回 duplicate 且不加新条目`() = runTest {
        val (executor, _) = setup()
        val args = """{"type":"SHORT_TERM","content":"主人喜欢炸鸡","keywords":["炸鸡"]}"""

        val first = executor.execute("create_memory", args)
        val second = executor.execute("create_memory", args)

        assertEquals("queued", str(first, "status"))
        assertEquals("duplicate", str(second, "status"))
        assertEquals(str(first, "id"), str(second, "id"))
        assertEquals(1, executor.drainPending().upserts.size)
    }

    @Test
    fun `update 同 turn 内可引用刚创建的 id`() = runTest {
        val (executor, _) = setup()
        val created = executor.execute(
            "create_memory",
            """{"content":"主人说明天要面试","keywords":["面试"]}"""
        )
        val id = str(created, "id")

        val updated = executor.execute(
            "update_memory",
            """{"id":"$id","content":"主人明天上午要面试","importance":0.9}"""
        )

        assertEquals("queued", str(updated, "status"))
        val pending = executor.drainPending()
        assertEquals(1, pending.upserts.size, "同 id 更新应覆盖而不是新增")
        assertEquals("主人明天上午要面试", pending.upserts.single().content)
        assertEquals(0.9f, pending.upserts.single().importance)
    }

    @Test
    fun `update 目标不存在返回 not_found 而不退化为新建`() = runTest {
        val (executor, _) = setup()

        val raw = executor.execute("update_memory", """{"id":"mem_nope","content":"x"}""")

        assertEquals("false", str(raw, "ok"))
        assertEquals("not_found", str(raw, "error"))
        assertTrue(executor.drainPending().isEmpty)
    }

    @Test
    fun `删除永久类记忆需要显式确认与原因`() = runTest {
        val (executor, repo) = setup()
        repo.save(
            MemoryItem(
                id = "mem_fact",
                content = "主人叫小明",
                type = MemoryType.FACTUAL,
                keywords = listOf("小明")
            )
        )

        val withoutConfirm = executor.execute("delete_memory", """{"id":"mem_fact"}""")
        assertEquals("permanent_delete_requires_confirmation", str(withoutConfirm, "error"))
        assertTrue(executor.drainPending().isEmpty)

        val withConfirm = executor.execute(
            "delete_memory",
            """{"id":"mem_fact","reason":"用户要求忘记","confirm_permanent":true}"""
        )
        assertEquals("queued", str(withConfirm, "status"))
        assertEquals(listOf("mem_fact"), executor.drainPending().deletes)
    }

    @Test
    fun `普通记忆可直接暂存删除`() = runTest {
        val (executor, repo) = setup()
        repo.save(MemoryItem(id = "mem_s", content = "临时信息", keywords = listOf("临时")))

        val raw = executor.execute("delete_memory", """{"id":"mem_s"}""")

        assertEquals("queued", str(raw, "status"))
        assertEquals(listOf("mem_s"), executor.drainPending().deletes)
    }

    /**
     * 删除要留**墓碑快照**（删除前存库的完整条目），供 UI 撤销还原；
     * 同 turn 内刚创建又删除的条目从未落库，不该有墓碑（撤销无意义）。
     */
    @Test
    fun `删除记录删除前条目的墓碑快照`() = runTest {
        val (executor, repo) = setup()
        val existing = MemoryItem(id = "mem_s", content = "临时信息", type = MemoryType.SHORT_TERM, keywords = listOf("临时"))
        repo.save(existing)

        executor.execute("delete_memory", """{"id":"mem_s"}""")
        val pending = executor.drainPending()

        assertEquals(listOf("mem_s"), pending.deletes)
        assertEquals(1, pending.deletedItems.size, "删除前已落库的条目应有墓碑")
        assertEquals("临时信息", pending.deletedItems.single().content)
    }

    @Test
    fun `同 turn 内新建后又删除的条目不产生墓碑`() = runTest {
        val (executor, _) = setup()
        val created = executor.execute("create_memory", """{"content":"刚建的","keywords":["新建"]}""")
        val id = str(created, "id")

        executor.execute("delete_memory", """{"id":"$id"}""")
        val pending = executor.drainPending()

        assertEquals(listOf(id), pending.deletes)
        assertTrue(pending.deletedItems.isEmpty(), "从未落库的条目不产生墓碑")
    }

    @Test
    fun `单 turn 写入超过上限时返回 too_many_writes`() = runTest {
        val (executor, _) = setup()
        repeat(6) { i ->
            val raw = executor.execute("create_memory", """{"content":"内容$i","keywords":["k$i"]}""")
            assertEquals("queued", str(raw, "status"))
        }
        val seventh = executor.execute("create_memory", """{"content":"内容7","keywords":["k7"]}""")
        assertEquals("too_many_writes", str(seventh, "error"))
        assertEquals(6, executor.drainPending().upserts.size)
    }

    @Test
    fun `discardPending 丢弃本 turn 暂存`() = runTest {
        val (executor, _) = setup()
        executor.execute("create_memory", """{"content":"x","keywords":["x"]}""")

        executor.discardPending()

        assertTrue(executor.drainPending().isEmpty)
    }

    @Test
    fun `无 store 时写类工具返回 unsupported`() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        val executor = MemoryToolExecutor(search = BigramMemorySearcher(repo))

        val raw = executor.execute("create_memory", """{"content":"x","keywords":["x"]}""")

        assertEquals("unsupported", str(raw, "error"))
    }
}
