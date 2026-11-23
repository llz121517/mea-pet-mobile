package com.meapet.mobile.backup

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chat / memory / compaction 三个文本条目的编解码（导出语义透传，不做重写）。
 */
@Suppress("NonAsciiCharacters")
class BackupCodecTest {

    private fun message(
        id: String = "m1",
        role: ChatRole = ChatRole.user,
        content: String = "你好",
        rawAssistantJson: String? = null,
        turnId: String? = "turn-1",
        toolCallId: String? = null,
        toolName: String? = null,
        timestamp: Long = 1795359286232L,
    ) = ChatMessage(
        role = role,
        content = content,
        id = id,
        timestamp = timestamp,
        turnId = turnId,
        toolCallId = toolCallId,
        toolName = toolName,
        rawAssistantJson = rawAssistantJson,
    )

    @Test
    fun `chat 往返保留原始 assistant JSON 与工具字段`() {
        val raw = """{"tool_calls":[{"id":"call_1","type":"function"}]}"""
        val messages = listOf(
            message(),
            message(id = "m2", role = ChatRole.assistant, content = "", rawAssistantJson = raw),
            message(
                id = "m3",
                role = ChatRole.tool,
                content = """{"ok":true}""",
                rawAssistantJson = null,
                toolCallId = "call_1",
                toolName = "web_search",
            ),
        )
        val decoded = BackupCodec.decodeChat(BackupCodec.encodeChat(messages))

        assertEquals(3, decoded.size)
        assertEquals(messages.map { it.id }, decoded.map { it.id })
        assertEquals(messages.map { it.role }, decoded.map { it.role })
        assertEquals(messages.map { it.content }, decoded.map { it.content })
        assertEquals(raw, decoded[1].rawAssistantJson)
        assertEquals("call_1", decoded[2].toolCallId)
        assertEquals("web_search", decoded[2].toolName)
        assertEquals(1795359286232L, decoded[0].timestamp)
        // 运行时状态不落备份
        assertFalse(decoded[0].isStreaming)
    }

    @Test
    fun `chat 空历史可往返`() {
        assertEquals(emptyList<ChatMessage>(), BackupCodec.decodeChat(BackupCodec.encodeChat(emptyList())))
    }

    @Test
    fun `memory 往返保留条目与轮数计数`() {
        val items = listOf(
            MemoryItem(
                id = "mem_a1b2c3d4",
                content = "喜欢炸鸡",
                type = MemoryType.FACTUAL,
                importance = 0.9f,
                createdAt = 100L,
                lastAccessedAt = 200L,
                accessCount = 3,
                sourceMessageId = "m1",
                keywords = listOf("炸鸡", "食物"),
            )
        )
        val payload = BackupCodec.decodeMemory(BackupCodec.encodeMemory(items, exchangeCount = 4))
        assertEquals(4, payload.exchangeCount)
        assertEquals(1, payload.items.size)
        val item = payload.items[0]
        assertEquals("mem_a1b2c3d4", item.id)
        assertEquals("喜欢炸鸡", item.content)
        assertEquals(MemoryType.FACTUAL, item.type)
        assertEquals(0.9f, item.importance, 1e-6f)
        assertEquals(3, item.accessCount)
        assertEquals("m1", item.sourceMessageId)
        assertEquals(listOf("炸鸡", "食物"), item.keywords)
    }

    @Test
    fun `compaction 往返保留摘要与游标`() {
        val state = CompactionState(
            summary = "前面聊了炸鸡",
            coveredToMessageId = "m42",
            coveredCount = 12,
            updatedAt = 123456L,
        )
        val decoded = BackupCodec.decodeCompaction(BackupCodec.encodeCompaction(state))
        assertEquals(state, decoded)
        assertTrue(decoded.hasSummary)
    }

    @Test
    fun `空压缩状态可往返`() {
        assertEquals(
            CompactionState(),
            BackupCodec.decodeCompaction(BackupCodec.encodeCompaction(CompactionState()))
        )
    }

    @Test
    fun `条目内容损坏按结构不完整拒绝`() {
        listOf(
            { BackupCodec.decodeChat("{broken") },
            { BackupCodec.decodeMemory("[1,2]") },
            { BackupCodec.decodeCompaction("""{"summary":42}""") },
        ).forEach { block ->
            val error = runCatching(block).exceptionOrNull()
            assertTrue("应抛 ManifestRejectException，实际：$error", error is ManifestRejectException)
            assertEquals(BackupFormat.STRUCTURE_INCOMPLETE, error!!.message)
        }
    }

    @Test
    fun `未知字段被忽略以便向前兼容`() {
        val decoded = BackupCodec.decodeMemory(
            """{"items":[],"exchangeCount":2,"futureField":{"x":1}}"""
        )
        assertEquals(2, decoded.exchangeCount)
        assertEquals(0, decoded.items.size)
    }
}
