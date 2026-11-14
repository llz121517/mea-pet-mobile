# 旧版兼容迁移：引用点与删除清单

> 模块位置：`app/src/main/java/com/meapet/mobile/legacy/`
> 模块内纪律：见同目录 `README.md`
> 本文记录**具体引用点、预计移除版本、删除步骤**，变更迁移模块时必须同步更新本文。

---

## 1. 预计移除版本

**1.9.0**（确认老用户升级覆盖率足够后）。在此之前每个小版本发布前确认一次是否可以删。

---

## 2. 迁移步骤清单

| 文件 | 职责 | 预计移除版本 | 被引用点 |
|---|---|---|---|
| `LegacyMigration.kt` | 总入口：IO 线程按 `STEPS` 顺序执行，单步失败只记日志，结束 `refreshState()` | 1.9.0 | `AppContainer.warmUp()` |
| `LegacyMigrationContext.kt` | 迁移上下文：`ttsModelDir` / `settingsManager` / `ttsLegacyDetected` 标志 | 1.9.0 | 仅 `steps/*` |
| `LegacyMigrationStep.kt` | 单步接口 `suspend fun migrate(context)` | 1.9.0 | 仅 `LegacyMigration.STEPS` 与 `steps/*` |
| `steps/LegacyTtsModelCleanup.kt` | 旧版 4 模块 ONNX（`enc_p/dp/flow/dec.onnx`，~73MB）→ 删除整个 `tts_model` 目录，置 `ttsLegacyDetected` | 1.9.0 | `LegacyMigration.STEPS` |
| `steps/LegacyTtsNativeLibCleanup.kt` | 旧版下载的 `tts_model/lib/<abi>/`（~20MB）→ 删除；置 `ttsLegacyDetected` | 1.9.0 | `LegacyMigration.STEPS` |
| `steps/LegacyTtsSwitchReset.kt` | `ttsLegacyDetected == true` 时关闭两个语音开关（主界面 / 悬浮窗） | 1.9.0 | `LegacyMigration.STEPS`（必须排在最后） |

依赖关系：`LegacyTtsSwitchReset` 依赖前两步置位的 `ttsLegacyDetected`，因此 `STEPS` 顺序不可调整。

---

## 3. 删除清单（到 1.9.0 时执行）

1. 删除整个目录 `app/src/main/java/com/meapet/mobile/legacy/`（含 `README.md`）；
2. 删除 `app/src/test/java/com/meapet/mobile/legacy/` 下全部测试；
3. 删除 `AppContainer.warmUp()` 中的一处调用：
   ```kotlin
   launch { LegacyMigration.run(context, settingsManager, ttsModelManager) }
   ```
   以及随之不再使用的 import；
4. 删除本文档 `docs/legacy-compat.md`，并同步更新 `docs/tts/migration-report-1.8.0.md` 的升级说明（改为「无旧版兼容逻辑」）；
5. 全仓 grep 确认无残留引用：
   ```
   grep -rn "com.meapet.mobile.legacy\|LegacyMigration\|ttsLegacyDetected" app/src docs
   ```
6. 跑全量单测 + `assembleDebug`，确认清理 `TtsModelManager.init` 后行为不变。

---

## 4. 现有外部引用点（随代码维护）

| 被引用 | 引用方 | 说明 |
|---|---|---|
| `LegacyMigration.run(context, settingsManager, ttsModelManager)` | `AppContainer.warmUp()`（`app/src/main/java/com/meapet/mobile/app/AppContainer.kt`） | App 启动时异步执行，不 join、不阻塞其它预热 |
| `TtsModelManager.modelDir()` / `refreshState()` | `LegacyMigration.kt` | 只读目录路径与状态刷新，不写模型管理逻辑 |
| `SettingsManager.setTtsMainEnabled(false)` / `setTtsOverlayEnabled(false)` | `steps/LegacyTtsSwitchReset.kt` | 命中旧版 TTS 残留时关闭两个开关 |

---

## 5. 升级行为（1.7.x → 1.8.0，供测试与排障）

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
