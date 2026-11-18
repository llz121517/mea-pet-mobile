package com.meapet.mobile.storage

import androidx.sqlite.SQLiteConnection
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole

/**
 * 基于 [SqliteDatabase] 的会话仓库。当前只有一个活跃会话（[conversationId]），
 * 表结构已为多会话与软删除预留（见 Migrations V1）。
 *
 * 每次写入都是一个小事务，取代原先每条消息整表重写 JSON 的做法。
 */
class SqliteConversationRepository(
    private val db: SqliteDatabase,
    private val conversationId: String = DEFAULT_CONVERSATION_ID
) : ConversationRepository {

    companion object {
        const val DEFAULT_CONVERSATION_ID = "default"
    }

    override suspend fun append(message: ChatMessage) {
        db.transaction { c ->
            ensureConversation(c)
            insert(c, message)
        }
    }

    /**
     * 一个 turn 的多条消息在**同一个事务**里落库。
     *
     * 与逐条 append 的区别不只是性能：turn 的片段必须原子提交，否则进程被杀时可能
     * 只写进前一半（assistant 的 tool_calls 有、tool 结果没有），下次启动把它当历史
     * 发给服务端会因孤立 `tool_call_id` 被拒。
     */
    override suspend fun appendAll(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        db.transaction { c ->
            ensureConversation(c)
            messages.forEach { insert(c, it) }
        }
    }

    override suspend fun remove(id: String): Boolean = db.transaction { c ->
        c.prepare("DELETE FROM messages WHERE id = ? AND conversation_id = ?").use { st ->
            st.bindText(1, id)
            st.bindText(2, conversationId)
            st.step()
        }
        c.queryLong("SELECT changes()") > 0L
    }

    override suspend fun clear() {
        db.transaction { c ->
            c.prepare("DELETE FROM messages WHERE conversation_id = ?").use { st ->
                st.bindText(1, conversationId)
                st.step()
            }
        }
    }

    override suspend fun replaceAll(messages: List<ChatMessage>) {
        db.transaction { c ->
            ensureConversation(c)
            c.prepare("DELETE FROM messages WHERE conversation_id = ?").use { st ->
                st.bindText(1, conversationId)
                st.step()
            }
            messages.forEach { insert(c, it) }
        }
    }

    override suspend fun loadRecent(limit: Int): List<ChatMessage> = pageBefore(null, limit)

    override suspend fun pageBefore(beforeId: String?, limit: Int): List<ChatMessage> =
        db.withConnection { c ->
            // tool 行也是历史的一部分（发给模型需要），这里不能像旧版那样跳过；
            // 展示层用 ChatMessage.forUi() 过滤，读取层保持完整。
            val columns =
                "id, role, content, memory_ops_block, created_at, turn_id, tool_call_id, tool_name, extra_json"
            val sql = if (beforeId == null) {
                "SELECT $columns FROM messages " +
                    "WHERE conversation_id = ? ORDER BY seq DESC LIMIT ?"
            } else {
                "SELECT $columns FROM messages " +
                    "WHERE conversation_id = ? AND seq < (SELECT seq FROM messages WHERE id = ?) " +
                    "ORDER BY seq DESC LIMIT ?"
            }
            val rows = c.prepare(sql).use { st ->
                st.bindText(1, conversationId)
                if (beforeId == null) {
                    st.bindLong(2, limit.toLong())
                } else {
                    st.bindText(2, beforeId)
                    st.bindLong(3, limit.toLong())
                }
                buildList {
                    while (st.step()) {
                        // 只有枚举完全不认识（未来版本写入了新角色）才跳过；
                        // 已知的 tool 角色必须正常读出，否则整轮工具历史丢失
                        val role = runCatching { ChatRole.valueOf(st.getText(1)) }.getOrNull()
                            ?: continue
                        add(
                            ChatMessage(
                                role = role,
                                content = st.getText(2),
                                memoryOpsBlock = if (st.isNull(3)) null else st.getText(3),
                                id = st.getText(0),
                                timestamp = st.getLong(4),
                                turnId = if (st.isNull(5)) null else st.getText(5),
                                toolCallId = if (st.isNull(6)) null else st.getText(6),
                                toolName = if (st.isNull(7)) null else st.getText(7),
                                rawAssistantJson = if (st.isNull(8)) null else st.getText(8)
                            )
                        )
                    }
                }
            }
            rows.asReversed()
        }

    override suspend fun count(): Int = db.withConnection { c ->
        c.prepare("SELECT COUNT(*) FROM messages WHERE conversation_id = ?").use { st ->
            st.bindText(1, conversationId)
            if (st.step()) st.getLong(0).toInt() else 0
        }
    }

    // ── 内部 ──────────────────────────────────────────

    private fun ensureConversation(c: SQLiteConnection) {
        val now = System.currentTimeMillis()
        c.prepare("INSERT OR IGNORE INTO conversations(id, created_at, updated_at) VALUES(?, ?, ?)").use { st ->
            st.bindText(1, conversationId)
            st.bindLong(2, now)
            st.bindLong(3, now)
            st.step()
        }
    }

    private fun insert(c: SQLiteConnection, m: ChatMessage) {
        // tool_calls 列留空：整段原始 assistant 消息（含 tool_calls / reasoning_content）
        // 只存 extra_json 一份，避免两份数据不一致时不知道该信谁
        c.prepare(
            "INSERT OR IGNORE INTO messages(" +
                "id, conversation_id, role, content, memory_ops_block, created_at, " +
                "turn_id, tool_call_id, tool_name, extra_json) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.bindText(1, m.id)
            st.bindText(2, conversationId)
            st.bindText(3, m.role.name)
            st.bindText(4, m.content)
            val block = m.memoryOpsBlock
            if (block == null) st.bindNull(5) else st.bindText(5, block)
            st.bindLong(6, m.timestamp)
            val turnId = m.turnId
            if (turnId == null) st.bindNull(7) else st.bindText(7, turnId)
            val toolCallId = m.toolCallId
            if (toolCallId == null) st.bindNull(8) else st.bindText(8, toolCallId)
            val toolName = m.toolName
            if (toolName == null) st.bindNull(9) else st.bindText(9, toolName)
            val raw = m.rawAssistantJson
            if (raw == null) st.bindNull(10) else st.bindText(10, raw)
            st.step()
        }
    }
}
