# 版本迁移框架：引用点与当前迁移步骤

> 框架位置：`app/src/main/java/com/meapet/mobile/legacy/`（**长期保留**）
> 模块内纪律：见同目录 `README.md`
> 本文记录**具体引用点、当前 step 的移除计划、删除步骤**，新增/删除迁移时必须同步更新本文。
> 关联：1.9.0 的删除动作在 [`TODO.md`](TODO.md) 待办 0 跟踪；文档地图见 [`README.md`](README.md)。

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
| `LegacyMigration.run(context, settingsManager, ttsModelManager)` | `AppContainer.warmUp()`（`app/src/main/java/com/meapet/mobile/app/AppContainer.kt`） | App 启动时异步执行，不 join、不阻塞其它预热 |
| `TtsModelManager.modelDir()` / `refreshState()` | `LegacyMigration.kt` | 只读目录路径与状态刷新，不写模型管理逻辑 |
| `LegacyMigrationContext` / `LegacyMigrationStep` | 仅本包内 `steps/*` | 业务代码不得直接引用 |

---

## 3. 当前迁移步骤

| 文件 | 职责 | 预计移除版本 | 被引用点 |
|---|---|---|---|
| `steps/LegacyTtsModelCleanup.kt` | 旧版 4 模块 ONNX（`enc_p/dp/flow/dec.onnx`，~73MB）→ 删除整个 `tts_model` 目录，置 `ttsLegacyDetected` | 1.9.0 | `LegacyMigration.STEPS` |
| `steps/LegacyTtsNativeLibCleanup.kt` | 旧版下载的 `tts_model/lib/<abi>/`（~20MB）→ 删除；置 `ttsLegacyDetected` | 1.9.0 | `LegacyMigration.STEPS` |
| `steps/LegacyTtsSwitchReset.kt` | `ttsLegacyDetected == true` 时关闭两个语音开关（主界面 / 悬浮窗） | 1.9.0 | `LegacyMigration.STEPS`（必须排在最后） |

依赖关系：`LegacyTtsSwitchReset` 依赖前两步置位的 `ttsLegacyDetected`，因此 `STEPS` 顺序不可调整。

---

## 4. 删除单个 step 的步骤（以 TTS 组为例，预计 1.9.0）

1. 删除 `steps/LegacyTtsModelCleanup.kt`、`steps/LegacyTtsNativeLibCleanup.kt`、`steps/LegacyTtsSwitchReset.kt`；
2. 从 `LegacyMigration.STEPS` 移除对应注册项；若该组独占字段（如 `LegacyMigrationContext.ttsLegacyDetected`）已无人使用，一并清理；
3. 删除 `app/src/test/java/com/meapet/mobile/legacy/` 下对应测试类；
4. 更新本文 §3 与 `docs/tts/migration-report-1.8.0.md` 的升级说明（改为「不再需要旧版 TTS 迁移」）；
5. 全仓 grep 确认无残留引用：
   ```
   grep -rn "LegacyTts\|ttsLegacyDetected" app/src docs
   ```
6. 跑全量单测 + `assembleDebug`。**不要删除 `LegacyMigration`/`LegacyMigrationStep`/`LegacyMigrationContext` 与 `AppContainer.warmUp()` 中的调用。**

---

## 5. 升级行为（1.7.x → 1.8.0，当前 step 组，供测试与排障）

1. App 启动 → `warmUp()` 异步触发 `LegacyMigration.run(...)`；
2. `LegacyTtsModelCleanup`：`filesDir/tts_model/` 内检测到 `enc_p.onnx` 等旧文件 → 删除整个目录 → `ttsLegacyDetected = true`；
3. `LegacyTtsNativeLibCleanup`：删除 `tts_model/lib/` 旧 native 库残留（同样置标志）；
4. `LegacyTtsSwitchReset`：置两个语音开关为 `false`；
5. `refreshState()`：模型状态变为「未下载」，设置页提示约 84MB，用户重新下载或本地 zip 导入。

日志 tag：`LegacyMigration`（每步），单步失败为 `W` 级；启动日志可 grep：
```
adb logcat -s LegacyMigration
```

---

## 6. 验证方式

- 单测：`app/src/test/java/com/meapet/mobile/legacy/`（目录清理、幂等、开关联动）；
- 真机模拟升级：把旧 `enc_p.onnx` 推到 `files/tts_model/` 后启动，确认目录被清空、两个开关关闭、设置页显示未下载（详见 `docs/tts/device-test-checklist.md` 升级迁移用例）。
