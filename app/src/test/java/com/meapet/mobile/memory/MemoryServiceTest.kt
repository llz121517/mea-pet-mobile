package com.meapet.mobile.memory

import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.test.FakeHttpClientEngine
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MemoryService 的 JVM 单元测试。
 *
 * [SettingsManager] 是持有真实 Android [android.content.Context]/DataStore 的具体类，
 * 无法在纯 JVM 测试里直接构造，用 mockito-kotlin 打桩即可（不涉及生产代码改动）。
 */
class MemoryServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeSettings(
        memoryEnabled: Boolean = true,
        autoSummaryEnabled: Boolean = true
    ): SettingsManager {
        val sm = mock<SettingsManager>()
        whenever(sm.isMemoryEnabled()).thenReturn(memoryEnabled)
        whenever(sm.isAutoSummaryEnabled()).thenReturn(autoSummaryEnabled)
        whenever(sm.getModel()).thenReturn("test-model")
        return sm
    }

    private fun service(
        repository: MemoryRepository,
        settingsManager: SettingsManager = fakeSettings(),
        client: OpenAiCompatibleClient? = null
    ) = MemoryService(
        repository = repository,
        summarizationClient = { client },
        settingsManager = settingsManager,
        config = AppConfig.DEFAULT
    )

    /** 构造一个形如 `{"choices":[{"message":{"content": assistantContent}}]}` 的假响应体。 */
    private fun fakeChatResponse(assistantContent: String): String {
        val obj: JsonObject = buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    putJsonObject("message") {
                        put("content", assistantContent)
                    }
                }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun fakeClient(assistantContent: String): OpenAiCompatibleClient {
        val engine = FakeHttpClientEngine {
            HttpResponse(
                statusCode = 200,
                body = fakeChatResponse(assistantContent).encodeToByteArray(),
                contentType = "application/json"
            )
        }
        return OpenAiCompatibleClient(apiKey = "k", baseUrl = "https://example.com/v1", engine = engine)
    }

    // ── summarizeShortTermMemories ─────────────────────

    @Test
    fun summarizeReturnsNullWhenNoShortTermMemories() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        val svc = service(repo)

        assertNull(svc.summarizeShortTermMemories())
    }

    @Test
    fun summarizeReturnsNullWhenMemoryDisabled() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(MemoryItem(id = "s1", content = "短期", type = MemoryType.SHORT_TERM))
        val svc = service(repo, fakeSettings(memoryEnabled = false))

        assertNull(svc.summarizeShortTermMemories())
        assertEquals(1, repo.getByType(MemoryType.SHORT_TERM).size, "关闭时短期记忆不应被处理")
    }

    /** 存入 [count] 条短期记忆（默认刚好够触发摘要）。 */
    private suspend fun MemoryRepository.seedShortTerm(count: Int = AppConfig.DEFAULT.minSummaryItems) {
        repeat(count) { i -> save(MemoryItem(id = "s$i", content = "短期$i", type = MemoryType.SHORT_TERM)) }
    }

    @Test
    fun summarizeSuccessDeletesConsumedShortTermAndSavesLongTerm() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.save(MemoryItem(id = "s1", content = "今天吃了火锅", type = MemoryType.SHORT_TERM))
        repo.save(MemoryItem(id = "s2", content = "喜欢辣的", type = MemoryType.SHORT_TERM))
        repo.save(MemoryItem(id = "s3", content = "和同事一起去的", type = MemoryType.SHORT_TERM))

        val modelOutput = """{"content":"用户喜欢吃辣，今天和同事吃了火锅","importance":0.6,"keywords":["火锅","辣"]}"""
        val svc = service(repo, client = fakeClient(modelOutput))

        val result = svc.summarizeShortTermMemories()

        assertEquals("用户喜欢吃辣，今天和同事吃了火锅", result?.content)
        assertEquals(MemoryType.LONG_TERM, result?.type)
        assertEquals(listOf("火锅", "辣"), result?.keywords)
        assertTrue(repo.getByType(MemoryType.SHORT_TERM).isEmpty(), "参与摘要的短期记忆应被删除")
        assertEquals(1, repo.getByType(MemoryType.LONG_TERM).size)
    }

    @Test
    fun summarizeSkippedWhenFewerThanMinItems() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.seedShortTerm(AppConfig.DEFAULT.minSummaryItems - 1)

        val modelOutput = """{"content":"总结内容","importance":0.5,"keywords":["a"]}"""
        val svc = service(repo, client = fakeClient(modelOutput))

        assertNull(svc.summarizeShortTermMemories(), "不足门槛时不应发起摘要请求")
        assertEquals(
            AppConfig.DEFAULT.minSummaryItems - 1,
            repo.getByType(MemoryType.SHORT_TERM).size,
            "跳过时短期记忆应原样攒着"
        )
    }

    @Test
    fun summarizeDiscardedWhenModelReturnsNoKeywords() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.seedShortTerm()

        // 无 keywords 的长期记忆永远匹配不到（getRelevant 直接跳过），落库等于净丢失
        val modelOutput = """{"content":"总结内容","importance":0.5,"keywords":[]}"""
        val svc = service(repo, client = fakeClient(modelOutput))

        assertNull(svc.summarizeShortTermMemories())
        assertEquals(
            AppConfig.DEFAULT.minSummaryItems,
            repo.getByType(MemoryType.SHORT_TERM).size,
            "放弃摘要时短期记忆应保留"
        )
        assertTrue(repo.getByType(MemoryType.LONG_TERM).isEmpty(), "不应留下检索不到的长期记忆")
    }

    @Test
    fun summarizeDropsBlankKeywordsButKeepsValidOnes() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.seedShortTerm()

        val modelOutput = """{"content":"总结内容","importance":0.5,"keywords":[" 火锅 ","","火锅","辣"]}"""
        val svc = service(repo, client = fakeClient(modelOutput))

        assertEquals(listOf("火锅", "辣"), svc.summarizeShortTermMemories()?.keywords)
    }

    @Test
    fun summarizeToleratesExtraTextAroundJsonObject() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.seedShortTerm()

        val modelOutput = """好的，这是摘要：{"content":"总结内容","importance":0.5,"keywords":["a","b"]} 谢谢"""
        val svc = service(repo, client = fakeClient(modelOutput))

        val result = svc.summarizeShortTermMemories()
        assertEquals("总结内容", result?.content)
    }

    @Test
    fun summarizeFailureLeavesShortTermMemoriesUntouched() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.seedShortTerm()

        val svc = service(repo, client = fakeClient("不是JSON的胡言乱语，没有大括号"))

        val result = svc.summarizeShortTermMemories()

        assertNull(result)
        assertEquals(
            AppConfig.DEFAULT.minSummaryItems,
            repo.getByType(MemoryType.SHORT_TERM).size,
            "解析失败时短期记忆应原样保留"
        )
    }

    @Test
    fun summarizeReturnsNullWhenNoClientAvailable() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.seedShortTerm()
        val svc = service(repo, client = null)

        assertNull(svc.summarizeShortTermMemories())
        assertEquals(AppConfig.DEFAULT.minSummaryItems, repo.getByType(MemoryType.SHORT_TERM).size)
    }

    @Test
    fun summarizeReturnsNullWhenAutoSummaryDisabled() = runTest {
        val repo = MemoryRepository(tmp.newFolder())
        repo.seedShortTerm()
        val svc = service(repo, fakeSettings(autoSummaryEnabled = false), fakeClient("{}"))

        assertNull(svc.summarizeShortTermMemories())
        assertEquals(AppConfig.DEFAULT.minSummaryItems, repo.getByType(MemoryType.SHORT_TERM).size)
    }
}
