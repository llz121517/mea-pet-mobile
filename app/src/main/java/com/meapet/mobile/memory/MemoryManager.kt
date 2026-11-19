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
    private val repository: MemoryStore,
    private val settingsManager: SettingsManager,
    private val config: AppConfig = AppConfig.DEFAULT
) {
    companion object {
        private const val TAG = "MemoryManager"
    }

    private val stateMutex = Mutex()

    /**
     * 最近一次工具 turn 删除的条目快照（墓碑），供撤销用。
     *
     * 写方是 [applyToolChanges]（IO 线程），读方是 UI 线程的成功收尾（[takeRecentToolDeletions]），
     * 用 [Volatile] 保证可见性；换 turn 时整体覆盖，不做跨 turn 累积。
     */
    @Volatile
    private var recentToolDeletions: List<MemoryItem> = emptyList()

    // ── 对外 API ──────────────────────────────────────

    /**
     * 在一次对话交换完成后调用。
     *
     * 轮数计数存在记忆存储里（[MemoryStore.getExchangeCount]）而非内存：
     * 一次会话往往聊不满默认的 10 轮，计数器若随进程归零，摘要就永远不会触发。
     * 也不放 DataStore——它是记忆运行状态而非用户设置，避免混进「设置部分导出」。
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
            val count = repository.getExchangeCount() + 1
            val reached = count >= interval
            repository.setExchangeCount(if (reached) 0 else count)
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
            val persona = personaSection(facts)
            if (persona.isNotBlank()) {
                appendLine()
                appendLine()
                append(persona)
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
     * 只取「用户人设」段（FACTUAL / CORE_TRAIT，全量、与当前输入无关）。
     *
     * 工具模式下不再走 [buildContext]（协议说明与相关回忆都由工具承担），但计划要求
     * 人设事实**仍全量注入**：模型不必为「主人叫什么、说过什么永久设定」去检索。
     * 返回空串表示暂无事实或记忆已关闭，调用方直接省略该段。
     *
     * 内容顺序固定为 createdAt 升序（[MemoryStore.getPersonaFacts] 契约），
     * 轮与轮之间字节稳定，不会破坏前缀缓存。
     */
    suspend fun buildPersonaSection(): String {
        if (!isMemoryEnabled()) return ""
        return personaSection(repository.getPersonaFacts(maxCount = config.maxPersonaFacts))
    }

    private fun personaSection(facts: List<MemoryItem>): String = buildString {
        if (facts.isEmpty()) return@buildString
        appendLine("【用户人设】（永久，可引用 id 更新/删除）")
        facts.forEach { appendLine("- [${it.id}] (${typeLabel(it.type)}) ${it.content}") }
    }.trimEnd()

    /**
     * 读取距上次摘要的对话轮数。
     *
     * 供「清除记忆」的撤销快照使用：清空会把计数归零，撤销要一并还原。
     */
    suspend fun getExchangeCount(): Int = repository.getExchangeCount()

    /**
     * 写回对话轮数计数（撤销「清除记忆」时还原用）。
     */
    suspend fun setExchangeCount(count: Int) {
        stateMutex.withLock { repository.setExchangeCount(count) }
    }

    /**
     * 提交一个 turn 内工具产生的记忆变更（暂存后一次性落库）。
     *
     * 由 ChatService 在整个 turn 成功后调用；失败/取消直接丢弃，不调用本方法。
     * 删除项的**墓碑快照**（[com.meapet.mobile.memory.tools.PendingMemoryChanges.deletedItems]）
     * 会在这里留存，供 UI 的撤销条还原（[takeRecentToolDeletions]）。
     */
    suspend fun applyToolChanges(changes: com.meapet.mobile.memory.tools.PendingMemoryChanges) {
        if (changes.isEmpty) return
        repository.applyChanges(changes.upserts, changes.deletes)
        recentToolDeletions = changes.deletedItems
        Log.i(
            TAG,
            "Applied tool memory changes: ${changes.upserts.size} upsert, " +
                "${changes.deletes.size} delete (${changes.deletedItems.size} tombstone(s))"
        )
    }

    /**
     * 取出并清空最近一次工具 turn 删除条目的墓碑快照（UI 撤销用）。
     *
     * 只返回**本 turn 删除、且删除前已在库里**的条目；同 turn 内刚创建又删除的
     * 记忆没有墓碑（它从未真正存在过，无需撤销）。
     */
    fun takeRecentToolDeletions(): List<MemoryItem> {
        val tombstones = recentToolDeletions
        recentToolDeletions = emptyList()
        return tombstones
    }

    /** 新一轮开始前清掉上一轮的墓碑，避免把旧 turn 的删除当成新 turn 的撤销项。 */
    fun clearRecentToolDeletions() {
        recentToolDeletions = emptyList()
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
        stateMutex.withLock { repository.setExchangeCount(0) }
        Log.i(TAG, "Memory manager reset")
    }

    /**
     * 用快照恢复记忆（撤销「清除全部」用）。
     *
     * 只负责记忆条目；[clearAll] 顺带重置的对话轮数计数由调用方（ChatViewModel）
     * 一并还原——那是设置项的职责，不在记忆模块里越界处理。
     *
     * @param snapshot [clearAll] 之前 [getAllMemories] 的结果
     */
    suspend fun restoreAll(snapshot: List<MemoryItem>) {
        if (snapshot.isEmpty()) return
        repository.saveAll(snapshot)
        Log.i(TAG, "Restored ${snapshot.size} memories from undo snapshot")
    }

    private fun typeLabel(type: MemoryType): String = when (type) {
        MemoryType.SHORT_TERM -> "短期"
        MemoryType.LONG_TERM -> "长期"
        MemoryType.CORE_TRAIT -> "特质"
        MemoryType.FACTUAL -> "事实"
    }
}
