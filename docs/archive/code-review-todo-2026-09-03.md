# MeaPet Mobile 待办清单（基于 2026-09-03 独立复核）

- **基线**：`dev` @ `8371c3b`
- **来源**：`code-review-verification-2026-09-03.md`（48 条复核 + 10 条新发现）
- **口径**：只列真实存在且值得动的；已剔除 #6（不成立）、#43 / #44（无实际危害）；#2 #3 #4 #5 #7 #39 #40 #46 已在 `8371c3b` 修完，只保留残留项
- **工时**：S ≤ 30min｜M = 半天｜L = 1–3 天｜XL = 需单独排期
- 提交信息沿用 `Fix:` / `Update:` / `Add:` 分段，长解释写进 commit message

---

## 批次 1 · 小改动高收益（建议一个 PR 打包，全部 S）

- [x] **新-1 修系统气泡"扣寿命"变"续命"** — `ChatViewModel.kt:111-115` + `:402-415`
      `lifeMap` 改存绝对 deadline（或 `scheduledAt`），扣减时 `newDeadline = deadline - REDUCE_STEP_MS`，
      重排用 `delay(newDeadline - now)`。顺手删掉 `scheduleRemove` 里 `delayMs <= 0` 的分支（新-9）
      或改成 `if (delayMs <= 0) { lifeMap.remove(msgId); removeBubble(msgId) }` 的正确顺序。
      **补一个 ChatViewModel 层测试**（虚拟时间推进 6s → 触发挤位 → 断言总寿命 < 7s）；
      现有 `SystemBubblePolicyTest` 只测纯函数，抓不到这个。
- [x] **新-5 / #18 让网关错误可见** — `ApiException.kt` + `OpenAiCompatibleClient.kt:126-130`
      解析 `responseBody` 里的 `error.message` 拼进用户文案（如 `请求过于频繁：<原文>`）；
      构造 `ApiException` 处至少 `Log.w(TAG, body)` 一次。
      ⚠️ 这条直接关系到 issue #15 里"发消息常报请求过于频繁"目前**连日志都查不出原因**，建议排最前。
- [x] **新-4 `onResume` 轮询加超时** — `MainActivity.kt:269-299`
      `Live2dRenderState.isRunning` 已是 StateFlow，换成
      `lifecycleScope.launch { withTimeoutOrNull(2000) { Live2dRenderState.isRunning.first { !it } }; glSurfaceView.onResume() }`，
      并删掉 `resumeGate`/`mainHandler` 那套。超时也要 resume，否则黑屏。
- [x] **#38 `LogExporter.waitFor()` 加超时** — `LogExporter.kt:149`
      `process.waitFor(5, TimeUnit.SECONDS)` + `destroyForcibly()` 兜底。危害极低，但一行的事。
- [x] **新-6 修 `MAX_TOTAL_FRAMES` 死护栏** — `DurationExpander.kt:24-28`
      注释"约等于 22 秒音频"错了约 250 倍（480000 帧 ≈ 93 分钟）；且在 `MAX_SYNTH_CHARS=200` 下永不触发。
      要么把上限下调到真能拦住异常路径的值（如 60 秒 ≈ 5200 帧），要么删掉并在注释里说明
      真正生效的是 `MAX_FRAMES_PER_PHONE`。
- [x] **新-7 / #32 修 `ConversationManager` 文档** — `:16`、`:142`
      `:142` 的"最后一条非 system 消息"与实现（只取 `role == user`）不符；
      `:16` 的"不关心消息是用户还是助手"与 `lastUserMessage()`/`lastAssistantMessage()`/`trimWindow` 矛盾。改注释即可。
- [x] **新-10 `retryLastMessage` 加边界判断** — `ChatService.kt:171`
      `if (userIdx < 0) return Result.failure(IllegalStateException("消息已不在历史中"))`，
      防止 `drop(0)` 误删全部 assistant 消息。当前不可达，一行成本。

> 建议 commit message：
> `Fix: 系统气泡挤位后寿命被重新计时导致延长` / `Fix: API 网关错误详情丢失，用户与日志均看不到原因` /
> `Fix: onResume 等待悬浮窗停止的轮询无超时上限` / `Update: 修正 TTS 帧数上限护栏与失效注释`

---

## 批次 2 · 稳定性（M，可分两个 PR）

- [ ] **#37 去掉 `currentPrefs()` 的主线程 `runBlocking`** — `SettingsManager.kt:192-193`
      异步预热**已存在**（`:44-51`），只需去掉兜底：`cachedPrefs` 为空时直接返回 `emptyPreferences()`，
      让各 getter 落到 `SettingsKeys.Defaults`；首帧主题闪一下由 `MainActivity.kt:87` 已有的 Flow 接管修正。
      若不接受首帧闪色，退路是在 `MeaPetApplication.onCreate` 里 `runBlocking` 一次（把阻塞挪到 Application 而非 Activity）。
- [ ] **#15 + 新-8 隐私授权统一存储源** — `PrivacyConsentManager.kt:51-69`
      ① 授权状态只留一份（推荐 SharedPreferences，因为要在 `Application.onCreate` 同步读）；
      ② `agreedFlow` 改为从同一份 SP 派生（`OnSharedPreferenceChangeListener` → `callbackFlow`），消除双源不一致；
      ③ 把 `setAgreed` 改 `suspend`，`commit()` 与写入都挪到 IO，`killProcess` 放在写完之后的回调里。
      注意 `agreedFlow` 有真实消费者（`SettingsViewModel.kt:177` → 关于页开关），改动要同步验证该开关仍响应。
- [ ] **#42 记忆库写放大** — `MemoryRepository.kt:280-296`、`MemoryService.kt:65-105`
      ① `applyOps` 批量化：现在循环里每个 op 调一次 `save`/`delete`，每次全量重写整库（约 180 KiB）；
      ② `enforceCapacity`（`:314-317`）把 FACTUAL/CORE_TRAIT 排除在 `maxItems=500` 之外 → 永久类条目**无上限**，
      写放大会随时间线性增长，给个硬顶或独立上限。
- [ ] **#19 `getRelevant()` 读写分离** — `MemoryRepository.kt:179-206`
      拆出 `markAccessed(ids)` 命令方法由调用方（`MemoryManager.kt:121`）显式调用；
      顺带解决"每轮命中就整库落盘一次"（与 #42 一起做更省事）。
- [ ] **#7 残留 · 持久化补 flush 时机** — `ConversationStore.kt`
      `replay=1 + DROP_OLDEST` 的 conflate 队列在进程被杀时没有落盘时机。
      在 `MainActivity.onStop` 或 `onTrimMemory` 调一次 `persist(snapshot)`（`suspend`，已有实现）。

---

## 批次 3 · 架构重构（XL，逐项独立 PR，不要混做）

按依赖顺序排，越靠前越应该先做：

- [ ] **#13 服务定位器 → CompositionLocal / 构造注入** — 7 处使用方
      `MainActivity:80`、`ChatScreen:161`、`OverlayMenu:74`、`FloatingLive2dService:375`、`OverlayPalette:46`、
      `ChatViewModel:43`、`SettingsViewModel:82`。
      `MeaPetApplication.from()` 的 KDoc 自己写着"仅在无法通过构造注入的场景使用（如较旧的 Java 组件）"——
      现状是全线在用，先把 Compose 侧换成 `LocalContainer`，ViewModel 侧换 `ViewModelProvider.Factory`。
- [ ] **#11 + 新-3 消息源统一** — `ChatViewModel.kt:178-199`
      让 `ConversationManager` 暴露 `StateFlow<List<ChatMessage>>` 成为唯一真相源，ViewModel 只订阅不自维护副本，
      触摸气泡改为独立的 `overlayBubbles` 列表在 UI 层拼接。这样 `mergeWithHistory`、`takeLast(5)` 兜底、
      `reloadHistory` 的 `ON_RESUME` hook 全部可以删掉。
- [ ] **#14 并发原语统一（含 #1 lifeMap）** — `ConversationManager` / `ConversationStore` / `ChatViewModel`
      `ConversationManager` 的 `synchronized(lock)` 改协程 `Mutex` + `suspend`（主线程确实会进这把锁：
      `ChatViewModel.kt:57/160/316` 三条路径）；lifeMap 随 #11 收敛为 actor / 单协程状态机。
- [ ] **#16 客户端生命周期统一** — `SettingsViewModel.kt:385-453`
      `fetchModels` 里 `:402 reloadClient()` 对本次拉取毫无作用（拉取用的是 `:405` 临时 new 的实例）。
      改成"落盘 → `reloadClient()` → 用容器的 `apiClient` 拉取"，删掉临时客户端。
- [ ] **#9 拆 ChatViewModel（423 行 / 9 块职责）与 SettingsViewModel（499 行 / 30 字段 / 32 公开方法）**
      ChatViewModel 优先拆出：气泡寿命调度、更新检测、记忆 CRUD；
      SettingsViewModel 按 Api / Tts / Appearance / Privacy 拆，或先把 `SettingsUiState` 按域拆成嵌套子状态。
- [ ] **#8 拆 Live2dDelegate（338 行 / 7 项职责）**
      优先切出：触摸交互与分区判定（`:224-330`）、分区语音与 TTS 互斥（`:259-291`）、
      壁纸与背景色参数（`:69-101`）。GL 生命周期 + 单例 + Activity 持有留在原类。
- [ ] **#10 companion var 布线改显式注入** — `AppContainer.kt:193-200`
      `Live2dDelegate.ttsPlayingChecker` / `ttsStopper` / `TtsManager.onPlaybackStart` 三个全局可变字段
      改为一个 `VoiceMutex` 接口（或共享 `StateFlow<Boolean>`）由容器注入。
- [ ] **#12 `Live2dModel` 别调 `Activity.finish()`** — `Live2dModel.kt:144, 219`
      改为把加载失败当作返回值/回调上抛（`onModelLoadFailed`），由 Activity 自行决定 finish。
      注意 `draw()` 跑在 GL 线程，回调要切主线程。
- [ ] **#31 拆 `ChatUiState`（7 字段 / 6 类关注点）** — `ChatState.kt:20-28`
      `updateNotice` 与 `memoryDialog` 移出（#4 那个 bug 就是它俩被卷进整体重置导致的）。

---

## 批次 4 · 异味 / 规范 / 性能（可按主题批量提交）

**重复代码**

- [ ] #20 `SettingsManager` 22 项设置 × 3 份样板（65 个重复成员）→ 泛型 `Setting<T>` + 委托生成 Flow/get/set
- [ ] #21 `SettingsViewModel` 5 处"读旧值-比较-写"（`saveApiKey:188` / `saveApiUrl:197` / `saveModel:206` / `saveSystemPrompt:214` / **`selectModel:455`**）→ 抽 `saveIfChanged`
- [ ] #25 抽公共基类：`dp()` / `Int.withAlpha()` 四处逐字重复（`OverlayAlphaWindow:248/251`、`OverlayBubbleWindow:303/305`、`OverlayInputWindow:330/332`、`OverlayMenuWindow:308/311`）；着色器 `compileShader` / `checkShaderCompiled` / **`checkProgramLinked`** 共 30 行逐字重复（`Live2dSpriteShader:48-77` ≡ `WallpaperBlurShader:173-202`）。**`place()` 只有 3 处且彼此有差异，别硬抽**
- [ ] #26 抽 `MeaPetDialogCard`：`PrivacyDialog:67` / `AutoUpdateOptInDialog:37` / `ChatScreen:350`（420/24/48/12/20 五个数值逐字一致）
- [ ] #45 抽 `BubbleContainer`：**只对 `UserBubble:54-85` 与 `AssistantBubble:87-148` 有效**，`SystemBanner:150-175` 结构不同别硬合
- [ ] #33 手写 Animatable 三状态机 → `AnimatedVisibility`：`OverlayMenu:60-70` 与 **`AppearanceSettings:379-389`**（这两处才是逐字重复）；`PrivacyDialog:61-65` 只有单向进场，另算

**可读性 / 命名**

- [ ] #24 `Triple<Long, Job, Int>`（`ChatViewModel.kt:86`）→ `data class BubbleLife(deadlineMs, job, reduceCount)`（与新-1 一起做）
- [ ] #17 / #47 `Pair` 换命名 data class：`ChatService:58/164` → `ChatExchange(user, assistant)`；`SystemBubblePolicy:35` → `NextLife(lifeMs, reduceCount)`；`SymbolTable:48` → 命名类型。**`checkForUpdate` 不在此列**（返回 Unit）
- [ ] #22 魔法数字提常量（8 处已核实全中）：`SystemBubblePolicy:43` 的排位阈值 `3`、`ChatViewModel:189` 的 `takeLast(5)`、`KtorHttpClientEngine:28-30` 的 `300_000`/`30_000`（顺便做成可配）、`SettingsViewModel:496-497` 的 `8`/`4`、`MainActivity:89/93/95` 的 `0x14`/`0xF7`/`0xFFF7F7F7`、`Live2dDelegate:282/297` 的 `30f`/`400L`/`±0.45f`/`-0.44f`/`0.66f`、`LogExporter:60` 的 `dropLast(5)`、`ChatScreen:244/278` 的 `100.dp`/`88.dp`
- [ ] #48 / #35 拆超长方法：`ChatService.sendMessage`（102 行 / 6 类职责）拆 `buildRequest` / `parseReply` / `schedulePostProcess`；`MainActivity.onCreate`（182 行 / 14 类职责）拆 `setupFirstFrameTheme` / `setupGlSurface` / `setupComposeContent` / `setupTouchForwarding`

**规范 / i18n**

- [ ] #23 文案抽 `strings.xml`：实测中文字面量 **387 处**、`stringResource` **0 次**。先抽高频页（`ChatScreen` 18 处、`PrivacyDialog` 11 处）；`SettingsKeys.kt:67-68` 的 SystemPrompt（745 字符 / 632 汉字）移 `assets/` 或 `res/raw/`
- [ ] #27 补空 catch 注释：全树 26 个空 catch、**18 处无注释**（`OverlayInputWindow` 6、`OverlayMenuWindow` 4、`MainActivity` 4、`OverlayAlphaWindow` 3 等）。`ErrorHandling.kt:20-22` 明文要求"必须有一行注释说明为何可安全忽略"
- [ ] #29 `EXCHANGE_COUNT`（`SettingsKeys.kt:18-24`）另立 `ExchangeCounter` 仓储（它也是唯一没有 Flow 的键）
- [ ] #30 + 新-2 `isStreaming` 处置 — **它在 main 源码里从未被置 true，`ChatService.kt:103` 固定 `stream = false`**。要么删掉它连同 `ChatBubble:137-145` 的死 UI 分支与 `MarkdownText` 的 `closeUnclosedFences` 分支，要么真的把流式做出来。别留着当装饰
- [ ] #28 清死代码：`Live2dSprite:16 textureId` 构造参数（被 `:48` 同名形参遮蔽）、`Live2dView:111-115 setClearColor()`、`Type.kt:18-33` 注释掉的 16 行、`ApiRequest:46/52/58` 三个仅测试调用的方法、`MeaPetApplication:101-104 onTerminate()`、`Color.kt:121-127` **6 个**零引用常量

**性能**

- [ ] #41 `DurationExpander:80` 稠密 attn 矩阵改稀疏/逐行流式（正常路径 12–36 MiB，不是数百 MB；优先级可放低，但与新-6 的护栏一起看）

**工程**

- [ ] #34 依赖拉齐 — `libs.versions.toml`：`coreKtx 1.10.1`（:3）、`lifecycleRuntimeKtx 2.6.1`（:7）、`lifecycleViewmodelCompose 2.6.1`（:8）与 `agp 9.3.0` / `kotlin 2.2.10` / `composeBom 2026.02.01` 断层严重；`build.gradle.kts:81-82/106` 的 Java 11 可升 17；`:51` 的 `abiFilters` 缺 **x86_64 与 x86**（模拟器调试不便）
- [ ] 建议追加：项目**没有 CI**（无 `.github/workflows`）。批次 1 的测试补完后加一个只跑 `./gradlew testDebugUnitTest` 的 workflow，成本低、能兜住这类"纯函数测了、接线没测"的漏洞

---

## 不做（已判定无收益）

- ~~#6 条件分支内 `collectAsState`~~ — 合法 Compose 用法，非缺陷（该段真正的问题是 #13）
- ~~#44 `ensureDict` 双重检查锁~~ — 底层 `PinyinDict.init` 已 `@Synchronized` + 幂等，无实际危害
- ~~#43 `WallpaperStore` 三次开流~~ — content 流不可回绕，测尺寸+解码天然两次；只有 EXIF 那次可省，不划算
- ~~#3 继续深挖 `trimWindow`~~ — 已修，且 `ConversationManager` 里从来不存 system 消息，原缺陷路径本就走不到

---

## 验证要求

- 批次 1 与批次 2 的每一项都要能独立编过（`./gradlew assembleDebug`，bisect safe）
- **运行时行为必须真机验证**（容器无设备）：气泡寿命（新-1）、黑屏恢复（新-4）、授权开关一致性（#15）、报错文案（#18）
- 提交前 `git status` 确认 `gradle.properties` / `local.properties` / `gradlew` 不在暂存区

---

## 追加候选项（2026-09-05，批次一真机验证后新增）

### 候选-1 悬浮窗返回主界面后约 2 秒才出画面

**现象**：真机验证批次一「新-4」时观察到，从悬浮窗返回主界面，Live2D 约 2 秒后才恢复显示。

**已排除**：不是新-4 的等待逻辑。导出日志里没有
`Overlay service still running after 2000ms, resuming GL anyway`，说明 gate 正常放行；
且 `FloatingLive2dService.onDestroy()` 的**第一行**就是 `Live2dRenderState.setRunning(false)`，
标志位几乎瞬间翻转，等待耗时可忽略。

**推测方向**（未验证）：时间花在 `glSurfaceView.onResume()` 之后的 GL 重初始化上——
Service 侧曾 `deleteInstance` 重建 `CubismShaderAndroid` 单例，Activity 的 GL 线程恢复后
需要重建 shader 并重新加载模型与纹理。

**下一步**：加带时间戳的 log 打点区分三段耗时（stopService→onDestroy 派发、
gate 等待、GL 恢复→首帧），再决定是否值得优化（例如恢复期间显示上一帧快照或渐显，
避免用户看到纯黑）。

**优先级**：低。属既有延迟，新旧代码一致，不是本次改动引入的回归。
