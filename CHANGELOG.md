# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

该项目的所有重大更改都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，
本项目遵循 [语义化版本控制](https://semver.org/spec/v2.0.0.html)。

---

## [Unreleased]

<!-- 暂无可记录变更 -->

## [1.7.2] - 2026-09-11

### Fixed

- **悬浮窗输入框长期霸占焦点：其他应用拉不起键盘、返回键被吞** — 该窗口是全项目唯一不带 `FLAG_NOT_FOCUSABLE` 的，长期占据 display 的焦点窗口（`FLAG_NOT_TOUCH_MODAL` 只放行窗外触摸，**不影响焦点归属**），下层应用的 `showSoftInput` 因「请求者不是当前焦点窗口」被静默拒绝，返回键也派发给无人处理的悬浮窗而丢失。现改为默认不带焦点，仅在用户要输入时动态取得、注意力移开立即归还。
- **发送后回复到达时抢焦点** — `setSending(false)` 原先只要窗口可见就再次请求焦点并弹键盘，用户发完消息切走后，回复到达会把键盘弹到别的应用上面。现改为发送即归还焦点，回复到达不再自动弹键盘（要继续输入点一下输入框）。
- **清空对话不再误关更新提示与记忆弹窗** — `clearConversation` 原以整体重建状态实现，会误关正在展示的弹窗；改为只重置对话相关字段。
- **会话持久化不再静默丢数据** — 落盘 collector 由外部作用域驱动，作用域取消后提交会无人消费而丢失；现保留任务引用、写入前检测存活，已取消时记日志告警。
- **消息裁剪不再打乱顺序** — 滑动窗口超限原先整体重排、把 system 消息挪到最前；改为从头部就地裁剪，保留原始交错顺序。
- **字体缩放后公式字号错乱** — Markdown 渲染缓存键漏了参与构造的字号参数，缩放后命中旧实例；补全缓存键消除。
- **吞掉的取消异常** — 模型 id 解析用裸 `catch(Exception)` 吞掉取消异常，与项目约定不符；改为取消时重抛，普通异常记日志。
- **系统气泡「挤旧」反而延长寿命** — 原按剩余时长扣减并重新倒计时，等于重置已流逝的时间；改为按绝对到期时刻运算，扣减只会让到期提前。
- **API 网关错误详情不可见** — 接口报错只显示状态码文案、日志无原始响应体，难以定位；现解析网关 `error.message` 拼入提示，响应体写入日志。
- **主界面偶发永久黑屏** — 返回主界面等悬浮窗停止的轮询无超时上限，异常路径永不恢复；改为订阅状态流并加 2 秒超时，超时照常恢复渲染。
- **日志导出无超时** — `logcat` 子进程挂起时导出无限阻塞；加超时与强制销毁兜底。
- **TTS 合成护栏失效** — 原帧数上限在 200 字截断下永远触发不到；改为按注意力矩阵内存封顶，正常语音不受影响。
- **发送失败残留「幽灵消息」、重试重复/错发/崩溃** — 失败消息原先从不回滚，会污染上下文；且 UI 与历史各建一条 id，会话恢复时同一句话并列成两条，重试还可能重发旧消息、撞重复 key 崩溃。现失败即回滚、发送与入史共用同一 id、合并按时间戳取新；重试目标由界面按 id 指定、分界丢失不再误删回复，列表消费点加重复 id 兜底。
- **宽表格被挤到逐字换行** — 气泡宽度固定 280dp、表格按列均分且无横滚；现放宽气泡宽度、按表格块分段，超宽表格单独横向滚动。

### Documentation

- **修正消息管理类的自相矛盾注释** — 类注释自称「不关心消息角色」，实现却处处按角色裁剪/重组/查询；改为如实说明。

### Performance

- **Markdown 解析结果缓存** — 仅颜色/透明度变化时不再重复解析 Markdown。
- **重试过滤降为线性** — 定位分界后单趟过滤，替代逐条 `indexOf`。

### Changed

- **助手 Markdown 表格观感增强** — 表格与正文不再难分：表头行加浅底色、数据行极淡斑马，单元格留 3dp 内边距（文字不再贴格线）。宽表格改在气泡内左右固定留白的可视窗内横向滚动，首/末列与气泡内缘始终保留可见空隙，不再像被气泡边缘截断；表下方新增主题色胶囊缩略条，长度与位置跟随横向进度，并在表格拖过头（overscroll）时随形变同步拉长、松手弹回。

### Notes

- 本版为质量加固版，无新功能。修复来自 48 项代码审查的复核跟进与多条真机反馈（宽表格排版、重试重复）。
- 随修复补充失败回滚、宽表格切段、气泡寿命等回归单测，全部通过。
- 悬浮窗输入条的「能弹键盘」（须可聚焦）与「不挡着别人」（不能占焦点）在窗口层面互斥，故把可聚焦改为按用户意图切换：flag 组合抽成纯逻辑 `OverlayInputFocus` 并加单测，窗口类只负责落到 `updateViewLayout`；取得/归还均有 D 级日志，真机复现时可看出是哪条归还信号没到。
- 版本号升至 1.7.2（versionCode 13）；无破坏性变更，可覆盖安装。

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

## 更早版本历史

[1.6.0] 及更早的发布记录已归档到 [`docs/archive/changelog/CHANGELOG-1.6-and-earlier.md`](docs/archive/changelog/CHANGELOG-1.6-and-earlier.md)。
