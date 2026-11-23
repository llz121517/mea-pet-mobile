package com.meapet.mobile.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备份包 manifest 中登记的一个条目。
 *
 * @property name 条目名（与 [BackupCategory.entryName] 一致）
 * @property schema 条目级版本；必须等于 [BackupFormat.SUPPORTED_SCHEMA] 中该条目的支持值
 * @property bytes 未压缩字节数，仅供 UI 展示与合理性对账，不参与完整性校验
 * @property path 包内路径（`files/<entry>` 或 `files/wallpaper/<name>`）
 */
@Serializable
data class ManifestEntry(
    val name: String,
    val schema: Int,
    val bytes: Long,
    val path: String,
)

/**
 * 包级 manifest。
 *
 * @property format 包级格式版本；必须等于 [BackupFormat.SUPPORTED_FORMAT]
 * @property createdAt ISO-8601 UTC 创建时间
 * @property appVersion 本项目补充字段，便于排障；读取方忽略未知字段
 * @property entries 只登记实际写出的条目（未勾选的类别不出现）
 */
@Serializable
data class BackupManifest(
    val format: Int,
    val createdAt: String,
    val appVersion: String = "",
    val entries: List<ManifestEntry> = emptyList(),
)

/**
 * 可备份的数据类别。
 *
 * [entryName] 与包内 `files/<entryName>` 对齐参考实现；[COMPACTION] 为本项目新增，
 * 沿用同一命名约定。枚举顺序即 UI 展示顺序（导出勾选、导入清单、落地顺序都按此）。
 */
enum class BackupCategory(val entryName: String, val label: String) {
    SETTINGS("settings", "设置"),
    CHAT("chat", "对话历史"),
    MEMORY("memory", "记忆库"),
    COMPACTION("compaction", "上下文压缩状态"),
    WALLPAPER("wallpaper", "背景壁纸"),
    ;

    companion object {
        /** 按条目名查找类别；未知条目名返回 null。 */
        fun fromEntryName(name: String): BackupCategory? =
            BackupCategory.entries.firstOrNull { it.entryName == name }

        /**
         * 按包内路径归入类别。
         *
         * 只接受两种形态：`files/<entry>`（文本条目）与 `files/wallpaper/<name>`
         * （壁纸二进制）。任何多余层级、空段、`.`/`..` 段、反斜杠、绝对路径、
         * 超长路径都返回 null，由调用方按「未知路径」整包拒绝。
         */
        fun fromPath(path: String): BackupCategory? {
            if (path.isEmpty() || path.length > BackupFormat.MAX_PATH_LENGTH) return null
            if (path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) return null
            val segments = path.split('/')
            if (segments.size < 2 || segments[0] != BackupFormat.FILES_DIR) return null
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            val category = fromEntryName(segments[1]) ?: return null
            return when (category) {
                BackupCategory.WALLPAPER -> if (segments.size == 3) category else null
                else -> if (segments.size == 2) category else null
            }
        }
    }
}

/** manifest 校验失败；UI 只展示 message。 */
class ManifestRejectException(message: String) : Exception(message)

/**
 * 包级格式常量与 manifest 编解码。
 *
 * 两级版本校验：`format`（包级）与 `entries[].schema`（条目级）**都必须等于支持值**，
 * 任一不等整包拒绝（见 [decode]、[entrySupported]）。
 */
object BackupFormat {
    /** 包级格式版本（初版）。 */
    const val SUPPORTED_FORMAT = 1

    /** manifest 在包内的固定路径。 */
    const val MANIFEST_ENTRY = "manifest.json"

    /** 业务条目在包内的目录前缀。 */
    const val FILES_DIR = "files"
    const val FILES_PREFIX = "$FILES_DIR/"

    /** 包内路径长度上限（防异常长路径）。 */
    const val MAX_PATH_LENGTH = 512

    /** 各条目当前支持的 schema 版本。新增类别必须同时登记在这里。 */
    val SUPPORTED_SCHEMA: Map<String, Int> = mapOf(
        BackupCategory.SETTINGS.entryName to 1,
        BackupCategory.MEMORY.entryName to 1,
        BackupCategory.CHAT.entryName to 1,
        BackupCategory.COMPACTION.entryName to 1,
        BackupCategory.WALLPAPER.entryName to 1,
    )

    /** 全部类别（枚举顺序即展示顺序）。 */
    val categories: List<BackupCategory> = BackupCategory.entries

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 类别对应的条目 schema 版本。 */
    fun schemaFor(category: BackupCategory): Int =
        checkNotNull(SUPPORTED_SCHEMA[category.entryName]) {
            "类别 ${category.entryName} 未登记 schema 版本"
        }

    /** 文本条目的标准包内路径（壁纸需由调用方给具体文件名）。 */
    fun pathFor(category: BackupCategory): String = FILES_PREFIX + category.entryName

    /** manifest 编码（导出收尾用）。 */
    fun encode(manifest: BackupManifest): String = json.encodeToString(manifest)

    /**
     * manifest 解码并校验包级格式版本。
     *
     * @throws ManifestRejectException 结构无法解析或 `format` 不在支持范围
     */
    fun decode(text: String): BackupManifest {
        val manifest = runCatching { json.decodeFromString<BackupManifest>(text) }
            .getOrElse { throw ManifestRejectException(STRUCTURE_INCOMPLETE) }
        if (manifest.format != SUPPORTED_FORMAT) {
            throw ManifestRejectException(
                "这个备份由不支持的版本创建（format ${manifest.format}，本应用支持 $SUPPORTED_FORMAT）"
            )
        }
        return manifest
    }

    /** 条目级 schema 是否受支持。 */
    fun entrySupported(entry: ManifestEntry): Boolean =
        SUPPORTED_SCHEMA[entry.name] == entry.schema

    /** 生成条目版本不支持的用户文案（列出第一个不支持的条目）。 */
    fun unsupportedEntryMessage(entry: ManifestEntry): String {
        val supported = SUPPORTED_SCHEMA[entry.name]
        return if (supported == null) {
            "备份包含本应用不认识的条目（${entry.name}）"
        } else {
            "这个备份由不支持的版本创建（条目 ${entry.name} 为 schema ${entry.schema}，" +
                "本应用支持 $supported）。请升级 App 后重试"
        }
    }

    /** 结构不完整的统一文案。 */
    const val STRUCTURE_INCOMPLETE = "备份包结构不完整"
}

/**
 * 把「磁盘现名」安全化成包内壁纸文件名：只留最后一段、字符集收窄到 `[A-Za-z0-9._-]`、
 * 截断 [maxLength] 字符，空则兜底。
 *
 * 越界判定走 [BackupCategory.fromPath] 的路径段校验；本函数只负责文件名本身，
 * 不能依赖 `contains("..")`（会误杀 `wallpaper..2.jpg` 这类合法名字）。
 */
internal fun safeWallpaperFileName(rawName: String, maxLength: Int = 96): String {
    val lastSegment = rawName.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = lastSegment.filter { c ->
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'
    }.take(maxLength)
    // 纯点号（"." / ".."）会被路径段校验认为越界，这里直接兜底
    if (cleaned.isEmpty() || cleaned.all { it == '.' }) return "wallpaper.jpg"
    return cleaned
}
