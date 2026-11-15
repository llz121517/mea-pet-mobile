package com.meapet.mobile.legacy

import com.meapet.mobile.settings.SettingsManager
import java.io.File

/**
 * 迁移上下文（长期迁移框架的一部分，见 [LegacyMigration]）。
 *
 * 当前字段服务于 TTS 迁移步骤；未来新增迁移时可在此扩展字段（例如新的目录路径、标志位）。
 */
internal class LegacyMigrationContext(
    val ttsModelDir: File,
    val settingsManager: SettingsManager,
) {
    /** 是否有任何旧版 TTS 残留被命中（供后续 step 决定联动动作）。 */
    var ttsLegacyDetected: Boolean = false
}
