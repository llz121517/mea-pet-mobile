package com.meapet.mobile.backup

import androidx.datastore.preferences.core.Preferences
import com.meapet.mobile.chat.compaction.CompactionStore
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.storage.ConversationRepository
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * 待写进备份包的一个条目。
 *
 * @property zipPath 包内路径（`files/<entry>` 或 `files/wallpaper/<name>`）
 * @property bytes 未压缩字节数（manifest 登记的 `bytes`）
 */
sealed interface BackupItem {
    val zipPath: String
    val bytes: Long

    /** 文本条目：内容已在内存中（JSON），按 DEFLATE 压缩写入加密流。 */
    data class Text(
        override val zipPath: String,
        val content: String,
    ) : BackupItem {
        override val bytes: Long get() = content.toByteArray(Charsets.UTF_8).size.toLong()
    }

    /** 二进制条目（壁纸）：直读源文件进加密流，按 STORE 存储。 */
    data class FileSource(
        override val zipPath: String,
        val file: File,
    ) : BackupItem {
        override val bytes: Long get() = file.length()
    }
}

/**
 * 导出采集所需的数据源。
 *
 * 全部来自 [AppContainer] 中既有实例（不在备份模块里新建数据库/设置实例）：
 * 设置读 DataStore 快照、会话/记忆/压缩状态读 SQLite 业务数据、壁纸读文件。
 */
class BackupSources(
    val settings: SettingsSink,
    val conversationRepository: ConversationRepository,
    val memoryStore: MemoryStore,
    val compactionStore: CompactionStore,
    /** 应用 filesDir（壁纸在 `filesDir/wallpaper/`）。 */
    val filesDir: File,
    /** 当前应用版本号，写进 manifest 便于排障。 */
    val appVersion: String,
)

/**
 * 设置的读写抽象。
 *
 * 导出读 [snapshot]（DataStore 原始 Preferences）；导入按类别整体 [apply] 白名单内的键。
 * 用接口而非直接依赖 [SettingsManager]，让导入/采集逻辑在 JVM 单测里可用假实现验证。
 */
interface SettingsSink {
    /** 当前 DataStore 快照（只读）。 */
    fun snapshot(): Preferences

    /** 把白名单键值写进 DataStore；只处理 [SettingsWhitelist] 认识的键。 */
    suspend fun apply(values: Map<String, JsonElement>)
}

/** 生产实现：经 [SettingsManager] 读写 DataStore（保持内存快照同步）。 */
class DataStoreSettingsSink(private val settingsManager: SettingsManager) : SettingsSink {

    override fun snapshot(): Preferences = settingsManager.rawSnapshot()

    override suspend fun apply(values: Map<String, JsonElement>) {
        settingsManager.editRaw { mutable -> SettingsWhitelist.apply(values, mutable) }
    }
}

/**
 * 导入落地目标：按类别分别覆盖本机数据。
 *
 * 每个类别用各自存储的原子操作落地（SQLite 事务 / DataStore 单次 edit），
 * 某一类别失败时其它类别不受影响。
 */
data class BackupTarget(
    val conversationRepository: ConversationRepository,
    val memoryStore: MemoryStore,
    val compactionStore: CompactionStore,
    /** 应用 filesDir（壁纸写到 `filesDir/wallpaper/`）。 */
    val filesDir: File,
    val settings: SettingsSink,
)
