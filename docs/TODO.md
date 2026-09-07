# MeaPet 待办 · 代码审查跟踪

> **本文档是全项目唯一在维护的待办清单。** 三份历史审查文档已归档到 [`docs/archive/`](archive/)。
> 每次在此勾掉/新增条目时,顺手把对应的用户可见改动写进 [`CHANGELOG.md`](../CHANGELOG.md)。

## 权威入口

| 内容 | 位置 |
|---|---|
| 修复的逐条记录(已发布口径) | `CHANGELOG.md` [1.7.2] |
| 原 48 项审查完整证据 | `docs/archive/mea-pet-mobile-code-review.html`(2026-08-31) |
| 48 项逐条判定表(属实/部分/剔除 + 证据行号) | `docs/archive/code-review-triage.md` |
| 旧批次清单(内容已并入本文,留档备查) | `docs/archive/code-review-todo-2026-09-03.md` |

背景:48 条审查(08-31)→ 09-03 独立复核(剔除 #6、#43、#44,新增新-1~新-10)→ 09-05/06 真机验证发现候选-1 与两条用户反馈 bug。

## ⚠️ 命名对照

历史上有三套互不相同的「第几批」编号:

- 旧 triage/todo 的 **「批次 1」** = 已合入的 **PR #18**(小改动高收益:气泡寿命/网关错误/多处护栏)。
- git 分支 `fix/code-review-batch1 / batch2` **与旧文档的批次编号无关**;其中 **PR #20**(`6eb4311`)其实是**「用户反馈修复批」**(宽表格/重试崩溃),≠ 旧待办的「批次 2(稳定性)」——稳定性批次**尚未动**。
- CHANGELOG 曾用「第二批/第三批」分别指 PR #18 / PR #20。
- 本文 **不再用批次 N**,改按主题分 A/B/C,避免再次撞名。

---

## ✅ 已完成(全部已并入 1.7.2,逐条细节见 CHANGELOG)

- **P0 批**(2026-08-31, `8371c3b`):#4 清空对话误清弹窗 · #7 persistAsync 静默丢数据 · #3 消息裁剪乱序 · #5 Markwon 缓存键 · #2+#46 吞 CancellationException;顺手 #39 Markdown 重复解析、#40 重试过滤 O(n²)→O(n)、#1 线程约束注释加固。
- **小改动高收益批 = 旧「批次 1」**(PR #18, `34d8a29`):新-1 系统气泡「挤旧反延长」(改绝对 deadline + `BubbleLife`)、#18 网关错误详情可见、新-4 `onResume` 等悬浮窗停止加 2s 超时、#38 `LogExporter.waitFor` 加超时、新-6 TTS attn 护栏改内存封顶、新-7/#32 修正 `ConversationManager` 文档、新-10 重试边界守卫。附 `SystemBubblePolicy`/`ApiResponse` 回归单测。
- **用户反馈批**(PR #20, `6eb4311`):发送失败回滚防「幽灵消息」、乐观/入史同 id、`mergeWithHistory` 按时间戳过滤、`retryLastMessage`→`retryMessage`(目标由 UI 按 id 指定,防重发旧消息 + LazyColumn 重复 key 崩溃)、分界丢失不再清空全部回复、`MessageList` 加 `distinctBy` 兜底、宽表格按段渲染 + 横向滚动 + 气泡宽度放开。附 14 条单测(全绿)。
- **复核定案剔除**:#6(合法 Compose 用法)、#43、#44(理由见「不做」)。

---

## 📌 待办 A · 稳定性 / ANR(优先于架构)

- [ ] **#37** `SettingsManager.currentPrefs()` 主线程 `runBlocking`(SettingsManager.kt:192-193) — 异步预热已存在(:44-51),去掉兜底即可:快照未就绪时返回 `emptyPreferences()`,各 getter 落到 `SettingsKeys.Defaults`;首帧主题闪色由 MainActivity 已有 Flow 接管。退路:把阻塞挪到 `MeaPetApplication.onCreate`。
- [ ] **#15 + 新-8** 隐私授权双存储 + 主线程 runBlocking + 2s 超时(PrivacyConsentManager.kt:51-69) — 统一为单一存储源(推荐 SharedPreferences,需 `Application.onCreate` 同步读);`agreedFlow` 从同一份 SP 派生(`OnSharedPreferenceChangeListener` → `callbackFlow`),消除双源不一致;`setAgreed` 改 `suspend`,写盘挪 IO,`killProcess` 放在写完后。**注意**:关于页授权开关(`SettingsViewModel`)有真实消费者,改动后必须真机验证开关仍响应。
- [ ] **#42** 记忆库写放大 — `applyOps` 循环里每个 op 一次 `save`/`delete`,每次全量重写整库(约 180 KiB);且 `enforceCapacity` 把 FACTUAL/CORE_TRAIT 排除在 `maxItems=500` 之外 → 永久类条目**无上限**、随会话线性增长,给硬顶或独立上限。
- [ ] **#19** `getRelevant()` 读写分离(MemoryRepository.kt:179-206) — 命中即更新 accessCount 并整库落盘;拆出 `markAccessed(ids)` 命令方法,由 `MemoryManager` 显式调用(与 #42 一起做更省)。
- [ ] **#7 残留** 持久化无进程死前落盘时机 — `ConversationStore` conflate 队列(只写最新)在进程被杀时丢最后一版;在 `MainActivity.onStop` / `onTrimMemory` 调一次 `persist(snapshot)`(suspend,已有实现)。

> 每项独立 `assembleDebug` 编过;运行时行为(尤其授权开关一致性 #15)需真机验证。

---

## 📌 待办 B · 架构重构(工作量大,逐项独立 PR,不要混做)

按依赖排序,越靠前越先做:

- [ ] **#13** 服务定位器 → CompositionLocal / 构造注入 — 7 处使用方:`MainActivity` / `ChatScreen` / `OverlayMenu` / `FloatingLive2dService` / `OverlayPalette` / `ChatViewModel` / `SettingsViewModel`。`MeaPetApplication.from()` 的 KDoc 自称"仅在无法构造注入时用",现状是全线在用;Compose 侧换 `LocalContainer`,ViewModel 侧换 `ViewModelProvider.Factory`。
- [ ] **#11 + 新-3** 消息源统一 — 让 `ConversationManager` 暴露 `StateFlow<List<ChatMessage>>` 成为唯一真相源,ViewModel 只订阅;触摸气泡改为独立 `overlayBubbles` 在 UI 层拼接。删掉 `mergeWithHistory`(含时间戳过滤兜底)、`takeLast(5)` 兜底、`reloadHistory` 的 ON_RESUME hook。
- [ ] **#14 并发原语统一(含 #1 lifeMap)** — `ConversationManager` 的 `synchronized(lock)` 改协程 `Mutex` + `suspend`(主线程确实会进这把锁:ChatViewModel 三条路径);lifeMap 随 #11 收敛为 actor / 单协程状态机(#1 目前仅线程约束注释,actor 化在此一并做)。
- [ ] **#16** 客户端生命周期统一 — `SettingsViewModel.fetchModels` 里 `reloadClient()` 对本次拉取无作用(用的是临时 new 的实例);改成"落盘 → `reloadClient()` → 用容器 `apiClient` 拉取",删临时客户端。
- [ ] **#9** 拆 ChatViewModel(约 423 行/9 块职责)与 SettingsViewModel(499 行/30 字段/32 公开方法)— 前者优先拆气泡寿命调度、更新检测、记忆 CRUD;后者按 Api/Tts/Appearance/Privacy 拆,或先把 `SettingsUiState` 按域拆成嵌套子状态。
- [ ] **#8** 拆 Live2dDelegate(338 行/7 项职责)— 优先切出触摸交互与分区判定、分区语音与 TTS 互斥、壁纸与背景色参数;GL 生命周期 + 单例 + Activity 持有留原类。
- [ ] **#10** companion var 布线改显式注入(AppContainer) — `ttsPlayingChecker`/`ttsStopper`/`TtsManager.onPlaybackStart` 三个全局可变回调改为一个 `VoiceMutex` 接口(或共享 `StateFlow<Boolean>`)由容器注入。
- [ ] **#12** `Live2dModel` 别调 `Activity.finish()`(Live2dModel.kt:144, 219)— 加载失败改为返回值/回调上抛(`onModelLoadFailed`),由 Activity 决定;注意 `draw()` 跑在 GL 线程,回调要切主线程。
- [ ] **#31** 拆 `ChatUiState`(ChatState.kt:20-28)— `updateNotice`/`memoryDialog` 移出(#4 那个 bug 就是它俩被卷进整体重置导致的)。

---

## 📌 待办 C · 异味 / 规范 / 性能(可按主题打包提交)

**重复代码**

- [ ] #20 `SettingsManager` 约 22 项设置 × 3 份样板(65 个重复成员)→ 泛型 `Setting<T>` + 委托生成 Flow/get/set
- [ ] #21 `SettingsViewModel` 5 处「读旧值-比较-写」(`saveApiKey`/`saveApiUrl`/`saveModel`/`saveSystemPrompt`/`selectModel`)→ 抽 `saveIfChanged`
- [ ] #25 抽公共基类:`dp()`/`Int.withAlpha()` 四处逐字重复(悬浮窗四个窗口);着色器 `compileShader`/`checkShaderCompiled`/`checkProgramLinked` 共 30 行逐字重复(`Live2dSpriteShader` ≡ `WallpaperBlurShader`)。**`place()` 仅 3 处且彼此有差异,别硬抽**
- [ ] #26 抽 `MeaPetDialogCard`:`PrivacyDialog`/`AutoUpdateOptInDialog`/`ChatScreen` 卡片五个数值逐字一致
- [ ] #45 抽 `BubbleContainer`:**只对 `UserBubble` 与 `AssistantBubble` 有效**,`SystemBanner` 结构不同别硬合

**可读性 / 命名**

- [ ] #22 魔法数字提常量(原 8 处;「SystemBubblePolicy 排位阈值 3」已在批次 1 提为 `KEEP_FULL_LIFE_POSITIONS`,余 7 处):`ChatViewModel` 的 `takeLast(5)`、`KtorHttpClientEngine` 的 `300_000`/`30_000`(顺便做成可配)、`SettingsViewModel` 的 `8`/`4`、`MainActivity` 的 `0x14`/`0xF7`/`0xFFF7F7F7`、`Live2dDelegate` 的 `30f`/`400L`/`±0.45f`/`-0.44f`/`0.66f`、`LogExporter` 的 `dropLast(5)`、`ChatScreen` 的 `100.dp`/`88.dp`
- [ ] #17 / #47 `Pair` 换命名 data class:`ChatService.sendMessage` → `ChatExchange(user, assistant)`(注意:现为两个重载 + 失败回滚,返回仍是 Pair);`SystemBubblePolicy` → `NextLife(lifeMs, reduceCount)`;`SymbolTable` → 命名类型
- [ ] #48 / #35 拆超长方法:`ChatService.sendMessage`(现两个重载 + 回滚逻辑,只更长)拆 `buildRequest`/`parseReply`/`schedulePostProcess`;`MainActivity.onCreate`(约 182 行)拆 `setupFirstFrameTheme`/`setupGlSurface`/`setupComposeContent`/`setupTouchForwarding`

**规范 / i18n**

- [ ] #23 文案抽 `strings.xml`(实测中文 387 处、`stringResource` 0 次)— 先抽高频页 `ChatScreen`/`PrivacyDialog`;`SettingsKeys` 里写死的 SystemPrompt(745 字)移 `assets/` 或 `res/raw/`
- [ ] #27 空 catch 补注释(全树 18 处无注释)— `ErrorHandling.kt` 明文要求"必须有一行注释说明为何可安全忽略"
- [ ] #29 `EXCHANGE_COUNT` 另立仓储(它是唯一没有 Flow 的键)
- [ ] #30 + 新-2 `isStreaming` 处置 — main 源码从未置 true(`ChatService` 固定 `stream = false`)。要么删掉它连同 `ChatBubble` 的死 UI 分支与 `MarkdownText` 的 `closeUnclosedFences` 参数,要么真的把流式做出来,别留着当装饰
- [ ] #28 清死代码:`Live2dSprite` `textureId` 构造参数(被同名形参遮蔽)、`Live2dView.setClearColor()`、`Type.kt` 注释掉的 16 行、`ApiRequest` 三个仅测试调用的方法、`MeaPetApplication.onTerminate()`、`Color.kt` 6 个零引用常量

**性能**

- [ ] #41 `DurationExpander` 稠密 attn 矩阵改稀疏/逐行流式(正常路径 12–36 MiB,不是数百 MB;优先级可放低,与新-6 的护栏一起看)

**工程**

- [ ] #33 手写 Animatable 三态机 → `AnimatedVisibility`(`OverlayMenu` 与 `AppearanceSettings` 逐字重复;`PrivacyDialog` 仅单向进场,另算)
- [ ] #34 依赖拉齐:`coreKtx 1.10.1`/`lifecycleRuntime 2.6.1` 与 `agp 9.3.0`/`kotlin 2.2.10`/`composeBom 2026.02.01` 断层严重;Java 11 可升 17;`abiFilters` 缺 x86_64/x86(模拟器调试不便)
- [ ] **无 CI** — 补一个只跑 `./gradlew testDebugUnitTest` 的 GitHub Actions workflow;对话/气泡链路的单测已补齐,CI 能兜住"纯函数测了、接线没测"这类漏洞

---

## 🔭 待调查

- **候选-1** 悬浮窗返回主界面后约 2 秒才出画面(2026-09-05 真机验证批次 1 时发现;属既有延迟,新旧一致,非本次改动回归;**低优先**)。
  已排除 gate 等待阻塞(日志无超时告警,`isRunning` 翻转很快)。推测时间花在 `glSurfaceView.onResume()` 之后的 GL 重初始化(shader 重建 + 模型纹理重载)。
  **下一步**:加带时间戳的 log 打点,区分 `stopService→onDestroy` 派发 / gate 等待 / GL 恢复→首帧三段耗时,再决定是否优化(如恢复期显示上一帧快照或渐显,避免纯黑)。

---

## ⛔ 不做 / 已剔除(复核定案)

- ~~#6 条件分支内 `collectAsState`~~ — 合法 Compose 用法,非缺陷(该段真正的问题是 #13)。
- ~~#44 `ensureDict` 双重检查锁~~ — 底层 `PinyinDict.init` 已 `@Synchronized` + 幂等,无实际危害。
- ~~#43 `WallpaperStore` 三次开流~~ — content 流不可回绕,测尺寸 + 解码天然两次;只有 EXIF 那次可省,不划算。
- ~~#3 继续深挖 `trimWindow`~~ — 已修,且 `ConversationManager` 里从不存 system 消息,原缺陷路径本就走不到。

---

## 验证要求与提交约定

- 批次 A / B 的每一项都要能独立编过(`./gradlew assembleDebug`,bisect safe)。
- **运行时行为必须真机验证**(容器无设备):气泡寿命、黑屏恢复、授权开关一致性(#15)、报错文案。
- 提交信息沿用 `Fix:` / `Update:` / `Add:` 分段,长解释写进 commit message。
- 提交前 `git status` 确认 `gradle.properties` / `local.properties` / `gradlew` 不在暂存区。
- ⚠️ 全局免责:本文行号是归档核对时点的引用,后续改动会漂移,以符号名为准。
