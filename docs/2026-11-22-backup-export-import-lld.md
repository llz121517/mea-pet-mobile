# 配置与数据导出导入与备份 — LLD（详细设计）

> 状态：**设计稿（2026-11-22）**，未实现。需求与决策见 [`2026-11-22-backup-export-import-prd.md`](2026-11-22-backup-export-import-prd.md)。
> 参考实现：[`mea-pet-mobile-fork-fo-pr`](https://gitee.com/flexiatom/mea-pet-mobile-fork-fo-pr) 的 `backup/` 包 —— **抄设计不抄实现**（它按磁盘 JSON 文件采集，本项目数据在 SQLite 单库）。

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

关键接口（已存在，无需改动）：
- `ConversationRepository`：`loadRecent(limit)` / `count()` / `replaceAll(list)` / `clear()`
- `MemoryStore`：`getAll()` / `saveAll(items)` / `clear()` / `getExchangeCount()` / `setExchangeCount()`
- `CompactionStore`：`load()` / `save(state)` / `clear()`

---

## 2. 包结构

```
<UTC紧凑>_MeaPet-Android.zip           ← AES-256 加密（zip4j）
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
  "appVersion": "1.10.0",
  "entries": [
    { "name": "settings",   "schema": 1, "bytes": 1234,  "path": "files/settings" },
    { "name": "wallpaper",  "schema": 1, "bytes": 90210, "path": "files/wallpaper/1758..._wallpaper.jpg" }
  ]
}
```

- `format`：包级格式版本；`entries[].schema`：条目级版本。**两者都必须等于支持值**，不等即整包拒绝。
- `appVersion`：本项目补充，便于排障；读取方忽略未知字段。
- 只登记实际写出的条目（未勾选的类别不出现）。
- `bytes` 仅用于 UI 展示与合理性对账，不做逐条目 hash（zip 口令已提供完整性校验）。

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
| **`api_key`** | String | **可选**：仅当用户勾选「包含 API Key」 |
| **`web_search_deepseek_key`** | String | **可选**：同上 |
| **`web_search_anysearch_key`** | String | **可选**：同上 |

**明确排除**：`first_launch`（一次性状态）、`privacy_version_shown`（隐私确认必须重新走）、`exchange_count`（已废弃，仅 legacy 迁移读）。

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

```
backup/
├── BackupFormat.kt        包级模型：ManifestEntry、BackupManifest、BackupCategory、
│                          SUPPORTED_FORMAT、SUPPORTED_SCHEMA、编解码、拒绝异常
├── SettingsWhitelist.kt   设置键白名单 + 类型化编解码（含可选 Key 三键）
├── BackupCodec.kt         各条目 JSON 模型与编解码（ChatPayload/MemoryPayload/CompactionPayload）
├── BackupCollector.kt     类别 + 数据源 → List<BackupItem>（Text / FileSource）
├── BackupExporter.kt      逐条目 AES-256 流式写出 + manifest 收尾
├── BackupInspector.kt     读包 + 两级版本校验 + 文本条目内存闸
├── BackupImporter.kt      按类别事务化落地
└── BackupService.kt       SAF 薄封装：暂存、开流、显示名、清扫
viewmodel/
└── BackupViewModel.kt     导出/导入状态机
ui/screen/settings/
└── DataSettings.kt        页面 UI
```

依赖注入：`AppContainer` 暴露 `backupService`、`backupSources`（由既有 manager/repository 组装）。

### 3.1 BackupFormat

```kotlin
@Serializable
data class ManifestEntry(val name: String, val schema: Int, val bytes: Long, val path: String)

@Serializable
data class BackupManifest(
    val format: Int, val createdAt: String, val appVersion: String = "",
    val entries: List<ManifestEntry>,
)

enum class BackupCategory(val entryName: String) {
    SETTINGS("settings"), MEMORY("memory"), CHAT("chat"),
    COMPACTION("compaction"), WALLPAPER("wallpaper");

    companion object {
        fun fromEntryName(name: String): BackupCategory?
        fun fromPath(path: String): BackupCategory?   // files/<entry> 或 files/<entry>/…
    }
}

object BackupFormat {
    const val SUPPORTED_FORMAT = 1
    val SUPPORTED_SCHEMA = mapOf(
        "settings" to 1, "memory" to 1, "chat" to 1,
        "compaction" to 1, "wallpaper" to 1,
    )
    fun decode(text: String): BackupManifest          // format 不等即抛
    fun entrySupported(entry: ManifestEntry): Boolean // schema 不等即 false
}
```

`ManifestRejectException(message: String)`：所有校验失败统一抛这个，UI 只展示 message。

### 3.2 BackupItem 与 Exporter

```kotlin
sealed interface BackupItem {
    val zipPath: String
    data class Text(override val zipPath: String, val content: String) : BackupItem
    data class FileSource(override val zipPath: String, val file: File) : BackupItem
}

object BackupExporter {
    const val MANIFEST_ENTRY = "manifest.json"
    fun pathFor(category: BackupCategory): String
    fun defaultFileName(utcCompact: String): String        // <utc>_MeaPet-Android.zip
    fun export(items: List<BackupItem>, manifest: BackupManifest,
               password: CharArray, sink: OutputStream)
}
```

实现要点：
- 用 zip4j 的 `io.outputstream.ZipOutputStream(OutputStream, char[])`（**`ZipFile` 没有 OutputStream 构造器**），逐条目 `putNextEntry(ZipParameters)` → `write` → `closeEntry`，最后写 manifest。
- `ZipParameters`：AES-256、`setFileNameInZip(path)`、图片用 `STORE`（已压缩），文本用 `DEFLATE`。
- 明文不落盘：文本在内存、图片直读源文件进加密流。
- 包里**不含**口令、不含设备标识。

### 3.3 Inspector

```kotlin
data class BackupContents(
    val manifest: BackupManifest,
    val perCategory: Map<BackupCategory, Pair<String, FileHeader>>,
) { val categories: Set<BackupCategory> get() = perCategory.keys }

object BackupInspector {
    fun inspect(stageFile: File, password: CharArray): BackupContents
    fun extractText(contents: BackupContents, category: BackupCategory): String
    fun extractToFile(contents: BackupContents, category: BackupCategory, target: File)
}
```

校验顺序（任一失败整包拒绝）：
1. 能打开（口令错 → 「口令错误或文件已损坏」）。
2. 有 `manifest.json` 且可解析。
3. `format == SUPPORTED_FORMAT`。
4. 每个 zip 条目：路径能归入已知类别（未知路径拒绝）、manifest 有登记（未登记拒绝）、`schema` 匹配。
5. 一个类别只允许出现一次。

**体积闸**：文本条目边读边计数，超 64 MiB 抛；二进制条目流式写盘不设上限（磁盘是边界）。

### 3.4 Importer

```kotlin
object BackupImporter {
    suspend fun import(
        contents: BackupContents,
        selected: Set<BackupCategory>,
        target: BackupTarget,
        setters: BackupTargetSetters,
    )
}

data class BackupTarget(
    val conversationRepository: ConversationRepository,
    val memoryStore: MemoryStore,
    val compactionStore: CompactionStore,
    val filesDir: File,
)
```

按类别独立落地，**每个类别一个事务**：

| 类别 | 落地动作 |
|---|---|
| settings | 逐键写 DataStore（只写白名单内的键；可选 Key 按包里实际有无） |
| chat | `conversationRepository.clear()` → `replaceAll(messages)` |
| memory | `memoryStore.clear()` → `saveAll(items)` → `setExchangeCount(n)` |
| compaction | 校验 `coveredToMessageId` 存在于目标对话；不存在则写空状态 |
| wallpaper | 写 `filesDir/<name>` → 更新 `wallpaper_path` |

失败语义：某类别失败时该类别回滚（事务），**其它类别按用户原本勾选继续**，回执里分别报告成功/失败类别。

### 3.5 Service

```kotlin
class BackupService(context: Context) {
    val stagingDir: File                                  // cacheDir/backup
    fun stageForRead(uri: Uri): File                      // 拷到 cacheDir 再按中央目录读
    fun openWriteSink(uri: Uri): OutputStream             // SAF CreateDocument
    fun displayName(uri: Uri): String?
    fun sweepStaleStaging()                               // 进页清扫
    companion object { fun utcCompactNow(): String }
}
```

### 3.6 ViewModel 状态机

```kotlin
data class BackupUiState(
    val busy: Boolean = false,
    val available: Set<BackupCategory> = emptySet(),   // 本机有内容的类别
    val exportSelection: Set<BackupCategory> = …全选…,
    val includeApiKeys: Boolean = false,
    val staged: BackupContents? = null,                // 已校验的待导入包
    val importSelection: Set<BackupCategory> = emptySet(),
    val receipt: String? = null,
)
```

- 口令**不进状态、不落盘、不写日志**，只存在于 `DataSettings` 的局部 `remember`。
- 导出：`refreshAvailability()` → 勾选 → 口令两次 → 告知弹窗 → SAF → `runExport`。
- 导入：SAF → `inspectPackage(uri, pwd)` → 展示清单 → 勾选 → 强制确认 → `runImport`。

---

## 4. 依赖与构建

- 新增 `net.lingala.zip4j:zip4j`（版本写进 `gradle/libs.versions.toml`）。
- **`NOTICE.md` 必须补**：zip4j 为 Apache-2.0，随 APK 分发，需要按其要求声明（这是既有发版合规流程的一部分）。
- 无需新增权限：SAF 走 `CreateDocument`/`OpenDocument`，不需要存储权限。

---

## 5. 错误与文案

| 场景 | 文案要点 |
|---|---|
| 口令错误/包损坏 | 「口令错误或文件已损坏」——不区分二者，不泄露包内信息 |
| format/schema 不支持 | 「这个备份由不支持的版本创建（format X，本应用支持 Y）」+ 建议升级 App |
| 条目缺失/未登记 | 「备份包结构不完整」 |
| 文本条目超限 | 「备份中的文本条目过大（超过 64 MiB）」 |
| 空间不足 | 「写入失败，请检查可用空间」 |
| 用户取消 | 「已取消，没有写出/改动任何文件」 |
| 导入后 | 「已恢复：设置、对话历史…（记忆库失败：原因）」 |

---

## 6. 测试设计

| 层 | 用例 |
|---|---|
| `BackupFormatTest` | format 不等拒绝、schema 不等拒绝、未知路径拒绝、类别↔路径映射、manifest 往返 |
| `SettingsWhitelistTest` | 白名单外的键不进包、Optional Key 三键受开关控制、类型正确性、未知键在导入时忽略 |
| `BackupExporterTest` | 文本+文件混合写出、明文不出现在字节里、口令能解、错误口令打不开、manifest 条目数与实际一致 |
| `BackupInspectorTest` | 正常包解析、口令错、缺 manifest、未登记条目、重复类别、超 64 MiB 文本闸 |
| `BackupImporterTest` | 各类别覆盖（原有数据 / 空库两种）、单类别失败不影响其它、compaction 的 coveredToMessageId 失效重置、壁纸路径安全化 |
| `BackupRoundTripTest` | 全类别导出→导入→比对：消息条数与关键字段、记忆条数与类型、设置值、压缩状态 |
| `BackupServiceTest` | 文件名生成、显示名兜底、清扫 |
| 安全 | 全流程日志不含口令、Key、对话正文（grep logcat 断言） |

---

## 7. 实施顺序（建议）

1. `BackupFormat` + `SettingsWhitelist` + 单测（纯 JVM，无 Android 依赖）。
2. `BackupCodec`（三份 payload 模型）+ 往返单测。
3. `BackupExporter` / `BackupInspector`（zip4j）+ 单测。
4. `BackupImporter` + 事务与失败语义单测（用 `InMemoryConversationRepository` 等既有测试替身）。
5. `BackupService` + `AppContainer` 装配。
6. `BackupViewModel` + `DataSettings` UI。
7. `NOTICE.md` 补 zip4j、隐私政策补备份章节、`docs/README.md` 登记本文、CHANGELOG 补条目。
8. 真机验收：导出→改数据→导入→核对；错误口令；取消路径；大壁纸。

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 导入把库写坏 | 按类别事务 + 导入前取快照（可选：提供「导入前自动导出一次」作为无痛兜底） |
| 口令遗忘导致包报废 | UI 明示「无法找回」；弱口令只提示不拦（用户自担） |
| zip4j 的 API 与直觉不符 | 已在 §3.2 写明：只有 `ZipOutputStream` 能往任意流写；`ZipParameters` 无 `isCompressed` |
| 未来新增设置键被意外导出 | 白名单是显式枚举，新增键默认不进包，有单测守护 |
| 未来备份格式升级 | 遵循 `docs/legacy-compat.md` §8：格式升级与导入兼容 step 同步提供 |
