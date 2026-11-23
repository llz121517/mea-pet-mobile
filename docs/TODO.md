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
- git 分支 `fix/code-review-batch1 / batch2` **与旧文档的批次编号无关**;其中 **PR #20**(`6eb4311`)其实是**「用户反馈修复批」**(宽表格/重试崩溃),≠ 旧待办的「批次 2(稳定性)」——稳定性批次已于 2026-11-15 处理(#37/#42/#19/#7 见 `CHANGELOG.md` [1.8.0],剩余 #15 + #42 残项见待办 A)。
- CHANGELOG 曾用「第二批/第三批」分别指 PR #18 / PR #20。
- 本文 **不再用批次 N**,改按主题分 A/B/C,避免再次撞名。

---

## 📌 待办 0 · 版本迁移收尾

- [ ] **1.11.0:删除 legacy 迁移步骤** — 按 [`legacy-compat.md`](legacy-compat.md) §3/§4 删除 3 个 TTS step 与 `LegacyExchangeCountMigration`(共 4 个 step)、注册项与对应测试;随之删除已废弃的 `SettingsKeys.EXCHANGE_COUNT` 及 `getLegacyExchangeCount`/`clearLegacyExchangeCount`;保留迁移框架本身。
- [ ] **后续 Patch 版本:观察 TTS 合成后 native 内存增长** — 真机复测(2026-11-15):前台冷启动未合成 TOTAL PSS 381MB / Native 110MB;10 条合成后 TOTAL PSS 690MB / Native 408MB(增量约 +300MB,超出清单预期的 100~250MB)。待确认是 ORT arena 缓存(长文本大张量)还是泄漏:继续合成是否持续增长、删除模型后是否回落(清单 §2.3)。

---

## 📌 待办 E · 1.9 收尾

1.9.0 的记忆工具、只读文本回退、联网工具、SQLite、历史分页与上下文压缩已接入。当前状态与行为见 [`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md)（记忆实现参考）、[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) 与 [`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md)。仍需处理：

- [ ] **1.9 真机验收** — 连续回复顺序与逐条 TTS、插队发送 /「停止」、工具消息落库后重启续聊、记忆检索四档、工具记忆写入 / 删除撤销、TEXT 模式无记忆内容变更、只读回退不触发文本写入或自动摘要、摘要触发范围、turn 超时、压缩分隔条与手动压缩、向上懒加载锚定。
- [ ] **M4 观察期指标** — 收集 `TurnMetrics` 中的工具调用率、平均轮数、失败 / 取消 / 超时占比和 `cacheHitRatio`，评估运行质量与容量 / 摘要参数。
- [ ] **1.10：清除对话改软删除**（M3 遗留）— `conversations.state=cleared` + 新活跃会话 + 撤销只带 conversationId；单会话模型下需先让 `conversationId` 可切换（仓库 / 压缩状态 / 会话指针）。
- [ ] **1.10：清理备胎代码** — `MemoryRepository`（JSON 实现）、`ConversationStore` 与相关单测；legacy 导入保留到 1.11（见 `legacy-compat.md`）。**2026-11-22 复核**：`MemoryRepository` 已无任何实例化点、`ConversationStore` 只剩一个无人读取的 `lazy` 属性，两者实际已是死代码，可提前到 1.9.x 删除。
- [ ] **被打断 turn 的 `interrupted` 落库标记** — 当前仅 `TurnMetrics` 日志记录，无消费方；等有「中断轮次分析」需求再补（`extra_json` 或 meta 记录，不改 schema）。
- [ ] **备用 tail 模式（`SEPARATE_USER`）未接线** — 只有常量与单测，没有设置入口，也没有计划里的「返回 400/500 且正文命中模板报错 → 回退 MERGE_USER 并按 baseUrl+model 缓存」探测 / 降级逻辑；默认 MERGE_USER 兼容性最好，暂不启用，等确有端点需要连续 user 时再做（避免写永不执行的死代码）。
- [ ] **数据与备份真机验收（1.9.0）**（[PRD](2026-11-22-backup-export-import-prd.md) §8）— 导出→改数据→导入→逐类核对（设置/对话/记忆/压缩状态/壁纸）；错误口令与版本不符整包拒绝；SAF 取消路径；大壁纸（不触发文本 64 MiB 上限）；导入后 UI 与模型上下文都能读到新数据。单测已覆盖 81 例（`app/src/test/java/com/meapet/mobile/backup/`），真机验收未做。
- [ ] **联网工具真机验收**（见 [联网工具方案](2026-11-19-web-search-plan.md)）— 验证 DeepSeek 官方 Key、AnySearch 匿名与 token、搜索后读取正文、设置切换、限流和额度提示。
- [ ] **设备残留数据清理**（可选，下次接设备时）— 测试注入的长消息、重复记忆 `mem_ln3glfhc` / `mem_39ypzmxj`。

---

## 📌 待办 A · 稳定性 / ANR(优先于架构)

- [ ] **#15 + 新-8** 隐私授权双存储 + 主线程 runBlocking + 2s 超时(PrivacyConsentManager.kt:51-69) — 统一为单一存储源(推荐 SharedPreferences,需 `Application.onCreate` 同步读);`agreedFlow` 从同一份 SP 派生(`OnSharedPreferenceChangeListener` → `callbackFlow`),消除双源不一致;`setAgreed` 改 `suspend`,写盘挪 IO,`killProcess` 放在写完后。**注意**:关于页授权开关(`SettingsViewModel`)有真实消费者,改动后必须真机验证开关仍响应。
- [ ] **#42 残项** 永久类记忆无硬顶 — 写放大与检索读写分离已修(见 `CHANGELOG.md` [1.8.0]),但 `enforceCapacity` 仍把 FACTUAL/CORE_TRAIT 排除在 `maxItems=500` 之外(`SqliteMemoryStore.enforceCapacity`, `storage/SqliteMemoryStore.kt:245-253`;JSON 备胎 `MemoryRepository` 同样如此)→ 存储层无上限、随会话线性增长(注入侧已由 `getPersonaFacts(maxCount)` 封顶)。需给硬顶或独立上限。

**2026-11-22 代码审查遗留(并发 / 数据一致性 / 正确性)**

- [ ] **G1 清除对话与在途 turn 取消收尾竞态** — 取消旧 turn 与取撤销快照可能交错:快照可能缺已展示片段,或清空后旧 turn 的片段又落库/回显;需串行化并明确「取消完成后取快照」。
- [ ] **G2 撤销「清除对话」不恢复压缩摘要** — `clearHistory` 异步清 `MetaCompactionStore`,撤销后旧摘要可能已删或残留并被注入新会话;撤销应一并恢复/作废压缩状态。
- [ ] **G3 整轮落库后记忆提交失败只回滚 user** — `ChatService` 先 `addMessages` 再 `memoryTools.onTurnSuccess()`,提交抛错时只移除 user,会留下无 user 的 assistant/tool 行;提交失败应与整轮落库同一失败边界。
- [ ] **G4 `ConversationManager` 落盘消费者取消后静默丢数据 / `flush()` 永久挂起** — 消费协程被取消后 `Flush` 永不完成,恢复 1.8.0 曾有的 `isActive` 保护与失败兜底完成。
- [ ] **G5 手动压缩与自动压缩无互斥、压缩状态读改写互相覆盖** — 两处 `compactionState` 的 load-modify-save 无共享锁,并发时后写覆盖先写,摘要/覆盖点可回退。
- [ ] **G6 并发 turn 无守卫** — 悬浮窗发送不检查 `isTurnActive`,`cancelActiveTurn` 只取消最新 job;可能出现两个 turn 同时读写历史/记忆。
- [ ] **G7 `removeMessages` 逐条事务,整轮回滚非原子** — 需批量删除 op(单事务),避免中途失败或并发插入造成半轮回滚。
- [ ] **G8 `create_memory` 允许空 `keywords`** — 空关键词记忆在只读/关键词检索路径永远匹配不到;应与摘要流程一致要求至少 1 个有效关键词。
- [ ] **G11 上下文窗口手动值 <1024 被静默当成自动、重新检测用固定 delay** — 非法值应校验/提示;「重新检测」应随探测完成信号刷新,而不是固定 `delay(1500)`。
- [ ] **G13 `search_memory` 的 `total_matched` 语义错误** — 当前取的是检索返回条数(受 limit/体积截断影响),不是库中总命中数;应改为真实总命中数,或改字段名/删除。

> 每项独立 `assembleDebug` 编过;运行时行为(尤其授权开关一致性 #15)需真机验证。

---

## 📌 待办 B · 架构重构(工作量大,逐项独立 PR,不要混做)

按依赖排序,越靠前越先做:

- [ ] **#13** 服务定位器 → CompositionLocal / 构造注入 — 1.8.0 后 `MeaPetApplication.from()` 只剩 **3 处**:`MainActivity` / `ChatViewModel` / `SettingsViewModel`(Compose 各屏与悬浮窗已改为参数传入)。剩余工作:Compose 侧换 `LocalContainer`、ViewModel 侧换 `ViewModelProvider.Factory`,让 `from()` 只作进程内兜底。
- [ ] **#11 + 新-3** 消息源统一 — 让 `ConversationManager` 暴露 `StateFlow<List<ChatMessage>>` 成为唯一真相源,ViewModel 只订阅;触摸气泡改为独立 `overlayBubbles` 在 UI 层拼接。删掉 `mergeWithHistory`(含时间戳过滤兜底)、`takeLast(5)` 兜底、`reloadHistory` 的 ON_RESUME hook。
- [ ] **#14 并发原语统一(含 #1 lifeMap)** — `ConversationManager` 的 `synchronized(lock)` 改协程 `Mutex` + `suspend`(主线程确实会进这把锁:ChatViewModel 三条路径);lifeMap 随 #11 收敛为 actor / 单协程状态机(#1 目前仅线程约束注释,actor 化在此一并做)。
- [ ] **#16** 客户端生命周期统一 — `SettingsViewModel.fetchModels` 里 `reloadClient()` 对本次拉取无作用(用的是临时 new 的实例);改成"落盘 → `reloadClient()` → 用容器 `apiClient` 拉取",删临时客户端。
- [ ] **#9** 拆 ChatViewModel(约 800 行/9 块职责)与 SettingsViewModel(约 760 行/约 40 个状态字段/43 个公开方法)— 前者优先拆气泡寿命调度、更新检测、记忆 CRUD;后者按 Api/Tts/Appearance/Privacy 拆,或先把 `SettingsUiState` 按域拆成嵌套子状态。
- [ ] **#8** 拆 Live2dDelegate(338 行/7 项职责)— 优先切出触摸交互与分区判定、分区语音与 TTS 互斥、壁纸与背景色参数;GL 生命周期 + 单例 + Activity 持有留原类。
- [ ] **#10** companion var 布线改显式注入(AppContainer) — `ttsPlayingChecker`/`ttsStopper`/`TtsManager.onPlaybackStart` 三个全局可变回调改为一个 `VoiceMutex` 接口(或共享 `StateFlow<Boolean>`)由容器注入。
- [ ] **#12** `Live2dModel` 别调 `Activity.finish()`(Live2dModel.kt:144, 219)— 加载失败改为返回值/回调上抛(`onModelLoadFailed`),由 Activity 决定;注意 `draw()` 跑在 GL 线程,回调要切主线程。
- [ ] **#31** 拆 `ChatUiState`(chat/ChatState.kt:21-29)— `updateNotice`/`memoryDialog` 移出(#4 那个 bug 就是它俩被卷进整体重置导致的)。

---

## 📌 待办 C · 异味 / 规范 / 工程(可按主题打包提交)

**重复代码**

- [ ] #20 `SettingsManager` 约 28 项设置 × 3 份样板(重复成员随之增加)→ 泛型 `Setting<T>` + 委托生成 Flow/get/set
- [ ] #21 `SettingsViewModel` 5 处「读旧值-比较-写」(`saveApiKey`/`saveApiUrl`/`saveModel`/`saveSystemPrompt`/`selectModel`)→ 抽 `saveIfChanged`
- [ ] #25 抽公共基类:`dp()`/`Int.withAlpha()` 四处逐字重复(悬浮窗四个窗口);着色器 `compileShader`/`checkShaderCompiled`/`checkProgramLinked` 共 30 行逐字重复(`Live2dSpriteShader` ≡ `WallpaperBlurShader`)。**`place()` 仅 3 处且彼此有差异,别硬抽**
- [ ] #26 抽 `MeaPetDialogCard`:`PrivacyDialog`/`AutoUpdateOptInDialog`/`ChatScreen` 卡片五个数值逐字一致
- [ ] #45 抽 `BubbleContainer`:**只对 `UserBubble` 与 `AssistantBubble` 有效**,`SystemBanner` 结构不同别硬合

**可读性 / 命名**

- [ ] #22 魔法数字提常量(原 8 处;「SystemBubblePolicy 排位阈值 3」已在批次 1 提为 `KEEP_FULL_LIFE_POSITIONS`,余 7 处):`ChatViewModel` 的 `takeLast(5)`、`KtorHttpClientEngine` 的 `300_000`/`30_000`(顺便做成可配)、`SettingsViewModel` 的 `8`/`4`、`MainActivity` 的 `0x14`/`0xF7`/`0xFFF7F7F7`、`Live2dDelegate` 的 `30f`/`400L`/`±0.45f`/`-0.44f`/`0.66f`、`LogExporter` 的 `dropLast(5)`、`ChatScreen` 的 `100.dp`
- [ ] #17 / #47 `Pair` 换命名 data class:`ChatService.sendMessage` → `ChatExchange(user, assistant)`(注意:现为两个重载 + 失败回滚,返回仍是 Pair);`SystemBubblePolicy` → `NextLife(lifeMs, reduceCount)`(`SymbolTable` 已随 1.8.0 删除,无需处理)
- [ ] #48 / #35 拆超长方法:`ChatService.sendMessage`(现两个重载 + 回滚逻辑,只更长)拆 `buildRequest`/`parseReply`/`schedulePostProcess`;`MainActivity.onCreate`(约 220 行)拆 `setupFirstFrameTheme`/`setupGlSurface`/`setupComposeContent`/`setupTouchForwarding`

**规范 / i18n**

- [ ] #23 文案抽 `strings.xml`(实测中文 387 处、`stringResource` 0 次)— 先抽高频页 `ChatScreen`/`PrivacyDialog`;`SettingsKeys` 里写死的 SystemPrompt(745 字)移 `assets/` 或 `res/raw/`
- [ ] #27 空 catch 补注释(全树 20 余处无注释)— `ErrorHandling.kt` 明文要求"必须有一行注释说明为何可安全忽略"
- [ ] #30 + 新-2 `isStreaming` 处置 — main 源码从未置 true(`ChatService` 固定 `stream = false`)。要么删掉它连同 `ChatBubble` 的死 UI 分支与 `MarkdownText` 的 `closeUnclosedFences` 参数,要么真的把流式做出来,别留着当装饰
- [ ] #28 清死代码:`Live2dSprite` `textureId` 构造参数(被同名形参遮蔽)、`Live2dView.setClearColor()`、`Type.kt` 注释掉的 16 行、`ApiRequest` 三个仅测试调用的方法、`MeaPetApplication.onTerminate()`、`Color.kt` 6 个零引用常量

**工程**

- [ ] #33 手写 Animatable 三态机 → `AnimatedVisibility`(`OverlayMenu` 与 `AppearanceSettings` 逐字重复;`PrivacyDialog` 仅单向进场,另算)
- [ ] #34 依赖拉齐:`coreKtx 1.10.1`/`lifecycleRuntime 2.6.1` 与 `agp 9.3.0`/`kotlin 2.2.10`/`composeBom 2026.02.01` 断层严重;Java 11 可升 17(现 `sourceCompatibility`/`jvmTarget` 均为 11);`abiFilters` 只有 arm(v8a/v7a)——**不是疏漏**:sherpa-onnx AAR 只带 arm 原生库,带语音的构建无法出 x86 包,模拟器调试需要另出不含 TTS 的变体

**2026-11-22 代码审查遗留(死代码 / 测试)**

- [ ] **G12 死代码清理** — 工具模式空回复兜底不可达、`ToolRegistry` 的 turn 生命周期钩子无调用、`shouldHintCompaction`/`currentContextTokens` 仅测试用、`FALLBACK_TAIL_MODE` 未用、`AppContainer.conversationStore` 与 `clearAllMemories`/`resetAll` 无调用方;逐项删除或接线。
- [ ] **G14 缺测试** — 落盘取消、成功路径回滚、并发 turn、清空竞态仍缺覆盖(分页、update 改类型 + 删除交叉已补);`SqliteDatabaseConcurrencyTest` 内有重复断言(`repo.count()` 连续两次)。
- ℹ️ 工具循环轮次上限 12 与 tool 结果内的 `x/12` 提示属于设计(轮次信号 + 收尾余量),不是缺陷,后续审查不必再报。

---

## 📌 待办 D · 语音增强(1.9.0)

- [x] **日文上下文数字/日期读法** — 已实现（2026-11-22）：`rewriteJapaneseNumbers` 在归一化层把日文上下文里的整数转为假名数词，覆盖日期（1日→ついたち、20日→はつか）、月份（9月→くがつ）、时刻（15時→じゅうごじ、4時→よじ）、分钟、人数、年龄与常见量词音变；中日文同形的数字串靠「整段是否含假名」区分，判不出时降级保留原数字交给 `ruleFsts`。实现细节见 `docs/tts/architecture.md` §3.1，用例见 `JapaneseNumbersTest`。**待真机验收**：日文句的听感与句间停顿。

---

## 🔭 待调查

- **候选-1** 悬浮窗返回主界面后约 2 秒才出画面(2026-09-05 真机验证批次 1 时发现;属既有延迟,新旧一致,非本次改动回归;**低优先**)。
  已排除 gate 等待阻塞(日志无超时告警,`isRunning` 翻转很快)。推测时间花在 `glSurfaceView.onResume()` 之后的 GL 重初始化(shader 重建 + 模型纹理重载)。
  **下一步**:加带时间戳的 log 打点,区分 `stopService→onDestroy` 派发 / gate 等待 / GL 恢复→首帧三段耗时,再决定是否优化(如恢复期显示上一帧快照或渐显,避免纯黑)。

---

## 验证要求与提交约定

- 批次 A / B 的每一项都要能独立编过(`./gradlew assembleDebug`,bisect safe)。
- **运行时行为必须真机验证**(容器无设备):气泡寿命、黑屏恢复、授权开关一致性(#15)、报错文案。
- 提交前 `git status` 确认 `gradle.properties` / `local.properties` / `gradlew` 不在暂存区。
- 本文行号是归档核对时点的引用,后续改动会漂移,以符号名为准。
