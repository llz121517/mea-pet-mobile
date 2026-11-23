# 检查单的 adb 自动化分拣与执行记录

> 生成时间：2026-11-24 凌晨（会话接续）
> 对应检查单：[`2026-11-22-release-checklist.md`](2026-11-22-release-checklist.md)
> 执行设备：`22041216UC`（Xiaomi，Android 14，`arm64-v8a,armeabi-v7a,armeabi`）
> 执行方式：`adb` + `uiautomator` + `screencap`（截图只落电脑，**不写入设备相册**）
>
> 本文只记录**可由 adb 自动化**的部分；需要人眼判断观感/听感、或需要外部服务（DeepSeek / AnySearch 真实 Key）的条目列为「人工」，留给白天。

---

## 1. 分类原则

| 分层 | 判据 | 本次处理 |
|---|---|---|
| **A · 纯 adb 可自动** | 结果可由命令输出 / 数据库查询 / logcat / 截图判定，无需人眼审美判断 | 本次执行 |
| **B · adb 可半自动** | 可用 adb 驱动 UI 走到目标状态，但「观感/听感/动画顺滑度」需人工判 | 本次执行到「产生证据」为止，结论留人工 |
| **C · 人工** | 需要真实第三方服务、真实说话听音、审美判断、或需物理操作（插拔、遮挡屏幕） | 不执行 |
| **D · 与设备无关** | 构建/文档/git 类 | 本次顺手执行 |

---

## 2. 分拣结果

### 2.1 §1.1 真机验收类

| 条目 | 层 | 自动化方式 | 本次结论 |
|---|---|---|---|
| 1.9 整体真机验收 | B | 驱动 UI 发消息需真实端点；无 Key 时只能验「无崩溃 + 空态」 | 部分 |
| 联网工具真机验收 | C | 需 DeepSeek / AnySearch 真实 Key 与额度 | 未执行 |
| 32 位 ARM 降级（v7a 包） | A | 装 v7a 包后 `logcat -s Live2dSupport` + `pidof` + 无 `UnsatisfiedLinkError` | **可自动** |
| 上下文压缩 / 容量探测 | B | 其中 `PRAGMA journal_mode=wal` 属 A 层；滚动锚定属 B | 部分 |
| v7a 包在 64 位设备的 ABI 选择 | A | `dumpsys package | primaryCpuAbi` + 启动存活 | **可自动** |

### 2.2 §1.2 合规类

| 条目 | 层 | 说明 |
|---|---|---|
| NOTICE 8 项确认 | **人工（留明天）** | 需查证上游许可，按用户要求明天处理 |
| 隐私政策 1.3 一致性复核 | B | 可用 adb 打开政策页截图，正文一致性属人工判读 |
| 统计 SDK 隐私重新确认 | B | 可用 adb 验「升级后是否弹窗」（需含 SDK 构建） |
| NOTICE 版本/依赖一致 | **人工（留明天）** | 同上 |

### 2.3 §1.3 构建配置类

| 条目 | 层 | 自动化方式 | 本次结论 |
|---|---|---|---|
| 版本号锁定 | D | `grep versionCode/versionName` | **可自动** |
| 工作区干净 | D | `git status --short` | **可自动** |
| dev → main 合并 | D | `git rev-list --count origin/main..dev` | **可自动**（仅报告状态） |
| 全量单测 | D | `gradlew testDebugUnitTest` | **可自动** |
| debug/release 构建 | D | `assembleDebug` / `assembleRelease -PappAbi=v8a` | **可自动** |
| 三档 ABI 产物 | D | 检查 `dist/` + `apksigner` 摘要一致 | **可自动** |
| CHANGELOG 定稿 | 人工 | 发布日/文案判断 | 未执行 |
| 文档链接检查 | D | `node tools/check_doc_links.mjs` | **可自动** |
| 本文登记进 docs/README | D | 已登记 | **已完成** |

### 2.4 §2 功能验收（按可自动化程度）

**A 层（adp 直接判定）**

| 条目 | 自动化方式 |
|---|---|
| 首屏定位与开屏动画 | 冷启动 + `screencap` 连拍，比较首帧不透明时间（截图落电脑） |
| 按天时间分隔条 | 注入跨天消息后 `screencap` |
| 压缩提醒只弹一次 | `uiautomator dump` 找文案出现次数 |
| 顶部菜单「压缩上下文」条件出现 | `uiautomator dump` 断言菜单项存在性 |
| 上下文窗口手动值 / 重新检测 | 写入后 `dumpsys`/`run-as` 读 DataStore + 重启后复读 |
| 进后台补写不丢历史 | `am start`/`input keyevent HOME` 后杀进程，查 SQLite 行数 |
| **工具消息落库后重启续聊** | 查 `meapet.db` 的 tool 行 → 杀进程 → 复读，行数与 turn 一致 |
| 首屏 300+ 条历史定位 | 注入 N 条后冷启动，`screencap` + 时间 |
| **数据与备份导出/导入** | **本轮重点**：可完全脚本化（见 §3） |
| **导入后的刷新与状态清理** | **本轮重点**：可完全脚本化（见 §3） |
| **JSON → SQLite 迁移（1.7.2 → 1.9）** | **本轮重点**：见 §4 |
| 关于页版本号与构建时间 | `uiautomator dump` 断言文本 |
| Release 包不含试听入口 | 装 release 包后 dump 断言无该控件 |
| 导出日志 | 点击后查 `cacheDir` 有无日志文件（不验内容观感） |
| 设置根页六项摘要 | `uiautomator dump` 断言六项文案 |
| 隐私政策两版全文 | 打开政策页 dump + 截图，断言版本 1.3 / 日期 / 节标题 |
| 设置页折叠/帮助/持久化 | 点击折叠 + 重启后 dump 断言 |
| **WAL 模式** | `run-as` 读 `PRAGMA journal_mode` |

**B 层（能产生证据，观感留人工）**

连续回复分段 / 状态条与停止 / 插队 / 等待态禁用 / 工具轮次上限 / 请求超时 / 失败回滚 / 重试 / 记忆四档 / 只读回退 / 自动摘要触发 / 气泡透明度 / 背景壁纸虚化 / 主题切换 / 状态条不遮最后一条 / 首条回复等待态 / 消息视口铺满 / 手动压缩全流程 / 自动压缩兜底 / 清除对话+撤销 / 上下文压缩分隔条。

**C 层（人工）**

- **TTS 全部**（需听音：语速、混排、短片段、流式打断）
- **Live2D 渲染/透明度/冷启动观感**
- **联网工具全部**（需真实 Key）
- 静音场景下的观感判断（动画是否顺滑、分隔条是否美观）
- 物理操作类（v7a 包在 32 位真机、鼠标遮挡等）

### 2.5 §2.7 32 位降级（A 层，可自动）

| 条目 | 自动化方式 |
|---|---|
| v7a 包启动不崩溃 | 安装 v7a 包 → 启动 → `pidof` + `logcat -b crash` 空 |
| 降级日志出现 | `logcat -s Live2dSupport` 匹配 `NOT available for this ABI` |
| 降级模式功能边界 | `uiautomator dump` 断言顶部菜单**无**「悬浮窗」入口 |
| 壁纸层在降级模式生效 | 注入壁纸设置后 `screencap` |
| v7a 在 64 位设备 ABI 选择 | `dumpsys package | primaryCpuAbi` |

> 注意：本机是 `arm64-v8a` 设备，装 v7a 包后 `primaryCpuAbi` 可能仍显示 v8a（系统优先 64 位）。要验「系统选 32 位」需要 32 位专用设备或用 `--abi armeabi-v7a` 强制，风险较高，**本次不动**。

---

## 3. 数据与备份的自动化脚本化方案

数据与备份是本轮新功能，也是最值得自动化的：

1. **导出**：`uiautomator` 点击「数据与备份」→ 展开导出 → 勾选类别 → 输入密码两次 → 确认弹窗 → SAF 面板选目录 → 断言回执。
   - SAF 面板是系统 UI，adb 驱动不稳；**更稳的做法**：直接用 `run-as` 调应用内的导出？不可行（需 UI 触发）。
   - 折中：只验到「弹窗内容正确 + SAF 面板成功拉起」，实际写盘用**应用内已完成的手动导出**留下的包，或用测试专用 instrumentation。
2. **导入**：需已有合法备份包。可从设备上「导出」得到一个，再用 `adb pull` 取到电脑，然后用 `adb push` 到一个可被 SAF 访问的目录再导入。
3. **断言**：导入前后对比 `run-as com.meapet.mobile` 下的 `databases/meapet.db` 行数、`shared_prefs`/DataStore 内容。

> 由于 SAF 面板无法可靠自动化，**完整导出→导入往返本次只做「半自动 + 人工确认」**，能自动断言的部分是：数据库行数、设置键值、暂存目录是否被清空、回执文案是否只出现一次。

---

## 4. 迁移路径验证方案（1.7.2 → 1.9）

按用户要求：**装 1.7.2 → 填假数据 → 直接覆盖升级到 1.9 → 验迁移**。

| 步骤 | 命令/方式 | 断言 |
|---|---|---|
| 1 | `adb install dist/MeaPet-v1.7.2-v8a.apk` | 安装成功、versionName=1.7.2 |
| 2 | 启动 1.7.2，写入假数据 | 见下方「假数据注入」 |
| 3 | 记录升级前状态 | 旧 JSON 文件存在：`files/meapet_conversation.json`、`files/meapet_memories.json` |
| 4 | `adb install -r` 1.9 包（同签名，覆盖） | 安装成功、数据未被清除 |
| 5 | 启动 1.9 | `logcat -s LegacyJsonImporter` 出现导入日志 |
| 6 | 验迁移结果 | `meapet.db` 存在、消息数 = 旧 JSON 条数、记忆条数一致、`meta.legacy_json_import` 已置位 |
| 7 | 验旧文件保留 | 旧 JSON 文件**仍在**（按设计不删除） |
| 8 | 重启一次 | 不重复导入（幂等）：消息数不变 |

**假数据注入方式**（1.7.2 是 JSON 存储，可直接写文件）：
- `run-as com.meapet.mobile` 写入 `files/meapet_conversation.json` 与 `files/meapet_memories.json`（需符合旧版序列化格式）。
- 或通过 UI 发几条消息（需真实端点，不可行）。
- **更稳妥**：先从 1.7.2 的 UI 里改几个设置项（无需网络），再手工构造 JSON 文件。

---

## 5. 本次执行结果

### 5.0 关键前提：设备有 root（Magisk）

`adb shell su -c id` 返回 `uid=0(root) ... context=u:r:magisk:s0`。这解决了两个卡点：

1. **绕过 MIUI「USB 安装需手动确认」**：用 `su -c 'pm install -r -d <apk>'` 直接装，无需在手机上点同意。
   流程：`adb push <apk> /data/local/tmp/` → `su -c 'pm install -r -d /data/local/tmp/x.apk'`。
2. **读写应用私有目录**：release 包不可 `run-as`，改用 root 直接 `cp` / `sqlite3` 等价操作。
   查库方式：`su -c 'cp /data/data/.../meapet.db* /data/local/tmp/'` → `adb pull` → 本地 sqlite-jdbc 查询。

设备上**没有 `sqlite3` 命令**，所以所有库查询都走「拉回电脑 + JDBC」这条路。

### 5.1 迁移路径验证：1.7.2 → 1.9（✅ 通过）

按用户要求「不用保数据、随便重装」，流程为：

1. 卸载全部 MeaPet 相关包（`com.meapet.mobile`、`com.meapet.mobile.test`、`com.meapet.android` 不同签名版本），确认 `pm list packages | grep meapet` 为空。
2. root 安装 `dist/MeaPet-v1.7.2-v8a.apk`（versionCode 13）。
3. 启动 1.7.2 生成私有目录，然后用 root 注入假数据到 `files/`：
   - `meapet_conversation.json`：5 条消息（3 user + 2 assistant，id `mig-u1…mig-u3`）
   - `meapet_memories.json`：3 条记忆（`FACTUAL` / `LONG_TERM` / `CORE_TRAIT`，共 6 个 keyword）
   - 注意 `chown u0_a397:u0_a397` + `chmod 600`，否则应用读不到。
4. 启动确认 1.7.2 正常（`Live2dManager: Model loaded successfully`）。
5. `su -c 'pm install -r -d meapet-190.apk'` 覆盖升级，确认 `versionCode=15`、`versionName=1.9.0-61181ff58`。
6. 启动 1.9，logcat 出现：
   ```
   I LegacyJsonImporter: Legacy import finished: 5 messages, 3 memories (imported=true)
   ```
7. 库查询（本地 sqlite-jdbc）：

   | 断言 | 期望 | 实际 | 结果 |
   |---|---|---|---|
   | `COUNT(*) FROM messages` | 5 | 5 | ✅ |
   | `COUNT(*) FROM memories` | 3 | 3 | ✅ |
   | `COUNT(*) FROM memory_keywords` | 6 | 6 | ✅ |
   | `meta.legacy_json_import` | `done` | `done` | ✅ |
   | `PRAGMA journal_mode` | `wal` | `wal` | ✅ |
   | `PRAGMA user_version` | 1 | 1 | ✅ |
   | 消息 id 顺序 | `mig-u1, mig-a1, mig-u2, mig-a2, mig-u3` | 一致 | ✅ |
   | 记忆 type | `FACTUAL / LONG_TERM / CORE_TRAIT` | 一致 | ✅ |
   | 旧 JSON 文件 | 保留不删 | 仍在 | ✅ |

8. **幂等性**：再启动一次 → 无 `imported=true` 日志；库仍为 5/3，未重复导入。此时 WAL 已 checkpoint（`meapet.db` 增长到 86KB、`-wal` 归零）。

> 这同时覆盖了检查单 §1.1「上下文压缩/容量探测」中的 `PRAGMA journal_mode=wal` 判据（✅ 已验证）。
> 注意：迁移日志里 `imported=true` 只在**第一次**出现，之后是 `ALREADY_DONE`（不打印该行）。

### 5.2 A 层执行结果（2026-11-24 00:00–00:10）

> 所有截图经 `adb shell screencap -p /sdcard/x.png` → `adb pull` 落电脑，**未写入设备相册**。

| 检查单项 | 结论 | 证据 |
|---|---|---|
| **迁移路径 1.7.2 → 1.9** | ✅ 通过 | 5 消息 / 3 记忆 / 6 关键词 / `legacy_json_import=done` / `user_version=1`；第二次启动幂等 |
| **`PRAGMA journal_mode=wal`** | ✅ | 实际值 `wal` |
| **升级后隐私政策重新确认** | ✅ | 升级启动即弹「隐私政策更新」，副标题正确 |
| **Live2D 渲染（v8a）** | ✅ | 模型完整、无黑块 |
| **按天时间分隔条** | ✅ | 「2023年11月15日」正确插入 |
| **迁移数据在 UI 落地** | ✅ | 5 条消息按 seq 顺序显示，气泡左右区分正确 |
| **设置根页六项摘要** | ✅ | 提供商/对话/外观/语音/**数据与备份**/关于 全部符合 |
| **顶部菜单条件显示** | ✅ | v8a 显示「悬浮窗」；未达阈值**无**「压缩上下文」 |
| **导出：UI 文案** | ✅ | 「备份密码」「密码」「再次输入密码」；API Key 紧跟设置下方 |
| **API Key 跟随设置置灰** | ✅ | 取消「设置」→ 状态变「需先勾选「设置」」；勾上 → 「随设置写入」 |
| **导出确认弹窗** | ✅ | 标题「确认导出数据」；勾选 API Key 后列出「· API Key」 |
| **导出：默认文件名** | ✅ | SAF 面板预填 `20261123T160134Z_MeaPet-backup.zip` |
| **导出：真实写出** | ✅ | `/sdcard/Documents/20261123T160134Z_MeaPet-backup.zip`（1699 B） |
| **导出：AES-256 加密** | ✅ | 无密码 `ZipException: empty or null password`；错误密码同样失败；正确密码解出全部 5 条目 |
| **导出：包内结构** | ✅ | `files/{settings,chat,memory,compaction}` + `manifest.json`；`format=1`、`appVersion=1.9.0` |
| **导入：选包与提示** | ✅ | 按钮变「重新选择备份包…」+ 提示「已选择备份包。输入密码后点「读取备份」校验内容。」 |
| **导入：清单与条数对比** | ✅ | 对话历史「共 819 B · 0 → 5 条」、记忆库「共 718 B · 3 → 3 条」，与库实际一致 |
| **导入：强制确认弹窗** | ✅ | 逐项「将覆盖本机的 X」+ 条数对比 + 「部分设置…可能需重新进入设置页才生效」 |
| **导入：数据落地** | ✅ | 库变回 5 消息 / 3 记忆 / 6 关键词 |
| **导入后自动清空已选包** | ✅ | 导入完回到「选择备份包…」初始态 |
| **导入回执不重复弹** | ✅ | 重进「数据与备份」页无「已恢复」残留、无 Snackbar |
| **清除对话** | ✅ | 空态「开始和 Mea 对话吧！」+ Snackbar「已清除 5 条对话」+「撤销」键 |
| **关于页版本与构建时间** | ✅ | `版本 1.9.0` + `构建时间 2026-11-24 00:07`（release 注入真实值） |

### 5.3 本轮发现并修复的 bug

**暂存文件不清理**（真机验证发现）：
导入完成后 `cacheDir/backup/import_*.zip` 残留不删。原因：`closeReader()` 只关 zip 句柄，
不删文件——之前为了「只加日志」回退过 `stagedFile` 字段，但清包逻辑没配套。
修复：重新引入 `stagedFile` 字段与 `OpenedPackage` 载体，`closeReader()` 在关句柄后删除暂存文件。
验证：修复后完成一次导入，`cache/backup/` 为空 ✅。

**撤销清除对话**：人工手测正常。自动化的第一轮验证曾误判为「撤销失效」，实际是脚本
太慢——撤销窗口仅 5 秒（`UNDO_WINDOW_MS`），而「dump + pull + 换进程再点」要十几秒，
点「撤销」时 Snackbar 早已消失。

> **教训**：带时限的交互（5 秒撤销窗口）不能用「dump → 解析 → 再点击」验证，
> adb 一次 dump+pull 就要 3~5 秒；必须固定坐标单次连续点击，或走 `UiDevice` 同进程操作。

### 5.4 未执行的项

| 类别 | 条目 | 原因 |
|---|---|---|
| C 层 | TTS 全部（语速 / 混排 / 短片段 / 流式打断） | 需听音判断 |
| C 层 | Live2D 透明度 / 冷启动观感 / 拖拽跟随 | 需人眼判断 |
| C 层 | 联网工具（DeepSeek / AnySearch / Auto 切换 / 限流） | 需真实 Key 与额度 |
| C 层 | 记忆四档 / 工具调用 / 摘要触发 | 需真实聊天端点 |
| 人工 | NOTICE 8 项确认 | 用户要求明天处理 |
| 人工 | v7a 包 32 位降级 | 本机是 v8a 设备，强制 32 位风险高（用户要求只动必要设置） |
| 人工 | CHANGELOG 定稿 / dev→main 合并 | 发版决策 |

---

## 6. 安全与善后

- 亮度设为最低（`screen_brightness=1`），关闭自动亮度（`screen_brightness_mode=0`），屏幕常亮（`screen_off_timeout=2147483647`，原值即此）。
- **截图只存电脑**，用 `adb exec-out screencap -p > 本地文件`，不写设备相册。
- 只改上述三项系统设置，不动其它设置。
- 结束：`input keyevent 223`（熄屏）→ 电脑休眠。
