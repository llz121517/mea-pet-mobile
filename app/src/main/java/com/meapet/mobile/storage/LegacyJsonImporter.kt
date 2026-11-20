package com.meapet.mobile.storage

import android.util.Log
import androidx.sqlite.SQLiteConnection
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemorySerialization
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 旧版 JSON 文件 -> SQLite 的一次性导入器（1.9 存储迁移）。
 *
 * - 必须在会话/记忆仓库首次访问之前同步完成（由 AppContainer.warmUp 前置调用）；
 * - 整个导入在一个 BEGIN IMMEDIATE 事务内完成，完成标记 [META_KEY] 与数据同事务提交；
 * - 幂等：标记存在则直接跳过，不会覆盖用户已在 SQLite 里产生的新数据；
 * - 失败整体回滚，不写标记，下次启动重试；
 * - 旧 JSON 文件导入后原样保留（不删除、不移动、不改名），损坏文件同样不动。
 */
class LegacyJsonImporter(
    private val db: SqliteDatabase,
    private val dir: File,
    private val conversationId: String = SqliteConversationRepository.DEFAULT_CONVERSATION_ID
) {
    enum class Outcome { IMPORTED, ALREADY_DONE, NOTHING_TO_IMPORT, FAILED }

    companion object {
        private const val TAG = "LegacyJsonImporter"
        const val META_KEY = "legacy_json_import"
        const val CONVERSATION_FILE = "meapet_conversation.json"
        const val MEMORY_FILE = "meapet_memories.json"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun importIfNeeded(): Outcome {
        return try {
            if (db.withConnection { readMeta(it) } != null) return Outcome.ALREADY_DONE

            val convFile = File(dir, CONVERSATION_FILE)
            val memFile = File(dir, MEMORY_FILE)
            if (!convFile.exists() && !memFile.exists()) {
                db.transaction { c -> if (readMeta(c) == null) writeMeta(c, "skipped") }
                return Outcome.NOTHING_TO_IMPORT
            }

            // 解码失败按空处理并告警；损坏文件保持原样，不改名不删除
            val messages = decodeMessages(convFile).distinctBy { it.id }
            val memories = decodeMemories(memFile).distinctBy { it.id }

            val imported = db.transaction { c ->
                if (readMeta(c) != null) return@transaction false
                ensureConversation(c)
                messages.forEach { insertMessage(c, it) }
                memories.forEach { insertMemory(c, it) }

                // 读回校验：数量不足就抛异常，整个事务回滚
                val msgCount = count(c, "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", conversationId)
                val memCount = count(c, "SELECT COUNT(*) FROM memories", null)
                check(msgCount >= messages.size) { "消息导入校验失败: $msgCount < ${messages.size}" }
                check(memCount >= memories.size) { "记忆导入校验失败: $memCount < ${memories.size}" }

                writeMeta(c, "done")
                true
            }
            Log.i(TAG, "Legacy import finished: ${messages.size} messages, ${memories.size} memories (imported=$imported)")
            if (imported) Outcome.IMPORTED else Outcome.ALREADY_DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 事务已回滚、未写标记，旧文件原样保留，下次启动重试
            Log.e(TAG, "Legacy import failed, will retry next launch", e)
            Outcome.FAILED
        }
    }

    // ── 解码 ──────────────────────────────────────────

    private fun decodeMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<ChatMessage>>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Legacy conversation file unreadable, skipped (file kept)", e)
            emptyList()
        }
    }

    private fun decodeMemories(file: File): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        return try {
            MemorySerialization.decode(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Legacy memory file unreadable, skipped (file kept)", e)
            emptyList()
        }
    }

    // ── SQL（均在 db 锁内调用）────────────────────────────

    private fun readMeta(c: SQLiteConnection): String? =
        c.prepare("SELECT value FROM meta WHERE key = ?").use { st ->
            st.bindText(1, META_KEY)
            if (st.step()) st.getText(0) else null
        }

    private fun writeMeta(c: SQLiteConnection, value: String) {
        c.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)").use { st ->
            st.bindText(1, META_KEY)
            st.bindText(2, value)
            st.step()
        }
    }

    private fun count(c: SQLiteConnection, sql: String, arg: String?): Long =
        c.prepare(sql).use { st ->
            if (arg != null) st.bindText(1, arg)
            if (st.step()) st.getLong(0) else 0L
        }

    private fun ensureConversation(c: SQLiteConnection) {
        val now = System.currentTimeMillis()
        c.prepare("INSERT OR IGNORE INTO conversations(id, created_at, updated_at) VALUES(?, ?, ?)").use { st ->
            st.bindText(1, conversationId)
            st.bindLong(2, now)
            st.bindLong(3, now)
            st.step()
        }
    }

    /** 按文件列表顺序写入（seq 自增），不据 timestamp 排序；origin 标记为 import。 */
    private fun insertMessage(c: SQLiteConnection, m: ChatMessage) {
        c.prepare(
            "INSERT OR IGNORE INTO messages(" +
                "id, conversation_id, role, content, origin, created_at, " +
                "turn_id, tool_call_id, tool_name, extra_json) " +
                "VALUES(?, ?, ?, ?, 'import', ?, ?, ?, ?, ?)"
        ).use { st ->
            st.bindText(1, m.id)
            st.bindText(2, conversationId)
            st.bindText(3, m.role.name)
            st.bindText(4, m.content)
            st.bindLong(5, m.timestamp)
            val turnId = m.turnId
            if (turnId == null) st.bindNull(6) else st.bindText(6, turnId)
            val toolCallId = m.toolCallId
            if (toolCallId == null) st.bindNull(7) else st.bindText(7, toolCallId)
            val toolName = m.toolName
            if (toolName == null) st.bindNull(8) else st.bindText(8, toolName)
            val raw = m.rawAssistantJson
            if (raw == null) st.bindNull(9) else st.bindText(9, raw)
            st.step()
        }
    }

    private fun insertMemory(c: SQLiteConnection, item: MemoryItem) {
        c.prepare(
            "INSERT OR IGNORE INTO memories(id, content, type, importance, created_at, last_accessed_at, " +
                "access_count, source_message_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.bindText(1, item.id)
            st.bindText(2, item.content)
            st.bindText(3, item.type.name)
            st.bindDouble(4, item.importance.toDouble().coerceIn(0.0, 1.0))
            st.bindLong(5, item.createdAt)
            st.bindLong(6, item.lastAccessedAt)
            st.bindLong(7, item.accessCount.toLong())
            val src = item.sourceMessageId
            if (src == null) st.bindNull(8) else st.bindText(8, src)
            st.step()
        }
        item.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEachIndexed { index, kw ->
            c.prepare(
                "INSERT OR IGNORE INTO memory_keywords(memory_id, position, keyword, keyword_norm) VALUES(?, ?, ?, ?)"
            ).use { st ->
                st.bindText(1, item.id)
                st.bindLong(2, index.toLong())
                st.bindText(3, kw)
                st.bindText(4, kw.lowercase())
                st.step()
            }
        }
    }
}
