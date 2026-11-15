package com.meapet.mobile.memory

import com.meapet.mobile.config.AppConfig
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
import org.mockito.kotlin.wheneverBlocking
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
     * 用一个变量模拟 DataStore 里的持久计数。
     *
     * [build] 每次返回新的 mock，但共享同一个 [storedCount]——相当于换了进程、
     * 换了 MemoryManager 实例，磁盘上的计数还在。
     */
    private class FakeSettings(private val interval: Int, private val memoryEnabled: Boolean = true) {
        var storedCount = 0

        fun build(): SettingsManager {
            val sm = mock<SettingsManager>()
            whenever(sm.isMemoryEnabled()).thenReturn(memoryEnabled)
            whenever(sm.getSummaryInterval()).thenReturn(interval)
            whenever(sm.getExchangeCount()).thenAnswer { storedCount }
            wheneverBlocking { sm.setExchangeCount(any()) }.thenAnswer {
                storedCount = it.arguments[0] as Int
            }
            return sm
        }
    }

    private fun manager(settings: SettingsManager, service: MemoryService) = MemoryManager(
        service = service,
        repository = MemoryRepository(tmp.newFolder()),
        settingsManager = settings,
        config = AppConfig.DEFAULT
    )

    @Test
    fun summaryTriggersOnlyAfterIntervalReached() = runTest {
        val settings = FakeSettings(interval = 3)
        val service = mock<MemoryService>()
        val mgr = manager(settings.build(), service)

        repeat(2) { mgr.onExchangeComplete(emptyList()) }
        verifyBlocking(service, never()) { summarizeShortTermMemories() }
        assertEquals(2, settings.storedCount)

        mgr.onExchangeComplete(emptyList())
        verifyBlocking(service, times(1)) { summarizeShortTermMemories() }
        assertEquals(0, settings.storedCount, "触发后计数应清零重新攒")
    }

    @Test
    fun exchangeCountSurvivesManagerRecreation() = runTest {
        val settings = FakeSettings(interval = 3)
        val service = mock<MemoryService>()

        // 第一次「启动」聊 2 轮
        repeat(2) { manager(settings.build(), service).onExchangeComplete(emptyList()) }
        verifyBlocking(service, never()) { summarizeShortTermMemories() }

        // 冷启动后再聊 1 轮，应接着数到 3 而不是从头开始
        manager(settings.build(), service).onExchangeComplete(emptyList())
        verifyBlocking(service, times(1)) { summarizeShortTermMemories() }
    }

    @Test
    fun shorteningIntervalTriggersOnNextExchange() = runTest {
        val long = FakeSettings(interval = 10)
        val service = mock<MemoryService>()
        val mgr = manager(long.build(), service)

        repeat(4) { mgr.onExchangeComplete(emptyList()) }
        assertEquals(4, long.storedCount)

        // 用户把间隔从 10 调到 3：已攒的 4 轮应立刻满足，而不是被取模逻辑跳过、再等一整个周期
        val short = FakeSettings(interval = 3).apply { storedCount = long.storedCount }
        manager(short.build(), service).onExchangeComplete(emptyList())
        verifyBlocking(service, times(1)) { summarizeShortTermMemories() }
        assertEquals(0, short.storedCount)
    }

    // ── buildContext ──────────────────────────────────

    private fun contextManager(repo: MemoryRepository, memoryEnabled: Boolean = true) = MemoryManager(
        service = mock(),
        repository = repo,
        settingsManager = FakeSettings(interval = 10, memoryEnabled = memoryEnabled).build(),
        config = AppConfig.DEFAULT
    )

    @Test
    fun stablePartHoldsProtocolAndPersonaFacts() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(MemoryItem(id = "f1", content = "用户叫小明", type = MemoryType.FACTUAL))

        val ctx = contextManager(repo).buildContext("你好")

        assertTrue(ctx.stable.startsWith("【记忆协议】"), "协议说明应在最前")
        assertTrue(ctx.stable.contains("用户叫小明"), "事实应注入稳定段")
        assertFalse(ctx.tail.contains("用户叫小明"), "事实不该同时出现在尾部段")
    }

    @Test
    fun tailPartHoldsRecollectionsAndEndsWithReminder() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(
            MemoryItem(
                id = "m1",
                content = "用户喜欢吃酸的水果",
                type = MemoryType.SHORT_TERM,
                keywords = listOf("水果")
            )
        )

        val ctx = contextManager(repo).buildContext("今天买了点水果")

        assertTrue(ctx.tail.contains("用户喜欢吃酸的水果"), "命中的回忆应进尾部段")
        // 提醒必须是整个请求的最后一段
        assertTrue(ctx.tail.trimEnd().endsWith(MemoryOpsProtocol.reminder()), "收尾提醒应在最末")
    }

    @Test
    fun tailStillCarriesReminderWithoutRecollections() = runTest {
        val ctx = contextManager(MemoryRepository(tmp.newFolder())).buildContext("你好")
        assertEquals(MemoryOpsProtocol.reminder(), ctx.tail)
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

        manager(settings.build(), service).onExchangeComplete(emptyList())

        verifyBlocking(service, never()) { summarizeShortTermMemories() }
        assertEquals(0, settings.storedCount, "开关关闭时不应计数")
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
}
