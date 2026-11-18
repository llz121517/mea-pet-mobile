# MeaPet 待办 · 代码审查跟踪

> **本文档是全项目唯一在维护的待办清单。** 历史代码审查文档（2026-08/09 的 48 项报告、逐条判定表、旧批次清单）已删除——其描述的是 1.7.2 之前的代码，结论已并入本文，留档会误导；需要追溯可查 git 历史。
> 每次在此勾掉/新增条目时,顺手把对应的用户可见改动写进 [`CHANGELOG.md`](../CHANGELOG.md)。
> 文档地图与各文档的权威性/维护状态见 [`docs/README.md`](README.md)。

## 权威入口

| 内容 | 位置 |
|---|---|
| 修复的逐条记录(已发布口径) | `CHANGELOG.md`（[1.7.2] / [1.8.0]） |
| TTS 迁移(1.8.0)结论、验证记录与遗留项 | [`docs/archive/reports/2026-11-15-migration-report-1.8.0.md`](archive/reports/2026-11-15-migration-report-1.8.0.md) |
| 待办条目编号(#N / 新-N)的来源说明 | 见下方「命名对照」与「背景」（原审查文档已删除） |

背景:48 条审查(08-31)→ 09-03 独立复核(剔除 #6、#43、#44,新增新-1~新-10)→ 09-05/06 真机验证发现候选-1 与两条用户反馈 bug。

## ⚠️ 命名对照

历史上有三套互不相同的「第几批」编号:

- 旧 triage/todo 的 **「批次 1」** = 已合入的 **PR #18**(小改动高收益:气泡寿命/网关错误/多处护栏)。
- git 分支 `fix/code-review-batch1 / batch2` **与旧文档的批次编号无关**;其中 **PR #20**(`6eb4311`)其实是**「用户反馈修复批」**(宽表格/重试崩溃),≠ 旧待办的「批次 2(稳定性)」——稳定性批次已于 2026-11-15 处理(#37/#42/#19/#7 见「已完成」,剩余 #15 + #42 残项见待办 A)。
- CHANGELOG 曾用「第二批/第三批」分别指 PR #18 / PR #20。
- 本文 **不再用批次 N**,改按主题分 A/B/C,避免再次撞名。

---

## ✅ 已完成(1.7.2 / 1.8.0,逐条细节见 CHANGELOG)

- **P0 批**(2026-08-31, `8371c3b`):#4 清空对话误清弹窗 · #7 persistAsync 静默丢数据 · #3 消息裁剪乱序 · #5 Markwon 缓存键 · #2+#46 吞 CancellationException;顺手 #39 Markdown 重复解析、#40 重试过滤 O(n²)→O(n)、#1 线程约束注释加固。
- **小改动高收益批 = 旧「批次 1」**(PR #18, `34d8a29`):新-1 系统气泡「挤旧反延长」(改绝对 deadline + `BubbleLife`)、#18 网关错误详情可见、新-4 `onResume` 等悬浮窗停止加 2s 超时、#38 `LogExporter.waitFor` 加超时、新-6 TTS attn 护栏改内存封顶、新-7/#32 修正 `ConversationManager` 文档、新-10 重试边界守卫。附 `SystemBubblePolicy`/`ApiResponse` 回归单测。
- **用户反馈批**(PR #20, `6eb4311`):发送失败回滚防「幽灵消息」、乐观/入史同 id、`mergeWithHistory` 按时间戳过滤、`retryLastMessage`→`retryMessage`(目标由 UI 按 id 指定,防重发旧消息 + LazyColumn 重复 key 崩溃)、分界丢失不再清空全部回复、`MessageList` 加 `distinctBy` 兜底、宽表格按段渲染 + 横向滚动 + 气泡宽度放开。附 14 条单测(全绿)。
- **复核定案剔除**:#6(合法 Compose 用法)、#43、#44(理由见「不做」)。
- **1.8.0 TTS 迁移批**(2026-11-01, `fc70902`..`aed66e6`):推理链整体换成 sherpa-onnx,连带作废本清单两条旧项——**#41 失效**(`DurationExpander` 连同旧 G2P/四模块引擎整体删除,无优化对象)、**「无 CI」失效**(`.github/workflows/ci.yml` 已上线:`:app:testDebugUnitTest` + PR 构建 debug APK)。新-6 的 attention 内存护栏代码与测试也随旧引擎删除。详见 [`archive/reports/2026-11-15-migration-report-1.8.0.md`](archive/reports/2026-11-15-migration-report-1.8.0.md)。
- **英文数字读法**(真机验收期修复, 2026-11-15):英文句中的阿拉伯数字在 App 归一化层预转换为英文数词(`3`→three、`12`→twelve),不再被 sherpa 中文 `ruleFsts` 读成 sān/shí èr;中文/日文上下文数字保持原路径;`3.5`/`10:30`/电话等复合或长数字不转换。补 3 组单测。日文数字读法见「待办 D」。
- **逐句流式播放**(2026-11-15):`TtsEngine.synthesizeStreaming` 走 `OfflineTts.generateWithConfigAndCallback`,每句合成完即回调;`TtsManager` 首块到达才点亮/停触摸语音并开播,后续分块进 `TtsAudioPlayer` 队列、由专用写线程写 AudioTrack;打断时回调返回 0 让原生推理中止剩余句子(不再白算完)。桌面 4 句首块 ~1.1s(整段 3.8s);补 `TtsManagerTest` 流式契约 8 条(含迟到分块中止、releaseEngine 串行)。并行(`maxNumSentences>1`)实测只快 ~8%,不启用;JNI 回调必须用 Java 适配器 `TtsStreamCallback`(sherpa 按 `invoke([F)Ljava/lang/Integer;` 查方法,Kotlin indy lambda 没这个签名,直接传会 CheckJNI SIGABRT,已加 `TtsStreamCallbackTest` 反射守护)。
- **稳定性批残留项**(2026-11-15, `199da5d`/`ee88462`):#37 设置同步 getter 不再主线程 `runBlocking`(快照未预热返回 `emptyPreferences()`,判定型读取走 `awaitLoaded()`)· #42 记忆写放大收敛(`applyChanges` 整批落盘;永久类存储层无硬顶见待办 A)· #19 `getRelevant` 读写分离 + `markAccessed` 显式记账 · #7 残留 `onStop` 调 `flush()` 补写。细节见 CHANGELOG `[1.8.0]`。

---

## 📌 待办 0 · 1.8.0 发布收尾(阻塞 v1.8.0 发布)

- [x] **真机验收** 按 [`archive/checklists/2026-11-15-tts-device-test-1.8.0.md`](archive/checklists/2026-11-15-tts-device-test-1.8.0.md) 逐条执行(21 条试听 + SIGILL/内存/打断/悬浮窗/删除重导入/1.7.x 升级迁移),结论回填清单 §3。**已完成(2026-11-15)**:清单全部勾选、§3 结论回填(设备 Xiaomi 22041216UC / MT6895Z / Android 14);自动化记录见清单 §A。当天修复并复验:英文数字读法、流式回调 JNI 签名崩溃、≤2s 短片段无声。
- [x] **模型包 `lexicon.txt` 重传确认** — **已实测确认(2026-11-15)**:App 只校验字节数;`artifacts.json` 把 `lexicon.txt` 标为 `content-identical`,发布包 `97e2b4fb…`(打包型行序)与本地 `tools/tts/dist` 的 `261ee0a7…`(生成型行序)`content_sha256` 相同、字节不同。设备当前为本地 zip 导入版本(`261ee0a7…`),功能与发布包一致。**结论:Release 上的包即最终版,无需重传**;双 sha 口径记录在 [`tts/g2p-pipeline.md`](tts/g2p-pipeline.md) 与 [`../tools/tts/README.md`](../tools/tts/README.md)(`tts/model-package.md` 只记发布包 sha)。
- [y] **打 tag `v1.8.0` 前先做 signed release 冒烟(本轮构建精简后新增门禁)** — 1.8.0 打包剔除了未使用的 sherpa `c-api`/`cxx-api` so 并启用 `-allowaccessmodification`,`assembleRelease` 现在直出已签名包(**实测 v8a 33.71MB / 双 ABI 51.83MB**,1.7.2 为 61.64MB)。**构建侧冒烟已完成(2026-11-15)**:`tools/build-release.ps1` 产出并验签(证书与线上 1.7.2 同一 SHA-256)、`zipalign -P 16` 通过、ABI 正确。**设备侧仍待做**:装一次 release(需先卸载 debug,会清掉本地 84MB 模型,记得重新导入),验证冷启动、TTS 试听、Live2D 与悬浮窗。
- [y] **打 tag `v1.8.0`** 走 `.github/workflows/release.yml`(须在 `main` 上且与 `versionName` 一致)。**前置:先把 `dev` 合并到 `main`**——`main` 落后 60 个提交,`release.yml` 的 `--require-main` 会拒掉 dev 上的 tag;当前最新 tag 仍为 `v1.7.2`。
- [] **1.11.0:删除 legacy TTS 迁移步骤** — 按 [`legacy-compat.md`](legacy-compat.md) §3/§4 删 3 个 step + 注册项 + 测试,保留迁移框架本身。
- [ ] **1.9.0:观察 TTS 合成后 native 内存增长** — 真机复测(2026-11-15):前台冷启动未合成 TOTAL PSS 381MB / Native 110MB;10 条合成后 TOTAL PSS 690MB / Native 408MB(增量约 +300MB,超出清单预期的 100~250MB)。待确认是 ORT arena 缓存(长文本大张量)还是泄漏:继续合成是否持续增长、删除模型后是否回落(清单 §2.3)。

---

## 📌 待办 E · 1.9 收尾（dev 分支）

1.9.0 的功能开发已完成（记忆工具化、工具消息入历史、连发多条、插队发送、`memoryMode`、turn 超时、检索修复、压缩与 SQLite 存储，详见 [`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §实施状态与 [`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md)）。剩余：

- [ ] **1.9 真机验收**（本轮按用户要求暂不操作设备）— 清单：连发多条气泡顺序与 TTS 逐条朗读、插队发送/「停止」、工具消息落库后重启续聊、记忆检索方式四档、检索返回多条、turn 超时提示、压缩分隔条与手动压缩、向上懒加载锚定。
- [ ] **M4 观察期数据收集** — `adb logcat -s TurnMetrics` 攒 1~2 周：工具调用率、平均轮数、失败/取消/超时占比、`cacheHitRatio`；据此决定 1.10 是否删除文本围栏协议（`MemoryOpsProtocol`）与默认模式取舍。
- [ ] **1.10：清除对话改软删除**（M3 遗留）— `conversations.state=cleared` + 新活跃会话 + 撤销只带 conversationId；单会话模型下需先让 `conversationId` 可切换（仓库/压缩状态/会话指针）。
- [ ] **1.10：清理备胎代码** — `MemoryRepository`（JSON 实现）、`ConversationStore`、旧 JSON 内存仓库与相关单测；legacy 导入保留到 1.11（见 `legacy-compat.md`）。
- [ ] **被打断 turn 的 `interrupted` 落库标记** — 当前仅 `TurnMetrics` 日志记录，无消费方；等有「中断轮次分析」需求再补（`extra_json` 或 meta 记录，不改 schema）。
- [ ] **设备残留数据清理**（可选，下次接设备时）— 测试注入的长消息、重复记忆 `mem_ln3glfhc` / `mem_39ypzmxj`。

---

## 📌 待办 A · 稳定性 / ANR(优先于架构)

- [ ] **#15 + 新-8** 隐私授权双存储 + 主线程 runBlocking + 2s 超时(PrivacyConsentManager.kt:51-69) — 统一为单一存储源(推荐 SharedPreferences,需 `Application.onCreate` 同步读);`agreedFlow` 从同一份 SP 派生(`OnSharedPreferenceChangeListener` → `callbackFlow`),消除双源不一致;`setAgreed` 改 `suspend`,写盘挪 IO,`killProcess` 放在写完后。**注意**:关于页授权开关(`SettingsViewModel`)有真实消费者,改动后必须真机验证开关仍响应。
- [ ] **#42 残项** 永久类记忆无硬顶 — 写放大与检索读写分离已修(见「已完成」),但 `enforceCapacity` 仍把 FACTUAL/CORE_TRAIT 排除在 `maxItems=500` 之外(MemoryRepository.kt:357-366)→ 存储层无上限、随会话线性增长(注入侧已由 `getPersonaFacts(maxCount)` 封顶)。需给硬顶或独立上限。

> 每项独立 `assembleDebug` 编过;运行时行为(尤其授权开关一致性 #15)需真机验证。

---

## 📌 待办 B · 架构重构(工作量大,逐项独立 PR,不要混做)

按依赖排序,越靠前越先做:

- [ ] **#13** 服务定位器 → CompositionLocal / 构造注入 — 1.8.0 后 `MeaPetApplication.from()` 只剩 **3 处**:`MainActivity` / `ChatViewModel` / `SettingsViewModel`(Compose 各屏与悬浮窗已改为参数传入)。剩余工作:Compose 侧换 `LocalContainer`、ViewModel 侧换 `ViewModelProvider.Factory`,让 `from()` 只作进程内兜底。
- [ ] **#11 + 新-3** 消息源统一 — 让 `ConversationManager` 暴露 `StateFlow<List<ChatMessage>>` 成为唯一真相源,ViewModel 只订阅;触摸气泡改为独立 `overlayBubbles` 在 UI 层拼接。删掉 `mergeWithHistory`(含时间戳过滤兜底)、`takeLast(5)` 兜底、`reloadHistory` 的 ON_RESUME hook。
- [ ] **#14 并发原语统一(含 #1 lifeMap)** — `ConversationManager` 的 `synchronized(lock)` 改协程 `Mutex` + `suspend`(主线程确实会进这把锁:ChatViewModel 三条路径);lifeMap 随 #11 收敛为 actor / 单协程状态机(#1 目前仅线程约束注释,actor 化在此一并做)。
- [ ] **#16** 客户端生命周期统一 — `SettingsViewModel.fetchModels` 里 `reloadClient()` 对本次拉取无作用(用的是临时 new 的实例);改成"落盘 → `reloadClient()` → 用容器 `apiClient` 拉取",删临时客户端。
- [ ] **#9** 拆 ChatViewModel(553 行/9 块职责)与 SettingsViewModel(675 行/约 40 个状态字段/34 个公开方法)— 前者优先拆气泡寿命调度、更新检测、记忆 CRUD;后者按 Api/Tts/Appearance/Privacy 拆,或先把 `SettingsUiState` 按域拆成嵌套子状态。
- [ ] **#8** 拆 Live2dDelegate(338 行/7 项职责)— 优先切出触摸交互与分区判定、分区语音与 TTS 互斥、壁纸与背景色参数;GL 生命周期 + 单例 + Activity 持有留原类。
- [ ] **#10** companion var 布线改显式注入(AppContainer) — `ttsPlayingChecker`/`ttsStopper`/`TtsManager.onPlaybackStart` 三个全局可变回调改为一个 `VoiceMutex` 接口(或共享 `StateFlow<Boolean>`)由容器注入。
- [ ] **#12** `Live2dModel` 别调 `Activity.finish()`(Live2dModel.kt:144, 219)— 加载失败改为返回值/回调上抛(`onModelLoadFailed`),由 Activity 决定;注意 `draw()` 跑在 GL 线程,回调要切主线程。
- [ ] **#31** 拆 `ChatUiState`(chat/ChatState.kt:21-29)— `updateNotice`/`memoryDialog` 移出(#4 那个 bug 就是它俩被卷进整体重置导致的)。

---

## 📌 待办 C · 异味 / 规范 / 工程(可按主题打包提交)

**重复代码**

- [ ] #20 `SettingsManager` 约 22 项设置 × 3 份样板(65 个重复成员)→ 泛型 `Setting<T>` + 委托生成 Flow/get/set
- [ ] #21 `SettingsViewModel` 5 处「读旧值-比较-写」(`saveApiKey`/`saveApiUrl`/`saveModel`/`saveSystemPrompt`/`selectModel`)→ 抽 `saveIfChanged`
- [ ] #25 抽公共基类:`dp()`/`Int.withAlpha()` 四处逐字重复(悬浮窗四个窗口);着色器 `compileShader`/`checkShaderCompiled`/`checkProgramLinked` 共 30 行逐字重复(`Live2dSpriteShader` ≡ `WallpaperBlurShader`)。**`place()` 仅 3 处且彼此有差异,别硬抽**
- [ ] #26 抽 `MeaPetDialogCard`:`PrivacyDialog`/`AutoUpdateOptInDialog`/`ChatScreen` 卡片五个数值逐字一致
- [ ] #45 抽 `BubbleContainer`:**只对 `UserBubble` 与 `AssistantBubble` 有效**,`SystemBanner` 结构不同别硬合

**可读性 / 命名**

- [ ] #22 魔法数字提常量(原 8 处;「SystemBubblePolicy 排位阈值 3」已在批次 1 提为 `KEEP_FULL_LIFE_POSITIONS`,余 7 处):`ChatViewModel` 的 `takeLast(5)`、`KtorHttpClientEngine` 的 `300_000`/`30_000`(顺便做成可配)、`SettingsViewModel` 的 `8`/`4`、`MainActivity` 的 `0x14`/`0xF7`/`0xFFF7F7F7`、`Live2dDelegate` 的 `30f`/`400L`/`±0.45f`/`-0.44f`/`0.66f`、`LogExporter` 的 `dropLast(5)`、`ChatScreen` 的 `100.dp`/`88.dp`
- [ ] #17 / #47 `Pair` 换命名 data class:`ChatService.sendMessage` → `ChatExchange(user, assistant)`(注意:现为两个重载 + 失败回滚,返回仍是 Pair);`SystemBubblePolicy` → `NextLife(lifeMs, reduceCount)`(`SymbolTable` 已随 1.8.0 删除,无需处理)
- [ ] #48 / #35 拆超长方法:`ChatService.sendMessage`(现两个重载 + 回滚逻辑,只更长)拆 `buildRequest`/`parseReply`/`schedulePostProcess`;`MainActivity.onCreate`(约 220 行)拆 `setupFirstFrameTheme`/`setupGlSurface`/`setupComposeContent`/`setupTouchForwarding`

**规范 / i18n**

- [ ] #23 文案抽 `strings.xml`(实测中文 387 处、`stringResource` 0 次)— 先抽高频页 `ChatScreen`/`PrivacyDialog`;`SettingsKeys` 里写死的 SystemPrompt(745 字)移 `assets/` 或 `res/raw/`
- [ ] #27 空 catch 补注释(全树 18 处无注释)— `ErrorHandling.kt` 明文要求"必须有一行注释说明为何可安全忽略"
- [ ] #29 `EXCHANGE_COUNT` 另立仓储(它是唯一没有 Flow 的键)
- [ ] #30 + 新-2 `isStreaming` 处置 — main 源码从未置 true(`ChatService` 固定 `stream = false`)。要么删掉它连同 `ChatBubble` 的死 UI 分支与 `MarkdownText` 的 `closeUnclosedFences` 参数,要么真的把流式做出来,别留着当装饰
- [ ] #28 清死代码:`Live2dSprite` `textureId` 构造参数(被同名形参遮蔽)、`Live2dView.setClearColor()`、`Type.kt` 注释掉的 16 行、`ApiRequest` 三个仅测试调用的方法、`MeaPetApplication.onTerminate()`、`Color.kt` 6 个零引用常量

**工程**

- [ ] #33 手写 Animatable 三态机 → `AnimatedVisibility`(`OverlayMenu` 与 `AppearanceSettings` 逐字重复;`PrivacyDialog` 仅单向进场,另算)
- [ ] #34 依赖拉齐:`coreKtx 1.10.1`/`lifecycleRuntime 2.6.1` 与 `agp 9.3.0`/`kotlin 2.2.10`/`composeBom 2026.02.01` 断层严重;Java 11 可升 17(现 `sourceCompatibility`/`jvmTarget` 均为 11);`abiFilters` 只有 arm(v8a/v7a)——**不是疏漏**:sherpa-onnx AAR 只带 arm 原生库,带语音的构建无法出 x86 包,模拟器调试需要另出不含 TTS 的变体

---

## 📌 待办 D · 语音增强(1.9.0)

- [ ] **日文上下文数字/日期读法** — 目前日文句里的阿拉伯数字走 sherpa 中文 `ruleFsts`,读作中文数词(`15 時`→shí wǔ shí、`9 月 1 日`→jiǔ yuè yī rì),假名数词(じゅうごじ)与日文日期(くがつついたち)都不对。需要:数字→假名数词 + 日文日期/量词特殊读法(1日→ついたち、9月→くがつ)与计数器规则。建议照 `docs/tts/architecture.md` §3 英文数字预转换的做法在 App 归一化层实现并补单测;用例来自 [`archive/checklists/2026-11-15-tts-device-test-1.8.0.md`](archive/checklists/2026-11-15-tts-device-test-1.8.0.md) #19/#21。

---

## 🔭 待调查

- **候选-1** 悬浮窗返回主界面后约 2 秒才出画面(2026-09-05 真机验证批次 1 时发现;属既有延迟,新旧一致,非本次改动回归;**低优先**)。
  已排除 gate 等待阻塞(日志无超时告警,`isRunning` 翻转很快)。推测时间花在 `glSurfaceView.onResume()` 之后的 GL 重初始化(shader 重建 + 模型纹理重载)。
  **下一步**:加带时间戳的 log 打点,区分 `stopService→onDestroy` 派发 / gate 等待 / GL 恢复→首帧三段耗时,再决定是否优化(如恢复期显示上一帧快照或渐显,避免纯黑)。

---

## ⛔ 不做 / 已剔除(复核定案)

- ~~#6 条件分支内 `collectAsState`~~ — 合法 Compose 用法,非缺陷(该段真正的问题是 #13)。
- ~~#44 `ensureDict` 双重检查锁~~ — 该链路(`PinyinDict` / 旧 G2P)已随 1.8.0 整体删除,无优化对象。
- ~~#43 `WallpaperStore` 三次开流~~ — content 流不可回绕,测尺寸 + 解码天然两次;只有 EXIF 那次可省,不划算。
- ~~#3 继续深挖 `trimWindow`~~ — 已修,且 `ConversationManager` 里从不存 system 消息,原缺陷路径本就走不到。

---

## 验证要求与提交约定

- 批次 A / B 的每一项都要能独立编过(`./gradlew assembleDebug`,bisect safe)。
- **运行时行为必须真机验证**(容器无设备):气泡寿命、黑屏恢复、授权开关一致性(#15)、报错文案。
- 提交信息沿用 `Fix:` / `Update:` / `Add:` 分段,长解释写进 commit message。
- 提交前 `git status` 确认 `gradle.properties` / `local.properties` / `gradlew` 不在暂存区。
- ⚠️ 全局免责:本文行号是归档核对时点的引用,后续改动会漂移,以符号名为准。
