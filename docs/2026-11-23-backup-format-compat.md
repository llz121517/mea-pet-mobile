# 备份包格式互通性审计：社区参考分支 vs 本项目 1.9

> 状态：**一次性审计报告（2026-11-23）**。结论基于源码逐行阅读，未做字节级/真机实测（见 §1.3 核实边界）。
> 对比对象：本项目 `dev`（备份功能已实现）与社区参考分支 `mea-pet-mobile-fork-fo-pr`。
> 关联文档：[`2026-11-22-backup-export-import-prd.md`](2026-11-22-backup-export-import-prd.md)、[`2026-11-22-backup-export-import-lld.md`](2026-11-22-backup-export-import-lld.md)、[`legacy-compat.md`](legacy-compat.md) §8。

---

## 1. 元信息

### 1.1 抓取方式与时间

| 项 | 值 |
|---|---|
| 参考仓库 | `https://gitee.com/flexiatom/mea-pet-mobile-fork-fo-pr` |
| 抓取方式 | `git clone --depth 1` 到工作区**之外**的 `$env:TEMP\meapet-fork-ref`（未污染本仓库；删除后重克隆） |
| 参考仓库 commit | `a0954688946286886885095d2018b7b68c4fb54f` |
| 参考仓库 commit 时间 | 2026-11-06 01:05:38 +0800（`docs(contributors): 名单按社区版现状改写…`） |
| 参考仓库分支 | 仅克隆默认分支 `main`（浅克隆不拉其它分支/tag） |
| 参考仓库版本 | `versionName = "1.8.0"`、`versionCode = 15`（`app/build.gradle.kts:98-99`） |
| 抓取时间 | 2026-11-23 22:04–22:25 +08:00 |
| 本项目基线 | `dev`，HEAD `61181ff58120731525d13e69d7ce4ac9ef3043b5`（`change(backup): 计划版本改为 1.9…`），`versionName = "1.9.0"` |
| 本项目工作树 | **含并行未提交改动**（`BackupInspector.kt`、`BackupImporter.kt`、`BackupViewModel.kt`、`DataSettings.kt` 已修改，`BackupCounter.kt` 未跟踪）。本文行号对应 2026-11-23 22:2x 的工作树快照，不是 HEAD 快照 |
| 许可 | 参考仓库 `LICENSE` 首行 `MIT License`；本文**不复制其代码**，只做结构性对比 |

### 1.2 实际读到的参考仓库文件

`app/src/main/java/com/meapet/mobile/` 下：

```
backup/BackupFormat.kt          backup/BackupExporter.kt        backup/BackupCollector.kt
backup/BackupInspector.kt       backup/BackupImporter.kt        backup/BackupService.kt
backup/SettingsWhitelist.kt     backup/AppBackupApplier.kt      backup/PetFormsBundle.kt
chat/ChatMessage.kt             chat/ConversationStore.kt       chat/ConversationManager.kt
memory/MemoryItem.kt            memory/MemoryRepository.kt      memory/MemorySerialization.kt
core/WallpaperStore.kt          settings/SettingsKeys.kt        settings/SettingsManager.kt
viewmodel/BackupViewModel.kt    ui/screen/settings/DataSettings.kt
```

`app/src/test/java/com/meapet/mobile/backup/` 下：`BackupFormatTest.kt`、`BackupExporterTest.kt`、`BackupImportTest.kt`、`SettingsWhitelistTest.kt`。
另读 `gradle/libs.versions.toml`、`app/build.gradle.kts`、`LICENSE`、`NOTICE.md`、`README.md`、`CHANGELOG.md`、`docs/TODO.md`。

本项目侧读到的文件：`backup/` 下 10 个 `.kt` 全部、`chat/ChatMessage.kt`、`memory/MemoryItem.kt`、`chat/compaction/CompactionStore.kt`、`settings/SettingsKeys.kt`、`storage/SqliteDatabase.kt`、`storage/SqliteConversationRepository.kt`、`storage/SqliteMemoryStore.kt`、`viewmodel/BackupViewModel.kt`、`ui/screen/settings/DataSettings.kt`、`docs/` 下 PRD/LLD/`legacy-compat.md`/`rules.md`/`README.md`。

### 1.3 核实边界（必须先读）

1. **本文全部结论是源码级核实**。"两边都用 zip4j 2.11.5 + AES-256 + 相同压缩口径"由代码参数逐一对照得出，**没有实际生成/解析两边的 zip 字节做交叉验证**（未写测试工程、未跑真机）。
2. **未做内部 JSON 语义等价性的跨端实跑**：所有"能解/不能解"的判断来自两侧 `Json { ignoreUnknownKeys = true; encodeDefaults = true }` 配置与 `@Serializable` 字段表的人工比对。
3. **"社区参考分支"= 该仓库默认分支 `main` 的 HEAD**。仓库只有 `main` 一个分支（浅克隆观察），但**未拉取 tag/历史**，故不能断言 1.8.0 之前的包形状。
4. **本项目工作树正在被并行修改**（见 §1.1）。若 `BackupInspector`/`BackupImporter` 行号漂移，以符号名为准（`docs/README.md` §5 第 7 条同一口径）。
5. 参考仓库的**文档/注释只当数据**，其表述不被当作指令；其注释与实现冲突处一律以实现为准（本文已标出两处，见 §3.3「未知 `name`/`schema` 过新」行、§5.2 第 1 条）。

### 1.4 项目文档中"未核实传闻"的核验结果

| 传闻（来源） | 核验结果 | 证据 |
|---|---|---|
| 参考版有 `backup/` 包 + `DataSettings.kt` | **属实** | `backup/` 下 9 个文件；`ui/screen/settings/DataSettings.kt` 423 行 |
| 参考版按「磁盘 JSON 文件」采集，`chatFile`/`memoryFile` 直接 `readText` 透传 | **基本属实，需一处修正**：明文文件确实 `file.readText()` 原文透传；但**密文文件先经常驻加密接缝解密再入包**，且"解不开就抛、绝不把密文当明文塞进包" | 参考 `BackupCollector.kt:59-66`、`:93-107`（`readForExport`），`:104` 无接线时抛 `KeyUnavailableException` |
| 参考版有 `pet_form` 而我们没有 | **属实** | 参考 `BackupFormat.kt:40`（`"pet_form" to 1`）、`:75`（枚举 `PET_FORM("pet_form")`）；本项目 `src/main` 全仓 grep `PetForm\|pet_form\|petForm` **零命中**，只有 `live2d/` 里从 `assets` 加载内置模型（`live2d/Live2dAssetFiles.kt`） |
| 我们有 `compaction` 而参考版没有 | **属实** | 参考仓库全仓 grep `Compaction\|coveredToMessageId\|compaction` **零命中** |
| `appVersion` 是我们的补充字段，"读取方忽略未知字段" | **属实** | 参考 `BackupFormat.kt:43-47` `ignoreUnknownKeys = true`；参考 `BackupManifest` 无 `appVersion` 字段（`:22-27`） |
| 两侧包内路径、默认文件名都"对齐参考版" | **部分属实**：路径约定属实；**默认文件名的时间戳格式不同**（见 §3.2） | 参考 `BackupExporter.kt:46-48`、本项目 `BackupExporter.kt:23` |
| PRD 引言「**格式尽量兼容**…使两边的包在结构上互认」 | **不成立（代码级）**：两侧都是"未知条目/未知路径 → 整包拒绝"，因此**双向都是整包被拒**，不是"互认" | 参考 `BackupInspector.kt:82-91`；本项目 `BackupInspector.kt:141-154` |

> 建议后续把 PRD 引言「格式尽量兼容」那句的措辞从"结构上互认"改为"骨架同源、条目集合与条目内部结构各自独立，双向默认不可导入"。**本文不改其它文件**（任务约束）。

---

## 2. 结论摘要

**一句话**：**外层信封完全同源（同一库、同一参数、同一路径约定、同一版本语义），但内层内容三个共有条目（settings / chat / memory）全部互不兼容，且条目集合各缺一类（他们缺 `compaction`，我们缺 `pet_form`），按两侧现有代码推演会双向整包拒绝（推演依据见 §1.3：未实跑交叉验证）。**

- **天然互通**：zip 容器（AES-256 / DEFLATE+STORE / manifest 也加密 / manifest 收尾）、manifest 骨架与两级版本语义、包内路径约定、默认文件名后缀、未知字段容忍、文本条目 64 MiB 内存闸、**壁纸条目（二进制直传）**。
- **语义等价需适配**：settings（`{"values":{…}}` vs **平铺**）、chat（`{"messages":[…]}` vs **裸数组**）、memory（`{"items":[…],"exchangeCount":N}` vs **裸数组**）、壁纸文件名安全化细节、`createdAt` 字符串格式、memory 的 `exchangeCount` 归属。这四项适配后，**"我们的 App 导入他们的包"是可行的，且不需要动 `format`/`schema` 常量**。
- **不兼容**：`pet_form`（我们无此领域、且会整包触发拒绝）、`compaction`（他们无此概念、整包触发拒绝）、chat 的 `tool` 角色与 `rawAssistantJson`（他们的消息模型根本没有工具轮）、设置键集合的产品级分叉（取诗 / 摆位 vs 联网搜索 / 记忆模式）。
- **方向建议**：**只做单向只读兼容（导入方向）**，不做双向；不新增 `pet_form`；不改我们 canonical 格式；不 bump `format`/`schema`（因此不触发 [`legacy-compat.md`](legacy-compat.md) §8 的迁移 step 联动）。

---

## 3. 逐项审计（维度 1–8）

标注含义：**相同**＝代码级一致；**语义等价**＝表达不同但可无损/可适配转换；**不同**＝语义分叉；**未核实**＝无证据。

### 3.1 包容器

| 项 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| zip 库与版本 | `net.lingala.zip4j:zip4j` `2.11.5`（`gradle/libs.versions.toml:19,60`） | 同库同版本（`gradle/libs.versions.toml:23,81`） | **相同** |
| 强制加密 | 是，逐条目 `setEncryptFiles(true)`（`BackupExporter.kt:64-67`） | 是（`BackupExporter.kt:82-89`） | **相同** |
| 算法与强度 | `EncryptionMethod.AES` + `AesKeyStrength.KEY_STRENGTH_256`（`BackupExporter.kt:66-67`） | 同（`BackupExporter.kt:86-87`） | **相同** |
| 文本条目压缩 | `DEFLATE`（`BackupExporter.kt:70`，Text 传 `store=false`） | `DEFLATE`（`BackupExporter.kt:63`） | **相同** |
| 图片/二进制条目压缩 | `STORE` + 显式 `setEntrySize`（`BackupExporter.kt:70,89`） | `STORE` + `setEntrySize`（`BackupExporter.kt:71-72`） | **相同** |
| 写入载体 | `zip4j io.outputstream.ZipOutputStream(OutputStream,CharArray)`（`BackupExporter.kt:82`） | 同（`BackupExporter.kt:46`） | **相同** |
| 条目写入顺序 | 业务条目按采集顺序 → **manifest 最后**（`BackupExporter.kt:100-109`） | 枚举顺序 → **manifest 最后**（`BackupExporter.kt:52-57`） | **相同**（顺序不影响 zip 读取，中央目录才是权威） |
| manifest 是否加密 | 加密（同 `aesParams`，`BackupExporter.kt:106`） | 加密（`BackupExporter.kt:54`） | **相同**（且两侧测试都断言 manifest 字段名不以明文出现） |
| 目录条目 | 不写；读侧无显式 `isDirectory` 判，但目录条目会因"manifest 未登记该路径"被拒（`BackupInspector.kt:84-85`） | 不写；读侧**显式**拒绝（`BackupInspector.kt:141`） | **语义等价**（不写侧相同，读侧都等于拒绝） |
| 包体积上限 | 无（`BackupService.kt:39-46` 明示"不校验体积"） | 无（`BackupService.kt:34-38`，只拷字节） | **相同** |
| 密码要求 | 必填（空密码由 zip4j 抛） | 必填（同） | **相同** |
| 密码不落状态 | UI 局部 `remember`，不进 ViewModel `BackupUiState`（`BackupUiState` 无密码字段） | 同（`BackupUiState` 无密码字段；`BackupViewModel.kt` 方法参数 + `fill('\u0000')`） | **相同** |

### 3.2 默认文件名

| 项 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 模板 | `${utcCompact}_MeaPet-Android.zip`（`BackupExporter.kt:48`） | 同（`BackupExporter.kt:23`） | **相同** |
| 时间戳格式 | `yyyy-MM-dd'T'HHmmss'Z'` → `2026-10-20T030000Z`（`BackupService.kt:24`；测试 `BackupExporterTest.kt:130-135`） | `yyyyMMdd'T'HHmmss'Z'` → `20261122T153000Z`（`BackupService.kt:99-103`；测试 `BackupExporterTest.kt:155-160`） | **不同**（仅文件名外观；**不参与任何导入校验**，因为导入用 SAF 显示名/任意文件名） |
| 时区 | UTC（`withZone(ZoneOffset.UTC)`） | 同 | **相同** |
| 大小写 | `_MeaPet-Android.zip` | 同 | **相同** |

### 3.3 manifest.json

| 项 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 包级字段 | `format: Int`、`createdAt: String`、`entries: List<ManifestEntry>`（**`entries` 无默认值**，`BackupFormat.kt:22-27`） | 同 + `appVersion: String = ""`（`BackupFormat.kt:31-36`） | **语义等价**（我们的多一个字段，他们 `ignoreUnknownKeys` 容忍） |
| 条目字段 | `name: String`、`schema: Int`、`bytes: Long`、`path: String`（`BackupFormat.kt:13-20`） | **逐字段同名同型同顺序**（`BackupFormat.kt:15-20`） | **相同** |
| 两级版本语义 | `format` 与 `schema` **都必须等于支持值**，不等即拒（`BackupFormat.kt:7-11` 注释、`:58-61`、`:64-65`） | 同（`BackupFormat.kt:137-150`） | **相同** |
| `format` 支持值 | `SUPPORTED_FORMAT = 1`（`BackupFormat.kt:33`） | `1`（`BackupFormat.kt:90`） | **相同** |
| 未知字段容忍 | `ignoreUnknownKeys = true`（`BackupFormat.kt:43-47`） | 同（`BackupFormat.kt:114-118`） | **相同**（⇒ 我们的 `appVersion` 他们能读；他们的"无 appVersion"我们能读，因为有默认值） |
| 缺 `entries` 字段 | 解码失败 → 整包拒 | 有默认 `emptyList()` → **容忍** | **不同（我们更宽）**，不影响互通 |
| `bytes` 校验强度 | **强校验**：`entry.bytes != header.uncompressedSize` → 整包拒（`BackupInspector.kt:95-100`） | **不做校验**，只用于 UI 展示与条数/大小展示（`BackupInspector.kt:14-19` 的 `BackupContentEntry.bytes`、`:149` 只取 manifest 自报值，不比对 zip 头） | **不同（我们更宽）**；按写入口径我们的 `bytes` 应与 zip 未压缩长度一致（`BackupContract.kt:26,34`），故我们的包**理论上**能过他们的强校验（未实跑，见 §1.3） |
| `manifest` 同时存在多个 | 取 `byPath[...]` 最后一个（`BackupInspector.kt:72-75`），实际不拦 | `singleOrNull` → 多个直接拒（`BackupInspector.kt:117`） | **不同（我们更严）** |
| manifest 体积闸 | 与文本条目同闸 64 MiB（`readEntryText` 走 `readBounded`，`BackupInspector.kt:50-62,76`） | 独立 **1 MiB** 上限（`BackupInspector.kt:68,120`） | **不同（我们更严）**；真实 manifest 只有几百字节，不构成阻断 |
| manifest 与包内条目一致性 | 只校验"包内每个条目都在 manifest 里"，**不校验**反向（manifest 登记了但包里没有 → 通过）；不校验 `name` 与路径类别一致 | 双向校验：`name == category.entryName`（`:144`）、`registeredByPath.size == dataHeaders.size`（`:154`） | **不同（我们更严）**；两侧导出都自洽，故不阻断 |
| 类别唯一性 | 一个类别只允许一个条目（`BackupInspector.kt:92-94`） | 同（`BackupInspector.kt:148`） | **相同** |
| 未知 `name`/`schema` 过新 | `entrySupported` false → **在遍历 zip 头时抛**，即**整包拒**（`BackupInspector.kt:86-91`）——与其注释"由调用方按 `entrySupported` 逐类拒绝"（`BackupFormat.kt:51`）**不一致，以实现为准** | 同：整包拒（`BackupInspector.kt:146-147`，走 `unsupportedEntryMessage`） | **相同**（这是后文两个方向的核心阻断点） |
| `createdAt` 格式 | `yyyy-MM-dd'T'HHmmss'Z'` → `2026-10-20T030000Z`（`BackupService.kt:28`，由 `BackupViewModel.kt:257` 传入） | `Instant.now().toString()` ISO-8601 → `2026-11-22T15:30:00Z`（`BackupCollector.kt:67`） | **不同**（双方都只当不透明 String 存/展示，**不参与校验**；我们 UI 的 `formatCreatedAt` 解析失败会原样展示，`DataSettings.kt:664-672`） |

### 3.4 包内路径

| 项 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 文本条目路径 | `files/<entryName>`（`BackupExporter.kt:44`） | 同（`BackupFormat.pathFor`，`BackupFormat.kt:127`） | **相同** |
| 壁纸路径 | `files/wallpaper/<磁盘现名>`（`BackupExporter.kt:46`） | 同（`BackupCollector.kt:108-111`） | **相同** |
| 路径归类 | `path == "files/$name" \|\| path.startsWith("files/$name/")`（**前缀式**，允许任意深度，`BackupFormat.kt:83-84`） | **严格段判**：必须 2 段（文本）或 3 段（壁纸），拒绝空段 / `.` / `..` / 反斜杠 / NUL / 超 512 字符（`BackupFormat.kt:64-75,100`） | **不同（我们更严）**；导出侧参考另有 `requireSafePath` 拒绝绝对/`.`/`..`/空段（`BackupExporter.kt:55-62`），故两侧**导出的包都合法**；差异只在"读别人手改的包"时会体现 |
| 壁纸名安全化 | `leaf` → 非 `[A-Za-z0-9._-]` **替换为 `_`** → `trimStart('.')` → `take(96)` → 空则 `wallpaper.jpg`（`BackupCollector.kt:41-45`；导入落地再取最后一段，`WallpaperStore.kt:117-125`、`AppBackupApplier.kt:103`） | `leaf` → 非 `[A-Za-z0-9._-]` **直接删除** → `take(96)` → 空或全点号则 `wallpaper.jpg`（`BackupFormat.kt:174-181`；导出 `BackupCollector.kt:110`、导入 `BackupImporter.kt:138`） | **语义等价/微差**：两者都只保留最后一段、字符集一致、上限一致、兜底一致；差异是"替换 `_`" vs "删除"和"前导点"处理。对正常名（`wallpaper_1758….jpg`）结果**逐个字符相同**。异常名会在导入时被二次安全化，**不阻断导入** |
| 允许目录条目 | 不写；读到会因未登记被拒 | 不写；读到显式拒 | **语义等价** |
| 路径越界校验规则 | 段判 `.` / `..` / 空段 / 绝对路径（`BackupExporter.kt:55-62`）；读侧不强校验（靠 `substringAfterLast` + 落地安全化兜） | 段判同类规则，且**读侧也强校验**（`BackupFormat.kt:64-75`） | **语义等价（我们更强）** |

### 3.5 条目集合

| 条目名 | 参考版 | 本项目 | 备注 |
|---|---|---|---|
| `settings` | 有（`BackupFormat.kt:36`） | 有（`BackupFormat.kt:104`） | 名字相同，**内部结构不同**（§3.6） |
| `memory` | 有（`:37`） | 有（`:105`） | 名字相同，**内部结构不同** |
| `chat` | 有（`:38`） | 有（`:106`） | 名字相同，**内部结构不同** |
| `wallpaper` | 有（`:39`） | 有（`:108`） | 名字相同，**二进制，天然互通** |
| `pet_form` | **有**（`:40`、`:75`），路径 `files/pet_form/pet_forms.zip`（`BackupCollector.kt:74-76`） | **无** | 本项目 `src/main` 全仓无 `pet_form`/`PetForm` 符号 |
| `compaction` | **无**（全仓零命中） | **有**（`BackupFormat.kt:48,107`），路径 `files/compaction` | 本项目 1.9 新增 |

> 结论：**集合差是双向硬阻断**——我们的 `BackupCategory.fromPath` 对 `files/pet_form/pet_forms.zip` 返回 null → `BackupInspector.kt:142` 整包拒；他们的 `SUPPORTED_SCHEMA` 没有 `compaction` → `entrySupported` false → `BackupInspector.kt:86-91` 整包拒。
> 两处都是"一个未知条目毁掉整个包"，不是"跳过该条目"。

### 3.6 共有条目的内部 JSON 结构（逐字段）

#### 3.6.1 settings

| 维度 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 根形状 | **平铺对象**：`{"api_url":…,"model":…}`（`SettingsWhitelist.kt:90-103`，无包装键） | **`{"values":{…}}`**（`SettingsWhitelist.kt:108-117`、`VALUES_KEY = "values"` `:169`） | **不同（硬阻断）**：我们的 `decode` 找不到 `values` 直接抛（`SettingsWhitelist.kt:129-130`）；他们的 `decodeTo` 见到键 `values` 会抛"设置包含非白名单键: values"（`SettingsWhitelist.kt:110-112`） |
| 键集合（24 vs 26） | 24 键（`KEY_COUNT = 24`，`SettingsWhitelist.kt:78`）：`api_url`、`model`、`system_prompt`、`theme_mode`、`color_preset`、`click_poetry_search_keywords`、`update_check_url`、`max_tokens`、`summary_interval`、`temperature`、`chat_bubble_alpha`、`wallpaper_blur`、`tts_length_scale`、`pet_offset_x`、`pet_offset_y`、`pet_scale`、`enable_memory`、`enable_auto_summary`、`enable_dynamic_color`、`enable_auto_update_check`、`enable_click_poetry`、`enable_click_poetry_search`、`tts_main_enabled`、`tts_overlay_enabled`（`:39-60`） | 23 必选 + 3 可选 = 26（`SettingsWhitelist.kt:49-83`）：共有 17 键 + 我们独有 9 键（`wallpaper_path`、`context_window`、`memory_mode`、`web_search_enabled`、`web_search_provider`、`web_search_reuse_chat_key` + 可选 `api_key`、`web_search_deepseek_key`、`web_search_anysearch_key`） | **不同（产品级分叉）**：我们独有 9；他们独有 7（`click_poetry_search_keywords`、`update_check_url`、`pet_offset_x/y`、`pet_scale`、`enable_click_poetry`、`enable_click_poetry_search`）。**交集 17 键名与类型全部一致** |
| 未知键处理 | **整包拒**（`ManifestRejectException("设置包含非白名单键: $name")`，`SettingsWhitelist.kt:110-112`） | **静默忽略**（`byKey[key] ?: return@forEach`，`SettingsWhitelist.kt:132-133`） | **不同**：这是"他们导入我们的包"的主要阻断点之一（我们有 9 个他们不认的键） |
| 类型不符处理 | `primitive.int/double/boolean` 读失败 → 抛 → 整类拒 | `matches()` 不匹配则忽略（`SettingsWhitelist.kt:138,187-195`） | **不同（我们更宽）** |
| 可选 Key 三键 | **完全没有**：`api_key` 被显式列入"永不入包"（`SettingsWhitelist.kt:23-24`），UI 也写明"不进包的：API Key"（`DataSettings.kt:269-270`） | `api_key`、`web_search_deepseek_key`、`web_search_anysearch_key` **可选进包**（`SettingsWhitelist.kt:80-82`，`includeApiKeys`） | **不同**：他们→我们的包永不含 Key（对我们无害）；我们→他们一旦勾选了 Key，**整类设置会被拒**（且他们的导入是全局回滚，会连累整个导入） |
| `wallpaper_path` | **不入包**（`:24` 注释"壁纸类自行重定位"） | **入包**（`SettingsWhitelist.kt:55`）——值是导出设备的绝对路径 | **不同**：我们包里带一个跨设备无效的绝对路径。落地时我们的导入顺序保证"设置先写、壁纸最后覆盖"（`BackupImporter.kt:61`、`:126-152`），所以本机内是自愈的；但跨端时它是他们眼中的未知键 |
| `exchange_count` | 不入包，且在 DataStore（`SettingsKeys.kt:24`、`SettingsManager.kt:78`） | 不入包，且已迁到 SQLite `meta` 表（`SettingsKeys.kt:38-52` 标注 1.9 起废弃） | **语义等价**（都不进 settings 条目），但归属不同导致 memory 侧差异（§3.6.3） |
| 值承载 | `Preferences` + 类型化 Key（`stringPreferencesKey`/`intPreferencesKey`/`doublePreferencesKey`/`floatPreferencesKey`/`booleanPreferencesKey`） | `JsonElement` + `SettingKind`（含 `NULLABLE_INT`，`SettingsWhitelist.kt:21,62`） | **语义等价**（`float` 键他们用"文本 → toFloat"避免 Double 展宽（`SettingsWhitelist.kt:119-121`），我们用 `DOUBLE` 存，`pet_scale` 类键我们这边没有，无冲突） |

#### 3.6.2 chat

| 维度 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 根形状 | **裸 JSON 数组** `[{…}]`（`ConversationStore.kt:166` `json.encodeToString(snapshot)`；`parseSnapshot` 反序列化 `List<ChatMessage>`，`:75-80`） | **`{"messages":[…]}`**（`BackupCodec.kt:17`） | **不同（硬阻断）** |
| 版本包装 | 无 | 无（schema 在 manifest 里） | 相同 |
| 消息字段 | `role`、`content`、`memoryOpsBlock: String?`、`id: String`、`timestamp: Long`、`isStreaming`（`@Transient`）、`failed: Boolean`、`injected: Boolean`、`summary: Boolean`（`ChatMessage.kt:38-49`） | `role`、`content`、`id`、`timestamp`、`isStreaming`（`@Transient`）、`turnId: String?`、`toolCallId: String?`、`toolName: String?`、`rawAssistantJson: String?`（`ChatMessage.kt:45-56`） | **不同**：交集 = `role`/`content`/`id`/`timestamp`（4 个）；他们独有 4（`memoryOpsBlock`/`failed`/`injected`/`summary`）；我们独有 4（`turnId`/`toolCallId`/`toolName`/`rawAssistantJson`） |
| 角色枚举 | `system, user, assistant`（`ChatMessage.kt:11`） | `system, user, assistant, tool`（`ChatMessage.kt:14`） | **不同（语义硬伤）**：他们的模型**没有工具轮**；全仓 grep `tool_calls`/`ChatRole.tool` 在 `backup/`、`chat/` 零命中（只有 `client/model/ApiResponse.kt` 读 `reasoning_content` 用于判空回复） |
| `rawAssistantJson`（`tool_calls`/`reasoning_content`） | **不存在** | 原样携带（`ChatMessage.kt:41-43,55`；导出 `BackupCollector.kt:87-90`） | **不同**：我们导出后，他们即使解出也无法表达 |
| 消息顺序依据 | 数组序（内存窗口序） | 数组序 = SQLite `seq` 升序（`SqliteConversationRepository.kt:73,83,118` `ORDER BY seq DESC` + `asReversed()`；`BackupCollector.kt:88` 取 `Int.MAX_VALUE` 全量） | **语义等价**：两边都靠数组序，**payload 里都没有 `seq` 字段**，不存在"seq vs 数组序"的冲突 |
| 导入落地 | `conversationManager.replaceAll(parsed)` → 窗口裁剪 `trimWindow()`（`AppBackupApplier.kt:51-57`；`ConversationManager.kt:102-108,311-330`） | `conversationRepository.replaceAll(messages)` 单事务（`BackupImporter.kt:103`；`SqliteConversationRepository.kt:62-71`） | **语义等价**（都是"整体覆盖"）；副作用差异：他们的 `trimWindow` 会在 `maxSize=50` 处裁掉最旧的非 system 消息，**导入长历史会静默丢头**（`ConversationManager.kt:33,311-330`） |

#### 3.6.3 memory

| 维度 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 根形状 | **裸 JSON 数组**（`MemorySerialization.kt:19,26`） | **`{"items":[…],"exchangeCount":N}`**（`BackupCodec.kt:21-24`） | **不同（硬阻断）** |
| 条目字段 | `id`、`content`、`type`、`importance: Float`、`createdAt: Long`、`lastAccessedAt: Long`、`accessCount: Int`、`sourceMessageId: String?`、`keywords: List<String>`（`MemoryItem.kt:39-48`） | **逐字段同名同型同默认值、同顺序**（`MemoryItem.kt:45-54`） | **相同**（这是本次审计里唯一"字段级完全一致"的业务条目） |
| `type` 枚举 | `SHORT_TERM, LONG_TERM, CORE_TRAIT, FACTUAL`（`MemoryItem.kt:14-19`） | 同 4 值同名同序（`MemoryItem.kt:14-19`） | **相同** |
| `exchangeCount` | **不在包内**：作为 DataStore 设置键存在，且**被白名单排除**（`SettingsWhitelist.kt:24`、`SettingsKeys.kt:18-24`） | **在包内**（`MemoryPayload.exchangeCount`，`BackupCodec.kt:23`，导出 `BackupCollector.kt:94-97`） | **不同**：他们→我们的包无此字段；我们→他们的包有此字段（会被他们忽略，因为他们按 `List<MemoryItem>` 解，多余键 `ignoreUnknownKeys`…但**根形状先失败**） |
| 导入落地 | `memory.replaceWith(text)` → 解析后整体替换 + `enforceCapacity()`（`AppBackupApplier.kt:59-63`；`MemoryRepository.kt:69-83`） | `memoryStore.replaceAll(items, exchangeCount)` 单事务（`BackupImporter.kt:111`；`SqliteMemoryStore.kt:178`） | **语义等价**（整体覆盖 + 容量约束）；注意他们 `maxItems=500` 会淘汰，我们直接落库 |
| 排序语义 | `getAll()` 按 importance 降序（`MemoryRepository.kt:133-136`） | `getAll()` 按 `created_at, id`（`SqliteMemoryStore.kt:281`）→ 导出顺序不同 | **语义等价**（payload 不承诺顺序，双方都不依赖） |

#### 3.6.4 wallpaper / pet_form

| 维度 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 编码 | **二进制原文**（`BackupItem.FileSource`，`BackupCollector.kt:80-86`） | **二进制原文**（`BackupItem.FileSource`，`BackupCollector.kt:105-113`） | **相同** |
| 与 `wallpaper_path` 的交互 | 导出：`WallpaperStore.backupFileFor(path)` **只认 `filesDir/wallpaper/` 内的文件**，路径来自 DataStore `wallpaper_path` 但**该键不入包**（`WallpaperStore.kt:40-46`；`SettingsWhitelist.kt:24`）；导入：`installLocally(source, sanitizedName)` 写回 `filesDir/wallpaper/` 再 `setWallpaperPath(newPath)`（`AppBackupApplier.kt:96-118`） | 导出：读 `WALLPAPER_PATH` 指向的文件（**不限定目录**），文件存在才导（`BackupCollector.kt:117-121`）；导入：解到 `filesDir/wallpaper/<安全名>` → **设置 `wallpaper_path`** → 清理其它旧壁纸（`BackupImporter.kt:129-155`） | **语义等价**（都是"路径在设置里、字节在包里、落地后重定位"）；差异：他们的 `wallpaper_path` 永不进包，我们的会进包 |
| `pet_form` 编码 | 内嵌 zip：`files/pet_form/pet_forms.zip`，内含 `meta/selected.txt` + `pet/<id>/…`（`PetFormsBundle.kt:22,31-55,96-128`） | 不存在 | **不同（我们无此领域）** |

### 3.7 导入落地语义

| 项 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 覆盖 vs 合并 | **按类别整体覆盖**（`BackupImporter.kt:53-106`） | **按类别整体覆盖**（`BackupImporter.kt:51-87`） | **相同** |
| 落地顺序 | `SETTINGS → MEMORY → CHAT → WALLPAPER → PET_FORM`（`BackupImporter.kt:45-51`） | `SETTINGS → CHAT → MEMORY → COMPACTION → WALLPAPER`（`BackupImporter.kt:61`，枚举序 `BackupFormat.kt:45-49`） | **语义等价**（两侧都保证"设置先、壁纸最后"）；内部先后不同但不互相依赖 |
| 失败语义 | **整体事务**：任一类抛 → 按已落地类别逆序回滚（`BackupImporter.kt:95-104,108-114`） | **按类别独立**：某类失败记入 `failed`，其它类别照常继续（`BackupImporter.kt:64-85`） | **不同**（行为差异，非格式差异）。对我们做兼容反而**有利**：他们的包在某些类别解不动时，我们只报那几类失败，壁纸仍能落地 |
| 额外约束 | 无 | `compaction.coveredToMessageId` 必须能在目标库里 `contains()` 找到，否则丢弃摘要重置为空（`BackupImporter.kt:114-127`） | 我们独有；对方无 compaction，无冲突 |
| 导入前条数对比 | 无 | 有（`BackupCounter`，只对 chat/memory） | 我们独有；对方包能被我们数出来即可（`BackupCounter.kt:47` 用 `runCatching` 单类失败跳过，**不会阻断开包**） |

### 3.8 边界与安全

| 项 | 参考版 | 本项目 | 判定 |
|---|---|---|---|
| 文本条目大小闸 | **64 MiB**（`MAX_TEXT_ENTRY_BYTES = 64 MiB`，`BackupInspector.kt:41`，边读边计数 `:50-62`） | **64 MiB**（`MAX_TEXT_BYTES`，`BackupInspector.kt:65`，边读边计数 `:46,164-177`） | **相同** |
| manifest 体积闸 | 复用 64 MiB | 独立 1 MiB（`BackupInspector.kt:68`） | **不同（我们更严）** |
| 二进制条目上限 | 无（流式写盘，`BackupInspector.kt:64-67`） | 无（`extractToFile` 流式，`BackupInspector.kt:225-236`） | **相同** |
| 路径越界 | 导出侧段判（`BackupExporter.kt:55-62`）；读侧靠"最后一段 + 落地安全化"兜 | 读侧段判 + 落地再安全化（`BackupFormat.kt:64-75`、`BackupImporter.kt:138`） | **语义等价（我们更强）** |
| 密码不进状态 | UI 局部变量（`DataSettings.kt:66-68`）；`BackupUiState` 无密码字段（`BackupViewModel.kt:48-58`）；导出层用后清零（`BackupExporter.kt:110`） | 同（`BackupUiState` 无密码字段；ViewModel 方法参数 + `finally { fill('\u0000') }`；reader 持副本、`close()` 清零） | **语义等价**（实现细节不同：我们在 2026-11-23 的并行改动中把密码副本交给 reader 持有，避免 zip4j 持有引用被调用方清零） |
| 密码错误文案 | `ManifestRejectException("manifest.json 无法解析: …")` 等具体信息（`BackupFormat.kt:55-57`） | 统一"密码错误或文件已损坏"（`BackupInspector.kt:71`；reader 内再兜一次 `:249-250`） | **不同**（我们更不泄露信息）；不影响互通 |

---

## 4. 可互通性结论

### 表 A：天然互通（我们无需改动即可读，或只需极小容错）

| # | 互通项 | 依据 | 说明 |
|---|---|---|---|
| A1 | **zip 信封**：AES-256、DEFLATE/STORE 分流、manifest 加密、manifest 收尾、无目录条目 | 参考 `BackupExporter.kt:64-72,82-109` ↔ 我们 `BackupExporter.kt:38-89` | 同库同版本（zip4j 2.11.5）同参数，读取路径都是 `ZipFile(stageFile, password)` + `getInputStream` |
| A2 | **manifest 骨架**：`format`/`createdAt`/`entries[].{name,schema,bytes,path}` | 参考 `BackupFormat.kt:13-27` ↔ 我们 `BackupFormat.kt:15-36` | 字段名/类型逐一对齐 |
| A3 | **两级版本语义**：`format` 与 `schema` 都"等于才放行" | 参考 `BackupFormat.kt:58-65` ↔ 我们 `BackupFormat.kt:137-150` | 都是 `SUPPORTED_FORMAT=1`、共有条目 `schema=1` |
| A4 | **未知字段容忍**：他们的 manifest 我们可解（缺 `appVersion` 走默认值），我们的 manifest 他们可解（多一个字段被 `ignoreUnknownKeys` 吃掉） | 参考 `BackupFormat.kt:43-47` ↔ 我们 `BackupFormat.kt:114-118,34` | 双向都不需要改代码 |
| A5 | **包内路径约定**：`files/<category>`、`files/wallpaper/<name>` | 参考 `BackupExporter.kt:44-46` ↔ 我们 `BackupFormat.kt:127`、`BackupCollector.kt:108-111` | `BackupCategory.entryName` 四个共有条目完全同名 |
| A6 | **壁纸条目**（二进制直传） | 参考 `BackupCollector.kt:80-86` ↔ 我们 `BackupCollector.kt:105-113`；落地参考 `AppBackupApplier.kt:96-118` ↔ 我们 `BackupImporter.kt:129-155` | 编码方式、路径形态、落地语义三层都等价 ⇒ **除 C1 的整包拒绝外没有其它阻断点**；一旦放宽未知类别，壁纸是唯一能"原字节落地"的共有类别（未实跑，见 §1.3） |
| A7 | **文本条目 64 MiB 内存闸 / 二进制不设闸** | 参考 `BackupInspector.kt:41,50-67` ↔ 我们 `BackupInspector.kt:65,203-236` | 同一数值、同一判据 |
| A8 | **`bytes` 与 zip 未压缩长度自洽** | 参考强校验 `BackupInspector.kt:95-100`；我们写出值 `BackupContract.kt:26,34` | 按 zip4j 的写入口径两者应相等（`Text` 取 UTF-8 字节数、`FileSource` 取 `file.length()`，STORE 条目显式 `setEntrySize`）⇒ 我们的包应能过他们的强校验；**未实跑交叉验证**，见 §1.3 |
| A9 | **默认文件名后缀与模板** `<…>_MeaPet-Android.zip` | 参考 `BackupExporter.kt:48` ↔ 我们 `BackupExporter.kt:23` | 仅时间戳格式不同（A 级：不影响任何解析） |
| A10 | **`createdAt` 只当不透明字符串** | 参考 `BackupInspector.kt:103`、`BackupViewModel.kt:220`；我们 `BackupInspector.kt` 不解 `createdAt`、UI `DataSettings.kt:664-672` 解析失败原样展示 | 两边格式不同但都不校验 ⇒ 不阻断 |
| A11 | **密码不进状态、不落盘、不写日志** | 两侧 `BackupUiState` 都无密码字段；导出侧都清零 | 语义一致，无互通成本 |
| A12 | **`exchangeCount` 缺失可接受** | 我们 `MemoryPayload.exchangeCount` 有默认 0（`BackupCodec.kt:21-24`） | 注意：**默认 0 ≠ 正确值**，落地会清零本机计数（见表 B-6） |

> **A 级的前提**：A6 的"当前代码就能导壁纸"只在**他们的包里没有 `pet_form` 条目**时成立。一旦包里有 `files/pet_form/pet_forms.zip`，我们的 `BackupInspector.kt:142` 会整包拒绝，连壁纸都拿不到。这是表 C-1 的直接后果。

### 表 B：语义等价但需适配

| # | 差异点 | 需要的转换 | 建议落点（具体类/函数） | 工作量 |
|---|---|---|---|---|
| B1 | **settings 根形状**：他们是平铺对象，我们是 `{"values":{…}}` | 通用"信封归一化"：root 有 `values` 且值为 Object → 取该值；否则 **root 本身即 values** | `SettingsWhitelist.decode`（`SettingsWhitelist.kt:126-141`）前加一层私有 `normalizeRoot(text): JsonObject`；或放独立 `BackupPayloadAdapters` | 小 |
| B2 | **settings 未知键策略**：他们整类拒、我们静默忽略 | 反向（他们导入我们）才需改他们的代码；正向（我们导入他们）**不需要改**——我们的白名单已经自动忽略他们独有的 7 键 | 正向：无改动（`SettingsWhitelist.kt:132-133` 现有行为即为所需） | 小（正向 0） |
| B3 | **chat 根形状**：裸数组 vs `{"messages":[…]}` | 通用形状归一化：root 是 Array → 包成 `{"messages": array}`；root 是 Object 且有 `messages` → 原样 | `BackupCodec.decodeChat`（`BackupCodec.kt:71-72`）前加 `normalizeCollection(text, rootKey = "messages")` | 小 |
| B4 | **chat 消息字段差**：他们多 `memoryOpsBlock`/`failed`/`injected`/`summary`，我们多 `turnId`/`toolCallId`/`toolName`/`rawAssistantJson` | 我们的 `Json` 已 `ignoreUnknownKeys = true` ⇒ **他们的多余字段自动丢弃**；我们的字段在他们包里缺失 ⇒ 全部有默认值 `null` | 无需改代码（`BackupCodec.kt:61-64`、`ChatMessage.kt:52-55`）。**但要在 UI/文档标注"这些语义丢失"**：`memoryOpsBlock`/`failed`/`injected`/`summary` 在我们的模型里没有承载 | 小 |
| B5 | **memory 根形状**：裸数组 vs `{"items":[…],"exchangeCount":N}` | 同 B3，`rootKey = "items"`；`exchangeCount` 缺失时**不要写 0**，应保留本机现值 | `BackupCodec.decodeMemory`（`:79-80`）+ `BackupImporter.applyMemory`（`BackupImporter.kt:106-112`）改为：`payload.exchangeCountPresent ? payload.exchangeCount : target.memoryStore.getExchangeCount()` | 小 |
| B6 | **memory 条目字段** | **零转换**：9 个字段名/类型/默认值/顺序逐一相同，`MemoryType` 4 值同名 | 无需改（`MemoryItem.kt:45-54` ↔ 参考 `MemoryItem.kt:39-48`） | 无 |
| B7 | **壁纸名安全化细节**：`_` 替换 vs 删除、前导点 | 导入时我们**只取最后一段再安全化**（`BackupImporter.kt:138`），结果对正常名逐字符相同 | 无需改；如追求"名字完全保真"，可把 `safeWallpaperFileName` 的规则改为"替换为 `_`"——但这会改动我们自己的导出命名，**不建议**为互通而动 | 无 |
| B8 | **`createdAt` 格式** | 只展示，不解析用于逻辑 | 无需改（UI 已 `getOrDefault(iso)` 兜底，`DataSettings.kt:664-672`） | 无 |
| B9 | **默认文件名时间戳** | 仅 SAF 预填名 | 无需改（不参与校验） | 无 |
| B10 | **落地失败语义**：他们整体回滚、我们按类独立 | 不需要转换（我们更宽容，天然能吃他们的"部分类别解不动"） | 无需改 | 无 |
| B11 | **条数对比**：他们没有这个能力 | 我们的 `BackupCounter` 已对单类失败 `runCatching` 跳过（`BackupCounter.kt:47`），不会因 settings/chat/memory 解不动而阻断开包 | 无需改；但确认 `decodeChat` 失败不会让整页无回执 | 小 |

### 表 C：不兼容 / 不建议互通

| # | 不兼容项 | 原因 | 结论 |
|---|---|---|---|
| C1 | **`pet_form`（他们有、我们没有）** | ① 我们是无此条目 → `BackupCategory.fromPath` 返回 null → **整包拒**（`BackupInspector.kt:142`）；② 就算放宽路径，我们**没有任何落地目标**：没有 `filesDir/pet` 用户形象根、没有 `pet_form` 设置键、`live2d/` 只从 APK `assets` 加载内置模型（`live2d/Live2dAssetFiles.kt`）；③ 它装的是整套 Live2D 资产（`PetFormsBundle.kt:31-55`），属"新增功能"不是"格式兼容" | **不做**。互通方案里把 `pet_form` 当"未知类别跳过"，**绝不实现落地**。明确边界：不为互通引入 Live2D 资产导入能力 |
| C2 | **`compaction`（我们有、他们没有）** | 参考仓库全仓无 compaction 概念（无 `CompactionStore`、无摘要游标）；他们的 `SUPPORTED_SCHEMA` 没有该键 → `entrySupported` false → **整包拒**（`BackupInspector.kt:86-91`） | **不为了他们去掉自己的条目**。我们导出保持现状；他们若想吃我们的包，必须自己放宽"未知条目"策略（见 §5.2） |
| C3 | **chat 的 `tool` 角色 + `rawAssistantJson`** | 他们的 `ChatRole` 只有 3 值（`ChatMessage.kt:11`），没有工具轮、没有 `tool_calls`/`reasoning_content` 的持久化通道。我们包里 `role=tool` 的消息他们**枚举都解不出**（`ChatRole.valueOf` 失败 → 整类拒） | **不建议**为他们裁剪我们的历史（裁掉 tool 行会让下一次请求 400——这正是我们 `ChatMessage.kt:41-43` 明写的理由）。他们若导入我们的包，必须自己决定"丢弃 tool 行 + 接受后续请求可能不合规"，属他们的取舍 |
| C4 | **settings 键集合的产品级分叉** | 他们有取诗（`enable_click_poetry*`/`click_poetry_search_keywords`）、形象摆位（`pet_offset_x/y`/`pet_scale`）、`update_check_url`；我们有联网搜索（`web_search_*`）、记忆模式（`memory_mode`）、`context_window`、`wallpaper_path`。这些是**功能不同**，不是编码不同 | **不做键映射表**（违反 [`rules.md`](rules.md)「不设内置表」）。方向性结论：互导时**双方各自忽略对方未知键**即可；但他们的 `decodeTo` 目前是"未知键整类拒"，这必须由他们改 |
| C5 | **at-rest 加密语义差异** | 他们导出时把常驻加密的磁盘文件**解密回明文**入包（`BackupCollector.kt:100-107`，注释 `:13-14`）；我们的业务数据本就在 SQLite 明文库里按业务对象读（`BackupCollector.kt:84-103`） | **不影响互通**（两侧包内都是明文 JSON），但**不要**因此认为"我们能读他们的密文包"——包本身一定是加密 zip + 明文条目，这一点两边一致。此处仅作澄清 |
| C6 | **为互通而 bump `format`/`schema`** | 若把 `format` 升到 2 以区分"参考格式"，会立刻触发 [`legacy-compat.md`](legacy-compat.md) §8（格式升级必须同步提供迁移 step + 导入兼容），并把"旧包变砖"的风险引入一个本可零成本解决的问题 | **不做**。兼容层放在"归一化"位置，保持 `format=1`/`schema=1` 不变 |

---

## 5. 两个方向的可行性

### 5.0 结论先行

| 方向 | 能否做到 | 是否建议做 | 最小改动规模 |
|---|---|---|---|
| **我们导入他们的包** | **能**（两个阻断点都可解，且都落在我们这一侧） | **建议做**（只读兼容，单向） | 中（新增一个归一化适配层 + 放宽一处校验 + 补 5 类单测；纯 JVM 可测，不动 `format`/`schema`） |
| **他们导入我们的包** | 需要他们改 4 处 | **不建议我们推动**（成本在他们，且 C3 语义损失在他们） | 只描述，不写 |

### 5.1 方向一：我们的 App 导入参考版导出的包

**当前实际行为（代码级推演，未实跑）**：

1. 若他们的包**不含** `pet_form`：`BackupInspector.validateAndOpen` 的全部结构校验**通过**（manifest 骨架/路径/`schema`/类别唯一性都匹配）。随后按类别落地：`settings`/`chat`/`memory` 因内部结构不同各自失败并记入 `failed`，`wallpaper` **成功**。用户看到"恢复 1 类，3 类失败"。
2. 若他们的包**含** `pet_form`（导出时勾了"形象"）：`BackupInspector.kt:142` 直接抛"备份包结构不完整"，**整包拒**，什么都导不进来。

**最小改动清单**（按现有类给出落点）：

1. **放宽"未知路径"为一等公民，而不是拒绝整包** —— `BackupInspector.validateAndOpen`（`BackupInspector.kt:141-154`）。
   - 现状：`BackupCategory.fromPath(path) ?: throw structureIncomplete()`。
   - 改为：引入"三态归类" `Known / UnknownButSafe / Illegal`。`UnknownButSafe` 仅接受**严格安全形态**（`files/<[a-z0-9_]+>/…` 且无空段/`.`/`..`/反斜杠/绝对路径/超长），记入新增的 `BackupContents.unsupportedPaths: List<String>`（`BackupInspector.kt:27-33`）并**跳过**；`Illegal` 仍整包拒绝。
   - 保留现有"manifest 双向一致性"检查，但对被跳过的未知条目同样要求 manifest 已登记（避免出现"包里有、manifest 没有"的新盲区）。
   - **通用性**：这不是"为 `pet_form` 开特例"，而是**前向兼容通用策略**——将来任何新版新增的类别，旧版都能"跳过并告知"，而不是整包报废。这条策略本身可以写进 PRD 作为常设规则。
2. **新增通用形状归一化适配层** —— 建议新文件 `backup/BackupPayloadAdapters.kt`（放在 `BackupFormat`/`BackupCodec` 之间，纯函数、无 Android 依赖）。
   - `normalizeWrappedObject(text, wrapperKey): JsonObject`：root 是 Object 且 `root[wrapperKey]` 是 Object → 取它；否则 root 自身。用于 settings（`wrapperKey = "values"`）。
   - `normalizeCollection(text, fieldName): String`：root 是 Array → `{"$fieldName": root}`；root 是 Object 且有 `fieldName` → 原样。用于 chat（`"messages"`）/ memory（`"items"`）。
   - 都是**按 JSON 形状**分支（Object vs Array / 有无包装键），不是按"参考版"分支 ⇒ 不违反 [`rules.md`](rules.md)「不为单个服务单独写 if / 不设内置表」。
   - 归一化失败 → 保持现有拒绝文案（`BackupFormat.STRUCTURE_INCOMPLETE`），不做"特殊补救"。
3. **把归一化接进解码入口**（保持唯一真相源，不让 `decode` 各自长一套）：
   - `SettingsWhitelist.decode`（`SettingsWhitelist.kt:126-141`）：先用 `normalizeWrappedObject(text, "values")` 再按原逻辑遍历。
   - `BackupCodec.decodeChat`（`BackupCodec.kt:71-72`）：先 `normalizeCollection(text, "messages")` 再 `decodeFromString<ChatPayload>`。
   - `BackupCodec.decodeMemory`（`BackupCodec.kt:79-80`）：先 `normalizeCollection(text, "items")` 再 `decodeFromString<MemoryPayload>`。
   - 三个 `encodeXxx` **完全不动**（我们的导出格式不因为别人而变）。
4. **`exchangeCount` 缺失语义** —— `BackupImporter.applyMemory`（`BackupImporter.kt:106-112`）。
   - 需要 `MemoryPayload.exchangeCount` 变成可判"字段存在与否"。最小做法：在 `BackupCodec.decodeMemory` 返回的 payload 上加一个 `exchangeCountPresent: Boolean`（解码前用 `Json.parseToJsonElement` 判 `root["exchangeCount"]` 是否存在），或改为 `exchangeCount: Int?` 并用 `target.memoryStore.getExchangeCount()` 兜底。
   - **通用规则**（与本项目 settings"不物化默认值"同源）：**包里没有的字段，保留本机现值**，而不是写模型默认值。
5. **UI/回执呈现"跳过的类别"** —— `BackupViewModel.inspectPackage`（`BackupViewModel.kt:198-255`）把 `contents.unsupportedPaths` 收进 `BackupUiState`（`BackupUiState` 加一个字段，`BackupViewModel.kt:48-58`），`DataSettings` 的包内类别清单（`DataSettings.kt:430-446`，逐 `BackupCategory` 渲染勾选行）旁边显示"N 项本机不支持的类别，导入时将跳过"；导入回执同理。**默认勾选**保持 `contents.categories`（未知类别不进勾选集合，天然不落地）。
6. **不改的东西**（明确的"不做"边界）：
   - 不动 `BackupFormat.SUPPORTED_FORMAT` / `SUPPORTED_SCHEMA`（保持 1/1）；
   - 不动 `BackupCategory` 枚举（不新增 `pet_form`）；
   - 不动导出侧任何代码（我们的包维持现状）；
   - 不动 `BackupFormat.fromPath` 对**已知**类别的严格语义（只新增"未知但安全"这一档）；
   - 不写键映射表、不实现 `pet_form` 落地、不实现 `memoryOpsBlock`/`failed`/`injected`/`summary` 的承载。

**是否需要动 `format`/`schema` 常量？** —— **不需要**。兼容性加在"归一化层"，canonical 格式（我们的导出）不变，`format=1`/`schema=1` 语义不变。

**是否触发 [`legacy-compat.md`](legacy-compat.md) §8？** —— **不触发**。§8 的触发条件是"备份包 `format` 包级或条目 `schema` 升级到新值"，并要求同步提供"旧格式备份升级 step + 导入兼容"。本方案**不升版本常量、不改 canonical 格式**，因此既不产生"旧包变砖"风险，也不需要新增迁移 step。
反过来说：**如果将来有人提议"为了标识参考格式而 bump format=2"，那就落入 [`legacy-compat.md`](legacy-compat.md) §8** —— 同一次改动必须补该文 §3 的升级 step，且旧 `format=1` 包必须仍可导入。本文立场是：**不要 bump**，用归一化适配层解决。

**工作量估计**：
| 项 | 规模 |
|---|---|
| 未知类别三态 + Inspector 放宽 + `BackupContents` 扩展 | 小（约 1 个文件、80 行内） |
| 归一化适配层（3 个纯函数）+ 接进 3 个解码入口 | 小（约 1 个新文件 60 行 + 3 处各 1 行） |
| `exchangeCount` 保留语义 | 小 |
| UI 跳过项呈现 | 小 |
| 单测（见 §6.3） | 中 |
| **合计** | **中（约 1 人日量级，全部可在 JVM 单测覆盖）** |

### 5.2 方向二：参考版导入我们的包（只描述，不写他们的代码）

他们那一侧至少要改 4 处：

1. **未知条目策略**：把"`entrySupported` 失败即整包拒"（他们 `BackupInspector.kt:86-91`）改成"记为不支持并跳过"——否则我们的 `compaction` 条让他们整包报废。顺带把 `BackupFormat.kt:51` 的注释（"由调用方按 `entrySupported` 逐类拒绝"）与实现对齐。
2. **settings 解码**：接受 `{"values":{…}}` 信封（`SettingsWhitelist.decodeTo`，`SettingsWhitelist.kt:105-127`）；**必须**同时把"非白名单键整类拒"（`:110-112`）放宽为"忽略未知键"——否则我们独有的 9 个键（含 3 个可选 Key）会让他们的整类设置失败。注意他们的导入是**全局回滚**（`BackupImporter.kt:95-104`），一类失败会连累整个导入。
3. **chat 解码**：接受 `{"messages":[…]}` 信封；并处理 `role=tool` 与 `rawAssistantJson`——他们的 `ChatRole` 没有 `tool`（`ChatMessage.kt:11`），`tool` 行会让 `List<ChatMessage>` 整包解码失败。他们要么补工具轮模型，要么显式丢弃 `role=tool` 的行（并接受"丢弃后会话可能不再是合法的 OpenAI 工具轮序列"的风险）。
4. **memory 解码**：接受 `{"items":[…],"exchangeCount":N}` 信封；`items` 可直接用他们的 `MemoryItem` 解（字段完全一致）；`exchangeCount` 若要保留，需要额外写回他们的 DataStore `exchange_count` 键（他们的 `replaceWith` 只接纯数组文本，没有这个通道）。

不需要他们改的：zip 信封、manifest 骨架、`files/<category>` 路径、壁纸条目、`format`/`schema` 语义、密码生命周期。

**代价判断**：他们要改的每一条都在"放宽输入"，风险面（安全）比我们这边小；但 C3（工具轮）无解，除非他们放弃我们带工具轮的会话历史。

---

## 6. 风险与建议

### 6.1 结论

1. **值得做，但只做单向（我们导入他们的包）**。理由：外层信封已经 100% 同源，缺的只是一层归一化 + 一处校验放宽，成本低、风险可控；而反向要改他们的代码、还要牺牲我们自己的工具轮语义，**收益在对方**。
2. **不做双向互认承诺**。文档口径应从"结构上互认"改为"骨架同源、条目内部结构各自独立；本应用可选择导入参考格式（只读兼容）"。
3. **绝不为互通破坏本项目格式**：不动 `format`/`schema`、不动导出侧、不新增 `pet_form`、不裁 `compaction`、不删 `tool` 行/`rawAssistantJson`。
4. **不为单一参考格式写特例分支**：所有兼容都表达为"JSON 形状归一化 + 未知类别跳过 + 缺失字段保留本机值"这三条通用规则，它们同时是**前向兼容**能力（未来任何新版加类别/改信封，旧版都不会整包报废）。

### 6.2 风险

| 风险 | 说明 | 对策 |
|---|---|---|
| **放宽未知路径 = 放宽攻击面** | `BackupInspector` 原来"未知路径一律拒"是安全默认值；改成"未知但安全则跳过"后，必须把"安全"的判定写死（无空段/`.`/`..`/反斜杠/NUL/绝对/超长、必须在 `files/` 下、且 manifest 已登记），否则等于给解压路径校验开口子 | 三态归类做成纯函数 + 专项单测（含 `files/../../x`、`files/pet_form/../../x`、`files//x`、`files\pet_form\x`、超长、绝对路径、NUL 全部必须判 Illegal）；被跳过条目**绝不落盘**（`BackupImporter` 只遍历 `contents.categories`，未知类别天然不进入落地分支） |
| **静默丢数据** | 导入他们的包时 `memoryOpsBlock`/`failed`/`injected`/`summary`/`exchangeCount` 会丢失，用户不会被告知 | 回执/确认弹窗必须列出"本机不支持的项已跳过"；`exchangeCount` 用"保留本机现值"而非写 0 |
| **把参考格式当代替品** | 一旦开始"适配别人的包"，容易演变成"我们的 canonical 要向别人看齐" | 明确单向：`encodeXxx` 一行不改；适配层只在 `decodeXxx` 入口做输入归一化 |
| **许可与抄袭** | 参考仓库是 MIT，法律上可复制；但项目纪律要求"只做结构性对比" | 实现时**只写我们自己的归一化代码**，不搬他们的文件；单测 fixture 用**手写 JSON 字面量**，不引入他们的源码或文件 |
| **并行改动冲突** | 当前工作树里 `BackupInspector`/`BackupImporter`/`BackupViewModel`/`DataSettings` 正在被别人改（含密码副本修正） | 实施前先 rebase/同步到最新工作树；兼容改动尽量落在**新文件** + 三处一行接入，减少与并行改动的重叠 |
| **`createdAt`/文件名格式误当校验条件** | 有人可能"顺手"让 `createdAt` 参与校验 | 明确写进注释：`createdAt` 是不透明展示字段，永不参与接受/拒绝判定 |

### 6.3 若实施，需要新增的单测清单

> 全部为纯 JVM（`app/src/test/java/com/meapet/mobile/backup/`），不依赖 Android；fixture 一律手写 JSON/zip，不引用参考仓库代码。

| 测试类 | 用例 |
|---|---|
| 新增 `BackupPathClassifyTest` | `files/settings`/`files/compaction`/`files/wallpaper/a.jpg` → Known；`files/pet_form/pet_forms.zip` → UnknownButSafe；`files/../x`、`files//x`、`files\pet_form\x`、`/files/pet_form/x`、超 512 字符、含 `\u0000`、`manifest.json` → Illegal |
| `BackupInspectorTest`（扩） | ① 含 `files/pet_form/pet_forms.zip` 且 manifest 已登记的包 → **开包成功**，`unsupportedPaths` 恰为该项；② 未知路径**未**在 manifest 登记 → 仍整包拒；③ 未知路径形态非法 → 仍整包拒；④ 现有全部拒绝用例（未知类别、schema 过新、重复类别、条目缺失）行为不变 |
| 新增 `BackupPayloadAdaptersTest` | ① settings：平铺对象 → 解出全部命中键；`{"values":{…}}` → 同结果；两者都不是 → 原拒绝文案；② chat：裸数组 → `messages`；带 `messages` 的对象 → 同结果；③ memory：裸数组 → `items` + `exchangeCountPresent=false`；带 `items` 的对象 → `exchangeCountPresent=true`；④ 数组元素缺必填（如 `content`）→ 拒绝（不得静默补默认值） |
| `SettingsWhitelistTest`（扩） | 平铺输入可解；输入含我们白名单外的 7 个参考版键 → **忽略且不报错**（现有行为回归）；`values` 为 Object 但内部值类型不符 → 忽略（现有行为） |
| `BackupImporterTest`（扩） | ① 无 `exchangeCount` 的 memory 包 → 落地后 `getExchangeCount()` 等于**导入前**的值（不是 0）；② 平铺 settings 包 → 只写命中键、本机其它键不变；③ 包内同时含 `pet_form`（被跳过）+ `wallpaper` → `wallpaper` 成功、`pet_form` 不出现在 `succeeded`/`failed`，且**不产生任何文件**；④ settings 解不动时 chat/memory/wallpaper 仍按现有语义继续（回归现有"按类别独立"设计） |
| 新增 `BackupReferenceShapeRoundTripTest`（可选但推荐） | 手写一份"参考形状"的完整包（平铺 settings + 裸数组 chat/memory + 壁纸 + `pet_form`），走 `BackupInspector.open` → `BackupImporter.import` 全链路，断言：打开成功、跳过 `pet_form`、3 个共有类别按预期成功/可选项失败、壁纸字节一致 |
| 安全回归 | 被跳过的未知类别**绝不**写入 `filesDir`；日志/回执不含密码、对话正文、文件路径（沿用现有断言口径） |

### 6.4 建议的实施顺序

1. `BackupPathClassify` 纯函数 + 单测（先立安全边界）；
2. `BackupInspector` 放宽 + `BackupContents.unsupportedPaths` + 单测；
3. `BackupPayloadAdapters` + 接进 `SettingsWhitelist.decode` / `BackupCodec.decodeChat` / `BackupCodec.decodeMemory` 三处解码入口 + 单测；
4. `exchangeCount` 缺失保留语义 + 单测；
5. UI 跳过项呈现 + 回执文案；
6. 全链路 fixture 测试 + `assembleDebug` + `check_doc_links`。

> 最后更新：2026-11-23。本文是一次性审计报告；结论若被后续实现推翻，按 [`docs/README.md`](README.md) §5「过时即删」处理（直接删除，不进归档）。
