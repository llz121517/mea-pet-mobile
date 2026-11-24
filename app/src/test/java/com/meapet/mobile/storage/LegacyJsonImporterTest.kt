package com.meapet.mobile.storage

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 旧 JSON -> SQLite 导入：正确性、顺序、幂等、不覆盖新数据、损坏文件与旧文件保留。 */
class LegacyJsonImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var db: SqliteDatabase

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        db = SqliteDatabase(JdbcSQLiteDriver(), File(tmp.root, "t.db").absolutePath)
    }

    @After
    fun tearDown() = runBlocking { db.close() }

    private val convJson = """[
        {"role":"user","content":"你好 \"q\"\n换行","id":"b","timestamp":9000},
        {"role":"assistant","content":"回复","memoryOpsBlock":"blk","id":"a","timestamp":1},
        {"role":"user","content":"重复id","id":"a","timestamp":2}
    ]"""

    private val memJson = """[
        {"id":"mem_aaaaaaaa","content":"喜欢炸鸡","type":"SHORT_TERM","importance":0.7,"createdAt":10,"keywords":["炸鸡","食物"]},
        {"id":"mem_bbbbbbbb","content":"叫小明","type":"FACTUAL","importance":0.9,"createdAt":5}
    ]"""

    @Test
    fun importsMessagesMemoriesAndKeepsOldFiles() = runBlocking {
        File(dir, LegacyJsonImporter.CONVERSATION_FILE).writeText(convJson)
        File(dir, LegacyJsonImporter.MEMORY_FILE).writeText(memJson)

        val outcome = LegacyJsonImporter(db, dir).importIfNeeded()

        assertEquals(LegacyJsonImporter.Outcome.IMPORTED, outcome)
        val msgs = SqliteConversationRepository(db).loadRecent(10)
        assertEquals(listOf("b", "a"), msgs.map { it.id }, "按文件顺序，重复 id 去重")
        assertEquals("回复", msgs[1].content)
        assertEquals(1L, msgs[1].timestamp)
        assertEquals(ChatRole.user, msgs[0].role)

        val store = SqliteMemoryStore(db)
        val all = store.getAll()
        assertEquals(2, all.size)
        assertEquals(listOf("炸鸡", "食物"), store.findById("mem_aaaaaaaa")?.keywords)

        assertTrue(File(dir, LegacyJsonImporter.CONVERSATION_FILE).exists())
        assertTrue(File(dir, LegacyJsonImporter.MEMORY_FILE).exists())
    }

    @Test
    fun ignoresOldMemoryOpsFieldWhilePreservingToolHistory() = runBlocking {
        val history = """[
            {"role":"assistant","content":"","id":"assistant","timestamp":37,"turnId":"turn-1",
             "rawAssistantJson":"{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call-1\"}]}",
             "memoryOpsBlock":"legacy block"},
            {"role":"tool","content":"工具结果 🐱","id":"tool","timestamp":12,"turnId":"turn-1",
             "toolCallId":"call-1","toolName":"search_memory","memoryOpsBlock":null}
        ]"""
        val oldFile = File(dir, LegacyJsonImporter.CONVERSATION_FILE)
        oldFile.writeText(history)

        assertEquals(LegacyJsonImporter.Outcome.IMPORTED, LegacyJsonImporter(db, dir).importIfNeeded())
        assertEquals(
            listOf(
                ChatMessage(
                    ChatRole.assistant, "", id = "assistant", timestamp = 37, turnId = "turn-1",
                    rawAssistantJson = """{"role":"assistant","content":"","tool_calls":[{"id":"call-1"}]}"""
                ),
                ChatMessage(
                    ChatRole.tool, "工具结果 🐱", id = "tool", timestamp = 12, turnId = "turn-1",
                    toolCallId = "call-1", toolName = "search_memory"
                )
            ),
            SqliteConversationRepository(db).loadRecent(10)
        )
        assertEquals(history, oldFile.readText())
    }

    @Test
    fun secondRunIsIdempotentAndDoesNotOverwriteNewData() = runBlocking {
        File(dir, LegacyJsonImporter.CONVERSATION_FILE).writeText(convJson)
        val importer = LegacyJsonImporter(db, dir)
        importer.importIfNeeded()

        val repo = SqliteConversationRepository(db)
        repo.append(ChatMessage(ChatRole.user, "新消息", id = "new", timestamp = 5))
        repo.remove("b")

        assertEquals(LegacyJsonImporter.Outcome.ALREADY_DONE, importer.importIfNeeded())
        assertEquals(listOf("a", "new"), repo.loadRecent(10).map { it.id })
    }

    @Test
    fun noFilesMarksSkipped() = runBlocking {
        val importer = LegacyJsonImporter(db, dir)
        assertEquals(LegacyJsonImporter.Outcome.NOTHING_TO_IMPORT, importer.importIfNeeded())
        assertEquals(LegacyJsonImporter.Outcome.ALREADY_DONE, importer.importIfNeeded())
    }

    @Test
    fun corruptFileIsSkippedButLeftUntouched() = runBlocking {
        val f = File(dir, LegacyJsonImporter.CONVERSATION_FILE)
        f.writeText("]]] 不是 JSON")
        File(dir, LegacyJsonImporter.MEMORY_FILE).writeText(memJson)

        val outcome = LegacyJsonImporter(db, dir).importIfNeeded()

        assertEquals(LegacyJsonImporter.Outcome.IMPORTED, outcome)
        assertEquals(0, SqliteConversationRepository(db).count())
        assertEquals(2, SqliteMemoryStore(db).getAll().size)
        assertEquals("]]] 不是 JSON", f.readText())
    }
}
