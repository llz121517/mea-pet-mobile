# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

该项目的所有重大更改都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，
本项目遵循 [语义化版本控制](https://semver.org/spec/v2.0.0.html)。

---

## [1.9.0] - 未发布（dev 分支）

### Added

- **记忆工具** — 在支持工具调用的模式下，模型可按需检索、创建、更新或删除记忆；写入按对话轮次暂存处理，删除支持撤销。
- **联网搜索** — 可在设置中启用 `web_search` 与 `web_fetch`，默认关闭；支持自动选择服务商或手动选择，并可配置 DeepSeek / AnySearch 独立 API Key。Auto 在请求失败或超时时会尝试下一家服务商。
- **DeepSeek Key 复用开关** — 新增「复用「API 配置」中的 Key」开关（默认开启）。开启时沿用既有行为：独立 Key 优先，留空且聊天地址是官方 DeepSeek HTTPS 地址时复用聊天 Key。关闭后只用独立 Key、不再回退聊天 Key，此时设置页显示单独的 DeepSeek Key 输入框（自动与 DeepSeek 两种服务商选择都生效）。此前是否复用只取决于地址，用户无法干预，存在把中转/聚合服务的 Key 发给 DeepSeek 而不自知的风险。
- **联网工具的前提与转发语义** — 联网工具需要聊天端点支持 function calling；Auto 模式在失败或超时改试下一家时，同一查询词或网址也会发送给下一家服务商（隐私与计费影响见设置页说明）。DeepSeek 独立 Key 留空时，官方聊天端点会复用聊天 Key；AnySearch 留空为匿名模式。
- **连续回复与中断控制** — 工具调用期间分段显示助手回复并逐条朗读。首条回复出现后输入栏恢复可用并显示「Mea 还在…」状态条：可点「停止」中止当前轮次（已显示的片段保留），或直接发送新消息——会取消当前轮次并立即开始新一轮；等待首条回复期间输入栏禁用并显示「Mea 正在思考...」。
- **上下文管理** — 会话和记忆改由 SQLite 保存；新增历史分页、上下文压缩，以及上下文窗口探测和手动设置。
- **按天时间分隔条** — 消息列表在相邻可见消息跨自然日时插入「今天」「昨天」或「M月D日」（跨年带年份）；向上翻页时在页交界处重新判断，不重复不遗漏。
- **手动压缩与提醒** — 上下文估算超过提醒阈值时弹一次「整理一下」提示，顶部菜单随后出现「压缩上下文」；压缩中显示「Mea 还在整理前面的内容…」，完成后在已折叠内容之后显示「早些时候」分隔条。自动压缩只在逼近有效预算 90% 时兜底，失败退化为仅在发送窗口内按轮丢弃（库里不删）。
- **请求总超时** — 一轮（含多轮工具调用）累计超过 180 秒按失败处理并回滚该轮，提示「请求超时（180 秒），请检查网络后重试」，避免移动网络下无限转圈。
- **工具轮次上限** — 单轮最多 12 轮工具调用；达到上限后不再执行工具，只回填「已达上限」提示让模型用文字收尾，轮次计数随工具结果下发但不写入历史。
- **记忆检索方式设置** — 设置页新增「记忆检索方式」（自动 / 工具 / 只读 / 关闭），默认「自动」；原「文本」模式在界面标注为「只读」。旧的文本记忆写入协议已移除，所有非工具路径都不会写入或修改记忆。
- **版本号与构建时间** — 自动构建（`tools/build-release.sh` / `.ps1`）把当前提交的 9 位短哈希经 `-PversionSuffix` 拼到 `versionName` 末尾（形如 `1.9.0-1a2b3c4d5`），便于从安装包反查提交；「关于」页在版本号下方按设备当前时区显示构建时间（构建时由 Gradle 注入时间戳）。手动构建不带短哈希。
- **上下文窗口「重新检测」** — 设置页可一键清除手动值并强制重新探测上下文容量；探测失败仍回退兜底值，不报错。
- **32 位 ARM 设备降级** — Live2D Cubism Core 只有 64 位 ARM / x86 原生库，没有 `armeabi-v7a`，此前 v7a 包启动即崩溃。现在启动前探测原生库，缺失时自动降级为「纯聊天 + 背景色 / 壁纸」：不加载桌宠模型、不提供触摸语音，主界面菜单也隐藏「悬浮窗」入口。保留 v7a 是为兼容 Android 8 时代的 32 位存量设备，后续考虑改为仅 64 位，见 [ADR 0003](docs/adr/0003-live2d-32bit-fallback.md)。

### Changed

- **联网工具独立启用** — 联网搜索不再依赖记忆总开关或记忆检索模式；关闭记忆、关闭模式和只读文本模式都可使用联网工具。只读模式继续注入关键词回忆；仅联网的轮次不提交记忆变更或推进记忆摘要。
- **记忆检索模式** — 自动和工具模式使用记忆工具；端点不支持工具调用时退回只读关键词注入，不使用文本写入路径。文本模式只按关键词注入、不改变记忆内容；关闭模式不注入记忆。人设中的稳定记忆引用仍保留。
- **记忆摘要** — 自动摘要仅在工具模式的成功对话轮次后独立运行；文本、关闭及只读回退不触发自动摘要。
- **设置帮助与状态概览** — 联网或记忆关闭时折叠相关选项；新增参数帮助，并在提供商概览中显示模型与联网状态。
- **消息列表视口铺满** — 列表不再在输入栏上方被裁掉，气泡可滚到悬浮输入栏后面（越界回弹沿用系统默认的拉伸效果）；底部留白按底栏实测高度自动变化，状态条出现时同步上移列表而不强制回到底部，最后一条不会被状态条遮住。输入胶囊与「Mea 还在…」状态条的不透明度都跟随「聊天气泡透明度」设置；状态条额外保留 0.9 的底数，设置 1.0 时约 90% 不透明。
- **背景效果文案** — 壁纸调节项改称「背景虚化强度」，准确说明整张背景的模糊效果。
- **默认模型** — 新装默认模型由 `deepseek-v4-flash` 改为 `deepseek-flash`，与 DeepSeek `/models` 返回的 id 对齐；老用户已保存的模型名与端点配置不受影响。
- **首屏定位与开屏动画** — 首屏改用一次性无动画定位（`initialLoadDone`），避免先按 35 条窗口滚到底、首屏 50 条在头部插入后停在半途；定位完成前列表透明，消除开屏闪空。
- **隐私政策修订至 1.3（2026-11-22）** — 补充联网搜索的数据流向（查询词 / 网页地址发往 DeepSeek / AnySearch，Auto 失败时可能切换下一家；DeepSeek Key 复用条件与 AnySearch 匿名模式；不记录查询、正文或 Key）与 AI 对话数据流向（对话、记忆工具结果、系统提示词、记忆摘要、压缩摘要、容量探测均发往用户配置的 API）；明确应用不运营服务器、数据仅存本机。含统计 SDK 的构建中，老用户会因版本号变化重新确认一次隐私政策。
- **历史与模型上下文分离** — 聊天记录保留并分页加载，发送给模型的上下文可按容量压缩，不再因上下文裁剪而从界面历史中消失。
- **升级与数据迁移** — 首次启动把旧版 JSON 会话与记忆一次性导入 SQLite（单事务、幂等、读回校验；旧 JSON 文件原样保留不删除，失败下次启动重试）；开发期旧库中的废弃记忆操作列忽略不迁移；SQLite 库版本高于应用时拒绝打开（不支持降级）。建议升级前按需备份。
- **关于页** — 移除「交流 QQ 群」入口，应用简介副标题调整，技术栈行不再显示构建工具版本号；版本号下方新增构建时间。

### Fixed

- **工具能力缓存与模型切换** — API 地址、Key 或模型变化后重新尝试工具；仅明确的工具能力拒绝触发只读降级，普通 HTTP / schema 错误不再禁用工具。工具循环中途遇到 HTTP 错误按整轮失败处理，丢弃暂存记忆变更。
- **记忆总开关** — 关闭记忆后不再提供记忆工具；执行器在调用与提交时再次读取开关，阻止在途轮次继续检索、写入或提交暂存变更。
- **工具模式下的人设记忆引用** — 工具调用模式现在同样提供稳定的事实 / 特质记忆上下文。
- **上下文预算与摘要** — 按上下文容量限制回复 Token 上限，并缩短摘要中的工具结果，避免冗长机器内容挤占摘要空间。
- **清空长对话时的偶发崩溃** — 清除或撤销恢复时重建消息列表状态，避免长列表瞬间变化造成闪退。
- **记忆上下文访问记录** — 只读关键词注入不再更新记忆访问记录，也不把注入内容写回会话历史。
- **首条回复等待与状态条时机** — 恢复等待首条回复时的「Mea 正在思考...」占位；「Mea 还在…」状态条改为首条回复出现后才显示并渐入，避免未产出任何内容就先展示进度。

### Notes

- 版本号升至 1.9.0（versionCode 15）；自动构建的 `versionName` 追加提交短哈希后缀（见 Added「版本号与构建时间」），「检测更新」比较时会先剥离该后缀，不会误判需要更新。

---

## [1.8.0] - 2026-11-15

### Changed

- **语音合成迁移到 sherpa-onnx** — 自写的 VITS 推理与 G2P 链路整体换为 sherpa-onnx，中日英混排由同一个模型完成，不再按语言切引擎；数字 / 日期 / 电话号码按规则归一化朗读。
- **英文句中的数字按英文数词朗读** — App 归一化层按上下文预转换：紧邻拉丁词的整数转英文基数词（`3`→three、`12`→twelve、`1000`→one thousand …），不再落入 sherpa 中文数字前端 `ruleFsts`（会读成 sān、shí èr）；中文与日文上下文保持原规则，`3.5`、`10:30`、电话号码等复合或超长数字不转换。日文句中的数字读法（`15 時`→じゅうごじ）仍是已知边界，排入 1.9.0。
- **语音模型包换新** — 新模型 6 个文件、约 84MB，与旧版 4 文件模型不兼容：升级后启动时会自动清理旧包并引导重新下载（网络受限可在设置页「从本地导入」）。
- **移除 onnxruntime-android 依赖** — 原生库改由 sherpa AAR 提供，不再有重复 so，仅保留 arm64-v8a / armeabi-v7a 两个 ABI。
- **Release 包剔除未使用的 sherpa C/C++ API 原生库** — sherpa AAR 带 `libsherpa-onnx-c-api.so` / `libsherpa-onnx-cxx-api.so`，但 App 使用的 Kotlin API 只加载 `libsherpa-onnx-jni.so`（jni 已静态链接 C/C++ API，且只有 cxx-api 依赖 c-api），两者在 App 内无任何加载路径；release 打包剔除后 v8a 单 ABI APK **33.7MB**、双 ABI **51.8MB**（1.7.2 为 61.6MB：其中剔除未使用 so 减约 7.3MB、触摸语音转 Opus 再减约 2.5MB）。
- **触摸语音改为 Opus** — 21 个触摸语音从 32kHz 单声道 WAV（2.7MB）转成 48kHz Opus 48kbps（共 225KB，减小 92%），听感无明显损失；assets 仍不压缩存储（`androidResources.noCompress` 显式声明），播放路径不变（`openFd` → MediaPlayer），重新编码用 `tools/encode-voice-assets.ps1`。
- **播放采样率跟随引擎输出** — 不再硬编码 22050Hz，换模型或换采样率不会变速、变调。
- **删除自写 G2P 与拼音资产** — 相关引擎与约 8.9 万行词表全部移除，APK 减小约 1.65MB。
- **破坏性操作统一为「立即执行 + 可撤销」** — 清除对话 / 清除全部记忆 / 恢复默认 System Prompt / 恢复默认壁纸四处原为弹窗确认、两段式点击或无提示直接删除，现统一改为点击即执行 + 底部倒计时撤销条（默认 5 秒，窗口内按快照回滚）；转屏切页不丢撤销机会，被新写入覆盖时窗口立即作废，壁纸到窗口到期才真正删文件。
- **软键盘改用应用自己的 inset 避让** — 窗口不再随键盘 resize / pan（此前主页视口会整体上移），改为按 IME inset 让页面自行收窄：输入栏、消息列表与底部提示条（含撤销条）始终在键盘上方；提示条另补了左右留边与远离屏幕底边、手势区的余量。
- **语音合成改为逐句流式播放** — 首句合成完即出声，后续句子边合成边写 AudioTrack，不再等整段合成完才播（桌面 4 句样本首块约 1.1s，整段合成需 3.8s）；打断 / 新消息到来时回调直接中止原生推理的剩余句子，不再白算完。
- **流式回调改用 Java 适配器 `TtsStreamCallback`** — sherpa JNI 按 `invoke([F)Ljava/lang/Integer;` 查找回调方法，Kotlin 2.x 的 indy lambda 脱糖后只有擦除版 `invoke(Object)`，直接传 lambda 会在真机 CheckJNI SIGABRT（`NoSuchMethodError`）。Java 适配器同时具备专用签名与擦除桥接，`TtsStreamCallbackTest` 反射守护该签名。
- **短片段（≤2 秒）播放前补齐 AudioTrack 缓冲** — 总帧数不足以填满缓冲的片段，AudioFlinger 不消费数据（`playState=PLAYING`、`underrun=0`，但播放头恒 0、marker 永不到）。写线程改为先写入首批数据再 `play()`，并在结束标记前用静音补齐到缓冲容量（marker 仍打在真实音频末尾，补齐静音不可闻），0.6~1.5 秒的短句与试听正常出声。
- **设置同步读取不再阻塞主线程** — `SettingsManager` 的同步 getter 在内存快照尚未预热时，原先会 `runBlocking` 读盘兜底；改为返回空快照、各项落到默认值，首帧真实值由对应 Flow 就绪后自动修正，全程零主线程磁盘阻塞。判定型读取（是否首次启动）不靠默认值猜，改由 `awaitLoaded()` 等预热完成再读。
- **记忆库写放大收敛** — 模型一轮声明的多条记忆操作原先逐条落盘（每条全量重写整库）；现收集后经 `applyChanges` 一次落盘。
- **记忆检索读写分离** — `getRelevant` 改为纯查询（不再每次命中就整库落盘），访问计数由调用方在注入后经 `markAccessed` 单独记账，一次落盘。
- **会话落盘 writer 只写「最近记录的快照」** — 合并写队列原先带着入队那一刻的快照去写：从取到旧快照到真正拿到锁之间若有更新的同步写入，旧快照会把磁盘倒退回去。现在写入时在锁内取最新记录的快照，队列与补写之间不再互相覆盖。
- **进后台补写移出主线程** — `onStop` 的 `flush()` 原先用 `runBlocking` 在主线程等写盘；改为在应用级 IO 作用域执行，主线程不再阻塞，确定性补写保留。
- **窗口背景跟随主题** — Compose 侧主题解析后同步窗口背景色（此前只在 `onCreate` 设一次），切换主题或 Activity 重建、GL 画面短暂不可见时不再露出旧主题底色。
- **LaTeX 字体表解析移出首帧** — `MarkdownText` 原先在首次组合时同步触发 jlatexmath 的 `TeXFormula` 类初始化（解析十来份字体/符号 XML，真机 debug 包实测 1s+），直接把首帧卡住；改为组合期只做毫秒级的 assets 加载器挂载，重量级预热由 `MainActivity` 投递到后台线程（`warmUpLatex`）。真机冷启动首帧 4.1s → 2.2s。
- **更新检查的 JSON 解析移出主线程** — `UpdateChecker.check()` 里的 `parseRelease` 改到 `Dispatchers.Default`，启动时静默检测不再占用主线程。
- **助手气泡去掉左侧圆形 M 头像** — 该头像只是装饰，去掉后助手消息与用户消息左右对称，气泡左缘统一对齐 12dp 边距。

### Fixed

- **进后台可能丢失最新会话历史** — 会话落盘是 conflate 合并写队列，进程被系统回收时排队快照可能来不及写盘；`onStop` 时补写一次（IO 线程，不阻塞主线程），确保最新对话不丢。
- **老用户被误判为首次启动** — 快照预热完成前同步 getter 落到默认值，而 `isFirstLaunch()` 默认 `true`：老用户会被当首启，写入 `first_launch=false` 并重弹隐私 / 更新窗，且状态已写坏无法自愈。新增 `SettingsManager.awaitLoaded()`，启动期的判定型读取先等预热完成（不回退到主线程阻塞读盘）。
- **任务根外启动崩溃** — `MainActivity.onCreate` 的 `isTaskRoot` 防护在退出路径上未调用 `super.onCreate()`，系统抛 `SuperNotCalledException` 直接崩溃；改为先走 `super.onCreate()` 再判断退出。
- **悬浮窗冷启动可能用默认主题配色** — 悬浮窗配色读设置内存快照，进程为悬浮窗冷启动时快照可能尚未预热，而这些窗口长期存活、不会自愈；建窗前先等预热完成。
- **Live2D 纹理预热失效** — `Live2dPal` 用「目录名是否以 `/` 结尾」判断目录，MIUI 等 ROM 的 `assets.list()` 不给尾斜杠，于是把纹理目录当文件 `open()` 抛 `FileNotFoundException`：子目录里的贴图/动作全部没预热，GL 线程只能现场读 assets（真机实测模型加载多花约 2s）。改为「能否列出子项」判定目录，并抽出 `collectAssetFiles` 加单测覆盖两种 ROM 行为。
- **悬浮窗透明度改为整幅合成** — 原先在 Cubism 每个 drawable 上逐层乘 alpha，同一像素有多层半透明叠加的部位（嘴部内腔、腮红、阴影等）alpha 会累积（两层各 a 叠出 `1-(1-a)²`），调低透明度时这些地方比其它部位更实；改为模型先按不透明画进离屏 FBO、再对整张结果乘一次 alpha 合成（离屏不可用时回退逐层乘 alpha），整只模型透明度均匀。全不透明时不分配离屏目标，零额外开销。

### Notes

- **中文声调准确度为模型语料上限** — 该模型以日语语料微调，少数多音字声调仍不完美；听感与旧版一致，已确认接受，不作为本版回归项。
- **历史 SIGILL 坑已包含修复** — 1.7.1 记录的 ORT 1.23.2 KleidiAI SME 崩溃（[onnxruntime#26921](https://github.com/microsoft/onnxruntime/issues/26921)）在 sherpa 内嵌的 1.28.2 中已含修复。
- **设置页 Debug 构建新增「试听文本」入口** — 任意文本直接合成，便于真机验证数字 / 日期 / 日语 / 中英混排；release 构建不显示该入口。
- 版本号升至 1.8.0（versionCode 14）；applicationId 与签名不变，可覆盖安装，但**升级后必须重新准备语音模型**（见上）。
- **流式播放的句间停顿** — 真机合成速度约为实时的 1.1 倍（RTF≈1.1），句子之间可能有轻微停顿，属流式播放的预期代价；多句并行未启用。
- **构建配置精简** — R8 增加 `-allowaccessmodification`（配合 optimize 规则做跨包类合并/内联）；移除 1.8.0 已无对应资产的旧 G2P 压缩豁免（`aaptOptions.noCompress` 的 `bin/dic/txt`），debug APK 同步从 80.9MB 降到 73.2MB。
- 真机验收记录（已通过、已归档）见 [`docs/archive/checklists/2026-11-15-tts-device-test-1.8.0.md`](docs/archive/checklists/2026-11-15-tts-device-test-1.8.0.md)。

### Documentation

- **文档整理** — `docs/` 新增总索引 [`docs/README.md`](docs/README.md)（文档地图、权威性说明、维护规则）；修正失效链接与过期说明（`TODO.md` 移除 1.8.0 已作废项并补「发布收尾」、TTS 迁移报告与真机清单状态对齐、客户端模块 README 路径纠正）；1.8.0 的实施计划归档到 [`docs/archive/plans/`](docs/archive/plans/)；**删除过时文档**：2026-08/09 的 48 项代码审查报告、逐条判定表、旧批次清单（描述 1.7.2 之前的代码，结论已并入 `TODO.md`）。
- **`NOTICE.md` 补 TTS 第三方组件** — sherpa-onnx / ONNX Runtime，以及模型与 G2P 数据（JMdict、open_jtalk 词典、CMUdict、pypinyin、ruleFsts）和随 APK 分发的派生产物。

---

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
