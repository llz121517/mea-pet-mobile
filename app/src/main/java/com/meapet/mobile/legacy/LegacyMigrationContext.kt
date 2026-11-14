package com.meapet.mobile.legacy

import com.meapet.mobile.settings.SettingsManager
import java.io.File

/**
 * 旧版迁移上下文（一次性兼容代码，见 [LegacyMigration]）。
 */
internal class LegacyMigrationContext(
    val ttsModelDir: File,
    val settingsManager: SettingsManager,
) {
    /** 是否有任何旧版 TTS 残留被命中（供后续 step 决定联动动作）。 */
    var ttsLegacyDetected: Boolean = false
}
