package com.meapet.mobile.client

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextCapacityTest {

    @Test
    fun parsesDeepSeekStyleEntry() {
        val body = """{"data":[{"id":"deepseek-flash","context_window":1048576,"max_output_tokens":393216}]}"""
        assertEquals(1048576, ContextCapacityParser.parse(body, "DeepSeek-Flash"))
    }

    @Test
    fun parsesNestedAndTakesSmaller() {
        val body = """{"data":[{"id":"m","context_length":1048576,"top_provider":{"context_length":262144}}]}"""
        assertEquals(262144, ContextCapacityParser.parse(body, "m"))
    }

    @Test
    fun parsesTopLevelArray() {
        assertEquals(8192, ContextCapacityParser.parse("""[{"id":"x","context_length":8192}]""", "x"))
    }

    @Test
    fun fieldWithoutContextInNameIsNotRecognizedAndDegrades() {
        // 通用规则只认名字含 context 的字段：max_model_len（vLLM）查不到，降级到兜底，由用户手动填写
        assertNull(ContextCapacityParser.parse("""[{"id":"x","max_model_len":8192}]""", "x"))
    }

    @Test
    fun missingEntryFieldOrGarbageReturnsNull() {
        assertNull(ContextCapacityParser.parse("""{"data":[{"id":"a"}]}""", "a"))
        assertNull(ContextCapacityParser.parse("""{"data":[{"id":"a","context_window":5}]}""", "a"))
        assertNull(ContextCapacityParser.parse("""{"data":[{"id":"a","context_window":9}]}""", "b"))
        assertNull(ContextCapacityParser.parse("not json", "a"))
    }

    @Test
    fun effectiveBudgetAppliesFactorMaxTokensAndFloor() {
        assertEquals((131072 * 0.85 - 4096).toInt(), effectiveBudget(131072, 4096))
        assertEquals(1000, effectiveBudget(4000, 4000))
    }

    @Test
    fun loopbackDetectionAndFallback() {
        assertTrue(isLoopbackUrl("http://localhost:11434/v1"))
        assertTrue(isLoopbackUrl("http://127.0.0.1:8080/v1"))
        assertTrue(isLoopbackUrl("http://[::1]:1234/v1"))
        assertFalse(isLoopbackUrl("http://192.168.1.5:11434/v1"))
        assertFalse(isLoopbackUrl("https://api.deepseek.com/v1"))
        assertEquals(32768, fallbackCapacity("http://localhost:11434/v1"))
        assertEquals(32768, fallbackCapacity("http://192.168.1.5/v1"))
    }
}
