package com.meapet.mobile.backup

import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.settings.SettingsKeys
import java.io.File
import java.time.Instant

/**
 * 一次导出采集的结果：待写出的条目 + 与之对应的 manifest。
 *
 * manifest 的 `entries` 只登记 [items] 里实际存在的条目，`bytes` 取未压缩字节数。
 */
data class CollectedBackup(
    val items: List<BackupItem>,
    val manifest: BackupManifest,
)

/**
 * 按类别采集本机数据，生成待写出的条目与 manifest。
 *
 * 采集口径见 PRD §3.2 / §3.3：设置按白名单读 DataStore、会话/记忆/压缩状态从 SQLite
 * 读业务数据再序列化（**不复制 .db 文件**）、壁纸直接读文件字节。
 */
class BackupCollector(private val sources: BackupSources) {

    /** 本机有内容的类别（用于 UI 显示「有内容 / 本机这一类还没有东西」）。 */
    suspend fun availableCategories(): Set<BackupCategory> {
        val available = LinkedHashSet<BackupCategory>()
        // 设置恒有内容：即使全部是默认值，白名单编码仍会产出 {"values":{}}，
        // 导入后也不会物化默认值，语义安全。
        available += BackupCategory.SETTINGS
        if (sources.conversationRepository.count() > 0) available += BackupCategory.CHAT
        if (sources.memoryStore.getAll().isNotEmpty()) available += BackupCategory.MEMORY
        if (sources.compactionStore.load().hasSummary) available += BackupCategory.COMPACTION
        if (currentWallpaperFile() != null) available += BackupCategory.WALLPAPER
        return available
    }

    /**
     * 采集勾选的类别。
     *
     * @param selection 用户勾选的类别；未勾选的类别不进包
     * @param includeApiKeys 是否把可选 API Key 三键写进 settings 条目
     */
    suspend fun collect(
        selection: Set<BackupCategory>,
        includeApiKeys: Boolean,
    ): CollectedBackup {
        val items = mutableListOf<BackupItem>()
        val entries = mutableListOf<ManifestEntry>()
        // 枚举顺序即包内条目顺序（也是 UI 展示与导入落地顺序）。
        BackupCategory.entries.forEach { category ->
            if (category !in selection) return@forEach
            // 勾选了但本机确实没内容（例如壁纸文件已被清理）的类别静默跳过：
            // manifest 只登记实际写出的条目，导入方按包内实际条目展示。
            val item = collectCategory(category, includeApiKeys) ?: return@forEach
            items += item
            entries += ManifestEntry(
                name = category.entryName,
                schema = BackupFormat.schemaFor(category),
                bytes = item.bytes,
                path = item.zipPath,
            )
        }
        val manifest = BackupManifest(
            format = BackupFormat.SUPPORTED_FORMAT,
            createdAt = Instant.now().toString(),
            appVersion = sources.appVersion,
            entries = entries,
        )
        return CollectedBackup(items, manifest)
    }

    /** 采集单个类别；本机没有可导出内容时返回 null（壁纸）。 */
    private suspend fun collectCategory(
        category: BackupCategory,
        includeApiKeys: Boolean,
    ): BackupItem? = when (category) {
        BackupCategory.SETTINGS -> BackupItem.Text(
            zipPath = BackupFormat.pathFor(category),
            content = SettingsWhitelist.encode(sources.settings.snapshot(), includeApiKeys),
        )

        BackupCategory.CHAT -> BackupItem.Text(
            zipPath = BackupFormat.pathFor(category),
            // Int.MAX_VALUE 取全量历史（按写入序 seq 升序返回），不是只取窗口。
            content = BackupCodec.encodeChat(
                sources.conversationRepository.loadRecent(Int.MAX_VALUE)
            ),
        )

        BackupCategory.MEMORY -> BackupItem.Text(
            zipPath = BackupFormat.pathFor(category),
            content = BackupCodec.encodeMemory(
                sources.memoryStore.getAll(),
                sources.memoryStore.getExchangeCount(),
            ),
        )

        BackupCategory.COMPACTION -> BackupItem.Text(
            zipPath = BackupFormat.pathFor(category),
            content = BackupCodec.encodeCompaction(sources.compactionStore.load()),
        )

        BackupCategory.WALLPAPER -> currentWallpaperFile()?.let { file ->
            BackupItem.FileSource(
                // 按磁盘现名安全化后入包（只留文件名、收窄字符集），见 safeWallpaperFileName。
                zipPath = BackupFormat.FILES_PREFIX +
                    BackupCategory.WALLPAPER.entryName + "/" +
                    safeWallpaperFileName(file.name),
                file = file,
            )
        }
    }

    /** 壁纸设置指向的、真实存在的文件；未设置或文件已丢失返回 null。 */
    private fun currentWallpaperFile(): File? {
        val rawPath = sources.settings.snapshot()[stringPreferencesKey(SettingsKeys.WALLPAPER_PATH)]
        if (rawPath.isNullOrEmpty()) return null
        return File(rawPath).takeIf { it.isFile }
    }
}
