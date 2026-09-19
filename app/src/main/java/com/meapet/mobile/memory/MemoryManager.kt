package com.meapet.mobile.memory

import android.util.Log
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 记忆管理器——记忆系统的高层入口。
 *
 * ## 职责
 * - 对外暴露记忆系统功能（ChatService、ViewModel 等调用方仅需与此类交互）；
 * - 编排 [MemoryService] 与 [MemoryRepository] 的调用顺序；
 * - 构建注入 system prompt 的记忆上下文（含 [MemoryOpsProtocol] 协议说明）。
 *
 * 记忆该不该记、记什么，全部由大模型在回复中通过 [MemoryOpsProtocol] 自己声明；
 * 本类只负责把声明的操作转交给 [MemoryService] 落库，以及按设置的轮次触发摘要。
 *
 * ## 用法示例
 * ```kotlin
 * val memoryManager = MemoryManager(memoryService, memoryRepository, settingsManager, config)
 *
 * // 对话后调用（ops 来自 MemoryOpsProtocol.extract）
 * memoryManager.onExchangeComplete(ops)
 *
 * // 注入上下文
 * val context = memoryManager.buildContext("用户问了我的名字")
 * ```
 *
 * @param service 记忆业务服务
 * @param repository 记忆存储器
 * @param settingsManager 设置管理器（读取记忆开关、摘要轮次）
 * @param config 应用配置（读取相关回忆注入条数上限）
 */
class MemoryManager(
    private val service: MemoryService,
    private val repository: MemoryRepository,
    private val settingsManager: SettingsManager,
    private val config: AppConfig = AppConfig.DEFAULT
) {
    companion object {
        private const val TAG = "MemoryManager"
    }

    private val stateMutex = Mutex()

    // ── 对外 API ──────────────────────────────────────

    /**
     * 在一次对话交换完成后调用。
     *
     * 轮数计数落在 DataStore 里（[SettingsManager.getExchangeCount]）而非内存：
     * 一次会话往往聊不满默认的 10 轮，计数器若随进程归零，摘要就永远不会触发。
     *
     * @param ops 模型本轮回复中声明的记忆操作（由 [MemoryOpsProtocol.extract] 解析得到）
     */
    suspend fun onExchangeComplete(ops: List<MemoryOpsProtocol.MemoryOp>) {
        // 记忆总开关关闭时不落库、不触发摘要
        if (!isMemoryEnabled()) {
            Log.i(TAG, "onExchangeComplete skipped: 记忆开关已关闭（设置页「启用记忆」）")
            return
        }

        Log.d(TAG, "onExchangeComplete: ${ops.size} op(s) to apply")
        if (ops.isNotEmpty()) {
            service.applyOps(ops)
        }

        val interval = settingsManager.getSummaryInterval().coerceAtLeast(1)
        // 计数改为「攒够 interval 就清零重来」而非取模：用户中途调小间隔时，
        // 取模会让已攒下的轮数错过触发点，白等一整个新周期
        val reached = stateMutex.withLock {
            val count = settingsManager.getExchangeCount() + 1
            val reached = count >= interval
            settingsManager.setExchangeCount(if (reached) 0 else count)
            Log.d(TAG, "Exchange $count/$interval since last summary")
            reached
        }

        if (reached) {
            Log.i(TAG, "Triggering short-term memory summary (interval=$interval reached)")
            service.summarizeShortTermMemories()
        }
    }

    /**
     * 记忆上下文，按「每轮是否会变」拆成两段。
     *
     * 拆分是为了让服务端的自动 prefix cache 能命中：[stable] 拼进首条 system 消息，
     * [tail] 压到对话历史之后。若把每轮都变的相关回忆混进首条 system 消息，
     * 排在它后面的协议说明与全部历史就都成了缓存不命中的内容。
     *
     * @property stable 协议说明 + 用户人设，轮与轮之间基本不变
     * @property tail 相关回忆 + 收尾提醒，随当前输入变化
     */
    data class MemoryContext(val stable: String, val tail: String) {
        companion object {
            val EMPTY = MemoryContext("", "")
        }
    }

    /**
     * 为当前对话构建记忆上下文。
     *
     * 开关开启时 [MemoryContext.stable] **必定**包含 [MemoryOpsProtocol.instructions]
     * （哪怕暂无任何记忆，模型也要知道协议格式才能开始创建）；事实/特质注入，条数受
     * [AppConfig.maxPersonaFacts] 限制；短期/长期记忆按关键词匹配注入，受
     * [AppConfig.maxContextMemories] 限制。
     *
     * @param currentInput 用户当前输入，用于匹配相关的短期/长期记忆
     * @return 两段上下文；记忆关闭时返回 [MemoryContext.EMPTY]
     */
    suspend fun buildContext(currentInput: String): MemoryContext {
        if (!isMemoryEnabled()) {
            Log.i(TAG, "buildContext skipped: 记忆开关已关闭，本轮不会注入记忆协议说明")
            return MemoryContext.EMPTY
        }

        val facts = repository.getPersonaFacts(maxCount = config.maxPersonaFacts)
        val recollections = repository.getRelevant(currentInput, maxCount = config.maxContextMemories)
        // 查询与访问记账分离（读写分离）：命中后单独记一次 LRU 权重，整批只落盘一次
        if (recollections.isNotEmpty()) {
            repository.markAccessed(recollections.map { it.id })
        }

        val stable = buildString {
            append(MemoryOpsProtocol.instructions())
            if (facts.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("【用户人设】（永久，可引用 id 更新/删除）")
                facts.forEach { appendLine("- [${it.id}] (${typeLabel(it.type)}) ${it.content}") }
            }
        }.trimEnd()

        val tail = buildString {
            if (recollections.isNotEmpty()) {
                appendLine("【相关回忆】（关键词匹配，只有相关时才出现，可引用 id 更新/删除）")
                recollections.forEach { appendLine("- [${it.id}] (${typeLabel(it.type)}) ${it.content}") }
                appendLine()
            }
            // 提醒必须是最后一段：越靠近生成点，模型越不容易忘了输出协议块
            append(MemoryOpsProtocol.reminder())
        }

        Log.d(
            TAG,
            "Context built: ${facts.size} persona fact(s), ${recollections.size} recollection(s), " +
                "${stable.length}+${tail.length} chars injected"
        )
        return MemoryContext(stable, tail)
    }

    /**
     * 获取所有记忆（按重要性排序）。
     */
    suspend fun getAllMemories(): List<MemoryItem> = repository.getAll()

    /**
     * 删除单条记忆。
     */
    suspend fun delete(id: String) = repository.delete(id)

    /**
     * 获取记忆统计。
     */
    suspend fun getStats(): MemoryStats = repository.getStats()

    /**
     * 获取记忆是否启用。
     */
    fun isMemoryEnabled(): Boolean = settingsManager.isMemoryEnabled()

    /**
     * 清除所有记忆。
     */
    suspend fun clearAll() {
        repository.clear()
        stateMutex.withLock { settingsManager.setExchangeCount(0) }
        Log.i(TAG, "Memory manager reset")
    }

    private fun typeLabel(type: MemoryType): String = when (type) {
        MemoryType.SHORT_TERM -> "短期"
        MemoryType.LONG_TERM -> "长期"
        MemoryType.CORE_TRAIT -> "特质"
        MemoryType.FACTUAL -> "事实"
    }
}
