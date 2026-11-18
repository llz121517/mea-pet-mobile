package com.meapet.mobile.client.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiResponseTest {

    @Test
    fun usagePromptTokensExtractsPositiveValue() {
        val body = """{"choices":[],"usage":{"prompt_tokens":1234,"completion_tokens":56}}"""
        assertEquals(1234, ApiResponse.usagePromptTokens(body))
        // 缺 usage / 0 值都返回 null（0 表示服务端没给，不是「输入 0 token」）
        assertNull(ApiResponse.usagePromptTokens("""{"choices":[]}"""))
        assertNull(ApiResponse.usagePromptTokens("""{"usage":{"prompt_tokens":0}}"""))
    }

    @Test
    fun usagePromptCacheHitTokensAllowsZeroAndNullOnMissing() {
        val hit = """{"usage":{"prompt_tokens":1000,"prompt_cache_hit_tokens":768}}"""
        assertEquals(768, ApiResponse.usagePromptCacheHitTokens(hit))
        // 0 是合法值：命中 0 与「端点没提供该字段」语义不同，前者返回 0
        assertEquals(0, ApiResponse.usagePromptCacheHitTokens("""{"usage":{"prompt_cache_hit_tokens":0}}"""))
        assertNull(ApiResponse.usagePromptCacheHitTokens("""{"usage":{"prompt_tokens":10}}"""))
    }

    @Test
    fun modelIdsFromOpenAiDataArray() {
        val body = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o", "object": "model"},
                {"id": "gpt-4o-mini", "object": "model"},
                {"id": "gpt-4o", "object": "model"}
              ]
            }
        """.trimIndent()
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), ApiResponse.modelIds(body))
    }

    @Test
    fun modelIdsFromTopLevelArray() {
        val body = """[{"id":"claude-3"},{"id":"claude-3.5"}]"""
        assertEquals(listOf("claude-3", "claude-3.5"), ApiResponse.modelIds(body))
    }

    @Test
    fun modelIdsIgnoresBlankAndMissing() {
        val body = """{"data":[{"id":""},{"name":"no-id"},{"id":"  ok  "}]}"""
        assertEquals(listOf("ok"), ApiResponse.modelIds(body))
    }

    @Test
    fun modelIdsMalformedReturnsEmpty() {
        assertTrue(ApiResponse.modelIds("not-json").isEmpty())
        assertTrue(ApiResponse.modelIds("""{"data":null}""").isEmpty())
    }

    @Test
    fun chatCompletionContentExtractsMessage() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"喵"}}]}"""
        assertEquals("喵", ApiResponse.chatCompletionContent(body))
    }

    @Test
    fun errorMessageFromNestedErrorObject() {
        val body = """{"error":{"message":"Rate limit exceeded for model gpt-4o","type":"rate_limit"}}"""
        assertEquals("Rate limit exceeded for model gpt-4o", ApiResponse.errorMessage(body))
    }

    @Test
    fun errorMessageFromPlainErrorString() {
        assertEquals("invalid key", ApiResponse.errorMessage("""{"error":"invalid key"}"""))
    }

    @Test
    fun errorMessageFromTopLevelMessage() {
        assertEquals("quota exhausted", ApiResponse.errorMessage("""{"message":"  quota exhausted  "}"""))
    }

    @Test
    fun errorMessageMalformedOrEmptyReturnsNull() {
        assertNull(ApiResponse.errorMessage("not-json"))
        assertNull(ApiResponse.errorMessage("""{"error":{"type":"rate_limit"}}"""))
        assertNull(ApiResponse.errorMessage("""{"error":"   "}"""))
        assertNull(ApiResponse.errorMessage("""[{"error":"array root"}]"""))
    }
}
