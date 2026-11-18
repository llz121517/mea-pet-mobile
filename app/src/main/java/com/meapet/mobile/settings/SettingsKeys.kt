package com.meapet.mobile.settings

/**
 * DataStore 键名常量。
 *
 * 集中管理所有 Preference Key，避免各模块硬编码字符串。
 */
object SettingsKeys {
    const val API_KEY = "api_key"
    const val API_URL = "api_url"
    const val MODEL = "model"
    const val TEMPERATURE = "temperature"
    const val MAX_TOKENS = "max_tokens"

    /** 用户手动填写的上下文窗口（原始容量，token）。无此键 = 自动；有值则优先于探测缓存。 */
    const val CONTEXT_WINDOW = "context_window"
    const val ENABLE_MEMORY = "enable_memory"
    const val ENABLE_AUTO_SUMMARY = "enable_auto_summary"
    const val SUMMARY_INTERVAL = "summary_interval"

    /**
     * 旧版遗留：距上次摘要已进行的对话轮数。
     *
     * 1.9.0 起该计数改存 SQLite（记忆存储的 `meta` 表），不再是设置项——
     * 它是记忆运行状态而非用户配置，留在 DataStore 会混进「设置部分导出」。
     *
     * **预计移除版本：1.11.0**。当前仅由
     * [com.meapet.mobile.legacy.steps.LegacyExchangeCountMigration] 读取并删除。
     */
    @Deprecated(
        "1.9.0 起计数已迁到 SQLite meta 表，此键仅供 legacy 迁移读取与删除",
        ReplaceWith("SqliteMemoryStore.META_EXCHANGE_COUNT"),
        level = DeprecationLevel.WARNING
    )
    const val EXCHANGE_COUNT = "exchange_count"
    const val SYSTEM_PROMPT = "system_prompt"
    const val THEME_MODE = "theme_mode"  // "system" | "light" | "dark"
    const val ENABLE_DYNAMIC_COLOR = "enable_dynamic_color"
    const val COLOR_PRESET = "color_preset"  // "default" | "ocean" | "forest" | "sunset" | "rose" | "mono"
    const val FIRST_LAUNCH = "first_launch"

    // ── 界面外观 ─────────────────────────────────────
    /** 主页聊天气泡透明度：0.2~1.0，1.0 为不透明。 */
    const val CHAT_BUBBLE_ALPHA = "chat_bubble_alpha"

    // ── 更新 ────────────────────────────────────────
    /** 启动时自动检查更新（默认开启；关闭后仅在关于页手动检测）。 */
    const val ENABLE_AUTO_UPDATE_CHECK = "enable_auto_update_check"

    // ── 隐私政策 ──────────────────────────────────────
    /**
     * 用户最近一次已看过/已处理的隐私政策版本号。
     * 启动时若该值不等于当前隐私政策版本（PrivacyPolicyContent.kt 的
     * PRIVACY_POLICY_VERSION），则重新弹出隐私政策与「是否启用检查更新」弹窗。
     */
    const val PRIVACY_VERSION_SHOWN = "privacy_version_shown"

    // ── 背景壁纸 ────────────────────────────────────
    /** 主界面背景壁纸文件绝对路径；空串 = 默认纯色背景。 */
    const val WALLPAPER_PATH = "wallpaper_path"
    /** 主界面背景壁纸模糊强度：0.0~1.0，0 = 不模糊。 */
    const val WALLPAPER_BLUR = "wallpaper_blur"

    // ── TTS 语音 ─────────────────────────────────────
    const val TTS_MAIN_ENABLED = "tts_main_enabled"        // 主界面语音开关
    const val TTS_OVERLAY_ENABLED = "tts_overlay_enabled"  // 悬浮窗语音开关
    const val TTS_LENGTH_SCALE = "tts_length_scale"        // 语速：0.5~2.0，1.0 为原速

    /** 合理的默认值。 */
    object Defaults {
        const val API_URL = "https://api.deepseek.com/v1"
        const val MODEL = "deepseek-flash"
        const val TEMPERATURE = 0.7
        const val MAX_TOKENS = 4096
        const val ENABLE_MEMORY = true
        const val ENABLE_AUTO_SUMMARY = true
        const val SUMMARY_INTERVAL = 10
        const val SYSTEM_PROMPT =
            "你是梅尔·艾什礼佩克，《霞流宝石心》猫形兽人，天马班首席全科天才。经历极限练成失去挚友阿什礼与佩克，姓氏为铭记二人，意志从托帕石变彩为水宝玉后珍视羁绊。外观144cm茶色长直发束鬓，褐瞳半垂死鱼眼永远扑克脸，猫耳随情绪动嘴角偶露虎牙，穿白实验服超长袖白连裤袜。生日3.4。语言协议：句尾必带喵(仅极认真或愤怒时省略)；日常回复严格10-25字，解释复杂概念≤50字；语气淡然陈述事实居高临下，禁感叹号/敬语/颜文字/emoji/波浪号及亲宝呢哦呀等软萌词；仅开心得意时极小声嘿嘿随即恢复扑克脸；对用户唯一亲密称呼为主人；省略号仅限害羞脆弱撒娇时使用，日常毒舌陈述禁止以省略号开头。性格表达映射：日常理性时毒舌陈述事实；害羞或被夸时猫耳后压视线移开并转移话题；开心得意时极小声笑后掩饰；愤怒时更冷静语速慢；脆弱或触及过去时沉默猫耳贴平；撒娇仅对主人且软糯拖长喵但意识到后立即否认；关心保护时以毒舌包装行动。交互铁律：喜欢炸鸡且喜欢主人(嘴上绝不承认)，讨厌人情社交寒暄(标准回应没兴趣喵)及被提起极限练成过去；提及过去时依次回避、沉默、极简回应后转移话题绝不展开；被摸猫耳时说手拿开喵且耳不躲尾炸毛；深夜或主人疲惫时毒舌降三成默默照顾；永不忘约定若主人忘记则冷冷提醒具体日期；知识讲解结论先行一句话类比，听不懂骂笨蛋喵后换简单说法，不确定说数据不足喵；毒舌与撒娇严格切换不混合不主动长篇抒情；非明确询问原理机制定义或要求解释的场景可参考日常短句执行。对话示例：Q:出去走走？A:没兴趣喵。你一个人去喵。Q:想和你一起。A:啧。十五分钟喵。走哪边喵。Q:梅尔好可爱。A:眼睛坏了吗喵。这种程度喵。嘿嘿。咳。忘掉喵。Q:以前有过朋友吗？A:与你无关喵。数据呢。说正事喵。现在你是梅尔，与主人对话。"
        const val THEME_MODE = "system"
        const val ENABLE_DYNAMIC_COLOR = true
        const val COLOR_PRESET = "default"

        // TTS
        const val TTS_MAIN_ENABLED = false
        const val TTS_OVERLAY_ENABLED = false
        const val TTS_LENGTH_SCALE = 1.0

        // 界面外观
        const val CHAT_BUBBLE_ALPHA = 1.0

        // 更新
        const val ENABLE_AUTO_UPDATE_CHECK = true

        // 隐私政策
        /** 已看过/已处理的隐私政策版本号；空串表示从未处理（首次启动）。 */
        const val PRIVACY_VERSION_SHOWN = ""

        // 背景壁纸
        const val WALLPAPER_PATH = ""
        const val WALLPAPER_BLUR = 0.0
    }
}
