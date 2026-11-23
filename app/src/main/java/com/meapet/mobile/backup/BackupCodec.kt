package com.meapet.mobile.backup

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.memory.MemoryItem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `chat` 条目（schema 1）：全部消息按 `seq` 顺序输出。
 *
 * `rawAssistantJson`（含 `tool_calls` / `reasoning_content`）原样带走：
 * 丢了下一次请求会 400，因此不做任何重写。
 */
@Serializable
data class ChatPayload(val messages: List<ChatMessage> = emptyList())

/** `memory` 条目（schema 1）：记忆条目 + 距上次摘要的轮数。 */
@Serializable
data class MemoryPayload(
    val items: List<MemoryItem> = emptyList(),
    val exchangeCount: Int = 0,
)

/** `compaction` 条目（schema 1）：压缩摘要与覆盖游标。 */
@Serializable
data class CompactionPayload(
    val summary: String = "",
    val coveredToMessageId: String? = null,
    val coveredCount: Int = 0,
    val updatedAt: Long = 0L,
) {
    /** 转回压缩状态对象。 */
    fun toState(): CompactionState = CompactionState(
        summary = summary,
        coveredToMessageId = coveredToMessageId,
        coveredCount = coveredCount,
        updatedAt = updatedAt,
    )

    companion object {
        /** 从压缩状态生成 payload。 */
        fun from(state: CompactionState): CompactionPayload = CompactionPayload(
            summary = state.summary,
            coveredToMessageId = state.coveredToMessageId,
            coveredCount = state.coveredCount,
            updatedAt = state.updatedAt,
        )
    }
}

/**
 * 各文本条目的 JSON 编解码。
 *
 * 统一约定：解码失败抛 [ManifestRejectException]（文案「备份包结构不完整」），
 * 让上层的错误处理与版本拒绝走同一条路径。
 */
object BackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── chat ─────────────────────────────────────────

    fun encodeChat(messages: List<ChatMessage>): String =
        json.encodeToString(ChatPayload.serializer(), ChatPayload(messages))

    fun decodeChat(text: String): List<ChatMessage> =
        decodeOrReject { json.decodeFromString(ChatPayload.serializer(), text) }.messages

    // ── memory ───────────────────────────────────────

    fun encodeMemory(items: List<MemoryItem>, exchangeCount: Int): String =
        json.encodeToString(MemoryPayload.serializer(), MemoryPayload(items, exchangeCount))

    fun decodeMemory(text: String): MemoryPayload =
        decodeOrReject { json.decodeFromString(MemoryPayload.serializer(), text) }

    // ── compaction ───────────────────────────────────

    fun encodeCompaction(state: CompactionState): String =
        json.encodeToString(CompactionPayload.serializer(), CompactionPayload.from(state))

    fun decodeCompaction(text: String): CompactionState =
        decodeOrReject { json.decodeFromString(CompactionPayload.serializer(), text) }.toState()

    // ── 内部 ──────────────────────────────────────────

    private fun <T> decodeOrReject(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
    } catch (e: IllegalArgumentException) {
        throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
    }
}
