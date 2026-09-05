package com.meapet.mobile.chat

/**
 * 系统气泡（Live2D 触摸分区提示）寿命调度策略。
 *
 * 纯逻辑、无协程/UI 依赖，便于单元测试。规则：
 * - 每个气泡初始寿命 [BASE_LIFE_MS]（7 秒）；
 * - 每次有新气泡加入、按 timestamp 重新排位后，排在 [KEEP_FULL_LIFE_POSITIONS] 之后的气泡
 *   被「挤旧」，**剩余**寿命按 [REDUCE_STEP_MS]（2 秒）扣减，累计最多扣 [MAX_REDUCE_COUNT]
 *   （2 次，共 4 秒）——因此单个气泡从产生到消失至少存活 [MIN_LIFE_MS]（3 秒），
 *   不会因新气泡持续到来而被无限挤压。
 *
 * ## 为什么用「到期时刻」而不是「剩余时长」
 * 扣减必须作用在**剩余**寿命上。若只记时长、扣减后从当前时刻重新倒计时，已流逝的时间
 * 就被丢掉了：一个已存活 6 秒的气泡「扣 2 秒」会变成再活 5 秒（总计 11 秒），
 * 比原定 7 秒更久，与「挤旧」的意图完全相反。所以本策略统一按**绝对到期时刻**运算，
 * 调用方只需保证 `nowMs` 与 `deadlineMs` 出自同一单调时间基准。
 */
object SystemBubblePolicy {

    /** 气泡初始寿命（ms）。 */
    const val BASE_LIFE_MS = 7_000L

    /** 位置靠后时每次扣减的寿命（ms）。 */
    const val REDUCE_STEP_MS = 2_000L

    /** 前若干位（最新）保持满寿命，不参与扣减。 */
    const val KEEP_FULL_LIFE_POSITIONS = 3

    /** 单个气泡最多被扣减的次数（7 秒 - 2×2 秒 = 3 秒保底）。 */
    const val MAX_REDUCE_COUNT = 2

    /** 气泡寿命下限（ms）：由扣减次数上限自然保证。 */
    const val MIN_LIFE_MS = BASE_LIFE_MS - MAX_REDUCE_COUNT * REDUCE_STEP_MS

    /**
     * 按当前排位计算气泡的下一个到期时刻。
     *
     * @param deadlineMs 当前到期时刻（与 [nowMs] 同一时间基准）
     * @param nowMs 当前时刻
     * @param reduceCount 本气泡已扣减次数（0 起步，上限 [MAX_REDUCE_COUNT]）
     * @param position 当前排位（1 = 最新，按 timestamp 降序）
     * @return Pair(新到期时刻, 新的扣减次数)；不需要扣减时原样返回入参
     */
    fun computeNextDeadline(
        deadlineMs: Long,
        nowMs: Long,
        reduceCount: Int,
        position: Int
    ): Pair<Long, Int> {
        if (position <= KEEP_FULL_LIFE_POSITIONS || reduceCount >= MAX_REDUCE_COUNT) {
            return deadlineMs to reduceCount
        }
        // 扣剩余寿命；已不足一个扣减步长时钳到当前时刻（下一次调度即移除），不允许倒退到过去
        val reduced = (deadlineMs - REDUCE_STEP_MS).coerceAtLeast(nowMs)
        return reduced to reduceCount + 1
    }
}
