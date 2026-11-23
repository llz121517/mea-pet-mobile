package com.meapet.mobile.backup

import android.util.Log
import com.meapet.mobile.chat.compaction.CompactionState
import com.meapet.mobile.settings.SettingsKeys
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

/**
 * 一次导入的结果：按类别分别报告成功/失败。
 *
 * 失败语义（PRD §4.3）：某类别失败时该类别回滚（存储层事务），
 * 其它类别按用户原本勾选继续。
 */
data class ImportOutcome(
    val succeeded: Set<BackupCategory>,
    val failed: Map<BackupCategory, String>,
) {
    /** 是否有类别失败。 */
    val hasFailure: Boolean get() = failed.isNotEmpty()
}

/**
 * 按类别事务化落地已校验的备份包。
 *
 * 落地顺序固定为「设置 → 对话 → 记忆 → 压缩状态 → 壁纸」：
 * - 压缩状态的 `coveredToMessageId` 需要对话先落地才能校验；
 * - 壁纸最后写，覆盖设置条目里带回的旧设备路径。
 *
 * 每个类别内部使用目标存储的原子操作（SQLite 单事务 / DataStore 单次 edit）。
 */
object BackupImporter {

    private const val TAG = "BackupImporter"

    /** 壁纸目录名（与 `WallpaperStore` 一致：`filesDir/wallpaper/`）。 */
    private const val WALLPAPER_DIR_NAME = "wallpaper"

    /** 写入失败的统一文案（LLD §5；不暴露底层异常消息，避免带出路径/正文）。 */
    private const val WRITE_FAILED = "写入失败，请检查可用空间"

    /**
     * 导入 [selected] 里、且包里实际存在的类别。
     *
     * @param reader 已通过 [BackupInspector.open] 校验的读取器
     * @param selected 用户勾选的类别
     * @param target 本机落地目标
     */
    suspend fun import(
        reader: BackupContentReader,
        selected: Set<BackupCategory>,
        target: BackupTarget,
    ): ImportOutcome {
        val present = reader.contents.categories
        val succeeded = LinkedHashSet<BackupCategory>()
        val failed = LinkedHashMap<BackupCategory, String>()
        // 固定落地顺序（不依赖 selected 作为 Set 的迭代顺序）：
        // 压缩状态必须在对话之后校验游标；壁纸最后覆盖 settings 里带回的旧路径。
        BackupCategory.entries.forEach { category ->
            // 只处理「用户勾选 ∩ 包内实际存在」；包内没有的类别静默跳过，不算失败。
            if (category !in selected || category !in present) return@forEach
            try {
                when (category) {
                    BackupCategory.SETTINGS -> applySettings(reader, target)
                    BackupCategory.CHAT -> applyChat(reader, target)
                    BackupCategory.MEMORY -> applyMemory(reader, target)
                    BackupCategory.COMPACTION -> applyCompaction(reader, target)
                    BackupCategory.WALLPAPER -> applyWallpaper(reader, target)
                }
                succeeded += category
            } catch (cancellation: CancellationException) {
                // 协程取消不是业务失败：必须继续向上抛，否则会把取消吞成「导入失败」。
                throw cancellation
            } catch (error: Exception) {
                // 单个类别失败不影响其它类别：记录简短原因，继续下一个类别。
                // 只记录异常类型，不记录 message/堆栈，避免日志带出对话正文或文件路径。
                Log.w(TAG, "导入 ${category.entryName} 失败（${error::class.java.simpleName}）")
                failed[category] = failureReason(error)
            }
        }
        return ImportOutcome(succeeded, failed)
    }

    // ── 各类别落地 ────────────────────────────────────

    private suspend fun applySettings(reader: BackupContentReader, target: BackupTarget) {
        val values = SettingsWhitelist.decode(
            reader.extractText(BackupCategory.SETTINGS)
        )
        target.settings.apply(values)
    }

    private suspend fun applyChat(reader: BackupContentReader, target: BackupTarget) {
        val messages = BackupCodec.decodeChat(
            reader.extractText(BackupCategory.CHAT)
        )
        // 单个事务整体替换（见 ConversationRepository.replaceAll）。
        target.conversationRepository.replaceAll(messages)
    }

    private suspend fun applyMemory(reader: BackupContentReader, target: BackupTarget) {
        val payload = BackupCodec.decodeMemory(
            reader.extractText(BackupCategory.MEMORY)
        )
        // 单事务整体替换记忆条目 + 摘要轮数（SQLite 实现覆写为原子操作）。
        target.memoryStore.replaceAll(payload.items, payload.exchangeCount)
    }

    private suspend fun applyCompaction(reader: BackupContentReader, target: BackupTarget) {
        val state = BackupCodec.decodeCompaction(
            reader.extractText(BackupCategory.COMPACTION)
        )
        val coveredTo = state.coveredToMessageId
        // 未同时导入对话时，旧摘要的覆盖游标在本机不存在：丢弃摘要、重置为空状态，
        // 避免把旧摘要注入新会话（LLD §2.2 / 已知问题 G2）；这不是失败。
        if (coveredTo != null && !target.conversationRepository.contains(coveredTo)) {
            Log.w(TAG, "压缩摘要的覆盖游标在目标对话中不存在，已重置为空状态")
            target.compactionStore.save(CompactionState())
        } else {
            target.compactionStore.save(state)
        }
    }

    private suspend fun applyWallpaper(reader: BackupContentReader, target: BackupTarget) {
        val entry = reader.contents.entries[BackupCategory.WALLPAPER]
            ?: throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
        val dir = File(target.filesDir, WALLPAPER_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建壁纸目录")
        }
        // 包内路径已由 BackupInspector 校验过形态；这里仍只取最后一段并安全化，
        // 保证文件名不会把文件写出 wallpaper 目录（`..`、分隔符、超长等）。
        val fileName = safeWallpaperFileName(entry.path.substringAfterLast('/'))
        val file = File(dir, fileName)
        try {
            reader.extractToFile(BackupCategory.WALLPAPER, file)
            // 文件落地成功后才把设置指向它；先写设置后写文件会出现「路径指向半成品」。
            target.settings.apply(
                mapOf(SettingsKeys.WALLPAPER_PATH to JsonPrimitive(file.absolutePath))
            )
        } catch (error: Exception) {
            // 中途失败：删掉刚写出的半成品，避免半张图留在目录里被误用。
            file.delete()
            throw error
        }
        // 新文件与设置都成功之后，才清理同目录下的其它旧壁纸（只保留刚落地的那张）。
        dir.listFiles()
            ?.filter { it.isFile && it.absolutePath != file.absolutePath }
            ?.forEach { it.delete() }
    }

    // ── 内部 ──────────────────────────────────────────

    /**
     * 失败原因：结构化拒绝（manifest/条目解析）沿用其安全文案；
     * 其它异常（IO / SQLite）统一为不含底层细节的写入失败文案。
     */
    private fun failureReason(error: Throwable): String = when (error) {
        is ManifestRejectException -> error.message ?: BackupFormat.STRUCTURE_INCOMPLETE
        else -> WRITE_FAILED
    }
}
