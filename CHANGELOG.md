# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

该项目的所有重大更改都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，
本项目遵循 [语义化版本控制](https://semver.org/spec/v2.0.0.html)。

---

## [Unreleased]（版本号待定）

### Fixed

- **清空对话误清更新提示与记忆弹窗（#4）** — `clearConversation` 原用 `ChatUiState(...)` 整体重建状态，清空对话会顺带关掉正在显示的更新提示（`updateNotice`）与记忆查看弹窗（`memoryDialog`）。改为 `copy(...)` 仅重置对话相关字段（`messages`/`isLoading`/`error`/`inputText`/`memoryContextInfo`），其余状态保留。
- **会话持久化在作用域取消后静默丢数据（#7）** — `ConversationStore.persistAsync` 的落盘 collector 由传入 `scope` 驱动，scope 一旦取消 collector 即结束，之后所有 `persistAsync` 只往 `MutableSharedFlow` emit、无人消费，会话快照静默丢失且无任何痕迹。现保留 collector 的 `Job` 引用，`persistAsync` 前检测 `isActive`，已取消时记 `Log.w` 告警暴露问题。
- **滑动窗口裁剪打乱消息原始顺序（#3）** — `ConversationManager.trimWindow` 原用「system 整体提前 + 非 system 尾部拼接」重建列表，把 system 消息全部挪到最前，打乱与 user/assistant 的交错顺序。改为就地迭代器从头部逐条裁掉最旧的超额非 system 消息，保留原始交错顺序。
- **Markwon 缓存键不含全部构造参数（#5）** — `MarkdownText` 的 `markwonCache` 键仅 `dark: Boolean`，但 `textSizePx` 参与构造；系统字体缩放/显示密度变化时同 dark 命中旧实例，公式字号错乱。缓存键改为含全部构造参数的 `MarkwonKey(dark, textSizePx, tableBorder)`，调用处 `remember` 键同步。
- **modelIds 吞 CancellationException（#2 / #46）** — `ApiResponse.modelIds` 原用裸 `catch (_: Exception)`，违反项目 `ErrorHandling.kt` 的取消重抛约定。现前置 `catch (e: CancellationException) { throw e }`，普通异常记 `Log.w` 后返回空列表，与同文件 `chatCompletionContent` 的 `runCatchingLog` 风格统一。
- **系统气泡「挤旧扣寿命」实际会延长寿命** — `SystemBubblePolicy` 原按「剩余时长」运算，`ChatViewModel` 扣减后取消旧 Job 并从当前时刻重新 `delay(newLife)`，已流逝的时间被丢弃：一个已存活 6 秒的气泡「扣 2 秒」变成再活 5 秒（总计 11 秒），比原定 7 秒更久，与「挤旧」意图相反。策略函数改为按**绝对到期时刻**运算（`computeNextDeadline(deadlineMs, nowMs, reduceCount, position)`），`lifeMap` 值类型由 `Triple<Long, Job, Int>` 换成语义明确的 `BubbleLife(deadlineMs, job, reduceCount)`，时间基准取 `SystemClock.elapsedRealtime()`；剩余不足一个扣减步长时钳到当前时刻立即移除。排位阈值 3 提取为 `KEEP_FULL_LIFE_POSITIONS`。补 3 条回归用例（含「任意时刻扣减都不会让到期时刻推后」的遍历断言）。
- **API 网关错误详情被完全丢弃** — `ApiException` 存了 `responseBody` 却无任何读取点：用户只看到「请求过于频繁」这类状态码文案，Logcat 里也没有原始响应体，无从判断是哪个模型、哪条限额触发。新增 `ApiResponse.errorMessage(body)` 解析网关错误说明（兼容 `error.message` 对象、`error` 字符串、顶层 `message` 三种形态），`ApiException.friendlyMessage(statusCode, detail)` 把它拼进用户可见文案（截断至 200 字符），原始响应体经 `Log.w` 落 Logcat 供导出日志排查。
- **等悬浮窗停止的 GL 恢复轮询无超时上限** — `MainActivity.onResume` 原用 `Handler.postDelayed(16L)` 轮询 `Live2dRenderState.isRunning`，Service 若异常退出、没能把 `isRunning` 置回 false，轮询永不终止且 `glSurfaceView.onResume()` 永不执行 → 主界面永久黑屏 + 每 16ms 唤醒主线程。改为 `lifecycleScope` 内订阅 StateFlow（`isRunning.first { !it }`）并加 2 秒 `withTimeoutOrNull` 上限，超时记 `Log.w` 后照常恢复 GL；随之删掉专用的 `mainHandler` 与 `resumeGate`。
- **LogExporter 的 waitFor() 无超时** — `logcat -d` 子进程异常挂起时 `process.waitFor()` 会无限占住 `Dispatchers.IO` 线程且不响应协程取消。改为 `waitFor(5, TimeUnit.SECONDS)`，超时记 `Log.w` 并 `destroyForcibly()` 兜底。
- **TTS attn 矩阵护栏形同虚设** — `DurationExpander.MAX_TOTAL_FRAMES = 480_000` 在上游 200 字截断（`TtsManager.MAX_SYNTH_CHARS`）下永远触发不到（最坏 50 帧/音素 × 约 3600 音素 ≈ 180000 帧），等于没有护栏；注释「约等于 22 秒音频」也算错约 250 倍（实为约 93 分钟）。改为按 attn 矩阵内存封顶（`MAX_ATTENTION_BYTES = 64MiB`，上限随 `tX` 动态换算成帧数），真正拦住 dp 输出异常、逐音素全被钳满那条原可膨胀到 GB 量级的路径；正常语音（200 字、语速 0.5~2.0）对应上限约 9300 帧 ≈ 108 秒，不会被截。
- **重试目标已不在历史时会误删全部 assistant 回复** — `ChatService.retryLastMessage` 的 `lastUserMessage()` 与 `getMessages()` 是两次独立加锁，之间该消息若被移除，`indexOfLast` 返回 -1 使 `drop(userIdx + 1)` 退化为 `drop(0)`，随后的 assistant 过滤会删掉整段历史的助手消息。补 `userIdx < 0` 前置判断，直接返回失败。

### Documentation

- **修正 ConversationManager 的自相矛盾文档** — 类注释原称「不关心消息是用户还是助手发送的，仅按顺序维护」，但 `trimWindow` 豁免 system、`buildApiMessages` 重组 system 前缀、并提供 `lastUserMessage`/`lastAssistantMessage` 按角色查询，实际处处关心角色；改为如实说明。另 `lastUserMessage()` 的 KDoc 写「最后一条非 system 消息」而实现只取 `role == user`（非 system 还包含 assistant），一并修正。

### Performance

- **Markdown 解析结果缓存（#39）** — `MarkdownText` 原在 `update` 阶段每次重组都 `setMarkdown` 重新解析；现 `remember(markwon, safe) { toMarkdown(safe) }` 缓存解析结果 Spanned，主题/气泡透明度变化只改色不再触发昂贵的 Markdown 重解析（流式期间 `safe` 每帧变仍逐帧解析）。
- **retryLastMessage 过滤 O(n²) → O(n)（#40）** — 原 `filterNot` 内对每条消息 `indexOf`，整体 O(n²)；现一次性 `indexOfLast` 定位分界后 `filterIndexed` 单趟过滤。

### Notes

- 首批修复对应 `code-review-triage.md` 的 P0 全部 5 项（#3/#4/#5/#7 + #2/#46），并顺手修复 #39/#40、为 #1 的 `lifeMap` 补充线程约束注释（说明其安全性依赖 `Dispatchers.Main.immediate` 单线程前提）。
- 第二批（本次）是对 48 条审查结论独立复核后的第一档「小改动高收益」项：一条报告未发现的真实逻辑 bug（系统气泡寿命）、#18 网关错误详情、#38 waitFor 超时，以及三处报告未提到的护栏/边界缺失（GL 恢复轮询无超时、TTS attn 护栏失效、重试边界）。复核同时认定 #6（条件分支内 `collectAsState`）为合法 Compose 用法、#44（`ensureDict` 竞态）因底层 `PinyinDict.init` 已 `@Synchronized` 幂等而无实际危害，两者不再列为待修。
- P1 稳定性（#37 `currentPrefs` 主线程 `runBlocking`、#15 隐私授权双存储）与架构重构（#8~#14/#16/#19）另行排期。

---

## [1.7.1] - 2026-08-31

### Added

- **Temperature 参数说明气泡** — 「提供商」子页 Temperature 滑杆标签旁新增问号图标，点击弹出 RichTooltip：讲解参数作用、桌宠场景建议取值（闲聊 0.8~1.2、严格听指令降到 0.3 以下），及「设为 0 不保证输出一致 / 部分提供商上限 1.0 / 推理模型忽略此参数」三条注意事项；气泡带「知道了」按钮，返回键先关气泡再翻页。新增通用组件 `ParamLabelWithHelp`（`SettingsCommon.kt`，参数标签 + 说明气泡），其余参数滑杆可直接复用；新图标 `ic_help_outline`（MDI help-circle-outline，Apache 2.0，516 字节），经 `tools/svg2vd.py` 生成，清单同步至 `tools/icons.txt`。
- **友盟统计 SDK 构建门控** — `umeng.enabled=false`（local.properties，或命令行 `-Pumeng.enabled=false`）时 SDK 不打包进 APK（依赖退化为 compileOnly，仅保留编译期符号）；统计接入代码（预初始化/正式初始化）、首启隐私弹窗与关于页授权管理经 `BuildConfig.UMENG_ENABLED` 全部失效，关于页统计数据采集卡片显示「该构建未包含统计 SDK，不会采集任何数据」，隐私政策正文同步适配为无 SDK 文案。默认 `true`，行为与既往构建一致。

### Changed

- **API 地址不再自动补 `/v1`** — 移除 baseUrl 末尾自动拼接 `/v1` 的规范化逻辑，用户填写的地址（含版本路径）原样保留后拼请求路径（如智谱清言 `https://open.bigmodel.cn/api/paas/v4`）；为版本号继续演进的端点留余地。
- **ONNX Runtime 升 1.24.3 且原生库随 APK 打包** — 1.23.2 在部分骁龙 Soc 上 CPU provider 的 KleidiAI SME MatMul 路径触发 SIGILL 崩溃（[onnxruntime#26921](https://github.com/microsoft/onnxruntime/issues/26921)），升 1.24.3 修复。`libonnxruntime.so` 由「运行时按需下载」改为随 APK 打包（`abiFilters` 仅保留 arm64-v8a / armeabi-v7a，APK 增大约 44MB）；4 个 ONNX 模型仍按需下载 / 本地 zip 导入，TTS 资源包不再携带 so（导入旧资源包时多余 so 自动跳过）。老版本下载到 `filesDir/tts_model/lib/` 的残留原生库启动后异步清理。
- **隐私政策修订至 1.2（2026-08-31 更新）** — 按构建是否包含统计 SDK 拆分为**两版独立全文**（含 SDK 版说明友盟采集范围、授权管理与数据安全；无 SDK 版声明不采集任何数据），两份政策在同一文件分开维护、共享同一套版本号/日期；修正预初始化描述（该阶段仅完成 SDK 初始化准备，不采集、不上报数据，正式采集仅在用户同意后进行）。版本号与生效/更新日期由 `local.properties` 改为**代码硬编码**（`PrivacyPolicyContent.kt` 的 `PRIVACY_POLICY_*` 常量）——修订政策必改代码文案，版本号随代码一并提交，避免配置漏改导致老用户不重新确认。
- **关于页应用简介副标题更换** — 由「借助 Claude Code CLI，由 DeepSeek V4 Flash 强力赋能辅助开发」改为「Say my name when a tree susurrates / Once and again telling a story lost in time」。

### Fixed
- **[#15](https://github.com/llz121517/mea-pet-mobile/issues/15)** TTS 功能在部分骁龙 Soc 上触发 SIGILL 崩溃的问题
- **TTS 偶发无声（段调度竞态）** — `TtsAudioPlayer` 的 `play`/`stop` 原非原子：两个 speak 并发（主界面 + 悬浮窗、或连发消息）时，新段的 `stopInternal` 会把正在写入的旧段 track `pause/flush` 造成短写（剩余音频被丢弃）；代际交错时 `obtainTrack` 的兜底 release 还可能误杀他段新 track；旧消息的合成协程也会因 `cancel()` 挡不住 ONNX native 推理而在完成后盖过新消息（最新回复无声）。现以 `playLock` 把"停旧段 → 取新号 → 启新线程"串行化；`TtsManager` 增加 speak 序号校验，过期协程在合成完成后自行丢弃本段。
- **短写导致的状态卡死与不可观测** — `AudioTrack.write()` 返回值此前被忽略：阻塞写被并发 `pause/flush` 提前解除时会短写返回，剩余样本被丢弃、末尾 marker 永不到达 → `isPlaying` 卡 true。现按全写/短写/错误码三分支记录日志，短写与错误码路径立即复位播放状态；短写事件自此在日志中直接可见（W 级「短写！」），无声问题不再与正常播放不可区分。
- **native 崩溃日志导出 protobuf 字节损坏** — `LogExporter` 原用字符流写出 tombstone protobuf，≥0x80 的字节被替换为 U+FFFD，导出日志解出的 pid/tid 全是垃圾值。改为二进制流直写，并新增 `tools/decode_tombstone.py` 解码工具与 `LogExporterTest` 回归单测（检测 U+FFFD 损坏特征）。
- **语音设置界面显示修正** — 移除未使用的 viewModel 导入；修正语音模型就绪状态描述文案；模型下载大小信息由 92MB 更新为 72MB（原生库已随 APK 打包，不再计入下载）。

### Notes

- Temperature 参数说明气泡来自社区贡献者 [@furina315](https://github.com/furina315)（PR #16）。

---

## [1.7.0] - 2026-08-29

### Added

- **助手消息 Markdown 渲染** — 接入 Markwon 4.6.2（core / ext-latex / ext-strikethrough / ext-tables / linkify / inline-parser），助手气泡支持代码块、表格、删除线、自动链接；用户气泡与系统提示保持纯文本。
- **LaTeX 公式渲染** — 基于 jlatexmath，注册 `MarkwonInlineParserPlugin` 并开启行内公式；将 `\[...\]`、`\(...\)`、含数学符号的 `$...$` 统一归一化为 `$$...$$`，首次渲染前显式 init 并预热符号表。
- **聊天气泡文字选择复制** — 用户气泡用 `SelectionContainer`、助手气泡 `TextView setTextIsSelectable(true)` 并保留 `LinkMovementMethod`；长按弹出系统复制 / 全选菜单，选择与链接点击并存。不改动根布局触摸透传，Live2D 点击互动不受影响。
- **流式输出自动补全未闭合代码围栏** — 流式渲染时临时补齐代码块围栏，避免半截代码块渲染错乱。
- **设置界面二级导航** — 设置页重构为「入口列表 + 提供商 / 对话 / 外观 / 语音 / 关于」五个子页，每个入口显示当前状态摘要（模型名、主题、发声开关、版本号），层级更清晰。
- **ErrorBubble 错误卡片** — 对话流末尾的错误卡片取代原错误 Snackbar：`errorContainer` 底色、右上角关闭、右下角重试，跟随气泡透明度设置。错误为瞬态 UI 状态，不进 `ChatMessage` 也不写会话历史。
- **应用信息并入关于页** — 新增 `AppInfoSection`；「更新」子页新增手动「检查更新」（按钮、进度、结果文案与发布页链接），启动静默检测仍保留。
- **MDI 矢量图标** — Material Design Icons（Apache 2.0）批量转为 `VectorDrawable`，附 `tools/svg2vd.py`（Iconify → VectorDrawable）与 `tools/contrast_check.py`（校验 12 套预设 WCAG 对比度）两个工具脚本；关于页附署名。
- **隐私政策版本号机制** — 新增 `app.privacyVersion` / `app.privacyEffectiveDate` / `app.privacyUpdateDate` 配置（`local.properties` → `BuildConfig` → `AppInfo`），用户已看过的隐私政策版本号（字符串，如 1.1）记录于 DataStore（`privacy_version_shown`）。启动时若记录值不等于当前版本号则重新弹出隐私政策，副标题提示「隐私政策更新」，确保政策更新后老用户重新确认；版本号、生效时间与修订时间展示于隐私政策头部。
- **启动时「是否启用检查更新」弹窗** — 首次启动时弹出 `AutoUpdateOptInDialog`（复用 `first_launch` 标记，仅首次启动一次，与隐私版本号解耦，政策更新不再重复询问），用户选择「开启 / 不开启」启动自动检查更新，结果写入 `enable_auto_update_check`；不开启时仍可在「关于」页手动检测。

### Changed

- **浅色模式配色对比度修复（12 套预设全部达标 WCAG）** — 原 `lightScheme` 用 `primary = seed` 搭 `onPrimary = seed.darken(0.6f)`，用户气泡对比度仅 1.89~3.90:1，全部低于 WCAG 正文 4.5:1（单色预设几乎不可读）。改为 `primary/secondary/tertiary` 先 `darken(0.35f)` 再配 `lighten(0.95f)` 的近白前景，12 套全部达标（5.51~12.19:1）。仅改浅色方案，深色方案数值不变。
- **设置代码结构拆分** — `SettingsScreen.kt` 由 1494 行单文件拆为 `ui/screen/settings/` 下八个文件；`SettingsGroup` 改为 `SettingsCard`（组标题可选）；`Page` 枚举由 `CHAT/SETTINGS/PRIVACY` 简化为 `CHAT/SETTINGS`，隐私政策页由顶层下移为设置子页（从政策页返回回到「关于」而非设置根页）。
- **输入栏改用 `BasicTextField`** — 去掉 M3 `TextField` 装饰盒按状态分档的容器色，避免半透明容器上露底。
- **清理死代码** — 移除 `AboutDialog`（162 行）、`PrivacyPolicyScreen.kt`、`ChatEvent.CheckForUpdate` 等、`ChatUiState` 的 about 字段、`LIVE2D_MODEL_SOURCE_URL` 及主界面「更多」菜单的「关于」项；`ChatScreen.kt` 由 646 行降至 474 行。
- **关于页外部链接间距微调** — 「Live2D 模型来源 / GitHub 仓库 / 交流 QQ 群」三条链接的间距由 2dp 调为 3dp，视觉更舒展，点击不易误触。
- **全局图标统一为 Material Design Icons** — 主页顶部菜单（设置 / 清除对话 / 查看记忆 / 悬浮窗 / 喇叭）、悬浮窗菜单（关闭 / 唤起输入 / 锁定 / 透明度）与两侧输入栏的发送、关闭、拖动抓手，全部由 `material-icons`、手画矢量或字符（✕ / ≡ / 系统 `ic_menu_send`）统一替换为 MDI 矢量图标（`tools/svg2vd.py` 生成），线条风格与设置页一致并随主题染色。
- **悬浮窗发送按钮与主页对齐** — 尺寸 / 圆形主色底 / 空输入置灰禁用态与主页 `ChatInputBar` 一致；配色经 `OverlayPalette` 复刻主界面 `Color.kt` 派生规则，修正原先 `primary`/`onPrimary` 与主界面不一致的偏差。
- **触摸气泡色相修正** — 主题 `tertiary` 原由 `hueShift()`（交换 G/B 通道）派生，会把紫甩成绿、蓝甩成黄绿，触摸人物的小气泡底色与主题色相错位；改为 seed 的更去饱和变体（`desaturate(0.6f)`），保留同色相仅更柔和，浅色 / 深色同改。`tools/contrast_check.py` 同步更新并验证 12 套预设全部达标。
- **预设方案补齐 surfaceContainer 系列** — 原先只设了 `background/surface/surfaceVariant`，M3 弹窗 / 菜单容器默认取的 `surfaceContainerHigh` 等未配置，回退成 `lightColorScheme()` 的默认紫调。现由各自的 `tintedBg` 逐档派生（浅色逐档压暗、深色逐档抬亮），弹窗与菜单底色跟随当前预设。
- **触摸气泡底色加深** — `tertiaryContainer` 浅色 `lighten(0.82→0.77f)`、深色 `darken(0.6→0.55f)`，触摸提示条更醒目，对比度仍全部达标。
- **隐私弹窗触发条件改为版本号驱动** — 由「是否已做出选择」改为：首次启动（`first_launch`）必弹；非首次则「已记录版本号 ≠ 当前构建版本号」时弹，与是否同意无关；`PrivacyConsentManager` 移除 `hasUserChosen` / `KEY_USER_CHOSEN`。弹窗判定移入 `LaunchedEffect` 异步执行，避免首帧同步读 DataStore 拖慢启动。

### Fixed

- **输入栏底部亮带（浅色模式）** — Compose 把投影与填充放在同一 `RenderNode`，填充 0.85 透明度时投影渗入内部压暗大部分区域，未被覆盖的一条反而是唯一正确的颜色；将 `shadowElevation` 从 16dp 改为 0dp 解决。

### Notes

- 本版本功能主要来自社区贡献者 [@furina315](https://github.com/furina315)（PR #11、PR #12）。
- 相对 1.6.0 无破坏性变更，applicationId 不变，可覆盖安装。
- 新增渲染依赖 Markwon 4.6.2（core / ext-latex / ext-strikethrough / ext-tables / linkify / inline-parser）。
- 新增 Material Design Icons 矢量图标（Apache 2.0），来源与署名见关于页。

---

## [1.6.0] - 2026-08-25

### 大版本总体介绍（自 1.5.0）

本次 1.6.0 历经 alpha → beta → 正式版三个迭代，核心围绕「语音」与「体验打磨」：

- **本地 VITS 语音合成（TTS）** — 梅尔音色端侧离线朗读，模型按需下载（约 92MB）并支持断点续传、主界面 / 悬浮窗独立开关、语速与语言调节（当前仅中文）。
- **语音交互增强** — 喇叭图标一键开关会话语音；TTS 与触摸语音互斥不叠音；「播放中」状态精确到实际播完。
- **日志一键导出** — 应用日志 + native 崩溃记录（tombstone）一键分享，方便反馈问题。
- **稳定性修复** — 模型下载偶发崩溃、Live2D 切后台主线程卡顿、TTS 偶发无声、长对话列表错位等一批问题解决。
- **TTS 打磨** — 合成自然度调优（音高 / 节奏 / 句末停顿）、语速滑杆方向与步进重做。
- **外观与设置** — 主页聊天气泡透明度可调；主界面背景壁纸（相册选图 + 模糊）；悬浮窗透明度修复（Android 10+ 部分机型失效）；设置界面按功能域分组重构。
- **更新可控** — 新增「启动时自动检查更新」开关（默认开启），更新提示不再被动打扰。

### Added

- **主页聊天气泡透明度调节** — 设置页「外观」新增「聊天气泡透明度」滑杆（20%–100%），实时作用于主页对话气泡（用户 / 助手 / 系统），记忆住设置。默认 100% 不透明。
- **启动自动检查更新开关** — 设置页「更新」新增开关（默认开启）：打开 App 时静默检测新版本，发现更新才提示；关闭后仅可在关于页手动检查。手动「检查更新」不受开关影响。
- **主界面背景壁纸** — 设置页「外观」新增「背景壁纸」：从相册选一张图片作为主界面聊天背景（Live2D 模型之下），**实时生效**，无需重启；含缩略图预览与「恢复默认」。壁纸**仅主界面生效，悬浮窗保持透明**。
  - **图片导入** — 走系统相册选择器（PhotoPicker，免存储权限），选图后自动做 EXIF 方向校正、下采样（最长边 ≤ 2048）与 JPEG 归一化，避免大图 OOM。
  - **背景模糊滑杆** — 同一小节提供「背景模糊」滑杆（0~100%，0.05 步进），GL 内「下采样 + 两遍可分离高斯」实时生效（半分辨率 FBO 两遍高斯后放大回全屏，高档位不饱和），不重解码图片，拖动即见效果；缩略图预览随滑杆实时重算，观感与主界面一致。
  - **实时生效机制** — 壁纸在 GL 渲染链内绘制（清屏纯色兜底之上、Live2D 模型之下），设置变化经 Flow → volatile → GL 每帧读取，**下帧即生效**；旋转 / 重建后自动恢复，无壁纸时回退主题纯色。
- **TTS 语音模型 zip 手动导入** — 设置页「语音」新增「从本地导入」：选择本地 TTS 资源包 zip（平铺 4 个 ONNX + `libonnxruntime-<abi>.so` 原生库）离线导入，无需访问 GitHub 下载；兼容打包时误嵌套子文件夹（自动查找 ≤2 层）。导入时 **ONNX 全量导入，原生库按当前设备 ABI 选择性导入**（只落盘匹配的那份），缺件/缺库会提示缺失项。

### Changed

- **悬浮窗透明度修复（Android 10+ 部分机型失效）** — 原实现用 `GLSurfaceView.setAlpha` 调节透明度，而 `GLSurfaceView` 的画面绘制在独立 Surface 上，`View` 层 alpha 只作用于占位层，**不进入 GL Surface 合成**；部分机型（含大量 Android 10+ 国产 ROM）合成路径不乘该 alpha，导致调节无效。改为 **GL 绘制管线内乘 alpha**（`CubismRendererAndroid.opacity` × `CubismShaderAndroid` 的 `u_baseColor`），对所有机型统一生效，并保留 `View.setAlpha` 双保险。
- **设置界面重构** — 设置页按功能域重组为分组卡片：**对话**（API 配置 / 模型参数 / System Prompt / 记忆系统）、**外观**（气泡透明度 + 主题）、**语音**（TTS）、**更新**、**隐私与数据**；组内小节标题降为次级视觉层级，整体更清晰。

### Fixed

- **悬浮窗透明度调节在 Android 10+ 部分机型上无效** — 根因见上「Changed」；现已改为 GL 内乘 alpha，所有机型统一生效。

### Notes

- 相对 1.6.0-beta1 无破坏性变更，applicationId 不变，可覆盖安装。

---

## [1.6.0-beta1] - 2026-08-23

### Fixed

- **修复模型下载偶发崩溃** — `ReentrantLock` 线程绑定，网络挂起点切线程后 `unlock` 崩溃；改用协程友好的 `Mutex`。
- **修复 Live2D 切后台主线程卡顿** — GL 线程同步读 asset 阻塞 `onPause`；新增 `Live2dPal` 文件字节缓存，启动时 IO 线程预热整目录。
- **修复 TTS 偶发「一条消息没有语音」** — ① AudioTrack 并发写竞态：停止时先 pause/flush 解除 native `write` 阻塞再 join，`obtainTrack` 改每段新建不再复用；② G2P 防空输出：不可读内容降级为停顿音素，保证任何非空文本都有语音。
- **修复长对话消息列表排版错位** — `reloadHistory` 改为以历史最后一个 id 为分界合并，被滑动窗口裁掉的旧消息不再被误拼到末尾。
- **修复喇叭图标时机** — 「播放中」改为 AudioTrack 真正开始播放前一刻才点亮，播完即灭，不再合成阶段误亮。

### Changed

- **TTS 自然度优化** — 合成参数调优（`noiseScale` 0.55 / `noiseScaleW` 0.7），句末自动补韵律停顿，长句不再生硬。
- **语速滑杆重做** — 双缓冲拖动（本地跟手、松手落盘）、0.1 粒度步进、方向改为直觉的「语速倍率」（0.5x–2.0x）。
- **TTS 排查日志增强** — 全链路关键节点补详细日志，便于定位问题。

---

## [1.6.0-alpha*] - 2026-08-21

### Added

- **本地 VITS 语音合成（TTS）** — LLM 的文字回复可用梅尔音色朗读。端侧推理、完全离线、不走云端。设置页新增「语音」分区：
  - **语音模型按需下载** — 4 个 ONNX 模型 + 当前设备架构的 ONNX Runtime 原生库（共约 92MB）首次使用时从远程下载，不打进安装包；支持删除释放空间。
  - **断点续传** — 下载中断 / 重试时跳过已完成且校验通过的文件，只补下缺失项，不再全量重下。
  - **主界面 / 悬浮窗独立开关** — 两个发声场景各自控制；模型未就绪时开关置灰。
  - **语速调节** — 滑杆调整朗读语速（0.5x–2.0x）。
  - **语言选择** — 预留切换入口，当前仅开放中文。
- **语音快捷开关（喇叭按钮）** — 主界面语音开关开启时，顶部菜单按钮左侧出现喇叭图标：播放中变主题色、静音变灰、待播为白；点击切换「本次会话」的语音（内存标志，重启恢复），静音立即停止当前朗读。
- **触摸语音与 TTS 互斥** — TTS 朗读时自动停止未播完的触摸语音；触摸人物触发语音时若在朗读则先停 TTS，两者不再叠音。
- **日志一键导出** — 设置页「隐私与数据」新增「导出日志」：抓取应用日志写入 `.log`，拉起系统分享菜单，便于向开发者提交问题日志。内容含：
  - **版本 / 设备 / 系统 / ABI 头信息**；
  - **Native 崩溃记录（tombstone）** — 经 `getHistoricalProcessExitReasons` 读取本进程历史 native 崩溃（段错误等）的退出原因与完整堆栈（Android 11+；低版本自动跳过）；
  - **全量 logcat** — 抓取整个日志缓冲区（不限定进程，覆盖独立进程的服务与崩溃前上下文）。项目日志不打对话正文，可放心分享。

### Changed

- **中文 G2P 全量拼音表** — 内置约 4.2 万字拼音数据（kMandarin 规范读音，含声调），常用字全覆盖；多音字按词表定音。修复早期版本因字典过小导致「只会零星读几个字」的问题。
- **推理链路** — 新增 `tts` 包：VITS 四模块 ONNX 引擎、时长展开 / 先验展开 / SDP 采样等胶水逻辑（移植自参考实现）、符号表与中文 G2P、AudioTrack 流式播放器、模型下载管理器。整段一次合成播放，新回复自动打断旧朗读。
- **播放完成判断改为真实进度** — 喇叭「播放中」状态改由 AudioTrack 末尾帧位置标记（`setPlaybackPositionUpdateListener`）驱动，硬件播到最后一帧才算完成，替代原先基于 `playState` 的估算（缓冲写完后仍一直显示播放中的问题随之消除）。
- **菜单图标矢量重绘** — 主界面菜单各项的 emoji（⚙ 🗨 🧠 🪟 ℹ）全部替换为 SVG 矢量图标（设置 / 删除 / 灯泡 / 浮层 / 信息），不同分辨率下更清晰。
- **操作二次确认** — 「清除对话」改为弹窗确认（防误清会话）；「System Prompt 恢复默认」改为按钮内联确认（首次点击变红字「确认恢复？」，再点才执行，3 秒无操作自动还原）。
- **悬浮窗自动主题跟随修复** — 动态取色（Material You）改读系统动态色资源 `system_accent1_*`（与主界面 `dynamicColorScheme` 同源），替代原先在部分 ROM（小米 / 澎湃等）上不准的壁纸取色近似，自动模式下悬浮窗菜单 / 输入框颜色现与主界面一致。

### Removed

- **日语 / 英语语音支持** — 本期下架（含相关依赖与词典下载），集中精力保证中文链路稳定；后续按效果再议。
- **分句并行合成** — 简化为整段一次合成，降低实现复杂度。

### Notes

- 语音模型与运行库需联网下载一次（GitHub Releases，地址经 `local.properties` 注入）。
- 相对 1.5.0 无破坏性变更，applicationId 不变，可覆盖安装。

---

## [1.5.0] - 2026-08-19

### Added

- **悬浮窗锁定** — 菜单新增「锁定 / 解锁」开关，锁定后人物不可拖动 / 缩放，双击菜单、三击关闭不受影响；菜单图标统一为矢量线条风格。
- **悬浮窗透明度调节** — 菜单新增「透明度」项，弹出独立滑杆面板实时调节人物透明度（20%–100%），无操作自动隐藏。
- **聊天失败重试** — 聊天页错误 Snackbar 新增「重试」按钮，可重发上一条失败消息。
- **System Prompt 恢复默认** — 设置页新增「恢复默认」按钮，一键还原为内置人设提示词。

### Changed

- **悬浮窗气泡存活时长加长** — 由「2s + 25ms/字」改为「3s + 200ms/字」，长回复便于完整阅读。
- **默认 API 改为 DeepSeek** — 新装 / 重置用户默认端点 `https://api.deepseek.com/v1`、默认模型 `deepseek-v4-flash`；老用户配置不受影响。
- **友盟分发渠道可配置** — 渠道名从 `local.properties` 注入，默认 `GitHub`。
- **Release 构建开启 R8 优化** — 新增混淆规则保护 Live2D / 友盟 / 序列化类。
- **内部质量** — 包结构治理（消除循环依赖、`framework` → `app`）、渲染 / 手势 / 设置等模块拆分、统一异常捕获与主题判断，并补充 ViewModel 与气泡策略单元测试。

### Fixed

- **悬浮窗渲染状态与内存泄漏** — 渲染协调标记改为 `StateFlow` 并原子消费，修复复合判断竞态与泄漏问题。
- **气泡寿命扣减无上限** — 修复多气泡挤压时旧气泡寿命被无限扣减至 0 的问题，改为封顶扣减。
- **其余稳定性修复** — 消除 46 处 `!!` 断言、协程取消被吞、记忆失败日志泄露内容等。

### Notes

- 相对 1.4.0 无破坏性变更，applicationId 不变，可覆盖安装。

---

## [1.4.0] - 2026-08-17

### Added

- **悬浮窗交互重构** — 双击人物唤起独立菜单悬浮窗（关闭悬浮窗 / 唤起输入框），点空白处或 5s 无操作自动隐藏，带中心缩放 + 淡入淡出动画；快速三击人物直接关闭整个悬浮窗。
- **悬浮窗输入框** — 独立可拖动输入条（左侧抓手），默认出现在人物正下方；发送走与主界面同一 `chat` 包（共享会话历史），发送后保持打开便于连续交流。
- **悬浮窗气泡回复** — AI 回复以聊天软件式带小尾巴气泡显示在人物左右侧（离屏幕较远的一侧），自动换行、按回复长度决定停留时间，多条气泡向上挤压、旧气泡渐隐消失，无气泡后延迟销毁窗口。
- **悬浮窗主题跟随** — 悬浮窗配色读取用户设置（颜色预设 / 动态取色 / 明暗模式），不再固定默认紫。
- **回到前台热加载聊天** — 后台切前台固定刷新一次会话历史，悬浮窗期间的聊天回到主界面即时可见（非破坏合并，不覆盖内存状态）。
- **本地模型免 API Key** — API Key 留空时不发送 `Authorization` 头，可直接对接 Ollama / LM Studio 等本地 OpenAI 兼容端点；云端缺 Key 返回 401 时给出「请填写 API Key」友好提示。
- **独特性标识信息注入** — 开发者名、GitHub 仓库地址、交流 QQ 群、友盟隐私政策链接从 `local.properties` 读取，经 `BuildConfig` 注入（新增 `AppInfo` 统一访问入口），源码不再硬编码，开源分叉可直接替换。
- **`LinkItem` 共享组件** — 关于浮层与隐私政策复用的可点击超链接组件，抽出为公共组件。
- **隐私政策头部信息** — 新增版本、生效时间、修订时间字段。

### Changed

- **悬浮窗双击行为** — 双击关闭 → 双击开菜单；三击关闭悬浮窗。
- **`SettingsManager` 新增主题同步 getter** — `getThemeMode()` / `isDynamicColorEnabled()` / `getColorPreset()`，供悬浮窗读取用户主题。
- **取消数据采集授权后直接退出** — 设置页确认取消后立即结束进程，保证 SDK 在本次进程内不再上报，无需等待重启。
- **隐私政策内容修订** — 增加政策主体（开发者名）、披露统计 SDK 预初始化、补充「你的权利」章节；设备标识符披露细化（OAID、Android ID、设备型号、操作系统版本等）；删除未实际集成的崩溃信息采集承诺；「匿名」统一改为「去标识化」；友盟隐私权政策链接改为可点击跳转。
- **更新检测接口地址** — 由注入的仓库地址动态拼装，跟随 `app.gitRepoUrl` 变化。

### Fixed

- **悬浮窗聊天回主界面不刷新** — 原先需要杀掉重启才能看到悬浮窗期间聊的内容，现在回到前台即重载共享会话。

### Notes

- 本版本含悬浮窗交互重构与多项目前【待定】的收尾，按语义化版本升 **MINOR**（versionCode 8）。
- 相对 1.3.0 无破坏性变更，applicationId 不变，可覆盖安装。

---

## [1.3.0] - 2026-07-29

### Added

- **友盟+ 统计 SDK 集成** — 接入友盟 U-APP 统计 SDK（common 9.9.2 + asms 1.8.7.2），采集匿名使用数据（启动次数、使用时长等）。AppKey 通过 `local.properties` 配置，与代码隔离。
- **隐私协议弹窗** — 首次启动展示隐私授权弹窗，用户可选择同意或不同意。不同意时 App 所有功能正常使用，仅不初始化统计 SDK。弹窗内可查看完整隐私政策。弹窗使用 `Dialog + Card` 风格，与关于弹窗统一。
- **设置页隐私入口** — 设置页底部新增「隐私与数据」分区，可跳转查看完整隐私政策，并支持取消数据采集授权。
- **隐私政策内容统一** — 抽取 `PrivacyPolicyContent` 共享组件，弹窗全文和设置页共用同一份文本，修改一处即可同步。
- **语音分区子目录管理** — `assets/voice/` 下新增 `upper/`、`lower_left/`、`lower_right/` 三个子目录，按触摸分区存放语音。`VoicePlayer` 新增 `listVoices()` 自动扫描目录内 `.wav` 文件。新增语音只需丢进文件夹即生效，无需改代码。

### Changed

- `build.gradle.kts` 开启 `buildConfig = true`，通过 `BuildConfig.UMENG_APP_KEY` 注入 AppKey。
- `AndroidManifest.xml` 新增 `ACCESS_WIFI_STATE` 权限与友盟集成测试 intent-filter。
- 新增 `PrivacyConsentManager` 管理用户授权状态的持久化。
- 语音随机选择从 `java.util.Random` 改为 `SecureRandom`，降低连续点击重复感。
- 语音播放改为互斥模式：新触摸触发时先停止所有在播语音再播放。

### Fixed

- **触摸气泡生命周期** — 每条气泡独立 7 秒倒计时。新气泡触发后按位置自动扣减旧气泡剩余寿命：position 4-5 扣 2 秒，position 6+ 共扣 4 秒，实现旧气泡加速消失。
- **VoicePlayer 资源释放** — `MediaPlayer` 回调完成后正确 `release()`。

### Notes

- 本版本含友盟 SDK 接入（功能增量）、隐私 UI 重构与语音系统改进，按语义化版本升 **MINOR**。
- 旧版本升级到 1.3.0 无破坏性变更，applicationId 不变，可覆盖安装。

---

## [1.2.1] - 2026-07-27

### Added

- **模型知道当前时间** — 每轮请求注入设备本地时间、星期与时区（`TimeContext`），问「几点了 / 今天几号」不再瞎猜。时间每轮都变，不写入会话历史。

### Fixed

- **自动摘要几乎从不触发** — 轮次计数器原先在内存里，冷启动即归零，默认 10 轮间隔实际上走不满。改存 DataStore 跨进程延续，并改为「攒够即清零」。
- **摘要可能反而丢信息** — 摘要未返回 `keywords` 时生成的长期记忆永远匹配不上检索。现关键词为空则放弃本次摘要，短期记忆原样保留。

### Changed

- **默认系统提示词更新** — 梅尔人设改为更完整的角色协议。仅影响未自定义过 system prompt 的新装 / 重置用户；已保存的不会被覆盖。
- **记忆协议块回贴历史** — 助手消息剥离协议块后写入历史，模型会照着「过去都没输出块」继续漏。现保留块原文并贴回最近几条助手消息当正例。
- **记忆记录门槛与查找词** — 优先记可复用信息，禁止把助手提议写成用户事实；`keywords` 改为查找词（实体 + 话题/类别），摘要侧同步。
- **请求分层与历史批量裁剪** — 稳定内容放首条 system，每轮易变内容压到历史之后，便于 prefix cache；超限一次裁 8 条，历史窗口 40→35。
- **【用户人设】注入封顶** — 事实 / 特质按重要性最多注入 30 条（只影响注入，不影响存储与「查看记忆」）。
- **摘要最小条数门槛** — 短期记忆不足 3 条时跳过本次摘要。
- **版本号升至 `1.2.1`**（versionCode 6）。

### Notes

- 相对 1.2.0 无破坏性 API / 存储格式变更，主要是记忆链路修复与行为打磨 + 默认人设更新，按语义化版本升 **PATCH**。
- `ChatMessage` 新增 `memoryOpsBlock`。旧会话文件没有该字段时为 null，升级前的历史不参与协议块回贴，其余不受影响。
- 请求现在可能包含两条 system 消息（首条与尾部）。OpenAI 兼容端点普遍支持；若中转要求 system 唯一或必须在首位，需自行把尾部块改为 `user`。

---

## [1.2.0] - 2026-07-26

### Added

- **聊天记录持久化** — 新增 `ConversationStore`，会话历史随消息变更异步落盘（合并写 + 原子替换 + 损坏文件 `.corrupt` 备份），启动时恢复到界面。此前是纯内存的，强杀进程重开必然清空。
- **「查看记忆」界面** — 首页三点菜单新增入口，展示记忆统计与条目列表（内容、类型、重要性、关键词），支持单条删除与「清除全部」（二次确认）。
- **摘要轮次可调** — 设置页「记忆系统」新增滑杆，可设定每隔多少轮触发一次摘要（3~30，默认 10）；关闭「自动摘要」时置灰。此前硬编码 10 轮。

### Changed

- **记忆创建改由大模型自主决定** — 移除程序侧启发式提取（按字数阈值 + 关键词表打分，中文日常聊天几乎触发不到）。新增 `MemoryOpsProtocol`：模型在回复末尾附加 ` ```memory-ops ` 代码块，声明本轮 `create` / `update` / `delete` 哪些记忆及其类型、重要性、关键词；该块解析后从回复中剥离，用户不可见。全程容错，块缺失 / JSON 畸形 / 围栏未闭合一律静默跳过，不影响聊天回复。未采用 function calling——不少 OpenAI 兼容中转对其支持不稳定，且要多一次往返。
- **记忆检索改为匹配模型给出的关键词** — 移除程序侧切词 / CJK 二元组匹配。原实现按空白与标点切词，中文整句被当成单个关键词，`contains` 几乎永不命中，磁盘上的记忆从来没被注入过上下文。
- **事实与特质永不自动淘汰** — `FACTUAL` / `CORE_TRAIT` 排除出容量淘汰池（`maxItems` 仅约束短期 + 长期），并每轮全量注入 system prompt（带 id 供模型引用）；短期 / 长期仍按关键词匹配注入。手动删除不受影响。
- **摘要改为消费短期记忆** — 不再发送原始对话文本，改为把已攒下的短期记忆压缩成一条长期记忆并删除参与摘要的短期条目；失败时原样保留，下次再试。
- **`MemoryItem.tags` → `keywords`** — 标签字段替换为检索关键词，按标签分组的相似度去重（`consolidate`）一并移除——模型每轮能看到全部事实与特质，重复时会自己走 `update`。
- **记忆 id 改为短 id** — 完整 UUID 改为 `mem_` + 8 位随机字符，省 token 且模型抄写更不易出错。
- **记忆链路日志** — 各决策点补充 Logcat（是否注入协议、是否解析到块、失败原因、落库结果），`adb logcat -s MemoryOpsProtocol MemoryManager MemoryService` 即可排查；仅记长度与计数，对话内容不入日志。
- **死代码清理** — 移除 `ChatService` / `ConversationManager` 中已无调用方的 `getRecentExchanges()`、`getContextText()`，以及 `MemoryService` 的 `extractFromExchange` / `calculateImportance` / `extractTags` / `consolidate` / `isSimilar`。
- **版本号升至 `1.2.0`**（versionCode 5）。

### Fixed

- **启动加载与首次写入竞态导致记忆被整体覆盖** — 异步加载未完成前若先发生一次保存，会把只含新条目的内存列表写回文件，旧记忆全丢。改为惰性加载兜底，读盘必定先于首次写盘。这是「记忆大退就没了」中真正丢数据的一环。
- **ViewModel 早于异步加载完成导致界面空白** — `AppContainer` 暴露 `warmUpJob`，`ChatViewModel` 等待完成后刷新消息列表，并按 id 去重保留加载期间的新消息。
- **`ConversationManager` 线程安全** — 启动恢复在 IO 线程执行，与发送链路并发访问消息列表，所有读写方法改为加锁串行化。
- **`MemoryRepository` 可测试性** — 构造参数由 `Context` 改为 `filesDir: File`，可在纯 JVM 单测中验证持久化、淘汰与检索。

### Notes

- **老数据会有降级**：已存在的旧记忆没有 `keywords`（旧 `tags` 静默丢弃），因此旧的短期 / 长期记忆无法被检索到；事实类不受影响，仍全量注入。建议升级后在「查看记忆」中清空重来。
- 本版本含用户可见新功能与记忆系统行为重构，聊天与 API 配置流程向后兼容，按语义化版本升 **MINOR**。
- 新增测试依赖 `mockito-kotlin`（仅 `testImplementation`，不进入 APK）。

---

## [1.1.0] - 2026-07-25

### Added

- **检测新版本** — 启动时静默请求 GitHub `releases/latest`，有正式新版本时在聊天页底部 Snackbar 轻提示（可点「查看」打开发布页）；关于卡片「检查更新」绑定同一逻辑，手动检测会反馈有更新 / 已最新 / 失败。网络异常启动路径静默失败，不打扰。Snackbar 动作色跟随主题 `primary`。
- **获取模型列表** — 设置页模型输入框下增加「获取模型列表」：用当前 API Key / 地址请求 `/v1/models`，解析 id 列表后点选写回；兼容 `data[]` 与顶层数组两种响应格式，并补充解析单测。
- **关于页可点击外链** — 关于卡片内新增主题色（`primary`）下划线链接：Live2D 模型来源、GitHub 仓库、交流 QQ 群。
- **Live2D 模型来源** — 关于页补充模型来源入口（Bilibili）。

### Fixed

- **记忆链路** — 启动时加载持久化记忆；手写 JSON 改为 `kotlinx.serialization` + 原子写；记忆总开关真正关闭提取 / 摘要 / 注入；访问统计落盘。
- **聊天链路** — 清空会话时取消在途请求，避免回复回写；记忆后处理异步化，摘要不再卡住发送。
- **API 客户端** — `reloadClient` 可热重建；`HttpTimeout` 适配 LLM 长回复；`baseUrl` 规范化（用户可带或不带 `/v1`，客户端统一补齐后拼 `models` / `chat/completions`）；`max_tokens` 入请求；取消与空响应处理。
- **悬浮窗 / GL** — `START_NOT_STICKY`、失败自停、native 模型释放、捏合后拖动锚点与屏幕边界钳制；Activity 与 Service 的 GL 线程串行化，避免共享 shader 单例竞态；单例改用 application context，避免持有已销毁 Activity。
- **设置保存** — 输入框失焦保存、Slider 结束写盘；`SettingsManager` 增加内存快照缓存；DataStore 备份排除敏感项。
- **「变态」语音** — 触摸语音文件原先只有 “hen”，已更换为正确的 “hentai” 资源。

### Changed

- **关于卡片** — 从设置页挪到聊天页三点菜单入口；改为带动画的悬浮对话框，展示应用介绍、版本号与技术栈，系统返回键可关闭；设置页原关于模块移除。
- **包名迁移** — `com.llz121517.meapet` → `com.meapet.mobile`（namespace / applicationId 同步；注意：applicationId 变更后与 1.0.x 安装包不连续升级，需重新安装）。
- **targetSdk 36** — 补充 `POST_NOTIFICATIONS`、前台服务 `specialUse` 声明。
- **死代码清理** — 移除 `Live2dActivity`、`TouchManager` 等未使用组件。
- **版本号升至 `1.1.0`**（versionCode 4）。

### Notes

- 本版本相对 1.0.2 含用户可见新功能（更新检测 / 模型列表 / 关于外链）与多项修复，按语义化版本升 **MINOR**；未升 MAJOR，因 API 配置与聊天流程仍向后兼容。包名变更对旧安装是例外，见上。

---

## [1.0.2] - 2026-07-23

### Fixed

- **悬浮窗未加载完返回应用崩溃** — `SurfaceView` 的延迟绘制回调（`performDrawFinished` → `requestTransparentRegion`）在 View 已被 `removeView` 摘除后触发，`getParent()` 为 null 导致 NPE。将 `removeView` 通过 `Handler.post` 延迟到当前消息队列末尾执行，确保所有 pending 回调先完成。
- **主题模式切换框选择菜单不稳定** — `ExposedDropdownMenuBox` 内 `menuAnchor` 的触摸处理与 `OutlinedTextField` 内部手势产生冲突，偶发点击不展开。改为独立透明点击覆盖层 + 手动 `Popup`，彻底解决。

### Changed

- **颜色预设系统重构** — 从每套预设手工编写完整 `ColorScheme`（24 个对象），改为单 seed 主色 + 工具函数（`lighten`/`darken`/`desaturate`/`hueShift`）自动生成全套浅/深色方案。新增 `seed` 字段，预览色块直接使用主色。
- **首页菜单重做** — 从 `DropdownMenu` 改为 `Popup + Surface + Animatable`，宽度缩至 130dp，菜单项间添加分隔线，弹出位置固定在三点按钮正下方，增加淡入 + 右上角缩放入场/退场动画。
- **主题模式选择器动画** — 弹出菜单增加淡入 + 缩放动画，宽度与输入框精确对齐。
- **浅色模式滑动条底色** — 未选中区域底色从白色 60% 透明度改为 35% 透明度（`Color.White.copy(alpha = 0.35f)`），呈现更浅白的半透明效果。
- **动态颜色开关** — Android 12 以下设备开关置灰不可操作，提示文字更新为"当前系统不支持动态颜色"。
- **Switch 组件** — 统一设置页 Switch 颜色，与主题背景色协调。
- **版本号升至 `1.0.2`**（versionCode 3）。

### Added

- **关于部分** — 在设置页面的关于部分添加了累计Token消耗量显示

---

## [1.0.1] - 2026-07-22

### Fixed

- **LifecycleManager 递归栈溢出导致切后台崩溃** — 构造参数 `onTrimMemory` 与 override 方法同名，导致无限递归调用自身而非 lambda。重命名为 `trimMemoryCallback`。
- **悬浮窗关闭时 GL 上下文跨域崩溃** — 主 Activity 试图释放悬浮窗 GL 上下文的 shader 程序，跨上下文 GL 操作导致原生崩溃。跳过 `releaseInvalidShaderProgram()`，直接 `deleteInstance()` 重建。同时修复 service `onDestroy()` 未先暂停 GL 线程就直接 `removeView` 的竞态问题。

### Changed

- API Key 输入框键盘类型从 `Password` 改为 `Uri`，允许使用剪贴板粘贴。
- 版本号升至 `1.0.1`（versionCode 2）。
- 关于页版本号改为从 `PackageManager` 动态读取 `versionName`，不再硬编码。

### Added

- API 配置区提示文字："需要一个 OpenAI 兼容的 API 端点"。
- 设置页关于介绍更新。

---

## [1.0.0] - 2026-07-21

### Added

- **Live2D 模型渲染** — 基于 Live2D Cubism SDK 的主页模型展示与悬浮窗模式。
- **AI 聊天** — OpenAI 兼容 API 客户端，支持对话管理、System Prompt 与记忆上下文注入。
- **记忆系统** — 短期/长期记忆提取、AI 摘要、相关性检索与文件持久化。
- **多主题配色** — Material You 动态取色 + 12 套预设色板，支持浅色/深色模式。
- **触摸分区反馈** — 模型区域分三区，点击触发随机语音播放与气泡文字。
- **视角跟随** — 触摸时模型头部与视线跟随手指方向。
- **悬浮窗** — 前台 Service 浮窗模式，支持拖拽、缩放、双击关闭。
- **设置页** — API 配置、模型参数、System Prompt、记忆开关、主题选择。
- **全屏沉浸** — 隐藏系统状态栏/导航栏，GLSurfaceView + ComposeView 混合渲染。

### Fixed

- 修复 AndroidManifest 缺失 `INTERNET` 权限导致的网络请求 `EPERM` 崩溃。
