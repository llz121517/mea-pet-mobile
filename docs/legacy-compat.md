# 版本迁移框架：引用点与当前迁移步骤

> 框架位置：`app/src/main/java/com/meapet/mobile/legacy/`（**长期保留**）
> 模块内纪律：见同目录 `README.md`
> 本文记录**具体引用点、当前 step 的移除计划、删除步骤**，新增/删除迁移时必须同步更新本文。
> 关联：1.11.0 的删除动作在 [`TODO.md`](TODO.md) 待办 0 跟踪；文档地图见 [`README.md`](README.md)。

---

## 1. 框架与步骤的区别

| | 位置 | 生命周期 |
|---|---|---|
| 迁移框架（总入口 / 上下文 / 接口） | `LegacyMigration.kt`、`LegacyMigrationContext.kt`、`LegacyMigrationStep.kt` | **长期保留**，每次不兼容变更都在这里加 step |
| 具体迁移步骤 | `steps/*.kt` | **一次性**，每个 step 头部注明「预计移除版本 + 被引用点」，到期单独删除 |

---

## 2. 框架引用点

| 被引用 | 引用方 | 说明 |
|---|---|---|
| `LegacyMigration.run(context, settingsManager, ttsModelManager, memoryStore)` | `AppContainer.warmUp()`（`app/src/main/java/com/meapet/mobile/app/AppContainer.kt`） | App 启动时异步执行，不 join、不阻塞其它预热 |
| `TtsModelManager.modelDir()` / `refreshState()` | `LegacyMigration.kt` | 只读目录路径与状态刷新，不写模型管理逻辑 |
| `MemoryStore`（`memoryRepository`） | `AppContainer.warmUp()` → `LegacyMigration.run` | 供「运行状态从 DataStore 迁到数据库」类步骤读写 |
| `SettingsManager.getLegacyExchangeCount()` / `clearLegacyExchangeCount()` | `steps/LegacyExchangeCountMigration.kt` | 仅供该 step 读取并删除旧键，业务代码不得使用 |
| `LegacyMigrationContext` / `LegacyMigrationStep` | 仅本包内 `steps/*` | 业务代码不得直接引用 |

---

## 3. 当前迁移步骤

| 文件 | 职责 | 预计移除版本 | 被引用点 |
|---|---|---|---|
| `steps/LegacyTtsModelCleanup.kt` | 旧版 4 模块 ONNX（`enc_p/dp/flow/dec.onnx`，~73MB）→ 删除整个 `tts_model` 目录，置 `ttsLegacyDetected` | 1.11.0 | `LegacyMigration.STEPS` |
| `steps/LegacyTtsNativeLibCleanup.kt` | 旧版下载的 `tts_model/lib/<abi>/`（~20MB）→ 删除；置 `ttsLegacyDetected` | 1.11.0 | `LegacyMigration.STEPS` |
| `steps/LegacyTtsSwitchReset.kt` | `ttsLegacyDetected == true` 时关闭两个语音开关（主界面 / 悬浮窗） | 1.11.0 | `LegacyMigration.STEPS`（必须排在最后） |
| `steps/LegacyExchangeCountMigration.kt` | 旧版存在 DataStore 的对话轮数计数迁入 SQLite `meta` 表（库中已有则不覆盖），随后删除 DataStore 旧键 | 1.11.0 | `LegacyMigration.STEPS` |

依赖关系：`LegacyTtsSwitchReset` 依赖前两步置位的 `ttsLegacyDetected`，因此其前面两个 TTS step 顺序不可调整。
`LegacyExchangeCountMigration` 不依赖其它 step，但必须在 `AppContainer.warmUp()` 完成 legacy JSON 导入、数据库已可打开之后运行（当前 `run` 即在此之后）。

---

## 4. 删除单个 step 的步骤（以 TTS 组为例，预计 1.11.0）

1. 删除 `steps/LegacyTtsModelCleanup.kt`、`steps/LegacyTtsNativeLibCleanup.kt`、`steps/LegacyTtsSwitchReset.kt`；
2. 从 `LegacyMigration.STEPS` 移除对应注册项；若该组独占字段（如 `LegacyMigrationContext.ttsLegacyDetected`）已无人使用，一并清理；
3. 删除 `app/src/test/java/com/meapet/mobile/legacy/` 下对应测试类；
4. 更新本文 §3；归档报告按 `docs/README.md` §3 的「归档不再维护」规则不改，如需留痕只在归档文件头部加一行一次性状态注记；
5. 全仓 grep 确认无残留引用：
   ```
   grep -rn "LegacyTts\|ttsLegacyDetected" app/src docs
   ```
6. 跑全量单测 + `assembleDebug`。**不要删除 `LegacyMigration`/`LegacyMigrationStep`/`LegacyMigrationContext` 与 `AppContainer.warmUp()` 中的调用。**

---

## 5. 升级行为（TTS step 组：1.7.x → 1.8.0，供测试与排障）

1. App 启动 → `warmUp()` 异步触发 `LegacyMigration.run(...)`；
2. `LegacyTtsModelCleanup`：`filesDir/tts_model/` 内检测到 `enc_p.onnx` 等旧文件 → 删除整个目录 → `ttsLegacyDetected = true`；
3. `LegacyTtsNativeLibCleanup`：删除 `tts_model/lib/` 旧 native 库残留（同样置标志）；
4. `LegacyTtsSwitchReset`：置两个语音开关为 `false`；
5. `refreshState()`：模型状态变为「未下载」，设置页提示约 84MB，用户重新下载或本地 zip 导入。

日志 tag：`LegacyMigration`（每步），单步失败为 `W` 级；启动日志可 grep：
```
adb logcat -s LegacyMigration
```

> 另有一个 1.8.x → 1.9.0 的 `LegacyExchangeCountMigration`（见 §3）：把 DataStore 里的对话轮数迁入 SQLite `meta` 表，随后删除旧键；幂等，库中已有计数时不覆盖。它不改 TTS/语音开关状态，行为不同于上面三步。

---

## 6. 验证方式

- 单测：`app/src/test/java/com/meapet/mobile/legacy/`（目录清理、幂等、开关联动）；
- 真机模拟升级：把旧 `enc_p.onnx` 推到 `files/tts_model/` 后启动，确认目录被清空、两个开关关闭、设置页显示未下载（详见 `docs/archive/checklists/2026-11-15-tts-device-test-1.8.0.md` 升级迁移用例）。

## 7. 1.9 记忆数据兼容（不属于迁移 step）

记忆 / 会话 JSON 导入由 `LegacyJsonImporter` 在 SQLite 可用后执行，不注册到上述 `LegacyMigration.STEPS`。历史 JSON 记录里的废弃记忆操作附加字段会被忽略，不会被重放或写入记忆。

开发期旧 SQLite 数据库可能保留 nullable 的旧记忆操作列。运行时不读取、不写入该列，也不通过破坏性迁移清除它；新建数据库不包含该列。记忆数据、会话历史与稳定人设引用不因这一兼容处理而删除。当前模式行为见 [`2026-11-18-memory-tools-plan.md`](archive/design/2026-11-18-memory-tools-plan.md)。

---

## 8. 备份格式升级与迁移 step 的联动约定

**备份包格式升级时，必须与 legacy 迁移 step 同步提供导入兼容。**

具体说，备份的 `format`（包级）或某条目 `schema`（条目级）升到新值时，同一次改动里必须做到：

1. 在本文 §3 增加一个升级 step，把用户手上已有的旧格式备份文件升级到新格式；
2. 在备份导入侧提供对应的兼容路径，或明确写出「旧 format 包由该 step 升级后导入」。

不允许出现「新版只认新包、用户旧备份变砖」的情况。备份格式与包版本常量见
[`2026-11-22-backup-export-import-prd.md`](archive/design/2026-11-22-backup-export-import-prd.md) §3、§6。

