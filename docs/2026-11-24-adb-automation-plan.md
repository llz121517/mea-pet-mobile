# 检查单的 adb 自动化分拣与执行记录（1.9.0）

> **状态**：一次性执行记录（2026-11-24），**已被本节结论消费**；检查单本身见 [`2026-11-22-release-checklist.md`](2026-11-22-release-checklist.md)。
> **设备**：Xiaomi `22041216UC`（MT6895Z，Android 14 / SDK 34，`arm64-v8a,armeabi-v7a`），已 root（Magisk）。
> **方式**：`adb` + `uiautomator dump` + `screencap`；截图只落电脑（`adb shell screencap -p` → `adb pull`），**未写入设备相册**。

---

## 1. 分拣原则

| 分层 | 判据 | 本次处理 |
|---|---|---|
| **A · 纯 adb 可自动** | 结果可由命令输出 / 数据库查询 / logcat / 截图判定，无需人眼审美判断 | 执行 |
| **B · 半自动** | 可用 adb 驱动到目标状态，但观感 / 听感 / 动画顺滑度需人工判 | 执行到「产生证据」，结论留人工 |
| **C · 人工** | 需真实第三方服务、真实听音、审美判断或物理操作 | 不执行 |
| **D · 与设备无关** | 构建 / 文档 / git 类 | 顺手执行 |

**结论分档**：`✅ 已通过` / `🟡 部分完成` / `⬜ 未执行` / `❌ 失败`。

---

## 2. 分拣结果

### 2.1 构建与文档（D 层，全部可自动）

| 检查单项 | 层 | 方式 | 结论 |
|---|---|---|---|
| 版本号锁定 | D | `grep versionCode/versionName` | ✅ |
| 全量单测（574 条） | D | `gradlew testDebugUnitTest` | ✅ |
| debug / release 构建 | D | `assembleDebug` / `assembleRelease -PappAbi=v8a` | ✅ |
| 文档链接检查 | D | `node tools/check_doc_links.mjs` | ✅ |
| 工作区干净 | D | `git status --short` | 🟡 检查单自身改写待提交 |
| 三档 ABI 产物 | D | `dist/` 清单 + `apksigner` | ⬜ 只有 v8a |
| dev → main 合并 | D | `git rev-list --count` | ⬜ 落后 78 个提交 |
| CHANGELOG 定稿 | 人工 | 发布日与文案判断 | ⬜ |

### 2.2 功能验收（A 层：本轮实测通过）

| 检查单项 | 结论 | 证据 |
|---|---|---|
| 迁移路径 1.7.2 → 1.9 | ✅ | 5 消息 / 3 记忆 / 6 关键词 / `legacy_json_import=done` / `user_version=1`；第二次启动幂等 |
| `PRAGMA journal_mode` | ✅ | `wal` |
| 升级后隐私政策重新确认 | ✅ | 启动即弹「隐私政策更新」 |
| 按天时间分隔条 | ✅ | 注入 2023-11-15 消息后插入「2023年11月15日」 |
| 迁移数据在 UI 落地 | ✅ | 5 条消息按 seq 顺序显示，气泡左右正确 |
| 设置根页六项摘要 | ✅ | 六项摘要全部符合 |
| 顶部菜单条件显示 | 🟡 | v8a 显示「悬浮窗」、未达阈值无「压缩上下文」；v7a 侧未验 |
| 导出：UI 文案与默认文件名 | ✅ | 「备份密码」「密码」「再次输入密码」；预填 `20261123T160134Z_MeaPet-backup.zip` |
| 导出：API Key 跟随设置置灰 | ✅ | 取消「设置」→「需先勾选「设置」」；勾上 →「随设置写入」 |
| 导出：确认弹窗 | ✅ | 标题「确认导出数据」；勾选后列出「· API Key」 |
| 导出：真实写出与 AES-256 | ✅ | `/sdcard/Documents/…zip`（1699 B）；无 / 错密码均失败；正确密码解出 5 条目 |
| 导出：包内结构 | ✅ | `files/{settings,chat,memory,compaction}` + `manifest.json`，`format=1`、`appVersion=1.9.0` |
| 导入：清单与条数对比 | ✅ | 对话历史「共 819 B · 0 → 5 条」、记忆库「共 718 B · 3 → 3 条」 |
| 导入：强制确认弹窗 | ✅ | 逐项「将覆盖本机的 X」+ 条数对比 + 生效提示 |
| 导入：数据落地 | ✅ | 库恢复为 5 消息 / 3 记忆 / 6 关键词 |
| 导入后自动清空已选包 | ✅ | 回到「选择备份包…」初始态，`cache/backup/` 为空 |
| 导入回执不重复弹 | ✅ | 重进「数据与备份」页无「已恢复」残留、无 Snackbar |
| 清除对话 | ✅ | 空态 + Snackbar「已清除 5 条对话」+「撤销」 |
| 关于页版本与构建时间 | ✅ | `版本 1.9.0-61181ff58` + `构建时间 2026-11-24 00:07`；debug 版隐藏该行 |

### 2.3 功能验收（B / C 层：未执行）

| 层 | 条目 | 原因 |
|---|---|---|
| B | 连续回复分段 / 状态条与停止 / 插队 / 等待态禁用 / 工具轮次上限 / 请求超时 / 失败回滚 / 重试 / 记忆四档 / 只读回退 / 自动摘要触发 / 气泡透明度 / 壁纸虚化 / 主题切换 / 消息视口 / 手动与自动压缩 / 清除对话撤销 / 压缩分隔条 | 需真实聊天端点；部分项观感需人眼判 |
| C | TTS 全部（语速 / 混排 / 短片段 / 流式打断） | 需听音 |
| C | Live2D 渲染 / 透明度 / 冷启动观感 / 拖拽跟随 | 需人眼 |
| C | 联网工具（DeepSeek / AnySearch / Auto 切换 / 限流） | 需真实 Key 与额度 |
| C | v7a 包 32 位降级 | 本机是 v8a 设备，强制 32 位风险高（用户要求只动必要设置） |
| C | 数据与备份边界（版本不符整包拒绝 / SAF 取消 / 大壁纸） | 未构造用例 |
| 人工 | `NOTICE.md` 8 项确认 | 用户要求另行处理 |
| 人工 | CHANGELOG 定稿 / dev→main 合并 | 发版决策 |

---

## 3. 关键前提：设备有 root（Magisk）

`adb shell su -c id` 返回 `uid=0(root) ... context=u:r:magisk:s0`。这解决了两个卡点：

1. **绕过 MIUI「USB 安装需手动确认」**：`adb push <apk> /data/local/tmp/` → `su -c 'pm install -r -d /data/local/tmp/x.apk'`，无需在手机上点同意。
2. **读写应用私有目录**：release 包不可 `run-as`，改用 root 直接 `cp`。
   查库方式：`su -c 'cp /data/data/<pkg>/databases/meapet.db* /data/local/tmp/'` → `adb pull` → 本地 sqlite-jdbc 查询。设备上**没有 `sqlite3` 命令**，所有库查询都走「拉回电脑 + JDBC」。

---

## 4. 迁移路径验证：1.7.2 → 1.9（✅）

按「不用保数据、随便重装」执行：

1. 卸载全部 MeaPet 相关包（`com.meapet.mobile`、`com.meapet.mobile.test`、`com.meapet.android` 不同签名版本），`pm list packages | grep meapet` 为空。
2. root 安装 `dist/MeaPet-v1.7.2-v8a.apk`（versionCode 13）。
3. 启动 1.7.2 生成私有目录，root 注入假数据到 `files/`：
   - `meapet_conversation.json`：5 条消息（3 user + 2 assistant，id `mig-u1…mig-u3`）
   - `meapet_memories.json`：3 条记忆（`FACTUAL` / `LONG_TERM` / `CORE_TRAIT`，共 6 个 keyword）
   - 注意 `chown u0_a397:u0_a397` + `chmod 600`，否则应用读不到。
4. `su -c 'pm install -r -d meapet-190.apk'` 覆盖升级，确认 `versionCode=15`、`versionName=1.9.0-61181ff58`。
5. 启动 1.9，`logcat` 出现 `LegacyJsonImporter: Legacy import finished: 5 messages, 3 memories (imported=true)`。
6. 库查询断言：

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

7. **幂等性**：再启动一次 → 无 `imported=true` 日志；库仍为 5/3。此时 WAL 已 checkpoint（`meapet.db` 86KB、`-wal` 归零）。

> `imported=true` 只在**第一次**出现，之后是 `ALREADY_DONE`（不打印该行）。

---

## 5. 本轮发现并修复的 bug

**暂存文件不清理**（真机验证发现）
导入完成后 `cacheDir/backup/import_*.zip` 残留。原因：`closeReader()` 只关 zip 句柄不删文件。修复：重新引入 `stagedFile` 字段与 `OpenedPackage` 载体，`closeReader()` 关句柄后删除暂存文件。验证：完成一次导入后 `cache/backup/` 为空 ✅。

**撤销清除对话（误判，已更正）**
第一轮自动化曾判「撤销失效」，实际是脚本太慢——撤销窗口仅 5 秒（`UNDO_WINDOW_MS = 5_000L`），而「dump + pull + 换进程再点」要十几秒，点「撤销」时 Snackbar 早已消失。维护者手测正常。

> **教训**：带时限的交互不能用「dump → 解析 → 再点击」验证；adb 一次 dump+pull 就要 3~5 秒，必须固定坐标单次连续点击，或走 `UiDevice` 同进程操作。

---

## 6. 安全与善后

- 亮度设为最低（`screen_brightness=1`）、关闭自动亮度、屏幕常亮；结束后已恢复维护者原值（`screen_brightness=8`、`screen_brightness_mode=1`、`screen_off_timeout=60000`）。
- **截图只存电脑**，不写设备相册；设备临时文件已清理。
- 结束：`input keyevent 223`（熄屏）→ 电脑休眠。
- 未改动其它系统设置。

---

## 7. 复现命令

```powershell
# 设备与 ABI
adb shell getprop ro.product.cpu.abilist
adb shell dumpsys package com.meapet.mobile | Select-String versionName,versionCode,primaryCpuAbi

# root 安装（绕过 MIUI USB 确认）
adb push dist\MeaPet-v1.9.0-v8a.APK /data/local/tmp/meapet.apk
adb shell su -c "pm install -r -d /data/local/tmp/meapet.apk"

# 拉库到电脑查询
adb shell su -c "cp /data/data/com.meapet.mobile/databases/meapet.db* /data/local/tmp/"
adb pull /data/local/tmp/meapet.db .\tmp\meapet.db
adb pull /data/local/tmp/meapet.db-wal .\tmp\meapet.db-wal

# 迁移日志
adb logcat -d -s LegacyJsonImporter

# 暂存目录应为空
adb shell su -c "ls -l /data/data/com.meapet.mobile/cache/backup/"
```
