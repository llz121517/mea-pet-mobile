package com.meapet.mobile.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 应用设置管理器。
 *
 * 基于 DataStore 的键值持久化，所有操作通过 Flow 暴露，
 * UI 层可响应式订阅变更。
 *
 * 同步 getter 读取的是构造时在 IO 协程里预热、并持续跟随 DataStore 变更的
 * 内存快照，全程不产生磁盘 IO；进程启动后快照尚未就绪的极短窗口内，读取
 * 退化为空快照（各 getter 落到 [SettingsKeys.Defaults]），绝不阻塞主线程。
 * 首帧主题等需要真实值的场景由对应的 Flow（如 MainActivity 的主题订阅）
 * 在快照就绪后自动修正。
 *
 * 该模块**不依赖**任何其他模块，可独立测试。
 *
 * @param context Application Context
 */
class SettingsManager(context: Context) {

    private val dataStore = context.appDataStore

    /** 设置项内存快照（跟随 DataStore 变更；null = 尚未完成首次加载）。 */
    @Volatile
    private var cachedPrefs: Preferences? = null

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 构造即预热：首次加载后持续订阅，保证快照与磁盘一致
        cacheScope.launch {
            dataStore.data.collect { prefs ->
                cachedPrefs = prefs
            }
        }
    }

    companion object {
        private const val TAG = "SettingsManager"
    }

    // ── Keys ──────────────────────────────────────────

    private val KEY_API_KEY = stringPreferencesKey(SettingsKeys.API_KEY)
    private val KEY_API_URL = stringPreferencesKey(SettingsKeys.API_URL)
    private val KEY_MODEL = stringPreferencesKey(SettingsKeys.MODEL)
    private val KEY_TEMPERATURE = doublePreferencesKey(SettingsKeys.TEMPERATURE)
    private val KEY_MAX_TOKENS = intPreferencesKey(SettingsKeys.MAX_TOKENS)
    private val KEY_ENABLE_MEMORY = booleanPreferencesKey(SettingsKeys.ENABLE_MEMORY)
    private val KEY_ENABLE_AUTO_SUMMARY = booleanPreferencesKey(SettingsKeys.ENABLE_AUTO_SUMMARY)
    private val KEY_SUMMARY_INTERVAL = intPreferencesKey(SettingsKeys.SUMMARY_INTERVAL)
    private val KEY_EXCHANGE_COUNT = intPreferencesKey(SettingsKeys.EXCHANGE_COUNT)
    private val KEY_SYSTEM_PROMPT = stringPreferencesKey(SettingsKeys.SYSTEM_PROMPT)
    private val KEY_THEME_MODE = stringPreferencesKey(SettingsKeys.THEME_MODE)
    private val KEY_ENABLE_DYNAMIC_COLOR = booleanPreferencesKey(SettingsKeys.ENABLE_DYNAMIC_COLOR)
    private val KEY_COLOR_PRESET = stringPreferencesKey(SettingsKeys.COLOR_PRESET)
    private val KEY_FIRST_LAUNCH = booleanPreferencesKey(SettingsKeys.FIRST_LAUNCH)
    private val KEY_TTS_MAIN_ENABLED = booleanPreferencesKey(SettingsKeys.TTS_MAIN_ENABLED)
    private val KEY_TTS_OVERLAY_ENABLED = booleanPreferencesKey(SettingsKeys.TTS_OVERLAY_ENABLED)
    private val KEY_TTS_LENGTH_SCALE = doublePreferencesKey(SettingsKeys.TTS_LENGTH_SCALE)
    private val KEY_CHAT_BUBBLE_ALPHA = doublePreferencesKey(SettingsKeys.CHAT_BUBBLE_ALPHA)
    private val KEY_ENABLE_AUTO_UPDATE_CHECK = booleanPreferencesKey(SettingsKeys.ENABLE_AUTO_UPDATE_CHECK)
    private val KEY_WALLPAPER_PATH = stringPreferencesKey(SettingsKeys.WALLPAPER_PATH)
    private val KEY_WALLPAPER_BLUR = doublePreferencesKey(SettingsKeys.WALLPAPER_BLUR)
    private val KEY_PRIVACY_VERSION_SHOWN = stringPreferencesKey(SettingsKeys.PRIVACY_VERSION_SHOWN)

    // ── Flows (响应式订阅) ────────────────────────────

    /** API Key 流。 */
    val apiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_API_KEY] ?: ""
    }

    /** API URL 流。 */
    val apiUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_API_URL] ?: SettingsKeys.Defaults.API_URL
    }

    /** 模型流。 */
    val modelFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_MODEL] ?: SettingsKeys.Defaults.MODEL
    }

    /** Temperature 流。 */
    val temperatureFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_TEMPERATURE] ?: SettingsKeys.Defaults.TEMPERATURE
    }

    /** Max tokens 流。 */
    val maxTokensFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_MAX_TOKENS] ?: SettingsKeys.Defaults.MAX_TOKENS
    }

    /** 记忆开关流。 */
    val enableMemoryFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_MEMORY] ?: SettingsKeys.Defaults.ENABLE_MEMORY
    }

    /** 自动摘要开关流。 */
    val enableAutoSummaryFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_AUTO_SUMMARY] ?: SettingsKeys.Defaults.ENABLE_AUTO_SUMMARY
    }

    /** 摘要轮次流（每隔多少轮对话触发一次短期记忆摘要）。 */
    val summaryIntervalFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SUMMARY_INTERVAL] ?: SettingsKeys.Defaults.SUMMARY_INTERVAL
    }

    /** System prompt 流。 */
    val systemPromptFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SYSTEM_PROMPT] ?: SettingsKeys.Defaults.SYSTEM_PROMPT
    }

    /** 主题模式流。 */
    val themeModeFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: SettingsKeys.Defaults.THEME_MODE
    }

    /** 动态颜色开关流。 */
    val enableDynamicColorFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_DYNAMIC_COLOR] ?: SettingsKeys.Defaults.ENABLE_DYNAMIC_COLOR
    }

    /** 颜色预设流。 */
    val colorPresetFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_COLOR_PRESET] ?: SettingsKeys.Defaults.COLOR_PRESET
    }

    /** 首次启动标记流。 */
    val isFirstLaunchFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FIRST_LAUNCH] ?: true
    }

    /** 主界面语音开关流。 */
    val ttsMainEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_MAIN_ENABLED] ?: SettingsKeys.Defaults.TTS_MAIN_ENABLED
    }

    /** 悬浮窗语音开关流。 */
    val ttsOverlayEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_OVERLAY_ENABLED] ?: SettingsKeys.Defaults.TTS_OVERLAY_ENABLED
    }

    /** 语速流（length_scale，1.0 原速）。 */
    val ttsLengthScaleFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_LENGTH_SCALE] ?: SettingsKeys.Defaults.TTS_LENGTH_SCALE
    }

    /** 主页聊天气泡透明度流（0.2~1.0，1.0 不透明）。 */
    val chatBubbleAlphaFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_CHAT_BUBBLE_ALPHA] ?: SettingsKeys.Defaults.CHAT_BUBBLE_ALPHA
    }

    /** 启动自动检查更新开关流（默认开启）。 */
    val enableAutoUpdateCheckFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLE_AUTO_UPDATE_CHECK] ?: SettingsKeys.Defaults.ENABLE_AUTO_UPDATE_CHECK
    }

    /** 主界面背景壁纸路径流（空串 = 默认纯色）。 */
    val wallpaperPathFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_WALLPAPER_PATH] ?: SettingsKeys.Defaults.WALLPAPER_PATH
    }

    /** 主界面背景壁纸模糊强度流（0~1，0 = 不模糊）。 */
    val wallpaperBlurFlow: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_WALLPAPER_BLUR] ?: SettingsKeys.Defaults.WALLPAPER_BLUR
    }

    /** 已看过/已处理的隐私政策版本号流（默认空串 = 未处理）。 */
    val privacyVersionShownFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PRIVACY_VERSION_SHOWN] ?: SettingsKeys.Defaults.PRIVACY_VERSION_SHOWN
    }

    // ── 同步 getter（非 Flow 场景使用，如 Client 构造）──
    // 读取内存快照，全程无磁盘 IO；快照未就绪时返回空快照，各 getter 落到 Defaults，
    // 绝不在主线程阻塞读盘（首帧真实值由对应 Flow 就绪后修正）

    private fun currentPrefs(): Preferences = cachedPrefs ?: emptyPreferences()

    fun getApiKey(): String = currentPrefs()[KEY_API_KEY] ?: ""
    fun getApiUrl(): String = currentPrefs()[KEY_API_URL] ?: SettingsKeys.Defaults.API_URL
    fun getModel(): String = currentPrefs()[KEY_MODEL] ?: SettingsKeys.Defaults.MODEL
    fun getTemperature(): Double = currentPrefs()[KEY_TEMPERATURE] ?: SettingsKeys.Defaults.TEMPERATURE
    fun getMaxTokens(): Int = currentPrefs()[KEY_MAX_TOKENS] ?: SettingsKeys.Defaults.MAX_TOKENS
    fun getSystemPrompt(): String = currentPrefs()[KEY_SYSTEM_PROMPT] ?: SettingsKeys.Defaults.SYSTEM_PROMPT
    fun isMemoryEnabled(): Boolean = currentPrefs()[KEY_ENABLE_MEMORY] ?: SettingsKeys.Defaults.ENABLE_MEMORY
    fun isAutoSummaryEnabled(): Boolean = currentPrefs()[KEY_ENABLE_AUTO_SUMMARY] ?: SettingsKeys.Defaults.ENABLE_AUTO_SUMMARY
    fun getSummaryInterval(): Int = currentPrefs()[KEY_SUMMARY_INTERVAL] ?: SettingsKeys.Defaults.SUMMARY_INTERVAL

    /** 主题模式（"system" | "light" | "dark"）。 */
    fun getThemeMode(): String = currentPrefs()[KEY_THEME_MODE] ?: SettingsKeys.Defaults.THEME_MODE

    /** 是否启用动态取色（Material You）。 */
    fun isDynamicColorEnabled(): Boolean = currentPrefs()[KEY_ENABLE_DYNAMIC_COLOR] ?: SettingsKeys.Defaults.ENABLE_DYNAMIC_COLOR

    /** 颜色预设 ID（"default" | "ocean" | ...）。 */
    fun getColorPreset(): String = currentPrefs()[KEY_COLOR_PRESET] ?: SettingsKeys.Defaults.COLOR_PRESET

    /** 距上次摘要已进行的对话轮数（跨进程存活，见 [SettingsKeys.EXCHANGE_COUNT]）。 */
    fun getExchangeCount(): Int = currentPrefs()[KEY_EXCHANGE_COUNT] ?: 0

    /** 主界面语音开关。 */
    fun isTtsMainEnabled(): Boolean = currentPrefs()[KEY_TTS_MAIN_ENABLED] ?: SettingsKeys.Defaults.TTS_MAIN_ENABLED

    /** 悬浮窗语音开关。 */
    fun isTtsOverlayEnabled(): Boolean = currentPrefs()[KEY_TTS_OVERLAY_ENABLED] ?: SettingsKeys.Defaults.TTS_OVERLAY_ENABLED

    /** 语速（length_scale，1.0 原速）。 */
    fun getTtsLengthScale(): Double = currentPrefs()[KEY_TTS_LENGTH_SCALE] ?: SettingsKeys.Defaults.TTS_LENGTH_SCALE

    /** 主页聊天气泡透明度（0.2~1.0，1.0 不透明）。 */
    fun getChatBubbleAlpha(): Double = currentPrefs()[KEY_CHAT_BUBBLE_ALPHA] ?: SettingsKeys.Defaults.CHAT_BUBBLE_ALPHA

    /** 是否启用启动自动检查更新（默认开启）。 */
    fun isAutoUpdateCheckEnabled(): Boolean =
        currentPrefs()[KEY_ENABLE_AUTO_UPDATE_CHECK] ?: SettingsKeys.Defaults.ENABLE_AUTO_UPDATE_CHECK

    /** 主界面背景壁纸路径（空串 = 默认纯色）。 */
    fun getWallpaperPath(): String = currentPrefs()[KEY_WALLPAPER_PATH] ?: SettingsKeys.Defaults.WALLPAPER_PATH

    /** 主界面背景壁纸模糊强度（0~1，0 = 不模糊）。 */
    fun getWallpaperBlur(): Double = currentPrefs()[KEY_WALLPAPER_BLUR] ?: SettingsKeys.Defaults.WALLPAPER_BLUR

    /** 已看过/已处理的隐私政策版本号（默认空串 = 未处理）。 */
    fun getPrivacyVersionShown(): String =
        currentPrefs()[KEY_PRIVACY_VERSION_SHOWN] ?: SettingsKeys.Defaults.PRIVACY_VERSION_SHOWN

    /** 是否首次启动（同步读取；默认 true）。 */
    fun isFirstLaunch(): Boolean = currentPrefs()[KEY_FIRST_LAUNCH] ?: true

    // ── 写入方法 ──────────────────────────────────────
    // edit 返回写入后的最新快照，随手更新缓存，保证同步 getter 读己之写

    suspend fun setApiKey(key: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_API_KEY] = key }
        Log.d(TAG, "API Key updated")
    }

    suspend fun setApiUrl(url: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_API_URL] = url }
        Log.d(TAG, "API URL updated: $url")
    }

    suspend fun setModel(model: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_MODEL] = model }
    }

    suspend fun setTemperature(temp: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TEMPERATURE] = temp }
    }

    suspend fun setMaxTokens(tokens: Int) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_MAX_TOKENS] = tokens }
    }

    suspend fun setEnableMemory(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_MEMORY] = enabled }
    }

    suspend fun setEnableAutoSummary(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_AUTO_SUMMARY] = enabled }
    }

    suspend fun setSummaryInterval(interval: Int) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_SUMMARY_INTERVAL] = interval }
    }

    suspend fun setExchangeCount(count: Int) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_EXCHANGE_COUNT] = count }
    }

    suspend fun setSystemPrompt(prompt: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_SYSTEM_PROMPT] = prompt }
    }

    suspend fun setThemeMode(mode: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode }
    }

    suspend fun setEnableDynamicColor(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setColorPreset(preset: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_COLOR_PRESET] = preset }
    }

    suspend fun markFirstLaunchDone() {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_FIRST_LAUNCH] = false }
    }

    suspend fun setTtsMainEnabled(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TTS_MAIN_ENABLED] = enabled }
    }

    suspend fun setTtsOverlayEnabled(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TTS_OVERLAY_ENABLED] = enabled }
    }

    suspend fun setTtsLengthScale(scale: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_TTS_LENGTH_SCALE] = scale }
    }

    suspend fun setChatBubbleAlpha(alpha: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_CHAT_BUBBLE_ALPHA] = alpha }
    }

    suspend fun setEnableAutoUpdateCheck(enabled: Boolean) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_ENABLE_AUTO_UPDATE_CHECK] = enabled }
    }

    /** 记录已看过/已处理的隐私政策版本号。 */
    suspend fun setPrivacyVersionShown(version: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_PRIVACY_VERSION_SHOWN] = version }
    }

    suspend fun setWallpaperPath(path: String) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_WALLPAPER_PATH] = path }
    }

    suspend fun setWallpaperBlur(blur: Double) {
        cachedPrefs = dataStore.edit { prefs -> prefs[KEY_WALLPAPER_BLUR] = blur }
    }

    /** 清除所有设置（恢复出厂）。 */
    suspend fun clearAll() {
        cachedPrefs = dataStore.edit { it.clear() }
        Log.i(TAG, "All settings cleared")
    }
}
