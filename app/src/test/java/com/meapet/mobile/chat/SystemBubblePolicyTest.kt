package com.meapet.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SystemBubblePolicy] 气泡寿命调度测试。
 *
 * 覆盖规则：
 * - 位置 1-3（最新）不扣寿命；
 * - 位置 4 及以后被挤旧时从**剩余**寿命里扣 2 秒，累计最多扣
 *   [SystemBubblePolicy.MAX_REDUCE_COUNT]（2 次，共 4 秒）；
 * - 已扣满上限后即使持续停留在旧位也不再下降；
 * - 扣减永不把总寿命拉长（回归用例：扣减必须作用在剩余寿命上，不能重新计时）。
 */
@Suppress("NonAsciiCharacters")
class SystemBubblePolicyTest {

    /** 气泡产生时刻，仅作为可读的时间基准原点。 */
    private val born = 1_000_000L

    /** 刚产生的气泡的到期时刻。 */
    private val baseDeadline = born + SystemBubblePolicy.BASE_LIFE_MS

    @Test
    fun `位置 1 到 3 不扣寿命且不增加计数`() {
        for (position in 1..3) {
            val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
                deadlineMs = baseDeadline,
                nowMs = born,
                reduceCount = 0,
                position = position
            )
            assertEquals("position=$position 不应扣寿命", baseDeadline, deadline)
            assertEquals("position=$position 不应增加计数", 0, count)
        }
    }

    @Test
    fun `位置 4 首次扣 2 秒`() {
        val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
            deadlineMs = baseDeadline,
            nowMs = born,
            reduceCount = 0,
            position = 4
        )
        assertEquals(baseDeadline - 2_000L, deadline)
        assertEquals(1, count)
    }

    @Test
    fun `位置 6 首次同样扣 2 秒`() {
        val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
            deadlineMs = baseDeadline,
            nowMs = born,
            reduceCount = 0,
            position = 6
        )
        assertEquals(baseDeadline - 2_000L, deadline)
        assertEquals(1, count)
    }

    @Test
    fun `位置 6 第二次扣 2 秒`() {
        val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
            deadlineMs = baseDeadline - 2_000L,
            nowMs = born,
            reduceCount = 1,
            position = 6
        )
        assertEquals(baseDeadline - 4_000L, deadline)
        assertEquals(2, count)
    }

    @Test
    fun `已达扣减上限后不再扣`() {
        val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
            deadlineMs = baseDeadline - 4_000L,
            nowMs = born,
            reduceCount = SystemBubblePolicy.MAX_REDUCE_COUNT,
            position = 6
        )
        assertEquals(baseDeadline - 4_000L, deadline)
        assertEquals(SystemBubblePolicy.MAX_REDUCE_COUNT, count)
    }

    @Test
    fun `位置退回 1 到 3 即使有配额也不扣`() {
        val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
            deadlineMs = baseDeadline - 2_000L,
            nowMs = born,
            reduceCount = 1,
            position = 2
        )
        assertEquals(baseDeadline - 2_000L, deadline)
        assertEquals(1, count)
    }

    @Test
    fun `连续挤压 6 位总寿命封底在 3 秒`() {
        var deadline = baseDeadline
        var count = 0
        val lives = mutableListOf<Long>()
        repeat(5) {
            val next = SystemBubblePolicy.computeNextDeadline(deadline, born, count, 6)
            deadline = next.first
            count = next.second
            lives += deadline - born
        }
        assertEquals(listOf(5_000L, 3_000L, 3_000L, 3_000L, 3_000L), lives)
        assertEquals(SystemBubblePolicy.MIN_LIFE_MS, deadline - born)
    }

    // ── 回归：扣减必须缩短寿命，不能重新计时 ────────────────

    @Test
    fun `已存活一段时间被挤旧时提前移除而不是续命`() {
        // 已存活 4 秒（剩 3 秒）。旧实现按「剩余时长」重新倒计时：扣完剩 5 秒，
        // 从第 4 秒起再等 5 秒 → 总寿命 9 秒，比原定 7 秒更久。
        val now = born + 4_000L
        val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
            deadlineMs = baseDeadline,
            nowMs = now,
            reduceCount = 0,
            position = 4
        )
        assertEquals("扣减后应比原到期时刻早 2 秒", baseDeadline - 2_000L, deadline)
        assertEquals("总寿命应缩短到 5 秒", 5_000L, deadline - born)
        assertTrue("总寿命不得超过初始寿命", deadline - born <= SystemBubblePolicy.BASE_LIFE_MS)
        assertEquals(1, count)
    }

    @Test
    fun `剩余不足一个扣减步长时钳到当前时刻立即移除`() {
        // 已存活 6 秒（剩 1 秒），扣 2 秒会越过当前时刻
        val now = born + 6_000L
        val (deadline, count) = SystemBubblePolicy.computeNextDeadline(
            deadlineMs = baseDeadline,
            nowMs = now,
            reduceCount = 0,
            position = 4
        )
        assertEquals("不应倒退到过去，应立即到期", now, deadline)
        assertEquals(1, count)
    }

    @Test
    fun `任意时刻扣减都不会让到期时刻推后`() {
        for (elapsed in 0L..7_000L step 500L) {
            val now = born + elapsed
            val (deadline, _) = SystemBubblePolicy.computeNextDeadline(
                deadlineMs = baseDeadline,
                nowMs = now,
                reduceCount = 0,
                position = 4
            )
            assertTrue("elapsed=$elapsed 时到期时刻被推后了", deadline <= baseDeadline)
        }
    }
}
