package com.meapet.mobile.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.settings.SettingsKeys
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置白名单：新增设置键默认不进包，可选 Key 受开关控制，导入忽略未知键。
 */
@Suppress("NonAsciiCharacters")
class SettingsWhitelistTest {

    private fun snapshot() = mutablePreferencesOf(
        stringPreferencesKey(SettingsKeys.THEME_MODE) to "dark",
        booleanPreferencesKey(SettingsKeys.ENABLE_DYNAMIC_COLOR) to false,
        doublePreferencesKey(SettingsKeys.TEMPERATURE) to 0.9,
        intPreferencesKey(SettingsKeys.MAX_TOKENS) to 2048,
        intPreferencesKey(SettingsKeys.CONTEXT_WINDOW) to 65536,
        stringPreferencesKey(SettingsKeys.API_URL) to "https://example.test/v1",
        stringPreferencesKey(SettingsKeys.WEB_SEARCH_REUSE_CHAT_KEY) to "ignored-wrong-type",
        // 以下都不在白名单：不得进包
        booleanPreferencesKey(SettingsKeys.FIRST_LAUNCH) to false,
        stringPreferencesKey(SettingsKeys.PRIVACY_VERSION_SHOWN) to "1.4",
        intPreferencesKey(SettingsKeys.EXCHANGE_COUNT) to 7,
        // 可选 Key
        stringPreferencesKey(SettingsKeys.API_KEY) to "sk-secret",
        stringPreferencesKey(SettingsKeys.WEB_SEARCH_DEEPSEEK_KEY) to "ds-secret",
        stringPreferencesKey(SettingsKeys.WEB_SEARCH_ANYSEARCH_KEY) to "as-secret",
    )

    private fun values(includeApiKeys: Boolean): Map<String, kotlinx.serialization.json.JsonElement> {
        val text = SettingsWhitelist.encode(snapshot(), includeApiKeys)
        return Json.parseToJsonElement(text).jsonObject.getValue("values").jsonObject
    }

    @Test
    fun `白名单外的键不进包`() {
        val values = values(includeApiKeys = true)
        assertFalse(SettingsKeys.FIRST_LAUNCH in values)
        assertFalse(SettingsKeys.PRIVACY_VERSION_SHOWN in values)
        assertFalse(SettingsKeys.EXCHANGE_COUNT in values)
        assertFalse("unknown_future_key" in values)
    }

    @Test
    fun `默认不导出可选 API Key`() {
        val values = values(includeApiKeys = false)
        SettingsWhitelist.optionalKeys.forEach { key ->
            assertFalse("可选 Key 默认不得进包：$key", key in values)
        }
        assertTrue(SettingsKeys.API_URL in values)
    }

    @Test
    fun `勾选后三个可选 Key 进包`() {
        val values = values(includeApiKeys = true)
        assertEquals("sk-secret", values.getValue(SettingsKeys.API_KEY).jsonPrimitive.content)
        assertEquals(
            "ds-secret",
            values.getValue(SettingsKeys.WEB_SEARCH_DEEPSEEK_KEY).jsonPrimitive.content
        )
        SettingsWhitelist.optionalKeys.forEach { assertTrue("$it 应进包", it in values) }
    }

    @Test
    fun `只导出实际写过的键，不物化默认值`() {
        val values = values(includeApiKeys = false)
        assertTrue(SettingsKeys.THEME_MODE in values)
        // SYSTEM_PROMPT 未写入快照：不应出现（导入时继续沿用目标机默认人设）
        assertFalse(SettingsKeys.SYSTEM_PROMPT in values)
        assertFalse(SettingsKeys.WALLPAPER_PATH in values)
    }

    @Test
    fun `导出值类型与设置类型一致`() {
        val values = values(includeApiKeys = false)
        assertEquals("dark", values.getValue(SettingsKeys.THEME_MODE).jsonPrimitive.content)
        assertEquals(false, values.getValue(SettingsKeys.ENABLE_DYNAMIC_COLOR).jsonPrimitive.booleanOrNull)
        assertEquals(0.9, values.getValue(SettingsKeys.TEMPERATURE).jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals(2048, values.getValue(SettingsKeys.MAX_TOKENS).jsonPrimitive.intOrNull)
        assertEquals(65536, values.getValue(SettingsKeys.CONTEXT_WINDOW).jsonPrimitive.intOrNull)
        // 类型不符的项（把 boolean 键写成字符串）不导出
        assertFalse(SettingsKeys.WEB_SEARCH_REUSE_CHAT_KEY in values)
    }

    @Test
    fun `导入忽略未知键与类型不符的键`() {
        val decoded = SettingsWhitelist.decode(
            """
            {"values":{
              "theme_mode":"light",
              "unknown_key":"x",
              "max_tokens":"not-a-number",
              "context_window":null
            }}
            """.trimIndent()
        )
        assertEquals("light", decoded.getValue(SettingsKeys.THEME_MODE).jsonPrimitive.content)
        assertFalse("unknown_key" in decoded)
        assertFalse(SettingsKeys.MAX_TOKENS in decoded)
        assertEquals(JsonNull, decoded.getValue(SettingsKeys.CONTEXT_WINDOW))
    }

    @Test
    fun `导入结构不完整按统一文案拒绝`() {
        val error = runCatching { SettingsWhitelist.decode("[]") }.exceptionOrNull()
        assertTrue(error is ManifestRejectException)
        assertEquals(BackupFormat.STRUCTURE_INCOMPLETE, error!!.message)
    }

    @Test
    fun `导入写回白名单键，null 的上下文窗口表示清除`() {
        val target = mutablePreferencesOf(
            intPreferencesKey(SettingsKeys.CONTEXT_WINDOW) to 123456,
            stringPreferencesKey(SettingsKeys.API_KEY) to "old-key",
        )
        SettingsWhitelist.apply(
            mapOf(
                SettingsKeys.THEME_MODE to JsonPrimitive("dark"),
                SettingsKeys.CONTEXT_WINDOW to JsonNull,
                SettingsKeys.FIRST_LAUNCH to JsonPrimitive(false),
            ),
            target
        )
        assertEquals("dark", target[stringPreferencesKey(SettingsKeys.THEME_MODE)])
        assertNull(target[intPreferencesKey(SettingsKeys.CONTEXT_WINDOW)])
        // 白名单外的键不会被写入
        assertNull(target[booleanPreferencesKey(SettingsKeys.FIRST_LAUNCH)])
        // 包里没有的键保持本机原值
        assertEquals("old-key", target[stringPreferencesKey(SettingsKeys.API_KEY)])
    }
}
