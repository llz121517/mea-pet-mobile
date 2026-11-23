# 配置与数据导出导入与备份（1.9 计划）

> 状态：**已实现（2026-11-23）**。实现落点：`app/src/main/java/com/meapet/mobile/backup/`（格式、设置白名单、编解码、采集、导出、校验、导入、SAF 封装）、`app/src/main/java/com/meapet/mobile/viewmodel/BackupViewModel.kt`、`app/src/main/java/com/meapet/mobile/ui/screen/settings/DataSettings.kt`，单测在 `app/src/test/java/com/meapet/mobile/backup/`。详细设计见 [`2026-11-22-backup-export-import-lld.md`](2026-11-22-backup-export-import-lld.md)。参考实现：社区版分支 [`mea-pet-mobile-fork-fo-pr`](https://gitee.com/flexiatom/mea-pet-mobile-fork-fo-pr)（其 `backup/` 包 + `DataSettings.kt`）。
>
> **借鉴原则：借鉴设计，不借鉴实现。**
>
> - **对齐**：包结构与 manifest 语义、两级版本校验、按类别选择/按类别覆盖、SAF 选位置、口令不落盘不进状态、暂存目录清扫、文本条目内存闸 + 二进制流式写盘、口令错误不泄露包内信息。
> - **不照搬**：参考版按「磁盘 JSON 文件」采集（`chatFile`/`memoryFile` 直接 `readText` 透传）。本项目 1.9 起会话、记忆、压缩状态都在 **SQLite 单库**，必须从库中读业务数据再序列化，不能复制 `.db` 文件，也不能沿用它的文件路径假设。
> - **格式尽量兼容**：条目名（`settings`/`memory`/`chat`/`wallpaper`）、`format`/`schema` 版本语义、`manifest.json` 字段形态、`files/<category>` 包内路径、默认文件名 `<UTC紧凑>_MeaPet-Android.zip`，都对齐参考版；新增条目（如 `compaction`）按同样约定扩展，使两边的包在结构上互认。**这不代表承诺互相导入**——条目的内部 JSON 结构各按自己的数据模型，只有格式骨架对齐。

## 1. 目标

让用户能把自己的**设置、对话历史、记忆库、上下文压缩状态、背景壁纸**完整导出成一个文件，换机或重装后原样恢复；导出包必须加密，避免备份文件落在云盘/聊天软件里时暴露对话与记忆内容。

## 2. 已确认的需求决策

| 议题 | 决策 |
|---|---|
| 加密 | **必须**口令加密，AES-256 zip（不可导出明文） |
| API Key | **可选勾选**，默认不勾；勾选时进包 |
| API 地址与模型名 | 都进包（换机可直接继续使用） |
| 导入策略 | **按类别整体覆盖**，导入前强制点名确认 |
| 覆盖范围 | 设置、对话历史、记忆库、上下文压缩状态、背景壁纸 |
| 不含 | TTS 语音模型（约 84MB，可重新下载）、Live2D 形象资产 |
| 版本兼容 | 版本不等**即拒**；`legacy-compat` 提供升级 step 时，**同步提供对应的导入兼容 step** |
| 入口 | 设置页新增「数据与备份」分组 |
| 自动化 | 第一版**只做手动**，不做定时自动备份 |
| 体积限制 | 文本条目解压后限 64 MiB（防解压炸弹）；二进制条目流式写盘不设上限 |
| 暂存清理 | 进入该页时清扫 `cacheDir/backup` 下的残留 |
| 隐私政策 | 需要更新（备份会把对话、记忆、可选 Key 写到用户指定位置） |

## 3. 包格式

```
<UTC紧凑时间戳>_MeaPet-Android.zip     ← AES-256 加密
├── manifest.json
└── files/
    ├── settings                 ← JSON（白名单键值，见 §3.2）
    ├── chat                     ← JSON（会话与消息，见 §3.3）
    ├── memory                   ← JSON（记忆条目 + 摘要计数等 meta）
    ├── compaction               ← JSON（压缩摘要与覆盖游标）
    └── wallpaper/<磁盘现名>      ← 图片二进制
```

### 3.1 manifest

```json
{
  "format": 1,
  "createdAt": "2026-11-22T15:30:00Z",
  "appVersion": "1.9.0",
  "entries": [
    { "name": "settings", "schema": 1, "bytes": 1234, "path": "files/settings" }
  ]
}
```

- 字段与语义对齐参考版：`format` 是包级格式版本，`entries[].schema` 是条目级版本，**两者都等于支持值才放行**。
- 未勾选的类别不进包，manifest 只登记实际写出的条目。
- 只保留 `bytes` 供 UI 展示与合理性对账，不做逐条目 sha256（zip 口令本身已提供完整性校验，解密失败即拒）。
- `appVersion` 为本项目补充字段（便于排障时判断包来自哪个版本），读取方按未知字段忽略。

### 3.1.1 支持的类别与条目名

| 条目名 | 类别 | 状态 |
|---|---|---|
| `settings` | 设置 | 对齐参考版 |
| `memory` | 记忆库 | 对齐参考版 |
| `chat` | 对话历史 | 对齐参考版 |
| `wallpaper` | 背景壁纸 | 对齐参考版（按磁盘现名入包，路径 `files/wallpaper/<现名>`） |
| `compaction` | 上下文压缩状态 | **本项目新增**（参考版无此数据） |
| `pet_form` | 导入的 Live2D 形象 | 参考版有，**本项目 1.9 不做**（见 §9） |


### 3.2 settings 条目

按**白名单**导出，只包含明确的用户设置：

- 包含：外观（主题、动态取色、配色、气泡透明度、壁纸路径与模糊）、模型参数（API 地址、模型名、Temperature、最大 Token、上下文窗口）、记忆（开关、检索模式、自动摘要、摘要间隔）、TTS 开关与语速、联网（开关、服务商、复用开关）、更新检查开关。
- 可选包含（受 §2 的 Key 勾选控制）：`api_key`、`web_search_deepseek_key`、`web_search_anysearch_key`。
- **不包含**：`first_launch`、`privacy_version_shown`、`exchange_count`（已废弃）、隐私同意状态、任何运行时状态。

### 3.3 chat / memory / compaction 条目

- 从 SQLite 读出**业务数据**再序列化为 JSON，不复制数据库文件：直接拷 `.db` 会带上本机的 `seq`、内部版本号与未清理的历史，导入时无法安全落地。
- 导出时**原文语义透传**：内容不做「重新生成」，避免导出即偷偷改数据。
- 导入时按类别整体替换：单个事务内删除旧数据、写入新数据；任一环节失败整类回滚。

## 4. 交互流程

### 4.1 导出

1. 设置 →「数据与备份」→ 展开「导出」。
2. 勾选类别（默认全勾除 Key）；每项显示「有内容 / 本机这一类还没有东西」。
3. 输入口令两次（不一致不允许继续；短于 8 位只提示不拦）。
4. 首次点「导出」先弹一次「包里有什么」的告知，关闭后才走系统保存面板。
5. 系统面板选位置 → 写出 → 回执显示文件名与大小。

### 4.2 导入

1. 展开「导入」→ 选包 → 输入口令。
2. 校验 manifest 与条目版本，列出包内类别与创建时间。
3. 勾选要恢复的类别（只显示包内存在的类别）。
4. 点「导入」→ **强制确认弹窗**：逐项列出「将覆盖本机的 X」，必须点名确认。
5. 落地完成 → 回执；提示部分设置（如外观）可能需要重新进入页面才完全生效。

### 4.3 异常与回执

- 口令错误：提示重新输入，不透露包内任何内容。
- 版本不支持：说明不支持的条目与支持版本，整包拒绝。
- 空间不足 / 写入失败：报告失败类别，已成功类别保持（按类别独立事务）。
- 用户取消任何系统面板：明确回执「已取消，没有写出/改动任何文件」。

## 5. 实现要点

| 位置 | 职责 |
|---|---|
| `backup/BackupFormat.kt` | manifest 模型、格式与条目版本常量、类别枚举、编码/解码 |
| `backup/BackupCollector.kt` | 按类别采集（DataStore 白名单 / SQLite 读业务数据 / 壁纸文件） |
| `backup/BackupExporter.kt` | 逐条目 AES-256 流式写出到 SAF 给的目标流，manifest 收尾 |
| `backup/BackupInspector.kt` | 读包（先暂存到 cacheDir 再按中央目录读）、两级版本校验、文本条目 64 MiB 内存闸 |
| `backup/BackupImporter.kt` | 按类别事务化落地 |
| `backup/SettingsWhitelist.kt` | 设置键白名单与类型化编解码 |
| `backup/BackupService.kt` | SAF 薄封装：暂存、打开写入流、显示名、清扫 |
| `viewmodel/BackupViewModel.kt` | 导出/导入状态机（空闲 / 忙碌 / 回执 / 待确认） |
| `ui/screen/settings/DataSettings.kt` | 页面 UI（两个可折叠块 + 共享回执区） |

- 加密库：`net.lingala.zip4j`（注意其 `ZipOutputStream` 才是往任意流写的载体，`ZipFile` 没有 OutputStream 构造器）。
- 口令只存在于页面局部状态，**不进 ViewModel 状态、不落盘、不写日志**。
- 导出全程不经过网络；不新增任何请求。

## 6. 与 legacy 迁移的关系

**约定：格式升级时，导入兼容 step 与 legacy 迁移 step 同步提供。**

具体说：如果将来备份 `format` 或某条目 `schema` 升到 2，必须同时做到——

1. 在 `docs/legacy-compat.md` 增加一个升级 step（把用户已有的旧格式备份文件升级到新格式）；
2. 在 `BackupImporter` 中提供对应的导入兼容路径，或明确写出「旧 format 包在新版中由该 step 升级后导入」。

不允许出现「只支持新包、旧备份变砖」的情况。当前 `format=1` / `schema=1` 为初版，尚无历史包袱。

> 该约定需同步写入 `docs/legacy-compat.md` 作为常设规则。

## 7. 隐私与合规

- 备份包含对话内容与记忆，属于敏感数据；导出包**必须**加密。
- 备份文件写到用户自己选择的位置，应用不保留副本；`cacheDir` 暂存在进页时清扫。
- 隐私政策需新增一节，说明：备份功能会把哪些数据写到用户指定位置；Key 默认不进包、勾选后才会写入；应用不上传任何备份。
- UI 需说明「口令遗忘无法找回」。

## 8. 测试清单

- 包格式：manifest 编解码、格式/条目版本不等即拒、未知路径拒绝、越界路径拒绝。
- 采集：各类别单独导出、组合导出；未勾选类别不进包；Key 勾选与不勾选两种。
- 导出：加密可被正确口令解开、错误口令打不开、明文不出现在文件里。
- 导入：按类别整体覆盖（覆盖前有数据、覆盖前无数据两种）、单类别失败不影响其他类别、版本不支持整包拒绝、口令错误无副作用。
- 体积：文本条目的 64 MiB 内存闸；二进制大文件不触发上限。
- SQLite：导入事务回滚（构造写入失败）、导入后 UI 与模型上下文都能读到新数据。
- 设置白名单：新增设置键时不会自动进包（防止未来把敏感新键意外导出）。

## 9. 不在本版范围

- 定时/自动备份与备份轮转。
- 云端同步。
- TTS 模型与 Live2D 形象资产的备份（体积大、可重新获取）。
- 跨平台（非 Android）备份格式。
