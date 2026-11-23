# 配置与数据导出导入与备份 — LLD（详细设计）

> 状态：**已实现（2026-11-23）**。需求与决策见 [`2026-11-22-backup-export-import-prd.md`](2026-11-22-backup-export-import-prd.md)。
> 参考实现：[`mea-pet-mobile-fork-fo-pr`](https://gitee.com/flexiatom/mea-pet-mobile-fork-fo-pr) 的 `backup/` 包 —— **借鉴设计，不借鉴实现**（它按磁盘 JSON 文件采集，本项目数据在 SQLite 单库）。

---

## 1. 现状约束（设计前提）

| 数据 | 当前存放 | 采集方式 |
|---|---|---|
| 用户设置 | DataStore Preferences | 键白名单逐项读取 |
| 对话历史 | SQLite `message` 表（`SqliteConversationRepository`） | 读业务对象 → 序列化 JSON |
| 记忆库 | SQLite `memory` 表（`SqliteMemoryStore`）+ `meta` 表（摘要计数） | 读业务对象 → 序列化 JSON |
| 上下文压缩状态 | SQLite `meta` 表单行 JSON（`MetaCompactionStore`） | `CompactionStore.load()` → 序列化 |
| 背景壁纸 | `filesDir` 图片文件 | 直接读文件字节 |

**不能复制 `.db` 文件**：库里有内部 `seq`、SQLite `user_version`、以及未来可能新增的表；整库替换会让「只恢复对话不恢复记忆」这类按类别操作变成不可能，也会把本机库结构强加给导入方。

关键接口（**在实现中补齐了三个方法**，接口本身仍只做读/整体替换，不掺备份逻辑）：
- `ConversationRepository`：`loadRecent(limit)` / `count()` / `replaceAll(list)` / `clear()` / **`contains(id)`**（压缩游标校验用）
- `MemoryStore`：`getAll()` / `saveAll(items)` / `clear()` / `getExchangeCount()` / `setExchangeCount()` / **`replaceAll(items, exchangeCount)`**（默认实现走 clear+saveAll+setExchangeCount，SQLite 实现覆写为单事务）
- `CompactionStore`：`load()` / `save(state)` / `clear()`
- `ConversationManager`：**`reloadWindowFromRepository()`**（导入后只读仓库重建内存窗口，不回写）

---

## 2. 包结构

```
<UTC紧凑>_MeaPet-backup.zip           ← AES-256 加密（zip4j）
├── manifest.json
└── files/
    ├── settings                        JSON
    ├── chat                            JSON
    ├── memory                          JSON
    ├── compaction                      JSON
    └── wallpaper/<磁盘现名>            图片二进制
```

包内路径与条目名对齐参考版（`files/<category>`、`files/wallpaper/<name>`）；`compaction` 为本项目新增，沿用同一命名约定。

### 2.1 manifest.json

```json
{
  "format": 1,
  "createdAt": "2026-11-22T15:30:00Z",
  "appVersion": "1.9.0",
  "entries": [
    { "name": "settings",   "schema": 1, "bytes": 1234,  "path": "files/settings" },
    { "name": "wallpaper",  "schema": 1, "bytes": 90210, "path": "files/wallpaper/1758..._wallpaper.jpg" }
  ]
}
```

- `format`：包级格式版本；`entries[].schema`：条目级版本。**两者都必须等于支持值**，不等即整包拒绝。
- `appVersion`：本项目补充，便于排障；读取方忽略未知字段。
- 只登记实际写出的条目（未勾选的类别不出现）。
- `bytes` 仅用于 UI 展示与合理性对账，不做逐条目 hash（zip 密码已提供完整性校验）。

### 2.2 各条目内部结构

#### settings（schema 1）

```json
{
  "values": {
    "theme_mode": "system",
    "enable_dynamic_color": true,
    "chat_bubble_alpha": 1.0,
    "api_url": "https://api.deepseek.com/v1",
    "model": "deepseek-flash",
    "temperature": 0.7,
    "max_tokens": 4096,
    "context_window": null,
    "enable_memory": true,
    "memory_mode": "AUTO",
    "enable_auto_summary": true,
    "summary_interval": 10,
    "tts_main_enabled": false,
    "tts_overlay_enabled": false,
    "tts_length_scale": 1.0,
    "web_search_enabled": false,
    "web_search_provider": "auto",
    "web_search_reuse_chat_key": true,
    "enable_auto_update_check": true,
    "wallpaper_path": "",
    "wallpaper_blur": 0.0
  }
}
```

**白名单表**（唯一真相源，新增键不会自动进包）：

| 键 | 类型 | 说明 |
|---|---|---|
| `theme_mode` | String | 主题：system/light/dark |
| `enable_dynamic_color` | Boolean | 动态取色 |
| `color_preset` | String | 配色预设 |
| `chat_bubble_alpha` | Double | 气泡透明度 |
| `api_url` | String | API 地址（用户选择进包） |
| `model` | String | 模型名 |
| `temperature` | Double | 温度 |
| `max_tokens` | Int | 输出上限 |
| `context_window` | Int? | 手动上下文窗口，null=自动 |
| `system_prompt` | String | 系统提示词 |
| `enable_memory` | Boolean | 记忆总开关 |
| `memory_mode` | String | 检索模式 |
| `enable_auto_summary` | Boolean | 自动摘要 |
| `summary_interval` | Int | 摘要间隔 |
| `tts_main_enabled` / `tts_overlay_enabled` | Boolean | 语音开关 |
| `tts_length_scale` | Double | 语速 |
| `web_search_enabled` | Boolean | 联网开关 |
| `web_search_provider` | String | 联网服务商 |
| `web_search_reuse_chat_key` | Boolean | 复用聊天 Key 开关 |
| `enable_auto_update_check` | Boolean | 启动检查更新 |
| `wallpaper_path` / `wallpaper_blur` | String / Double | 壁纸路径与模糊 |
| **`api_key`** | String | **可选**：仅当用户勾选「API Key」 |
| **`web_search_deepseek_key`** | String | **可选**：同上 |
| **`web_search_anysearch_key`** | String | **可选**：同上 |

**明确排除**：`first_launch`（一次性状态）、`privacy_version_shown`（隐私确认必须重新走）、`exchange_count`（已废弃，仅 legacy 迁移读）。

**导出/导入的实际行为**（实现细节，易被误读）：

- **只导出 DataStore 里实际写过的键，不物化默认值**。这样导入到另一台设备时，未设置过的项继续沿用该设备的默认值，语义与旧机一致。
- 导入**只写白名单内、且包里实际存在的键**：未知键与类型不符的键静默忽略（前向兼容）；`context_window: null` 表示「清除，回到自动」。
- 用 `preferences.asMap()` 按类型读取再判断，而不是直接 `preferences[booleanPreferencesKey(...)]`——后者遇到脏类型会抛 `ClassCastException` 打断整个导出。

#### chat（schema 1）

```json
{
  "messages": [
    {
      "id": "…", "role": "user", "content": "…", "timestamp": 1795359286232,
      "turnId": "…", "toolCallId": null, "toolName": null,
      "rawAssistantJson": null
    }
  ]
}
```

- 按 `seq` 顺序输出全部消息。
- `rawAssistantJson`（含 `tool_calls`/`reasoning_content`）原样带走：丢了下一次请求会 400。
- 导入时 `replaceAll(messages)`，单个事务。

#### memory（schema 1）

```json
{
  "items": [
    {
      "id": "mem_…", "content": "…", "type": "SHORT_TERM",
      "importance": 0.5, "createdAt": 0, "lastAccessedAt": 0,
      "accessCount": 1, "sourceMessageId": null, "keywords": ["…"]
    }
  ],
  "exchangeCount": 3
}
```

- `exchangeCount` 属于 `meta` 表的运行状态，但它是「距上次摘要的轮数」，跨机恢复有意义，随记忆一起带。
- 导入：`clear()` → `saveAll()` → `setExchangeCount()`，同一事务。

#### compaction（schema 1）

```json
{
  "summary": "…", "coveredToMessageId": "…", "coveredCount": 12, "updatedAt": 0
}
```

- 导入后 `coveredToMessageId` 可能在新库里不存在（若没同时导入对话）——**落地时校验**：若该 id 在目标库找不到，则丢弃 `summary` 并重置为 `CompactionState()`，避免旧摘要注入新会话（这是已知问题 G2 的同一个坑）。

#### wallpaper

- 二进制图片原文，路径 `files/wallpaper/<磁盘现名>`。
- 现名安全化：只留最后一段、字符集收窄到 `[A-Za-z0-9._-]`、截断 96 字符、空则兜底，**按路径段判越界**（不能用 `contains("..")`，会误杀 `wallpaper..2.jpg`）。
- 导入后写回 `filesDir` 并把 `wallpaper_path` 设成新路径。

---

## 3. 模块设计

> **实现状态（2026-11-23）**：本文已按实现同步。较早草稿中的 `BackupInspector.inspect()` /
> `BackupTargetSetters` 等形态已由下方实际接口取代（`open()` 返回 `BackupContentReader`，密码不进 UI 状态）。

```
backup/
├── BackupFormat.kt        包级模型：ManifestEntry、BackupManifest、BackupCategory、
│                          SUPPORTED_FORMAT、SUPPORTED_SCHEMA、编解码、拒绝异常、
│                          路径三态判定（已知/未知安全/越界）、壁纸名安全化
├── SettingsWhitelist.kt   设置键白名单 + 类型化编解码（含可选 Key 三键）
├── BackupCodec.kt         各条目 JSON 模型与编解码（ChatPayload/MemoryPayload/CompactionPayload）
├── BackupContract.kt      BackupItem、BackupSources、BackupTarget、SettingsSink
├── BackupCollector.kt     类别 + 数据源 → List<BackupItem>（Text / FileSource）+ manifest
├── BackupCounter.kt       导入前条数对比：对话 / 记忆的「本机 → 包内」条数
├── BackupExporter.kt      逐条目 AES-256 流式写出 + manifest 收尾
├── BackupInspector.kt     读包 + 两级版本校验 + 未知条目忽略 + 文本条目内存闸
├── BackupImporter.kt      按类别事务化落地
└── BackupService.kt       SAF 薄封装：暂存、开流、显示名、清扫
viewmodel/
└── BackupViewModel.kt     导出/导入状态机
ui/screen/settings/
└── DataSettings.kt        页面 UI（两个可折叠块 + Snackbar 回执）
```

依赖注入：`AppContainer` 暴露 `backupService`、`backupSettingsSink`、`backupSources`、`backupTarget`
（全部由既有 `SettingsManager` / `ConversationRepository` / `MemoryStore` / `CompactionStore` 组装），
另提供导入后的数据变更广播：`dataRevision: StateFlow<Long>` + `notifyDataReplaced()`。

### 3.1 BackupFormat

```kotlin
@Serializable
data class ManifestEntry(val name: String, val schema: Int, val bytes: Long, val path: String)

@Serializable
data class BackupManifest(
    val format: Int, val createdAt: String, val appVersion: String = "",
    val entries: List<ManifestEntry>,
)

enum class BackupCategory(val entryName: String, val label: String) {
    SETTINGS("settings", "设置"), MEMORY("memory", "记忆库"), CHAT("chat", "对话历史"),
    COMPACTION("compaction", "上下文压缩状态"), WALLPAPER("wallpaper", "背景壁纸");

    companion object {
        fun fromEntryName(name: String): BackupCategory?
        fun fromPath(path: String): BackupCategory?   // files/<entry> 或 files/wallpaper/<name>
        fun isIllegalPath(path: String): Boolean      // 越界/畸形（`..`、空段、绝对路径…）
    }
}

object BackupFormat {
    const val SUPPORTED_FORMAT = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val FILES_PREFIX = "files/"
    const val MAX_PATH_LENGTH = 512
    val SUPPORTED_SCHEMA = mapOf(
        "settings" to 1, "memory" to 1, "chat" to 1,
        "compaction" to 1, "wallpaper" to 1,
    )
    val categories: List<BackupCategory>
    fun schemaFor(category: BackupCategory): Int
    fun pathFor(category: BackupCategory): String      // files/<entryName>
    fun encode(manifest: BackupManifest): String
    fun decode(text: String): BackupManifest          // format 不等即抛
    fun entrySupported(entry: ManifestEntry): Boolean // schema 不等即 false
    fun unsupportedEntryMessage(entry: ManifestEntry): String
}
```

`ManifestRejectException(message: String)`：所有校验失败统一抛这个，UI 只展示 message。

**路径三态**（实现要点，决定「拒绝」还是「忽略」）：

| 判定 | 条件 | 处理 |
|---|---|---|
| 已知 | `fromPath` 能归入类别 | 正常校验与导入 |
| 未知但安全 | 形态合法（`files/<名>` 或 `files/wallpaper/<名>`）但类别名不认识 | **忽略 + logcat 警告** |
| 越界/畸形 | `..`、空段、`.`、绝对路径、反斜杠、NUL、超长 | **整包拒绝** |

壁纸文件名安全化 `safeWallpaperFileName(raw)` 是包级内部函数（只留最后一段、字符集收窄到
`[A-Za-z0-9._-]`、截断 96 字符、空则兜底），导出与导入两侧共用。

### 3.2 BackupItem 与 Exporter

```kotlin
sealed interface BackupItem {
    val zipPath: String
    val bytes: Long                                         // 未压缩字节数（写 manifest 用）
    data class Text(override val zipPath: String, val content: String) : BackupItem
    data class FileSource(override val zipPath: String, val file: File) : BackupItem
}

object BackupExporter {
    const val MANIFEST_ENTRY = "manifest.json"
    fun defaultFileName(utcCompact: String): String        // <utc>_MeaPet-backup.zip
    fun export(items: List<BackupItem>, manifest: BackupManifest,
               password: CharArray, sink: OutputStream)
}
```

实现要点：
- 用 zip4j 的 `io.outputstream.ZipOutputStream(OutputStream, char[])`（**`ZipFile` 没有 OutputStream 构造器**），逐条目 `putNextEntry(ZipParameters)` → `write` → `closeEntry`，最后写 manifest。
- `ZipParameters`：AES-256（`EncryptionMethod.AES` / `AesKeyStrength.KEY_STRENGTH_256`）、`setFileNameInZip(path)`、图片用 `STORE`（已压缩），文本用 `DEFLATE`。**写入任一流的 `STORE` 条目必须 `setEntrySize(...)`**（zip4j 不能回填大小，否则 `putNextEntry` 抛 "uncompressed size should be set"）。
- 明文不落盘：文本在内存、图片直读源文件进加密流。
- 包里**不含**密码、不含设备标识。

### 3.3 Inspector

```kotlin
data class BackupContentEntry(val path: String, val bytes: Long)
data class BackupContents(
    val manifest: BackupManifest,
    val entries: Map<BackupCategory, BackupContentEntry>,
) { val categories: Set<BackupCategory> get() = entries.keys }

interface BackupContentReader : Closeable {
    val contents: BackupContents
    fun extractText(category: BackupCategory, maxBytes: Long = BackupInspector.MAX_TEXT_BYTES): String
    fun extractToFile(category: BackupCategory, target: File)
}

object BackupInspector {
    const val MAX_TEXT_BYTES = 64L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024L * 1024
    fun open(stageFile: File, password: CharArray): BackupContentReader
}
```

密码不落 UI 状态：zip 句柄与密码由 `BackupContentReader` 私有持有，`BackupUiState` 只放
`contents`（元信息）。读取器在重新选包 / 关闭 / `onCleared` 时 `close()`。

校验顺序（前四条任一失败整包拒绝）：
1. 能打开（密码错 → 「密码错误或文件已损坏」）。
2. 有且只有一个 `manifest.json`（读取上限 1 MiB）且可解析。
3. `format == SUPPORTED_FORMAT`。
4. **路径越界/畸形**（`..`、空段、`.`、绝对路径、反斜杠、超长）→ 拒绝（安全底线）。
5. 已知条目：manifest 有登记且 name/path 一致、`schema` 匹配；一个类别只允许出现一次。
6. **未知但安全的条目**（本版本不认识的类别名，如未来新增类别或参考实现的 `pet_form`）：
   **静默忽略 + logcat 警告**（`BackupInspector` 的 `忽略未知备份条目：<path>`），不进
   `BackupContents`。这样新版 App 导出的包在旧版仍可导入其余类别，而不是整包报废。
7. manifest 登记数与包内条目数对账（两边都排除第 6 步已忽略的条目）：「登记了但包里没有」
   或「包里已知条目未登记」都按结构不完整拒绝。

> 取舍：**未知 ≠ 危险**。忽略未知条目是为了前向兼容；路径越界、已知类别未登记、schema
> 不支持这些「自相矛盾或会危及本机」的情况仍然整包拒绝。

**体积闸**：文本条目边读边计数，超 64 MiB 抛；二进制条目流式写盘不设上限（磁盘是边界）。

### 3.4 Importer

```kotlin
object BackupImporter {
    suspend fun import(
        reader: BackupContentReader,
        selected: Set<BackupCategory>,
        target: BackupTarget,
    ): ImportOutcome
}

data class BackupTarget(
    val conversationRepository: ConversationRepository,
    val memoryStore: MemoryStore,
    val compactionStore: CompactionStore,
    val filesDir: File,
    val settings: SettingsSink,
)

data class ImportOutcome(val succeeded: Set<BackupCategory>, val failed: Map<BackupCategory, String>)
```

按类别独立落地，**每个类别一个事务**：

| 类别 | 落地动作 |
|---|---|
| settings | `SettingsWhitelist.decode` → 逐键写 DataStore（只写白名单内、包里实际存在的键；可选 Key 按包里有无） |
| chat | `conversationRepository.replaceAll(messages)`（单事务整体替换） |
| memory | `memoryStore.replaceAll(items, exchangeCount)`（单事务整体替换 + 轮数计数） |
| compaction | 校验 `coveredToMessageId` 是否被 `conversationRepository.contains(id)` 找到；找不到则写空状态 |
| wallpaper | 解到 `filesDir/wallpaper/<安全化文件名>` → 更新 `wallpaper_path` → 清理其它旧壁纸 |

失败语义：某类别失败时该类别回滚（事务），**其它类别按用户原本勾选继续**，回执里分别报告成功/失败类别。

### 3.4.1 条数对比（BackupCounter）

```kotlin
data class ImportCount(val inPackage: Int, val local: Int)

object BackupCounter {
    val countedCategories = listOf(BackupCategory.CHAT, BackupCategory.MEMORY)
    suspend fun compare(
        reader: BackupContentReader,
        target: BackupTarget,
        selected: Set<BackupCategory> = reader.contents.categories,
    ): Map<BackupCategory, ImportCount>
}
```

- 只对**按条数计**的类别有意义：`chat` 是消息条数、`memory` 是记忆条目数；设置 / 压缩状态 / 壁纸各是一份整体数据，不参与。
- 读包成功后立即算好（`inspectPackage` 内、与校验同一 IO 线程），随 `BackupContents` 一起进 `BackupUiState.counts`；
  读包清单里每个类别显示「本机 → 包内」条数，强制确认弹窗再列一次，避免用户误判覆盖规模。
- 单个类别解析失败只跳过该类别（不阻断读包）；真正的结构校验与拒绝仍在 `BackupInspector` / `BackupImporter`。

### 3.5 Service

```kotlin
class BackupService(context: Context) {
    val stagingDir: File                                  // cacheDir/backup
    fun stageForRead(uri: Uri): File                      // 拷到 cacheDir 再按中央目录读
    fun openWriteSink(uri: Uri): OutputStream             // SAF CreateDocument（"wt" 截断）
    fun displayName(uri: Uri): String?                    // OpenableColumns.DISPLAY_NAME
    fun sweepStaleStaging()                               // 进页清扫
    companion object { fun utcCompactNow(): String }
}
```

清扫逻辑抽成 `internal fun sweepStagingDir(dir: File)`（纯函数，JVM 单测可验证）。

### 3.6 ViewModel 状态机

```kotlin
data class BackupUiState(
    val busy: Boolean = false,
    val available: Set<BackupCategory> = emptySet(),   // 本机有内容的类别
    val exportSelection: Set<BackupCategory> = …全选…,
    val includeApiKeys: Boolean = false,
    val staged: BackupContents? = null,                // 已校验的待导入包（只含元信息）
    val stagedFileName: String? = null,                // 备份包显示名（SAF 取不到时为 null）
    val counts: Map<BackupCategory, ImportCount> = emptyMap(),  // 对话/记忆：本机 → 包内条数
    val importSelection: Set<BackupCategory> = emptySet(),
    val receipt: String? = null,
)
```

- 密码**不进状态、不落盘、不写日志**，只作为方法参数（用后 `fill('\u0000')`）或 `DataSettings` 的局部 `remember`。
- 导出：`refreshAvailability()` → 勾选 → 密码两次 → 「确认导出数据」弹窗 → SAF → `runExport`。
- 导入：SAF → `inspectPackage(uri, pwd)`（**同时算好 `counts`**）→ 展示清单（每个类别显示包内大小，对话/记忆附加「本机 → 包内」条数）→ 勾选 → 强制确认（再列一次条数）→ `runImport`。
- 回执一律走 **Snackbar**（带可手动关闭的 ✕），不占用页面纵向空间。

### 3.7 导入后的刷新链路

导入直接写 SQLite、绕过 `ChatService` 与 `ChatViewModel`；设置页返回主页**不触发 `ON_RESUME`**，
因此 `runImport` 成功后按顺序做四件事：

```kotlin
container.chatService.reloadAfterExternalImport()   // 窗口 + 压缩状态 + token 估算
if (SETTINGS in outcome.succeeded) container.reloadClient()   // 新端点/Key/模型生效
container.notifyDataReplaced()                      // dataRevision 自增，广播给 UI 层
refreshAvailability()                               // 备份页自身的可用性标记
```

- `ChatService.reloadAfterExternalImport()`：`conversationManager.reloadWindowFromRepository()` +
  强制 `compactionStore.load()`（绕开 `compactionLoaded` 一次性缓存）+ `refreshContextTokens()`。
- `ChatViewModel` 订阅 `dataRevision` 后调 `reloadAfterDataReplaced()`：**整表替换**
  （导入是整库替换，增量合并会把本机旧消息当成新追加留在尾部），重置
  `hasMoreHistory` / `isLoadingOlder` / `pendingUndo` / 压缩提示标记。
- 设置项与壁纸由 DataStore Flow 自动刷新；记忆弹窗打开时按需读库，无需额外处理。

---

## 4. 依赖与构建

- 新增 `net.lingala.zip4j:zip4j`（版本写进 `gradle/libs.versions.toml`）。
- **`NOTICE.md` 必须补**：zip4j 为 Apache-2.0，随 APK 分发，需要按其要求声明（这是既有发版合规流程的一部分）。
- 无需新增权限：SAF 走 `CreateDocument`/`OpenDocument`，不需要存储权限。

---

## 5. 错误与文案

| 场景 | 文案要点 |
|---|---|
| 密码错误/包损坏 | 「密码错误或文件已损坏」——不区分二者，不泄露包内信息 |
| format/schema 不支持 | 「这个备份由不支持的版本创建（format X，本应用支持 Y）」+ 建议升级 App |
| 条目缺失/未登记 | 「备份包结构不完整」 |
| 文本条目超限 | 「备份中的文本条目过大（超过 64 MiB）」 |
| 空间不足 | 「写入失败，请检查可用空间」 |
| 用户取消 | 「已取消，未改动任何数据」 |
| 导入后 | 「已恢复 设置、对话历史…；失败：记忆库（原因）」 |
| 导出后 | 「已导出 <文件名>（<大小>）」 |
| 未知条目 | 不进 UI 文案，仅 logcat：`忽略未知备份条目：<path>` |

---

## 6. 测试设计

| 层 | 用例 |
|---|---|
| `BackupFormatTest` | format 不等拒绝、schema 不等拒绝、**越界/未知路径区分**、类别↔路径映射、manifest 往返、壁纸名安全化 |
| `SettingsWhitelistTest` | 白名单外的键不进包、Optional Key 三键受开关控制、只导出实际写过的键、类型正确性、未知/类型不符键在导入时忽略、null 上下文窗口清除 |
| `BackupCodecTest` | chat/memory/compaction 往返、rawAssistantJson 与工具字段保留、损坏内容拒绝、未知字段忽略 |
| `BackupCollectorTest` | 可用性判定、各类别条目与 manifest 对账、未勾选类别不进包、壁纸文件缺失时跳过 |
| `BackupExporterTest` | 文本+文件混合写出、明文不出现在字节里、密码能解、错误密码打不开、manifest 条目数与实际一致 |
| `BackupInspectorTest` | 正常包解析、密码错、缺 manifest、未登记条目、重复类别、越界路径拒绝、未知安全条目忽略、schema 不符、文本大小闸 |
| `BackupImporterTest` | 各类别覆盖（原有数据 / 空库两种）、单类别失败不影响其它、compaction 的 coveredToMessageId 失效重置、壁纸路径安全化、未勾选类别不动 |
| `BackupCounterTest` | 对话 / 记忆的「本机 → 包内」条数、空库与空包为 0、非条数类别不参与、条目损坏时跳过该类别 |
| `BackupRoundTripTest` | 全类别导出→导入→比对：消息、记忆、设置、压缩状态、壁纸字节；错误密码拒绝 |
| `BackupServiceTest` | 文件名生成（UTC 紧凑）、暂存目录清扫 |
| 安全 | 全流程日志不含密码、Key、对话正文（代码审查 + grep 断言） |

---

## 7. 实施顺序（实际落地）

1. `BackupFormat` + `SettingsWhitelist` + 单测（纯 JVM，无 Android 依赖）。
2. `BackupCodec`（三份 payload 模型）+ 往返单测。
3. `BackupExporter` / `BackupInspector`（zip4j）+ 单测。
4. `BackupImporter` + 事务与失败语义单测（用 `InMemoryConversationRepository` 等既有测试替身）。
5. `BackupService` + `AppContainer` 装配（含 `dataRevision` 广播）。
6. `BackupViewModel` + `DataSettings` UI。
7. `NOTICE.md` 补 zip4j、隐私政策补备份章节、`docs/README.md` 登记本文、CHANGELOG 补条目。
8. 真机验收：导出→改数据→导入→核对；错误密码；取消路径；大壁纸。**（当前未执行，见 [`TODO.md`](TODO.md)）**

> 后续（未做）：与社区参考实现的互通。审计结论是最小方案只需一层**按 JSON 形状归一化**
> 的解码前适配（不看来源、只看形状），约 1 人日、不必动 `format`/`schema`；详见
> [`2026-11-23-backup-format-compat.md`](2026-11-23-backup-format-compat.md)。

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 导入把库写坏 | 按类别事务 + 导入前取快照（可选：提供「导入前自动导出一次」作为无痛兜底） |
| 密码遗忘导致包报废 | UI 明示「无法找回」；弱密码只提示不拦（用户自担） |
| zip4j 的 API 与直觉不符 | 已在 §3.2 写明：只有 `ZipOutputStream` 能往任意流写；`STORE` 条目必须 `setEntrySize(...)`；`ZipParameters` 无 `isCompressed` |
| zip4j 持有密码数组的**引用** | `open()` 内 `password.copyOf()` 交给 `ZipFile`，副本由 reader 持有并在 `close()` 清零；否则调用方读包后清零会让后续导入解密失败（已修复，有回归用例） |
| 导入后 UI 不刷新 | `dataRevision` 广播 + `reloadAfterExternalImport()`，见 §3.7 |
| 未来新增设置键被意外导出 | 白名单是显式枚举，新增键默认不进包，有单测守护 |
| 未来新增类别的包在旧版打不开 | 未知条目改为忽略 + 警告（仅越界路径与结构矛盾拒绝） |
| 未来备份格式升级 | 遵循 `docs/legacy-compat.md` §8：格式升级与导入兼容 step 同步提供 |
