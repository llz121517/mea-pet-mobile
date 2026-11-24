package com.meapet.mobile.memory

import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MemoryManager 的摘要触发逻辑测试。
 *
 * 重点验证轮数计数器**跨进程存活**——早期版本把计数放在实例字段里，
 * 冷启动即归零，默认 10 轮的间隔实际上永远走不满，摘要一次都不会触发。
 */
class MemoryManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 用一个变量模拟持久化在记忆存储里的对话轮数计数。
     *
     * 计数自 1.9.0 起存在记忆存储（SQLite meta 表）而非 DataStore；[build] 每次
     * 返回新的 mock，但共享同一个 [CountHolder]——相当于换了进程、换了 MemoryManager
     * 实例，存下来的计数还在。
     */
    private class CountHolder(var value: Int = 0)

    /** 在真实 JSON 存储上叠加共享计数，模拟「换进程后计数还在」。 */
    private class CountStore(
        private val delegate: MemoryStore,
        private val holder: CountHolder
    ) : MemoryStore by delegate {
        override suspend fun getExchangeCount(): Int = holder.value
        override suspend fun setExchangeCount(count: Int) {
            holder.value = count
        }
    }

    private class FakeSettings(private val interval: Int, private val memoryEnabled: Boolean = true) {
        fun build(): SettingsManager {
            val sm = mock<SettingsManager>()
            whenever(sm.isMemoryEnabled()).thenReturn(memoryEnabled)
            whenever(sm.getSummaryInterval()).thenReturn(interval)
            return sm
        }
    }

    private fun manager(
        settings: SettingsManager,
        service: MemoryService,
        count: CountHolder = CountHolder()
    ) = MemoryManager(
        service = service,
        repository = CountStore(MemoryRepository(tmp.newFolder()), count),
        settingsManager = settings,
        config = AppConfig.DEFAULT
    )

    @Test
    fun summaryTriggersOnlyAfterIntervalReached() = runTest {
        val settings = FakeSettings(interval = 3)
        val service = mock<MemoryService>()
        val count = CountHolder()
        val mgr = manager(settings.build(), service, count)

        repeat(2) { mgr.onExchangeComplete() }
        verifyBlocking(service, never()) { summarizeShortTermMemories() }
        assertEquals(2, count.value)

        mgr.onExchangeComplete()
        verifyBlocking(service, times(1)) { summarizeShortTermMemories() }
        assertEquals(0, count.value, "触发后计数应清零重新攒")
    }

    @Test
    fun exchangeCountSurvivesManagerRecreation() = runTest {
        val settings = FakeSettings(interval = 3)
        val service = mock<MemoryService>()
        val count = CountHolder()

        // 第一次「启动」聊 2 轮
        repeat(2) { manager(settings.build(), service, count).onExchangeComplete() }
        verifyBlocking(service, never()) { summarizeShortTermMemories() }

        // 冷启动后再聊 1 轮，应接着数到 3 而不是从头开始
        manager(settings.build(), service, count).onExchangeComplete()
        verifyBlocking(service, times(1)) { summarizeShortTermMemories() }
    }

    @Test
    fun shorteningIntervalTriggersOnNextExchange() = runTest {
        val long = FakeSettings(interval = 10)
        val service = mock<MemoryService>()
        val count = CountHolder()
        val mgr = manager(long.build(), service, count)

        repeat(4) { mgr.onExchangeComplete() }
        assertEquals(4, count.value)

        // 用户把间隔从 10 调到 3：已攒的 4 轮应立刻满足，而不是被取模逻辑跳过、再等一整个周期
        val short = FakeSettings(interval = 3)
        manager(short.build(), service, count).onExchangeComplete()
        verifyBlocking(service, times(1)) { summarizeShortTermMemories() }
        assertEquals(0, count.value)
    }

    // ── buildContext ──────────────────────────────────

    private fun contextManager(repo: MemoryRepository, memoryEnabled: Boolean = true) = MemoryManager(
        service = mock(),
        repository = repo,
        settingsManager = FakeSettings(interval = 10, memoryEnabled = memoryEnabled).build(),
        config = AppConfig.DEFAULT
    )

    @Test
    fun stablePartContainsReadonlyGuidanceAndPersonaFacts() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(MemoryItem(id = "f1", content = "用户叫小明", type = MemoryType.FACTUAL))

        val ctx = contextManager(repo).buildContext("你好")

        assertTrue(ctx.stable.startsWith("【记忆只读】"), "稳定段应简短说明只读边界")
        assertTrue(ctx.stable.contains("不可增删改"), "应明确当前模式不能写入记忆")
        assertTrue(ctx.stable.contains("不要声称已记录或保存"), "应避免模型承诺保存新信息")
        assertTrue(ctx.stable.contains("用户叫小明"), "事实应注入稳定段")
        assertFalse(ctx.stable.contains("memory-ops"), "不应包含已移除的文本协议")
        assertFalse(ctx.stable.contains("可引用 id 更新/删除"), "只读上下文不应指示通过 id 写入")
        assertFalse(ctx.tail.contains("用户叫小明"), "事实不该同时出现在尾部段")
    }

    @Test
    fun tailContainsOnlyKeywordMatchedRecollections() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(MemoryItem(id = "fact", content = "用户叫小明", type = MemoryType.FACTUAL))
        repo.save(
            MemoryItem(
                id = "hit",
                content = "用户喜欢吃酸的水果",
                type = MemoryType.SHORT_TERM,
                keywords = listOf("水果")
            )
        )
        repo.save(
            MemoryItem(
                id = "miss",
                content = "用户喜欢看电影",
                type = MemoryType.LONG_TERM,
                keywords = listOf("电影")
            )
        )

        val ctx = contextManager(repo).buildContext("今天买了点水果")

        assertEquals(
            "【相关回忆】（关键词匹配，仅供参考）\n- [hit] (短期) 用户喜欢吃酸的水果\n",
            ctx.tail.replace("\r\n", "\n")
        )
        assertTrue(ctx.stable.contains("用户叫小明"), "人设事实应留在稳定段")
        assertFalse(ctx.tail.contains("用户叫小明"), "事实不应重复进入检索尾段")
        assertFalse(ctx.tail.contains("用户喜欢看电影"), "未命中关键词的回忆不应注入")
    }

    @Test
    fun tailIsEmptyWhenThereAreNoKeywordHits() = runTest {
        val ctx = contextManager(MemoryRepository(tmp.newFolder())).buildContext("你好")

        assertEquals("", ctx.tail)
    }

    @Test
    fun toolPersonaSectionRemainsByteForByteUnchanged() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(
            MemoryItem(
                id = "f1",
                content = "用户叫小明",
                type = MemoryType.FACTUAL,
                createdAt = 100
            )
        )
        repo.save(
            MemoryItem(
                id = "t1",
                content = "喜欢简短回复",
                type = MemoryType.CORE_TRAIT,
                createdAt = 200
            )
        )

        val persona = contextManager(repo).buildPersonaSection()

        assertEquals(
            "【用户人设】（永久，可引用 id 更新/删除）\n- [f1] (事实) 用户叫小明\n- [t1] (特质) 喜欢简短回复",
            persona.replace("\r\n", "\n")
        )
    }

    @Test
    fun repeatedContextReadsPreserveMemoryContentAndAccessMetadata() = runTest {
        val dir = tmp.newFolder()
        val repo = MemoryRepository(dir)
        val stored = MemoryItem(
            id = "m1",
            content = "用户喜欢吃酸的水果",
            type = MemoryType.SHORT_TERM,
            createdAt = 100,
            lastAccessedAt = 123,
            accessCount = 7,
            keywords = listOf("水果")
        )
        repo.save(stored)
        val manager = contextManager(repo)

        val first = manager.buildContext("今天买了点水果")
        val second = manager.buildContext("今天买了点水果")

        assertEquals(first, second, "相同输入应得到稳定的只读上下文")
        assertTrue(first.tail.contains("用户喜欢吃酸的水果"), "关键词命中仍应注入相关回忆")
        assertEquals(listOf(stored), repo.getAll(), "构建上下文不能修改记忆或访问计数")
        assertEquals(listOf(stored), MemoryRepository(dir).getAll(), "读取上下文后持久化内容也必须不变")
    }

    @Test
    fun personaFactsAreCappedForInjection() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        val cap = AppConfig.DEFAULT.maxPersonaFacts
        repeat(cap + 5) { i ->
            repo.save(
                MemoryItem(
                    id = "f$i",
                    content = "事实$i",
                    type = MemoryType.FACTUAL,
                    importance = i / 100f
                )
            )
        }

        val stable = contextManager(repo).buildContext("你好").stable

        assertEquals(cap, stable.lines().count { it.startsWith("- [f") }, "注入条数应封顶")
        assertFalse(stable.contains("事实0"), "重要性最低的应被挤出注入")
        assertEquals(cap + 5, repo.getAll().size, "封顶只影响注入，不影响存储")
    }

    @Test
    fun contextIsEmptyWhenMemoryDisabled() = runTest {
        val ctx = contextManager(MemoryRepository(tmp.newFolder()), memoryEnabled = false)
            .buildContext("你好")

        assertEquals("", ctx.stable)
        assertEquals("", ctx.tail)
    }

    @Test
    fun nothingHappensWhenMemoryDisabled() = runTest {
        val settings = FakeSettings(interval = 1, memoryEnabled = false)
        val service = mock<MemoryService>()
        val count = CountHolder()

        manager(settings.build(), service, count).onExchangeComplete()

        verifyBlocking(service, never()) { summarizeShortTermMemories() }
        assertEquals(0, count.value, "开关关闭时不应计数")
    }

    // ── 撤销：用快照恢复记忆 ──────────────────────────

    private fun managerWithRepository(repository: MemoryRepository) = MemoryManager(
        service = mock(),
        repository = repository,
        settingsManager = FakeSettings(interval = 10).build(),
        config = AppConfig.DEFAULT
    )

    @Test
    fun restoreAllWritesSnapshotBackToRepository() = runTest {
        val repository = mock<MemoryRepository>()
        val items = listOf(
            MemoryItem(id = "m1", content = "喜欢下雨天"),
            MemoryItem(id = "m2", content = "猫叫 Mea"),
        )

        managerWithRepository(repository).restoreAll(items)

        verifyBlocking(repository) { saveAll(items) }
    }

    @Test
    fun restoreAllNoOpOnEmptySnapshot() = runTest {
        val repository = mock<MemoryRepository>()

        managerWithRepository(repository).restoreAll(emptyList())

        // 空快照没有可还原的内容，不该多写一次盘
        verifyBlocking(repository, never()) { saveAll(any()) }
    }

    // ── 工具删除的墓碑快照 ────────────────────────────

    @Test
    fun toolDeletionTombstonesAreKeptForUndo() = runTest {
        val repository = mock<MemoryStore>()
        val mgr = MemoryManager(
            service = mock(),
            repository = repository,
            settingsManager = FakeSettings(interval = 10).build(),
            config = AppConfig.DEFAULT
        )
        val deleted = MemoryItem(id = "m1", content = "旧事实", type = MemoryType.FACTUAL)

        mgr.applyToolChanges(
            com.meapet.mobile.memory.tools.PendingMemoryChanges(
                deletes = listOf("m1"),
                deletedItems = listOf(deleted)
            )
        )

        verifyBlocking(repository) { applyChanges(emptyList(), listOf("m1")) }
        assertEquals(listOf(deleted), mgr.takeRecentToolDeletions(), "墓碑应完整保留删除前条目")
        assertTrue(mgr.takeRecentToolDeletions().isEmpty(), "取出后清空，不会重复撤销")

        // 撤销路径：快照原样写回
        mgr.restoreAll(listOf(deleted))
        verifyBlocking(repository) { saveAll(listOf(deleted)) }
    }

    @Test
    fun clearingTombstonesDropsPreviousTurnDeletions() = runTest {
        val mgr = MemoryManager(
            service = mock(),
            repository = mock<MemoryStore>(),
            settingsManager = FakeSettings(interval = 10).build(),
            config = AppConfig.DEFAULT
        )
        mgr.applyToolChanges(
            com.meapet.mobile.memory.tools.PendingMemoryChanges(
                deletes = listOf("m1"),
                deletedItems = listOf(MemoryItem(id = "m1", content = "旧事实", type = MemoryType.FACTUAL))
            )
        )

        mgr.clearRecentToolDeletions()

        assertTrue(mgr.takeRecentToolDeletions().isEmpty(), "旧 turn 的删除不能混进新一轮的撤销项")
    }
}
