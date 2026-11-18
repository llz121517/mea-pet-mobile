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
            val sql = if (beforeId == null) {
                "SELECT id, role, content, memory_ops_block, created_at FROM messages " +
                    "WHERE conversation_id = ? ORDER BY seq DESC LIMIT ?"
            } else {
                "SELECT id, role, content, memory_ops_block, created_at FROM messages " +
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
                        // 预留的 tool 等未知角色跳过，不让旧代码读到新数据时崩溃
                        val role = runCatching { ChatRole.valueOf(st.getText(1)) }.getOrNull() ?: continue
                        add(
                            ChatMessage(
                                role = role,
                                content = st.getText(2),
                                memoryOpsBlock = if (st.isNull(3)) null else st.getText(3),
                                id = st.getText(0),
                                timestamp = st.getLong(4)
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
        c.prepare(
            "INSERT OR IGNORE INTO messages(id, conversation_id, role, content, memory_ops_block, created_at) " +
                "VALUES(?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.bindText(1, m.id)
            st.bindText(2, conversationId)
            st.bindText(3, m.role.name)
            st.bindText(4, m.content)
            val block = m.memoryOpsBlock
            if (block == null) st.bindNull(5) else st.bindText(5, block)
            st.bindLong(6, m.timestamp)
            st.step()
        }
    }
}
