package com.meapet.mobile.storage

import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.chat.compaction.CompactionStore
import kotlinx.serialization.json.Json

/**
 * 压缩状态存储：整份状态序列化为 JSON，存进 `meta` 表的一行。
 *
 * 摘要状态是「一个会话一份」的整体对象（摘要正文 + 覆盖范围），不需要按消息粒度查询，
 * 所以复用 V1 已有的 `meta` 表即可，无需 schema 迁移。
 */
class MetaCompactionStore(
    private val db: SqliteDatabase,
    private val key: String = META_KEY
) : CompactionStore {

    companion object {
        /** meta 表中的键名。 */
        const val META_KEY = "context_compaction"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun load(): CompactionState = db.withConnection { c ->
        val raw = c.prepare("SELECT value FROM meta WHERE key = ?").use { st ->
            st.bindText(1, key)
            if (st.step()) st.getText(0) else null
        } ?: return@withConnection CompactionState()
        runCatching { json.decodeFromString<CompactionState>(raw) }.getOrElse { CompactionState() }
    }

    override suspend fun save(state: CompactionState) {
        val text = json.encodeToString(state)
        db.transaction { c ->
            c.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)").use { st ->
                st.bindText(1, key)
                st.bindText(2, text)
                st.step()
            }
        }
    }

    override suspend fun clear() {
        db.transaction { c ->
            c.prepare("DELETE FROM meta WHERE key = ?").use { st ->
                st.bindText(1, key)
                st.step()
            }
        }
    }
}
