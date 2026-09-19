package com.meapet.mobile.memory

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MemoryRepository 的 JVM 单元测试（临时目录持久化）。
 *
 * 依赖 build.gradle.kts 中 `unitTests.isReturnDefaultValues = true`，
 * 使 android.util.Log 调用在 JVM 上返回默认值。
 */
class MemoryRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(
        id: String,
        content: String,
        importance: Float = 0.5f,
        type: MemoryType = MemoryType.SHORT_TERM,
        keywords: List<String> = emptyList()
    ) = MemoryItem(id = id, content = content, type = type, importance = importance, keywords = keywords)

    // ── 持久化 round-trip ─────────────────────────────

    @Test
    fun persistThenReloadRoundTrip() = runTest {
        val dir = tmp.newFolder()
        val repo1 = MemoryRepository(dir)
        repo1.save(item("a", "用户叫小明", type = MemoryType.FACTUAL, importance = 0.9f))
        repo1.save(item("b", "用户喜欢猫"))

        // 新实例模拟重启
        val repo2 = MemoryRepository(dir)
        val all = repo2.getAll()
        assertEquals(2, all.size)
        assertEquals("a", all.first { it.type == MemoryType.FACTUAL }.id)
    }

    // ── 懒加载兜底：先写后载不丢旧数据 ────────────────

    @Test
    fun saveBeforeExplicitLoadDoesNotClobberDisk() = runTest {
        val dir = tmp.newFolder()
        MemoryRepository(dir).save(item("old", "旧记忆"))

        // 重启后：loadFromDisk 尚未被调用时就先 save（模拟启动竞态）
        val repo = MemoryRepository(dir)
        repo.save(item("new", "新记忆"))
        repo.loadFromDisk() // 晚到的预热

        val ids = repo.getAll().map { it.id }.toSet()
        assertTrue("old" in ids, "磁盘上的旧记忆不应被启动早期写入覆盖")
        assertTrue("new" in ids)

        // 再重启一次确认磁盘内容完整
        val ids2 = MemoryRepository(dir).getAll().map { it.id }.toSet()
        assertEquals(setOf("old", "new"), ids2)
    }

    // ── 损坏文件备份 ──────────────────────────────────

    @Test
    fun corruptedFileIsBackedUpAndIgnored() = runTest {
        val dir = tmp.newFolder()
        File(dir, "meapet_memories.json").writeText("not a json }{")

        val repo = MemoryRepository(dir)
        assertTrue(repo.getAll().isEmpty())
        assertTrue(File(dir, "meapet_memories.json.corrupt").exists())

        // 损坏处理后仍可正常保存
        repo.save(item("x", "新的"))
        assertEquals(1, MemoryRepository(dir).getAll().size)
    }

    // ── clear 后不复活 ────────────────────────────────

    @Test
    fun clearBeforeLoadDoesNotResurrectOldData() = runTest {
        val dir = tmp.newFolder()
        MemoryRepository(dir).save(item("ghost", "该被清掉"))

        val repo = MemoryRepository(dir)
        repo.clear() // 未加载就清除
        assertTrue(repo.getAll().isEmpty(), "clear 后惰性加载不应复活旧数据")
        assertTrue(MemoryRepository(dir).getAll().isEmpty(), "clear 应落盘")
    }

    // ── 容量淘汰（事实/特质豁免） ──────────────────────

    @Test
    fun capacityEvictsLowValueShortTermItems() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir, maxItems = 3)
        repo.save(item("keep1", "重要", importance = 0.9f))
        repo.save(item("keep2", "也重要", importance = 0.8f))
        repo.save(item("keep3", "还行", importance = 0.5f))
        repo.save(item("evict", "不重要", importance = 0.1f))

        val ids = repo.getAll().map { it.id }.toSet()
        assertEquals(3, ids.size)
        assertTrue("evict" !in ids)
    }

    @Test
    fun factsAndTraitsNeverAutoEvicted() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir, maxItems = 2)
        // 事实/特质数量远超 maxItems，也不应被淘汰
        repo.save(item("fact1", "用户叫小明", type = MemoryType.FACTUAL))
        repo.save(item("fact2", "生日 3 月 25 日", type = MemoryType.FACTUAL))
        repo.save(item("trait1", "说话喜欢简短", type = MemoryType.CORE_TRAIT))
        repo.save(item("st1", "短期1", importance = 0.9f))
        repo.save(item("st2", "短期2", importance = 0.9f))
        repo.save(item("st3", "短期3(应被淘汰)", importance = 0.1f))

        val all = repo.getAll().map { it.id }.toSet()
        assertTrue(setOf("fact1", "fact2", "trait1").all { it in all }, "事实/特质不应被容量淘汰")
        assertTrue("st3" !in all, "低价值短期记忆仍应正常淘汰")
    }

    // ── getPersonaFacts：全量事实+特质 ─────────────────

    @Test
    fun getPersonaFactsReturnsAllFactsAndTraits() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(item("fact", "用户叫小明", type = MemoryType.FACTUAL))
        repo.save(item("trait", "喜欢简短回复", type = MemoryType.CORE_TRAIT))
        repo.save(item("short", "今天吃了火锅", type = MemoryType.SHORT_TERM))
        repo.save(item("long", "长期总结", type = MemoryType.LONG_TERM))

        val facts = repo.getPersonaFacts().map { it.id }.toSet()
        assertEquals(setOf("fact", "trait"), facts)
    }

    // ── getRelevant：仅按 keywords 匹配 ─────────────────

    @Test
    fun getRelevantMatchesByKeywordsRegardlessOfLanguage() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(item("name", "用户提到: 我叫小明", importance = 0.9f, keywords = listOf("小明", "名字")))
        repo.save(item("cat", "用户喜欢橘猫", importance = 0.6f, keywords = listOf("橘猫", "猫")))
        repo.save(item("misc", "今天天气不错", importance = 0.3f, keywords = listOf("天气")))

        val result = repo.getRelevant("你还记得小明吗")
        assertEquals(listOf("name"), result.map { it.id })
    }

    @Test
    fun getRelevantIgnoresContentWithoutMatchingKeyword() = runTest {
        // 关键词是模型给出的，不是 content 本身——即使 content 里有词，关键词没命中也不该返回
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(item("a", "用户喜欢玩 minecraft", importance = 0.7f, keywords = listOf("我的世界")))

        assertTrue(repo.getRelevant("minecraft 好玩吗").isEmpty())
    }

    @Test
    fun getRelevantIgnoresFactsAndTraits() = runTest {
        // 事实/特质走 getPersonaFacts 全量注入，不参与关键词检索
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(item("fact", "用户叫小明", type = MemoryType.FACTUAL, keywords = listOf("小明")))

        assertTrue(repo.getRelevant("小明在吗").isEmpty())
    }

    @Test
    fun getRelevantSkipsItemsWithNoKeywords() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(item("nokw", "用户叫小明", keywords = emptyList()))

        assertTrue(repo.getRelevant("小明").isEmpty())
    }

    @Test
    fun getRelevantIsPureReadAndDoesNotTouchAccessStats() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(item("hit", "用户喜欢猫", importance = 0.8f, keywords = listOf("猫")))
        val before = repo.findById("hit")?.accessCount

        repo.getRelevant("你喜欢什么猫")
        // 读写分离：纯查询不改访问计数、不落盘
        assertEquals(before, repo.findById("hit")?.accessCount)
        assertEquals(before, MemoryRepository(dir).findById("hit")?.accessCount)
    }

    @Test
    fun markAccessedUpdatesStatsAndPersists() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(item("hit", "用户喜欢猫", importance = 0.8f, keywords = listOf("猫")))
        val before = repo.findById("hit")?.accessCount ?: 0

        repo.markAccessed(listOf("hit"))
        // accessCount 更新应落盘（重启后不回退）
        val reloaded = MemoryRepository(dir).findById("hit")
        assertEquals(before + 1, reloaded?.accessCount)
    }

    @Test
    fun markAccessedIgnoresUnknownIds() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(item("hit", "用户喜欢猫", keywords = listOf("猫")))
        // 不存在的 id 静默跳过，不抛异常
        repo.markAccessed(listOf("ghost", "hit"))
        assertEquals(1, MemoryRepository(dir).getAll().size)
    }

    @Test
    fun getPersonaFactsCapsByImportanceButKeepsCreationOrder() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        // 故意让创建顺序与重要性顺序相反
        repo.save(item("old", "最早但很重要", importance = 0.9f, type = MemoryType.FACTUAL))
        repo.save(item("mid", "中等", importance = 0.5f, type = MemoryType.CORE_TRAIT))
        repo.save(item("new", "最新但不重要", importance = 0.1f, type = MemoryType.FACTUAL))

        val kept = repo.getPersonaFacts(maxCount = 2)

        assertEquals(listOf("old", "mid"), kept.map { it.id }, "按重要性取前 2，输出仍按创建时间")
        assertEquals(3, repo.getAll().size, "封顶只影响注入，不影响存储")
    }

    @Test
    fun getPersonaFactsWithoutCapReturnsAll() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(item("a", "甲", type = MemoryType.FACTUAL))
        repo.save(item("b", "乙", type = MemoryType.CORE_TRAIT))

        assertEquals(2, repo.getPersonaFacts().size)
    }

    // ── 批量删除 ──────────────────────────────────────

    @Test
    fun deleteAllRemovesOnlyGivenIdsAndPersists() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(item("a", "甲"))
        repo.save(item("b", "乙"))
        repo.save(item("c", "丙"))

        repo.deleteAll(listOf("a", "c", "不存在的id"))

        assertEquals(listOf("b"), MemoryRepository(dir).getAll().map { it.id }, "删除应落盘")
    }

    @Test
    fun deleteAllWithEmptyIdsIsNoOp() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(item("a", "甲"))

        repo.deleteAll(emptyList())

        assertEquals(1, repo.getAll().size)
    }

    // ── 批量应用（新增/覆盖 + 删除，一次落盘） ─────────

    @Test
    fun applyChangesUpsertsAndDeletesInOnePass() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(item("keep", "保留", importance = 0.5f))
        repo.save(item("upd", "旧内容", importance = 0.5f))
        repo.save(item("gone", "待删"))

        repo.applyChanges(
            upserts = listOf(
                item("upd", "新内容", importance = 0.9f),   // 覆盖已存在
                item("add", "新增条目")                       // 新增
            ),
            deleteIds = listOf("gone")
        )

        // 重启后确认全部变更已落盘
        val reloaded = MemoryRepository(dir)
        val byId = reloaded.getAll().associateBy { it.id }
        assertEquals(setOf("keep", "upd", "add"), byId.keys)
        assertEquals("新内容", byId["upd"]?.content)
        assertEquals(0.9f, byId["upd"]?.importance)
    }

    @Test
    fun applyChangesWithNothingIsNoOp() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        repo.save(item("a", "甲"))

        repo.applyChanges(emptyList(), emptyList())

        assertEquals(1, repo.getAll().size)
    }
}
