# MeaPet Mobile 代码审查报告 · 复查与优先级处理报告

- **原报告**：`E:\Downloads\mea-pet-mobile-code-review.html`（2026-08-31，48 项发现）
- **复查日期**：2026-08-31
- **复查方式**：对全部 48 条逐条对照当前源码核实（分支 `dev`，含行号、代码证据）
- **复查结论统计**：✅ 属实 **41** · ⚠️ 部分属实 **6** · ❌ 不属实 **1**
- **修复进度**：P0 全 5 项 ✅ 已完成（2026-08-31，`compileDebugKotlin` 通过）；顺手修 #39 / #40 ✅、#1 注释加固 ✅；复核待办批次 1 全部 7 项 ✅ 已完成（2026-09-03）

## 一、结论总览

| # | 问题 | 位置 | 复查结论 | 修正后优先级 |
|---|------|------|----------|--------------|
| 1 | lifeMap 无锁并发修改 | ChatViewModel.kt:82, 392-405 | ⚠️ 部分属实（CME 实际不可能） | P2 加固（✅ 2026-08-31 已加线程约束注释） |
| 2 | ~~modelIds 吞 CancellationException~~ | ApiResponse.kt:54 | ✅ 属实（实际危害极低） | ✅ 已修复（2026-08-31） |
| 3 | ~~trimWindow 打乱消息原始顺序~~ | ConversationManager.kt:190-197 | ✅ 属实（影响有限） | ✅ 已修复（2026-08-31） |
| 4 | ~~clearConversation 整体重置丢字段~~ | ChatViewModel.kt:314-316 | ✅ 属实（用户可见 bug） | ✅ 已修复（2026-08-31） |
| 5 | ~~markwonCache 缓存键不含构造参数~~ | MarkdownText.kt:86-111 | ✅ 属实（仅 textSizePx 会发散） | ✅ 已修复（2026-08-31） |
| 6 | 条件分支内 collectAsState | OverlayMenu.kt:75-77 | ❌ 不属实（合法 Compose 用法） | 剔除 |
| 7 | ~~persistAsync scope 取消后静默丢数据~~ | ConversationStore.kt:50-89 | ✅ 属实（真实数据丢失） | ✅ 已修复（2026-08-31） |
| 8 | Live2dDelegate God Class | Live2dDelegate.kt（338 行） | ✅ 属实 | P1 架构 |
| 9 | ChatViewModel / SettingsViewModel 上帝类 | 413 / 499 行 | ✅ 属实（UiState 实为 30 字段） | P1 架构 |
| 10 | companion var 全局可变回调 | AppContainer.kt:193-201 等 | ✅ 属实 | P1 架构 |
| 11 | 消息双源 + mergeWithHistory 补丁 | ChatViewModel.kt:176-189 | ⚠️ 部分属实（takeLast(5) 仅兜底） | P1 架构 |
| 12 | 模型层直接 Activity.finish() | Live2dModel.kt:144, 219 | ✅ 属实 | P1 架构 |
| 13 | 服务定位器反模式 | MainActivity/ChatScreen/OverlayMenu 等 | ✅ 属实（实际超 3 处） | P1 架构 |
| 14 | 三套并发原语混用 | ConversationManager/Store/ViewModel | ✅ 属实 | P1 架构 |
| 15 | 隐私授权双存储 + 主线程 runBlocking | PrivacyConsentManager.kt:51-69 | ✅ 属实（ANR 风险） | **P1 稳定性（优先）** |
| 16 | 客户端生命周期两套并存 | AppContainer/SettingsViewModel | ✅ 属实（同一方法内并用） | P1 架构 |
| 17 | sendMessage 返回 Pair 语义模糊 | ChatService.kt:58, 164 | ✅ 属实（有 KDoc 缓解） | P2 |
| 18 | ~~ApiException 丢弃网关 error.message~~ | ApiException.kt / OpenAiCompatibleClient.kt:127-131 | ✅ 属实 | ✅ 已完成（2026-09-03 批次一） |
| 19 | getRelevant() 读方法写数据 | MemoryRepository.kt:179-206 | ✅ 属实 | P1 架构 |
| 20 | SettingsManager 三处重复样板 | SettingsManager.kt（346 行） | ⚠️ 部分属实（21 项设置，非 30） | P2 |
| 21 | save* 重复"读-比-写-reload" | SettingsViewModel.kt | ⚠️ 部分属实（完整模式仅 4 处） | P2 |
| 22 | 魔法数字遍布 | 多处 | ✅ 属实（抽查 5 项全中） | P2 |
| 23 | UI 文案硬编码中文 | ChatScreen/ChatBubble 等 | ⚠️ 部分属实（strings.xml 存在但仅 app_name） | P2 |
| 24 | ~~Triple<Long, Job, Int> 语义模糊~~ | ChatViewModel.kt:82 | ✅ 属实 | ✅ 已完成（2026-09-03 批次一，BubbleLife） |
| 25 | Overlay 四窗口类逐字重复 | live2d/overlay/*.kt | ✅ 属实 | P2 |
| 26 | 弹窗 Card 模板三处重复 | PrivacyDialog 等 | ✅ 属实 | P2 |
| 27 | 空无注释 catch 违反自家约定 | MainActivity.kt:104/241/244/409 等 | ✅ 属实 | P2 |
| 28 | 死代码 / 死参数 | 多处 | ✅ 属实（抽查全中） | P2 |
| 29 | EXCHANGE_COUNT 混入 SettingsKeys | SettingsKeys.kt:18-24 | ✅ 属实 | P2 |
| 30 | ChatMessage 混入 isStreaming | ChatMessage.kt:35 | ✅ 属实 | P2 |
| 31 | ChatUiState 上帝状态 | ChatState.kt:20-28 | ✅ 属实 | P2 |
| 32 | ~~ConversationManager 文档自相矛盾~~ | ConversationManager.kt:16 vs 143/148 | ✅ 属实 | ✅ 已完成（2026-09-03 批次一） |
| 33 | 手动 Animatable 未用 AnimatedVisibility | OverlayMenu.kt:60-70 等 | ✅ 属实 | P2 |
| 34 | 依赖版本断层 + Java 11 | libs.versions.toml / build.gradle.kts | ✅ 属实 | P2 |
| 35 | MainActivity.onCreate 约 182 行 | MainActivity.kt:72-253 | ✅ 属实 | P2 |
| 36 | ~~onResume 16ms 自旋忙等~~ | MainActivity.kt:277-292 | ✅ 属实 | ✅ 已完成（2026-09-03 批次一） |
| 37 | currentPrefs() 主线程 runBlocking | SettingsManager.kt:192-193 | ✅ 属实（启动期 ANR 风险） | **P1 稳定性（优先）** |
| 38 | ~~LogExporter waitFor() 无超时~~ | LogExporter.kt:149 | ✅ 属实 | ✅ 已完成（2026-09-03 批次一） |
| 39 | ~~MarkdownText 每次重组重解析~~ | MarkdownText.kt:177-182 | ✅ 属实（流式卡顿） | ✅ 已修复（2026-08-31，P0 顺手） |
| 40 | ~~retryLastMessage O(n²) 过滤~~ | ChatViewModel.kt:280-286 | ✅ 属实 | ✅ 已修复（2026-08-31，P0 顺手） |
| 41 | DurationExpander 稠密矩阵 | DurationExpander.kt:80 | ✅ 属实（有帧数封顶缓解） | P2 |
| 42 | 每次突变全量重写 JSON | MemoryRepository.kt:280-296 | ✅ 属实 | P2 |
| 43 | 同一 URI 三次 openInputStream | WallpaperStore.kt:47/51/63 | ✅ 属实 | P2 |
| 44 | ensureDict check-then-act 竞态 | ChineseG2p.kt:24-32 | ⚠️ 部分属实（实际无锁，竞态存在） | P2 |
| 45 | 三个气泡 Composable 重复 | ChatBubble.kt:54-174 | ✅ 属实 | P2 |
| 46 | ~~CancellationException 处理不一致~~ | ApiResponse.kt:54 vs 其他 | ✅ 属实（与 #2 同源） | ✅ 已随 #2 修复（2026-08-31） |
| 47 | Pair 作返回类型多处 | ChatService/SystemBubblePolicy 等 | ✅ 属实 | P2 |
| 48 | sendMessage 约 102 行超长方法 | ChatService.kt:58-159 | ✅ 属实 | P2 |

## 二、对原报告的关键修正

### ❌ 应剔除：#6 条件分支内 collectAsState

`OverlayMenu.kt:75-77` 的写法确实存在，但**"违反 Compose 组合规则"的定性错误**。Compose 明确允许可组合函数的条件调用，编译器通过 group 插入/移除正确处理分支切换；`container` 经 `remember(context)` 稳定化，分支变化只会触发正常订阅/退订。无 bug，最多算风格可议。

### ⚠️ 应降级：#1 lifeMap 并发崩溃风险

事实层面全部属实（`LinkedHashMap` 无同步，多协程读写），但**"可能 ConcurrentModificationException"的结论不成立**：

- collect 协程与 scheduleRemove 协程均为 `viewModelScope.launch`，运行在 `Dispatchers.Main.immediate` 单线程调度器上，不存在并行执行；
- 代码从未对 lifeMap 本身做迭代（`sysIds.forEachIndexed` 迭代的是 List），map 只有按 key 的 get/put/remove，且 collect lambda 内各 map 操作之间无挂起点。

线程封闭使竞争实际不可能发生。但结构本身无同步保障、依赖"恰好都在主线程"这一隐式前提，**建议降级为 P2 加固项**（加注释说明线程约束，或顺手改 actor 模式），而非 P0。另注：报告称 scheduleRemove 在 335-340 行，实际为 392-405 行。

### ⚠️ 危害重估：#2 吞 CancellationException

属实（`ApiResponse.kt:54` 裸 `catch (_: Exception)`，违反项目 `ErrorHandling.kt:11-14` 约定；同文件 `chatCompletionContent` 用 `runCatchingLog` 正确重抛，风格不一致）。但 `modelIds` 是**纯同步 JSON 解析、无挂起点**，实践中 CancellationException 几乎不可能在此抛出，实际危害极低。修复成本一行，建议并入 P0 顺手修，动机是**统一约定**而非防崩溃。

### ⚠️ 影响面收窄：#3、#5

- **#3**：trimWindow 乱序属实，但 `buildApiMessages`（ConversationManager.kt:113-114）发请求时会过滤 history 里的 system 消息并自行重组 system 前缀，因此**对发给模型的内容影响有限**，主要影响内存中 history 的排列（以及将来若改用 history 原序时的隐患）。
- **#5**：缓存键不全属实，但 `tableBorder` 是 `dark` 的纯函数（MarkdownText.kt:146），同 dark 下不可能不同；真正会发散的只有 `textSizePx`（来自 LocalDensity，系统字体缩放/显示密度变化时同 dark 命中旧实例）。场景存在但触发条件苛刻，修复仍建议做（键纳入全部构造参数）。

### ⚠️ 机制描述偏差：#11、#20、#21、#23、#44

- **#11**：`takeLast(5)` 只是分界 id 找不到时的保守兜底分支；主弥合机制是"以 history 最后一条 id 为分界，之前以 history 为准、之后保留尾部"（ChatViewModel.kt:176-189，有 KDoc 说明）。核心论点（双消息源、一边裁剪一边不裁剪）成立。
- **#20**：模式属实，但实为约 21 项设置（报告称 30）。
- **#21**：完整的"读旧值-比较-写"模式仅 4 处（saveApiKey:188 / saveApiUrl:197 / saveModel:206 / saveSystemPrompt:214），带 reload 的只 2 处；"重复 10+ 次"夸大。
- **#23**：strings.xml 存在（`res/values/strings.xml`，仅含 app_name），"全项目无 strings.xml"字面不实；但 UI 文案硬编码中文、SystemPrompt 600+ 字写死在 SettingsKeys.kt:67-68 均属实。
- **#44**：竞态属实，但报告称"@Volatile + 锁"，实际代码**没有任何锁**——就是无保护的 check-then-act。

### 复查的补充发现

- **#13 服务定位器实际超过 3 处**：除报告所列外，SettingsScreen、ProviderSettings、AboutSettings、PrivacyDialog、OverlayPalette、FloatingLive2dService 等也有 container/MeaPetApplication 直接引用。
- **#9 SettingsUiState 实为 30 个字段**（报告称 25），公开方法实测 32 个。
- **#16** 两种客户端机制并非互斥路径：`fetchModels` 在同一方法里先调 `container.reloadClient()` 又临时 new 一个客户端（SettingsViewModel.kt:402-414）。
- **#28** 细节修正：`Live2dView.clearColor` 字段本身有被使用（:136），死代码是其 `setClearColor` setter（:111）无调用点。

## 三、修正后优先级处理路线

### P0 — 立即修复（真实用户可见缺陷，工作量均很小）

> ✅ **P0 全部 5 项已于 2026-08-31 修复**（`compileDebugKotlin` 通过）。顺手修复：#40（O(n²)→O(n)）、#39（remember 缓存 Spanned）、#1（加线程约束注释）。

| 顺序 | 项 | 修复要点 | 状态 |
|---|---|---|---|
| 1 | ~~**#4** clearConversation 整体重置~~ | 改 `ChatUiState(...)` 整体重建为 `it.copy(messages = emptyList(), isLoading = false, error = null, inputText = "", memoryContextInfo = "对话已清除")`，保留 updateNotice/memoryDialog | ✅ 已修复 |
| 2 | ~~**#7** persistAsync 静默丢数据~~ | emit 前检测 collector 存活（或改 Channel + isClosedForSend），失败至少 `Log.w` 告警 | ✅ 已修复（存 `persistJob`，检测 `isActive`） |
| 3 | ~~**#3** trimWindow 乱序~~ | 就地逐条裁剪（removeAt(0)）或按原始 index 保留尾部，不打乱交错顺序 | ✅ 已修复（迭代器就地从头部删） |
| 4 | ~~**#5** markwonCache 缓存键~~ | 键改为含 textSizePx/tableBorder 的 data class（如 `MarkwonKey(dark, textSizePx, tableBorder)`） | ✅ 已修复（`MarkwonKey` data class） |
| 5 | ~~**#2 + #46** 吞 CancellationException~~ | `modelIds` 前置 `catch (e: CancellationException) { throw e }`，与项目约定统一 | ✅ 已修复 |

### P1 — 近期处理（先稳定性/ANR，后架构）

**稳定性（优先，有真实 ANR/卡顿风险）：**

1. **#37** SettingsManager.currentPrefs() 主线程 runBlocking：启动路径（MainActivity.onCreate → getThemeMode）触发，DataStore 慢时直接 ANR。改异步预加载 + 默认值兜底。
2. **#15** PrivacyConsentManager 双存储 + 主线程 runBlocking + 2s 超时：统一为单一存储源（DataStore），isAgreed() 改读缓存或挂起函数。
3. ~~**#36** onResume 16ms 自旋忙等~~（✅ 已完成）：改用 `Live2dRenderState.isRunning` 的 Flow 订阅或回调恢复 GL。
4. ~~**#38** LogExporter waitFor() 无超时~~（✅ 已完成）：改 `waitFor(5, TimeUnit.SECONDS)` + `destroyForcibly()` 兜底。
5. ~~**#18** ApiException 丢弃网关 error.message~~（✅ 已完成）：解析 responseBody 中 `error.message` 拼入用户可见文案，改善报错体验。

**架构重构（工作量大，建议单独排期、逐个拆分）：**

- **#8** Live2dDelegate 拆分（GL 生命周期 / 触摸交互 / 分区语音 / 配色壁纸）
- **#9** ChatViewModel、SettingsViewModel 拆分（Memory / Update / ApiSettings / TtsSettings 等）
- **#10** companion var 回调 → 显式接口注入或共享 Flow
- **#11** 消息源统一为 ConversationManager 单一真相源
- **#12** Live2dModel 的 Activity.finish() 改为回调上层
- **#13** 服务定位器 → CompositionLocal / ViewModel 暴露（实际超过 3 处）
- **#14** 并发原语统一为协程友好模型（含 #1 的 lifeMap 加固一并处理）
- **#16** 客户端生命周期统一由容器管理
- **#19** getRelevant() 读写分离（CQS）

### P2 — 持续改善（可按主题打包提交）

- **重复代码**：#20、#21（泛型委托/抽取 saveIfChanged）、#25（公共基类/工具对象）、#26（MeaPetDialogCard）、#45（BubbleContainer）
- **可读性**：#22（魔法数字提常量）、~~#24~~（BubbleLife data class，✅ 已完成）、#47（命名 data class 替代 Pair）、#17（ChatExchange）
- **规范/i18n**：#23（文案抽 strings.xml、SystemPrompt 移 assets/res/raw）、#27（空 catch 补注释）、~~#32~~（修正文档矛盾，✅ 已完成）、#29（EXCHANGE_COUNT 另立仓储）、#30（isStreaming 移出领域模型）、#31（拆分 ChatUiState）
- **性能**：~~#39~~（✅ 已修复）、~~#40~~（✅ 已修复）、#41（稀疏结构）、#42（增量写入）、#43（流复用）
- **并发加固**：#1（✅ 已加线程约束注释；actor 化仍随 #14 一并）、#44（ensureDict 双重检查锁）
- **工程**：#33（AnimatedVisibility）、#34（依赖版本拉齐、Java 升级、补 x86_64）、#35（onCreate 拆方法）、#28（清理死代码）

### 剔除项

- ~~#6 条件分支内 collectAsState~~ — 复查认定为合法 Compose 用法，非缺陷。

---

*复查覆盖原报告全部 48 条；所有"属实"结论均以当前 `dev` 分支源码行号为证。*
