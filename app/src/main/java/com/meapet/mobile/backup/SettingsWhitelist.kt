package com.meapet.mobile.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.settings.SettingsKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** 设置项的值类型（决定导出 JSON 类型与导入时的写入方式）。 */
enum class SettingKind { STRING, BOOLEAN, DOUBLE, INT, NULLABLE_INT }

/**
 * 白名单中的一条设置。
 *
 * @property key DataStore 键名（见 [SettingsKeys]）
 * @property kind 值类型
 * @property optional 是否属于「可选包含」的敏感 Key（仅当用户勾选「包含 API Key」时进包）
 */
data class WhitelistEntry(
    val key: String,
    val kind: SettingKind,
    val optional: Boolean = false,
)

/**
 * 设置键白名单与类型化编解码。
 *
 * **白名单是唯一真相源**：新增设置键默认不进包，必须显式登记到 [entries] 才会被导出，
 * 避免将来新增的敏感键被意外写进备份（测试守护，见 `SettingsWhitelistTest`）。
 *
 * 导出只包含**用户实际写过**的键（DataStore 中存在的项），不把默认值物化：
 * 这样导入到另一台设备时，未设置过的项继续沿用该设备的默认值，语义与旧机一致。
 * 导入只写白名单内、且包里实际存在的键；未知键与类型不符的键忽略。
 */
object SettingsWhitelist {

    /** 白名单（顺序即导出 JSON 键顺序）。 */
    val entries: List<WhitelistEntry> = listOf(
        // 外观
        WhitelistEntry(SettingsKeys.THEME_MODE, SettingKind.STRING),
        WhitelistEntry(SettingsKeys.ENABLE_DYNAMIC_COLOR, SettingKind.BOOLEAN),
        WhitelistEntry(SettingsKeys.COLOR_PRESET, SettingKind.STRING),
        WhitelistEntry(SettingsKeys.CHAT_BUBBLE_ALPHA, SettingKind.DOUBLE),
        WhitelistEntry(SettingsKeys.WALLPAPER_PATH, SettingKind.STRING),
        WhitelistEntry(SettingsKeys.WALLPAPER_BLUR, SettingKind.DOUBLE),
        // 模型参数
        WhitelistEntry(SettingsKeys.API_URL, SettingKind.STRING),
        WhitelistEntry(SettingsKeys.MODEL, SettingKind.STRING),
        WhitelistEntry(SettingsKeys.TEMPERATURE, SettingKind.DOUBLE),
        WhitelistEntry(SettingsKeys.MAX_TOKENS, SettingKind.INT),
        WhitelistEntry(SettingsKeys.CONTEXT_WINDOW, SettingKind.NULLABLE_INT),
        WhitelistEntry(SettingsKeys.SYSTEM_PROMPT, SettingKind.STRING),
        // 记忆
        WhitelistEntry(SettingsKeys.ENABLE_MEMORY, SettingKind.BOOLEAN),
        WhitelistEntry(SettingsKeys.MEMORY_MODE, SettingKind.STRING),
        WhitelistEntry(SettingsKeys.ENABLE_AUTO_SUMMARY, SettingKind.BOOLEAN),
        WhitelistEntry(SettingsKeys.SUMMARY_INTERVAL, SettingKind.INT),
        // TTS
        WhitelistEntry(SettingsKeys.TTS_MAIN_ENABLED, SettingKind.BOOLEAN),
        WhitelistEntry(SettingsKeys.TTS_OVERLAY_ENABLED, SettingKind.BOOLEAN),
        WhitelistEntry(SettingsKeys.TTS_LENGTH_SCALE, SettingKind.DOUBLE),
        // 联网
        WhitelistEntry(SettingsKeys.WEB_SEARCH_ENABLED, SettingKind.BOOLEAN),
        WhitelistEntry(SettingsKeys.WEB_SEARCH_PROVIDER, SettingKind.STRING),
        WhitelistEntry(SettingsKeys.WEB_SEARCH_REUSE_CHAT_KEY, SettingKind.BOOLEAN),
        // 更新
        WhitelistEntry(SettingsKeys.ENABLE_AUTO_UPDATE_CHECK, SettingKind.BOOLEAN),
        // 可选 Key（默认不进包）
        WhitelistEntry(SettingsKeys.API_KEY, SettingKind.STRING, optional = true),
        WhitelistEntry(SettingsKeys.WEB_SEARCH_DEEPSEEK_KEY, SettingKind.STRING, optional = true),
        WhitelistEntry(SettingsKeys.WEB_SEARCH_ANYSEARCH_KEY, SettingKind.STRING, optional = true),
    )

    /** 可选包含的敏感键集合。 */
    val optionalKeys: Set<String> = entries.asSequence()
        .filter { it.optional }
        .map { it.key }
        .toSet()

    /** 非可选键集合（便于测试断言排除项）。 */
    val alwaysIncludedKeys: Set<String> = entries.asSequence()
        .filterNot { it.optional }
        .map { it.key }
        .toSet()

    private val byKey: Map<String, WhitelistEntry> = entries.associateBy { it.key }

    /**
     * 把当前设置编码成 `settings` 条目 JSON：
     * ```json
     * { "values": { "theme_mode": "dark", "...": ... } }
     * ```
     *
     * @param preferences DataStore 当前快照
     * @param includeApiKeys 是否把可选 Key 一并写入
     */
    fun encode(preferences: Preferences, includeApiKeys: Boolean): String {
        val values = buildJsonObject {
            entries.forEach { entry ->
                if (entry.optional && !includeApiKeys) return@forEach
                val element = readValue(entry, preferences) ?: return@forEach
                put(entry.key, element)
            }
        }
        return buildJsonObject { put(VALUES_KEY, values) }.toString()
    }

    /**
     * 解析 `settings` 条目，返回包里实际存在的白名单键值。
     *
     * 未知键、类型不符的键直接忽略（向后兼容：新版写入的新键在旧版导入时被跳过）。
     *
     * @throws ManifestRejectException 根结构不是 `{ "values": { ... } }`
     */
    fun decode(text: String): Map<String, JsonElement> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
        val values = root[VALUES_KEY] as? JsonObject
            ?: throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
        val result = LinkedHashMap<String, JsonElement>()
        values.forEach { (key, element) ->
            val entry = byKey[key] ?: return@forEach
            if (element is JsonNull) {
                if (entry.kind == SettingKind.NULLABLE_INT) result[key] = JsonNull
                return@forEach
            }
            if (matches(entry.kind, element)) result[key] = element
        }
        return result
    }

    /**
     * 把 [decode] 的结果写入 DataStore 编辑事务。
     *
     * 只写白名单内的键；`null` 的 NULLABLE_INT 表示「清除、回到自动」。
     */
    fun apply(values: Map<String, JsonElement>, target: MutablePreferences) {
        values.forEach { (key, element) ->
            val entry = byKey[key] ?: return@forEach
            when (entry.kind) {
                SettingKind.STRING -> stringValue(element)?.let { target[stringPreferencesKey(key)] = it }
                SettingKind.BOOLEAN -> booleanValue(element)?.let { target[booleanPreferencesKey(key)] = it }
                SettingKind.DOUBLE -> doubleValue(element)?.let { target[doublePreferencesKey(key)] = it }
                SettingKind.INT -> intValue(element)?.let { target[intPreferencesKey(key)] = it }
                SettingKind.NULLABLE_INT -> {
                    if (element is JsonNull) {
                        target.remove(intPreferencesKey(key))
                    } else {
                        intValue(element)?.let { target[intPreferencesKey(key)] = it }
                    }
                }
            }
        }
    }

    // ── 内部 ──────────────────────────────────────────

    private const val VALUES_KEY = "values"

    /** 读出 DataStore 中实际存在的值；不存在返回 null（不物化默认值）。 */
    private fun readValue(entry: WhitelistEntry, preferences: Preferences): JsonElement? {
        // 用无类型快照读取再按实际类型判断：同一 key 若被别的存储路径写成不同类型
        // （开发期脏数据），typed key 直接取会抛 ClassCastException 把整个导出打断。
        // asMap() 的键是 Preferences.Key<*>（不是字符串），按 name 匹配。
        val raw = preferences.asMap().entries
            .firstOrNull { it.key.name == entry.key }
            ?.value ?: return null
        return when (entry.kind) {
            SettingKind.STRING -> (raw as? String)?.let { JsonPrimitive(it) }
            SettingKind.BOOLEAN -> (raw as? Boolean)?.let { JsonPrimitive(it) }
            SettingKind.DOUBLE -> (raw as? Double)?.let { JsonPrimitive(it) }
            SettingKind.INT, SettingKind.NULLABLE_INT -> (raw as? Int)?.let { JsonPrimitive(it) }
        }
    }

    private fun matches(kind: SettingKind, element: JsonElement): Boolean {
        val primitive = element as? JsonPrimitive ?: return false
        return when (kind) {
            SettingKind.STRING -> primitive.isString
            SettingKind.BOOLEAN -> primitive.booleanOrNull != null
            SettingKind.DOUBLE -> primitive.doubleOrNull != null
            SettingKind.INT, SettingKind.NULLABLE_INT -> primitive.intOrNull != null
        }
    }

    private fun stringValue(element: JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun booleanValue(element: JsonElement): Boolean? =
        (element as? JsonPrimitive)?.booleanOrNull

    private fun doubleValue(element: JsonElement): Double? =
        (element as? JsonPrimitive)?.doubleOrNull

    private fun intValue(element: JsonElement): Int? =
        (element as? JsonPrimitive)?.intOrNull
}
