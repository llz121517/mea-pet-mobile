# 配置与数据导出导入与备份（1.9 计划）

> 状态：**已实现（2026-11-23）**。实现落点：`app/src/main/java/com/meapet/mobile/backup/`（格式、设置白名单、编解码、采集、条数对比、导出、校验、导入、SAF 封装）、`app/src/main/java/com/meapet/mobile/viewmodel/BackupViewModel.kt`、`app/src/main/java/com/meapet/mobile/ui/screen/settings/DataSettings.kt`，单测在 `app/src/test/java/com/meapet/mobile/backup/`（10 个文件 / 10 个测试类、87 例；`BackupTestFakes.kt` 是测试辅助，`BackupImporterTest.kt` 内含 `BackupImporterTest` 与 `BackupCollectorTest` 两个类）。详细设计见 [`2026-11-22-backup-export-import-lld.md`](2026-11-22-backup-export-import-lld.md)；与社区参考实现的**逐字段互通性审计**见 [`2026-11-23-backup-format-compat.md`](2026-11-23-backup-format-compat.md)。参考实现：社区版分支 [`mea-pet-mobile-fork-fo-pr`](https://gitee.com/flexiatom/mea-pet-mobile-fork-fo-pr)（其 `backup/` 包 + `DataSettings.kt`）。
>
> **借鉴原则：借鉴设计，不借鉴实现。**
>
> - **对齐**：包结构与 manifest 语义、两级版本校验、按类别选择/按类别覆盖、SAF 选位置、密码不落盘不进状态、暂存目录清扫、文本条目内存闸 + 二进制流式写盘、密码错误不泄露包内信息。
> - **不照搬**：参考版按「磁盘 JSON 文件」采集（`chatFile`/`memoryFile` 直接 `readText` 透传）。本项目 1.9 起会话、记忆、压缩状态都在 **SQLite 单库**，必须从库中读业务数据再序列化，不能复制 `.db` 文件，也不能沿用它的文件路径假设。
> - **格式尽量对齐**：条目名（`settings`/`memory`/`chat`/`wallpaper`）、`format`/`schema` 版本语义、`manifest.json` 字段形态、`files/<category>` 包内路径，都对齐参考版；新增条目（`compaction`）按同样约定扩展。**默认文件名例外**：参考版为 `<UTC紧凑>_MeaPet-Android.zip`，本项目为 `<UTC紧凑>_MeaPet-backup.zip`（`BackupExporter.kt:23`），文件名不参与任何校验，差异不影响互通。
> - **互通边界（实测结论）**：**zip 信封与 manifest 完全同源，但内层三个共有条目的 JSON 根形状不同**——参考版 settings 为平铺对象而非 `{"values":{…}}`、chat/memory 为裸数组而非包装对象；且条目集合各缺一类（参考版有 `pet_form`、我们有 `compaction`）。按两侧现有代码，双向都会因根形状不匹配而「读包通过、逐类导入失败」。详见互通性审计文档。

## 1. 目标

让用户能把自己的**设置、对话历史、记忆库、上下文压缩状态、背景壁纸**完整导出成一个文件，换机或重装后原样恢复；导出包必须加密，避免备份文件落在云盘/聊天软件里时暴露对话与记忆内容。

## 2. 已确认的需求决策

| 议题 | 决策 |
|---|---|
| 加密 | **必须**密码加密，AES-256 zip（不可导出明文） |
| API Key | **可选勾选**，默认不勾；勾选时进包 |
| API 地址与模型名 | 都进包（换机可直接继续使用） |
| 导入策略 | **按类别整体覆盖**，导入前强制点名确认 |
| 覆盖范围 | 设置、对话历史、记忆库、上下文压缩状态、背景壁纸 |
| 不含 | TTS 语音模型（约 84MB，可重新下载）、Live2D 形象资产 |
| 版本兼容 | `format` / `schema` 不等**即拒**；`legacy-compat` 提供升级 step 时，**同步提供对应的导入兼容 step** |
| 未知条目 | **忽略 + logcat 警告**（前向兼容：新版 App 导出的包在旧版仍能导入其余类别）；但**路径越界/畸形**仍整包拒绝 |
| 入口 | 设置页新增「数据与备份」分组 |
| 自动化 | 第一版**只做手动**，不做定时自动备份 |
| 弱密码 | 短于 6 位**只提示不拦**（用户自担） |
| 体积限制 | 文本条目解压后限 64 MiB（防解压炸弹）；二进制条目流式写盘不设上限 |
| 暂存清理 | 进入该页时清扫 `cacheDir/backup` 下的残留 |
| 导入后刷新 | 导入完成**广播数据变更**，各页面/服务重载自己的缓存（见 §4.3） |
| 隐私政策 | 已更新至 1.3（新增「备份与导出」小节，版本号不变、不额外触发重新确认） |

## 3. 包格式

```
<UTC紧凑时间戳>_MeaPet-backup.zip     ← AES-256 加密
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
- 只保留 `bytes` 供 UI 展示与合理性对账，不做逐条目 sha256（zip 密码本身已提供完整性校验，解密失败即拒）。
- `appVersion` 为本项目补充字段（便于排障时判断包来自哪个版本），读取方按未知字段忽略。

### 3.1.1 支持的类别与条目名

| 条目名 | 类别 | 状态 |
|---|---|---|
| `settings` | 设置 | 对齐参考版（**根形状不同**：我们 `{"values":{…}}`，参考版平铺） |
| `memory` | 记忆库 | 对齐参考版（**根形状不同**：我们 `{"items":[…],"exchangeCount":N}`，参考版裸数组） |
| `chat` | 对话历史 | 对齐参考版（**根形状不同**：我们 `{"messages":[…]}`，参考版裸数组） |
| `wallpaper` | 背景壁纸 | 对齐参考版（安全化后入包，路径 `files/wallpaper/<安全名>`） |
| `compaction` | 上下文压缩状态 | **本项目新增**（参考版无此数据） |
| `pet_form` | 导入的 Live2D 形象 | 参考版有，**本项目 1.9 不做**（见 §9）；导入时按「未知条目」**忽略 + 警告**，不再整包拒绝 |

> 逐字段对照与两方向最小改动见 [`2026-11-23-backup-format-compat.md`](2026-11-23-backup-format-compat.md)。


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
2. 勾选类别（默认全勾除 Key）；每项显示「有内容 / 本机这类还没有东西」。
   - `API Key` 是与类别同款的勾选条目，位置紧跟「设置」下方（Key 存在 settings 条目里）；
     「设置」未勾选时该项**置灰**并提示「需先勾选「设置」」。
3. 输入密码两次（不一致不允许继续；短于 6 位只提示不拦）。
4. 首次点「导出」先弹一次「确认导出数据」告知弹窗（列出将写出的类别与「密码遗忘无法找回」），确认后才走系统保存面板。
5. 系统面板选位置 → 写出 → **Snackbar 回执**显示文件名与大小（可手动关闭）。

### 4.2 导入

1. 展开「导入」→ 选包 → 输入密码 → 点「读取备份」。
2. 校验 manifest 与条目版本，列出包内类别、创建时间与文件名。
   - 每个类别显示包内大小；**对话历史与记忆库额外显示「本机 → 包内」条数**（如 `共 24 KB · 37 → 152 条`）。
3. 勾选要恢复的类别（只显示包内存在的类别）。
4. 点「导入所选类别」→ **强制确认弹窗**：逐项列出「将覆盖本机的 X」，必须点名确认。
   - 其中**对话历史与记忆库**再给一次条数对比（本机现有条数 → 包内条数），包内或本机为空时显示 0。
   - 其余类别（设置、压缩状态、壁纸）不是条数概念，只列类别名。
5. 落地完成 → **Snackbar 回执**逐类报告成功/失败；部分设置（如外观）可能需要重新进入页面才完全生效。

### 4.3 导入后的刷新

各 UI/服务持有的都是自己的副本，导入直接写库会绕过它们；而设置页返回主页**不触发 `ON_RESUME`**，所以导入成功后主动广播：

| 读取方 | 刷新方式 |
|---|---|
| `ChatService`（模型上下文） | `reloadAfterExternalImport()`：重载内存窗口 + 强制重读压缩状态 + 重算 token 估算 |
| 主页消息列表（`ChatViewModel`） | 订阅 `AppContainer.dataRevision`，**整表重载**（整库替换不能用增量合并），并重置分页/撤销/压缩提示状态 |
| 设置项 / 壁纸 | DataStore Flow 自动推送 |
| 记忆弹窗 | 打开时按需读库，无需额外处理 |
| API 客户端 | 设置类别成功时 `reloadClient()`，让新端点 / Key / 模型立即生效 |

### 4.4 异常与回执

- 密码错误：提示「密码错误或文件已损坏」，不透露包内任何内容。
- 版本不支持：说明不支持的条目与支持版本，整包拒绝。
- 未知条目：**忽略并在 logcat 打警告**，其余类别正常导入（前向兼容）。
- 空间不足 / 写入失败：报告失败类别，已成功类别保持（按类别独立事务）。
- 用户取消任何系统面板：回执「已取消，未改动任何数据」。

## 5. 实现要点

| 位置 | 职责 |
|---|---|
| `backup/BackupFormat.kt` | manifest 模型、格式与条目版本常量、类别枚举与三态路径判定、编解码、壁纸名安全化 |
| `backup/SettingsWhitelist.kt` | 设置键白名单与类型化编解码（含可选 Key 三键） |
| `backup/BackupCodec.kt` | chat / memory / compaction 三份 payload 的 JSON 编解码 |
| `backup/BackupContract.kt` | `BackupItem`、`BackupSources`、`BackupTarget`、`SettingsSink` 抽象 |
| `backup/BackupCollector.kt` | 按类别采集（DataStore 白名单 / SQLite 读业务数据 / 壁纸文件）+ 生成 manifest |
| `backup/BackupCounter.kt` | 导入前条数对比（对话 / 记忆的「本机 → 包内」） |
| `backup/BackupExporter.kt` | 逐条目 AES-256 流式写出到 SAF 目标流，manifest 收尾 |
| `backup/BackupInspector.kt` | 读包（先暂存到 cacheDir 再按中央目录读）、两级版本校验、未知条目忽略、文本条目 64 MiB 内存闸 |
| `backup/BackupImporter.kt` | 按类别事务化落地，逐类报告成功/失败 |
| `backup/BackupService.kt` | SAF 薄封装：暂存、打开写入流、显示名、清扫 |
| `viewmodel/BackupViewModel.kt` | 导出/导入状态机（空闲 / 忙碌 / 回执 / 待确认） |
| `ui/screen/settings/DataSettings.kt` | 页面 UI（两个可折叠块 + Snackbar 回执） |

- 加密库：`net.lingala.zip4j`（注意其 `ZipOutputStream` 才是往任意流写的载体，`ZipFile` 没有 OutputStream 构造器）。
- 密码只存在于页面局部状态，**不进 ViewModel 状态、不落盘、不写日志**；`BackupInspector.open()` 内部复制一份交给读取器，`close()` 时清零。
- 导出全程不经过网络；不新增任何请求。
- 装配：`AppContainer` 暴露 `backupService` / `backupSettingsSink` / `backupSources` / `backupTarget`，以及导入后广播用的 `dataRevision` + `notifyDataReplaced()`。

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
- 隐私政策**已更新**（版本保持 1.3，新增「备份与导出」小节）：说明备份会把哪些数据写到用户指定位置；Key 默认不进包、勾选后才会写入；应用不上传任何备份。
- UI 需说明「密码遗忘无法找回」。

## 8. 测试清单

- 包格式：manifest 编解码、格式/条目版本不等即拒、**越界路径拒绝**、**未知安全条目忽略**、类别↔路径映射。
- 采集：各类别单独导出、组合导出；未勾选类别不进包；Key 勾选与不勾选两种。
- 导出：加密可被正确密码解开、错误密码打不开、明文不出现在文件里、`STORE` 条目需要 `setEntrySize`。
- 导入：按类别整体覆盖（覆盖前有数据、覆盖前无数据两种）、单类别失败不影响其他类别、版本不支持整包拒绝、**密码错误无副作用**、**调用方清零密码后仍能解出条目**（zip4j 持有引用的回归用例）。
- 条数对比：对话 / 记忆在清单与确认弹窗里显示「本机 → 包内」条数；空库与空包显示 0；非条数类别不出现数字。
- 体积：文本条目的 64 MiB 内存闸；二进制大文件不触发上限。
- SQLite：导入事务回滚（构造写入失败）、导入后 UI 与模型上下文都能读到新数据。
- 刷新链路：导入后 `ChatService` 压缩状态重载、主页消息列表整表重载。
- 设置白名单：新增设置键时不会自动进包（防止未来把敏感新键意外导出）。
- 安全：全流程日志不含密码、Key、对话正文。

## 9. 不在本版范围

- 定时/自动备份与备份轮转。
- 云端同步。
- TTS 模型与 Live2D 形象资产的备份（体积大、可重新获取）。
- 跨平台（非 Android）备份格式。
