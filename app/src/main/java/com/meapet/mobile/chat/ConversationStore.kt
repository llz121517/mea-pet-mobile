package com.meapet.mobile.chat

import android.util.Log
import com.meapet.mobile.core.logErrorSanitized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话历史持久化存储。
 *
 * ## 存储策略
 * 与 [com.meapet.mobile.memory.MemoryRepository] 一致：
 * - JSON 文件（`meapet_conversation.json`），临时文件 + rename 保证原子性；
 * - 损坏文件备份为 `.corrupt` 后丢弃，不阻塞后续使用；
 * - [persistAsync] 采用合并写（conflate）：连续多次提交只落盘最新快照，
 *   避免每条消息一次磁盘 IO。
 * - writer **总是写「最近一次记录的快照」**，而不是入队时那份：否则从取到旧快照
 *   到真正拿到锁之间若有更新（[flush] / [persist]）已写入，旧快照会把磁盘倒退回去。
 *
 * ## 低耦合
 * - 仅操作 [ChatMessage] 数据与文件，不依赖任何业务模块；
 * - 由 [ConversationManager] 在消息变更后调用。
 *
 * @param dir 存储目录（应用 filesDir；JVM 测试传临时目录）
 * @param scope 落盘用协程作用域（应用级；测试可传 TestScope）
 */
class ConversationStore(
    private val dir: File,
    scope: CoroutineScope,
    /** 落盘 writer 的调度器。生产为 IO；测试注入测试调度器，才能确定性地驱动合并写队列。 */
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "ConversationStore"
        private const val FILE_NAME = "meapet_conversation.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val file: File get() = File(dir, FILE_NAME)

    /**
     * 最近一次请求落盘的快照。writer 真正落盘时以它为准——不写「排队时那一刻」的
     * 旧快照，否则会把更新的写入覆盖掉（见类注释）。
     */
    @Volatile
    private var latestSnapshot: List<ChatMessage>? = null

    /** 落盘信号流。replay=1 + DROP_OLDEST = 天然 conflate，多次请求合并成一次写。 */
    private val pending = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 落盘 collector 协程。保留引用以便 [persistAsync] 检测其存活：
     * 传入 scope 一旦被取消，collector 结束，之后所有 emit 都无人消费——
     * 不检测的话数据静默丢失。
     */
    private val persistJob = scope.launch(writeDispatcher) {
        pending.collect {
            // 只当作「该写盘了」的信号；写什么由 writeLatest 在锁内决定。
            writeLatest()
        }
    }

    /**
     * 从磁盘加载会话历史。启动时调用一次。
     *
     * 文件损坏时备份为 `.corrupt` 并返回空列表。
     */
    suspend fun load(): List<ChatMessage> = mutex.withLock {
        if (!file.exists()) {
            Log.d(TAG, "No persisted conversation found")
            return emptyList()
        }
        try {
            val messages = json.decodeFromString<List<ChatMessage>>(file.readText())
            Log.i(TAG, "Loaded ${messages.size} conversation messages from disk")
            messages
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.logErrorSanitized(TAG, "Failed to load conversation, backing up corrupted file")
            backupCorruptedFileLocked()
            emptyList()
        }
    }

    /**
     * 提交当前会话快照，异步合并落盘（不阻塞调用方）。
     *
     * 只记录快照并发一个「该写盘了」的信号：真正的写入由 writer 在锁内取
     * [latestSnapshot]，因此后一次调用永远覆盖前一次，不会出现旧快照后写。
     */
    fun persistAsync(snapshot: List<ChatMessage>) {
        record(snapshot)
        // scope 已取消时 collector 不在，emit 会静默丢弃——至少告警暴露问题
        if (!persistJob.isActive) {
            Log.w(TAG, "Persist collector inactive (scope cancelled?); dropping snapshot of ${snapshot.size} messages")
            return
        }
        pending.tryEmit(Unit)
    }

    /**
     * 只记录「最新快照」，不请求落盘。
     *
     * 供 [flush] 的调用方使用：先把当前内存状态记进来，再同步写，保证写下去的是
     * 这一刻的状态（[ConversationManager.restore] 这类不发异步写的路径也靠它补齐）。
     */
    fun record(snapshot: List<ChatMessage>) {
        latestSnapshot = snapshot
    }

    /** 记录快照并同步落盘（测试可直接调用；业务侧确定性落盘见 [flush]）。 */
    suspend fun persist(snapshot: List<ChatMessage>) {
        record(snapshot)
        writeLatest()
    }

    /**
     * 同步落盘**最近一次记录的快照**，直到写完才返回。
     *
     * 用于 `onStop` 等「即将进后台或被回收」的确定性场景。写的是最近记录的那份，
     * 因此与合并写队列之间不存在「旧快照后写」的顺序问题。
     */
    suspend fun flush() {
        writeLatest()
    }

    // ── 内部 ──────────────────────────────────────────

    /** 落盘最近一次记录的快照（在 [mutex] 内取，保证写的是最新状态）。 */
    private suspend fun writeLatest() {
        mutex.withLock {
            val snapshot = latestSnapshot ?: return@withLock
            persistLocked(snapshot)
        }
    }

    /** 必须在持有 [mutex] 时调用。 */
    private fun persistLocked(snapshot: List<ChatMessage>) {
        try {
            val text = json.encodeToString(snapshot)
            dir.mkdirs()
            // 先写临时文件再 rename，避免写入中途崩溃导致文件截断
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    Log.e(TAG, "Failed to replace conversation file")
                }
            }
        } catch (e: Exception) {
            e.logErrorSanitized(TAG, "Failed to persist conversation")
        }
    }

    /** 将损坏的持久化文件挪到 `.corrupt` 备份，防止每次启动重复解析失败。 */
    private fun backupCorruptedFileLocked() {
        try {
            val backup = File(dir, "$FILE_NAME.corrupt")
            if (backup.exists()) backup.delete()
            if (!file.renameTo(backup)) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to back up corrupted conversation file", e)
        }
    }
}
