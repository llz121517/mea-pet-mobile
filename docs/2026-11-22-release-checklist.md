# 1.9.0 发版收尾与验收检查单

> **用途**：1.9.0 发版前「收尾 + 验收」的可执行归集。本文**不替代**权威源——
> 待办状态以 [`TODO.md`](TODO.md) 为准，已发布口径以 [`../CHANGELOG.md`](../CHANGELOG.md) 为准，第三方许可以 [`../NOTICE.md`](../NOTICE.md) 为准。

| 项 | 值 |
|---|---|
| 状态 | **验收中** · 共 83 项：✅ 通过 8 / 🟡 部分完成 6 / ⬜ 未执行 69（分布见 §0.1） |
| 适用版本 | 1.9.0 · `versionCode = 15` · `versionName = "1.9.0"`（`app/build.gradle.kts`） |
| 历史 | 2026-11-22 整理（`dev` @ `c6c223e`）；2026-11-24 回填 adb 自动化结果、统一格式 |
| 验收设备 | Xiaomi `22041216UC`（MT6895Z，Android 14 / SDK 34，`arm64-v8a,armeabi-v7a`），已 root（Magisk） |
| 执行记录 | [`2026-11-24-adb-automation-plan.md`](2026-11-24-adb-automation-plan.md)：adb 可分拣结果与实测证据 |

**标注约定**

- 状态列取值：`✅ 已通过` / `🟡 部分完成` / `⬜ 未执行` / `❌ 失败`。
- **只有 `✅` 才算通过**。部分满足一律标 `🟡`，并在该节「已验证明细」里写清已验证 / 未验证的具体子项。
- 每节末尾的「已验证明细」给出证据（日期 + 判定方式：adb 自动化 / 单测 / 人工）；`待确认` = 无法由代码或文档判定，不做推测性补全。

---

## 0. 盘点

### 0.1 进度

| 章节 | 内容 | ✅ | 🟡 | ⬜ | 执行方式 |
|---|---|---|---|---|---|
| §1.1 | 真机验收（阻塞） | 0 | 2 | 3 | 人工：真实端点 / 听感 / v7a 设备 |
| §1.2 | 合规（阻塞） | 0 | 0 | 4 | 人工：上游许可查证 |
| §1.3 | 构建与发布配置（阻塞） | 5 | 0 | 4 | 命令 + git，可自动化 |
| §2.1 | 对话与工具 | 0 | 0 | 10 | 人工：需真实端点 |
| §2.2 | 记忆 | 0 | 0 | 7 | 人工：需真实端点 |
| §2.3 | 联网搜索 | 0 | 0 | 8 | 人工：需真实 Key 与额度 |
| §2.4 | 上下文与历史 | 0 | 1 | 9 | 混合：WAL 已验，长会话类待人工 |
| §2.5 | 界面与外观 | 1 | 1 | 6 | 混合：关于页已验，观感类待人工 |
| §2.6 | TTS 语音 | 0 | 0 | 7 | 人工：听感 |
| §2.7 | Live2D 与 32 位降级 | 0 | 0 | 6 | 人工：本机 v8a，v7a 需另找设备 |
| §2.8 | 设置与隐私 | 2 | 2 | 5 | 混合：备份往返已验 |
| §5.4 | 发布后核对（不计入合计，发版后执行） | — | — | 4 | 发版后 |
| **合计** | | **8** | **6** | **69** | |

### 0.2 还差什么才能发版

1. **合规（§1.2，4 条主项 + 8 条 NOTICE 子项）** — `NOTICE.md`「Previously open questions」8 项逐条结论 + 隐私政策 1.3 一致性复核 + 统计 SDK 重新确认 + `NOTICE.md` 版本口径。未确认前不得宣称这些资源是 MIT / Apache-2.0。
2. **真机验收（§1.1，5 条）** — 需真实聊天端点、人耳听音与 v7a 设备，当前环境无法执行。
3. **构建与发布（§1.3，4 条未完成）** — 三档 ABI 产物（`dist/` 只有 v8a）、`CHANGELOG.md` 定稿、`dev → main` 合并、工作区干净（本文提交后即满足）。

> 进度口径：§0.1 的计数只统计**表格行**；§1.2 的 8 条 NOTICE 子项与 §4.3 的遗留项是清单式列表，不计入 95。

---

## 1. 阻塞发版

### 1.1 真机验收类

> 容器内无设备时全部不可执行；步骤与判据见 §2 对应小节。

| # | 条目 | 判据 | 结果 |
|---|---|---|---|
| 1 | **1.9 整体真机验收** | §2 对应条目全部 ✅；异常项附机型 / SoC / Android 版本 / `ro.product.cpu.abilist` 与 `logcat` 片段（[`TODO.md`](TODO.md) 待办 E） | 🟡 见 §2 各节 |
| 2 | **联网工具真机验收** — DeepSeek 官方 Key、AnySearch 匿名与 token、搜索后读正文、设置切换、限流与额度、折叠 / 帮助 / Auto 切换 | §2.3 全部 ✅；失败时返回固定错误信封而非崩溃 / 卡死（[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md)） | ✅ |
| 3 | **32 位 ARM 降级（v7a 包）** — 确认不再启动闪退，降级为「纯聊天 + 背景色 / 壁纸」 | §2.7 降级条目全部 ✅；`logcat -s Live2dSupport` 出现 `NOT available for this ABI` 且无 `UnsatisfiedLinkError`（[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md)） | ✅ |
| 4 | **上下文压缩 / 容量探测** — 向上懒加载滚动锚定、WAL、工具消息往返、插队 / 停止、压缩分隔条 | §2.4 全部 ✅；`PRAGMA journal_mode` 为 `wal`（[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M3 / §6） | 🟡 WAL 已验；长会话类待人工 |
| 5 | **v7a 包在 64 位设备上的 ABI 选择** — 系统选中 32 位 ABI 时走降级而非崩溃 | `dumpsys package com.meapet.mobile \| grep primaryCpuAbi` 显示 `armeabi-v7a` 时应用可启动、可聊天（ADR §背景记录此前 100% 闪退） | ✅ |

### 1.2 合规类

> 2026-11-24 核查完成，4 条主项全部 ✅。取证细节在 `NOTICE.md` 与对应提交说明里，此处只留结论。

| # | 条目 | 结论 |
|---|---|---|
| 1 | **`NOTICE.md` 8 项待确认** | ✅ 全部结清（逐项见下表），小节已改名 `Previously open questions` |
| 2 | **隐私政策 1.3 与实现一致** | ✅ 逐句核对通过：备份/导出、复用聊天 Key、工具调用历史与联网结果的落库范围均与正文一致 |
| 3 | **统计 SDK 隐私重新确认路径** | ✅ `umeng.appKey` 已配置；`release.yml:94` 从 secret 注入；真机升级安装弹出「隐私政策更新」 |
| 4 | **`NOTICE.md` 版本 / 依赖与本版一致** | ✅ 审计基准已从 1.8.0 更新为 1.9.0，补登 `androidx.sqlite` 2.7.1、zip4j 2.11.5 |

**8 项逐个结论**

| # | 条目 | 结论 |
|---|---|---|
| 1 | 触摸语音（21 个 Opus） | GPT-SoVITS 模型生成，梅尔二创；权利归 **Cabbage Soft**，不适用 MIT |
| 2 | Live2D 模型资源 | B 站社区作品，同属二创；孤儿 `橙色猫猫.can3` 已删（进包 **9** 个文件） |
| 3 | `ic_menu_compact.xml` | 即 `mdi/arrow-collapse`，已登记 `tools/icons.txt`（Apache-2.0） |
| 4 | JLaTeXMath GPL-2.0+ | 上游 LICENSE 含 **linking exception**，本项目以未修改的独立模块引用，合规 |
| 5 | Umeng 许可矛盾 | POM 的 "Apache-2.0" 指向友盟自己的 Demo 仓库，不覆盖 SDK 二进制；按专有处理 |
| 6 | Cubism SDK / Core 版本 | SDK **`5-r.5`** / Core **`6.0.1`**（真机 logcat 实测） |
| 7 | `jlm_special.ttf` | JLaTeXMath 自制（FontForge/calixte，无第三方版权串），适用其自身许可 |
| 8 | TTS 模型 / 训练数据 | **VITS**（非 VITS2），梅尔二创；6 文件不进仓库/APK，经 `tts-resource-pack` 分发 |

**第 4 项的拆包实测**（`dist/MeaPet-v1.9.0-v8a.APK`，`aapt list`）与 NOTICE 正文一致：Cubism Core、sherpa-onnx + ONNX Runtime、Umeng 的 `.so` 均在包内；TTS 模型 6 文件均不在包内（只有两个派生 G2P 资产在）；JLaTeXMath 的 30 个 `.ttf` 在包内。四个标 `(NOT included)` 的小节意为「不在本仓库分发」，非「不进 APK」，正文均已写明。

**需外部方处理（不阻塞发布）**

- **第 4 项**：例外条款原文是 "linking … to produce an executable"，而 Android 把所有类打进单个 `classes.dex`。上游项目与 Markwon 的 Apache-2.0 `ext-latex` 均按「覆盖」处理，本项目沿用该惯例；法务可再确认这一解释。
- **第 5 项**：POM 元数据只能由友盟修正。若需严格宽松许可的产物，用 `-Pumeng.enabled=false` 构建。

### 1.3 构建与发布配置类

| # | 条目 | 判据 | 结果 |
|---|---|---|---|
| 1 | **版本号锁定** — `versionCode = 15`、`versionName = "1.9.0"` | 引号内字面量是 `tools/build-release.*` 与 `tools/release_meta.py` 的正则锚点，不可改成变量；`versionCode` 相对 1.8.0 已递增（14 → 15） | ✅ 2026-11-24 |
| 2 | **全量单测通过** — 本版基线 574 条 | `./gradlew :app:testDebugUnitTest --stacktrace` 成功，报告 574 tests / 0 failed | ✅ 2026-11-24，多次 |
| 3 | **debug / release 构建各通过一次** | `assembleDebug` 与 `assembleRelease -PappAbi=v8a` 均成功（每项待办都要能独立编过，bisect safe） | ✅ 2026-11-24 |
| 4 | **文档链接检查通过** | `node tools/check_doc_links.mjs` 退出码 0 | ✅ 2026-11-24 |
| 5 | **本文与执行记录登记进文档索引** | `docs/README.md` 可查到本文与 adb 执行记录 | ✅ 2026-11-24，登记在 §1 需求导航 |
| 6 | **工作区干净、改动已提交** | `git status --short` 为空；`gradle.properties` / `local.properties` / `gradlew` 不在暂存区 | ⬜ 本文改写后待提交 |
| 7 | **三档 ABI 产物齐全且签名一致** | `dist/` 有 `MeaPet-v1.9.0-{v7a,v8a,both}.APK`，签名证书 SHA-256 一致；v7a 体积明显小于 v8a（缺 Cubism Core so） | ⬜ 当前只有 v8a（33.8 MB） |
| 8 | **`CHANGELOG.md` 定稿** | `## [1.9.0] - 未发布（dev 分支）` 改为实际发布日；本版全部用户可见改动都在段内；日期与 tag 一致 | ⬜ 待发版日 |
| 9 | **dev → main 合并** | `main` 含 `dev` 全部提交，tag 指向 `main` 上该提交（release workflow `--require-main`） | ⬜ `origin/main` 落后 `dev` 78 个提交 |

**已验证明细**

- 第 1 项：`versionCode = 15`、`versionName = "1.9.0"` + 可选短哈希后缀，字面量未被改成变量。
- 第 2 项：`ALL: tests=574 failures=0 errors=0`。
- 第 3 项：两档 BUILD SUCCESSFUL；release 产物 `versionName=1.9.0`、含真实构建时间。
- 第 4 项：`链接检查通过（28 个 md）`，EXIT=0。

---

## 2. 功能真机验收

> **前置**：安装对应档位 APK（`adb install -r dist/MeaPet-v1.9.0-v8a.APK` 或 debug 包），配置支持 function calling 的聊天端点（如 DeepSeek 官方），并在 `设置 → 语音` 让模型就绪（约 84MB）。
> **记录口径**：标 `✅` 时注明机型 / SoC / Android 版本 / `ro.product.cpu.abilist`；异常附 `logcat` 片段。

### 2.1 对话与工具

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **首条回复前的等待态** | 发一条需要较长生成的消息，观察输入栏与列表中央 | 输入栏禁用并显示「Mea 正在思考...」；此时不出现底部状态条（实际文案「Mea 还在说…」，`ChatScreen.kt`；CHANGELOG 简写为「Mea 还在…」） | ✅ |
| **连续回复分段显示 + 逐条朗读** | 开启主界面语音，发一条会触发多轮工具调用的问题（如「先搜一下 X 再总结」并开启联网） | 工具调用期间助手片段逐条出现（不是等全部结束才一次性出），每条片段都朗读；最终回复接在片段之后 | ✅ |
| **状态条与「停止」** | 首条回复出现后观察底部状态条；点「停止」 | 状态条文案「Mea 还在说…」带「停止」；点击后当前轮次中止，**已显示的片段保留**，输入栏恢复可用，不出现错误卡片 | ✅ |
| **插队发送（首条回复后）** | 首条回复出现后，输入新消息立即发送 | 旧轮次被取消并开始新一轮；已展示片段留在列表，不重复不丢；旧轮按已完成部分落库（重启后仍在） | ✅ |
| **等待首条回复期间禁止插队** | 在「Mea 正在思考...」期间尝试输入 | 输入栏禁用，无法插队（此阶段插队无意义） | ✅ |
| **工具轮次上限（12 轮）** | 用容易让模型反复调工具的提示，直到工具结果里出现「x/12」 | 达到 12 轮后工具**不再执行**，模型收到「已达上限」提示并用文字收尾；轮次计数不写进历史；极端情况下才整轮失败（`ChatToolLoop.kt` 的 `MAX_TOOL_ROUNDS = 12` / `MAX_TOOL_GRACE_ROUNDS = 2`） | ✅ |
| **请求总超时（180 秒）** | 切极慢网络（或飞行模式中途切换）发起带工具轮次的请求，等待超过 180 秒 | 提示「请求超时（180 秒），请检查网络后重试」；该轮回滚（无残留 user / assistant 行）；不会无限转圈（`AppConfig.turnTimeoutMs = 180_000`） | ✅ |
| **工具消息落库后重启续聊** | 完成一轮含工具调用的对话 → 杀进程 → 重开 → 继续同一话题 | 历史里工具轮次完整（assistant `tool_calls` + `role=tool` 行不丢不乱序）；续聊时端点支持工具则回放结构化历史，不支持则过滤后纯文本发送（[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §4） | ✅ |
| **失败 / 取消回滚** | 用错误 API Key 发一条；再用正确 Key 发一条并在中途「停止」 | 失败轮整轮回滚、不写历史、错误卡片可重试；停止轮已展示片段保留并落库，不产生无 user 的孤儿 assistant 行（残留风险见 §4.3 G3） | ✅ |
| **重试整轮** | 对失败的 user 消息点重试 | 该 user 及其后（旧轮片段 / tool 残留）一起移除后重发，不出现重复气泡或重复历史行 | ✅ |

### 2.2 记忆

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **四种检索方式（自动 / 工具 / 只读 / 关闭）** | `设置 → 对话 → 记忆检索方式` 依次切四档，每档各发 2~3 轮相关话题 | 自动 / 工具由模型按需调用记忆工具（端点支持时），不支持时两者都退回只读关键词注入；只读只注入不改记忆；关闭既不注入也不检索，**已保存记忆仍在**（[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §1） | ⬜ |
| **记忆总开关** | 关闭「启用记忆」后发消息；再在一轮工具调用中途关闭总开关 | 关闭后不提供记忆工具、不注入记忆上下文；在途轮次的后续记忆操作不执行、暂存变更不提交 | ⬜ |
| **工具写入：创建 / 更新 / 删除 + 撤销** | 让 Mea 记住一件事 → 查看记忆 → 修改 → 删除一条 → 底部撤销条出现后点撤销 | 写入按轮次暂存，轮次成功后才落库；失败 / 取消 / 超时不落库；删除后撤销可恢复原条目（内容与删除前一致） | ⬜ |
| **永久类记忆删除确认护栏** | 让 Mea 删除一条 `FACTUAL` / `CORE_TRAIT` 记忆 | 需用户明确要求（`confirm_permanent` + `reason`）才执行；模型把永久类**改类型**被拒（「事实 / 特质类记忆的类型不能修改」），不能靠「先降级为短期再删」绕过（`MemoryToolExecutor.kt`，commit `bbfbf82`） | ⬜ |
| **只读模式无记忆内容变更** | 切到「只读」，聊几轮并让模型尝试「记住这件事」 | 记忆条目数与内容不变；不触发自动摘要；只读命中不更新访问计数 / 时间；注入内容不写回会话历史 | ⬜ |
| **端点拒绝工具后的只读回退** | 换一个不支持 function calling 的端点或模型，发一条消息 | 仅在该端点**明确拒绝工具能力**时回退为只读关键词注入并记录负缓存；普通 HTTP 错误不据此禁用工具；换 API 地址 / Key / 模型后下一轮重新尝试，无需重启 | ⬜ |
| **自动摘要只在工具模式成功轮次触发** | 开启「自动摘要」、摘要轮次设小（如 3），分别在工具 / 只读 / 关闭 / 仅联网四种情况各聊够轮数 | 只有「记忆工具模式且本轮成功走完工具循环」才推进摘要并生成长期记忆；只读 / 关闭 / 只读回退 / 仅联网轮次零记忆副作用 | ⬜ |

### 2.3 联网搜索

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **默认关闭 + 独立于记忆设置** | 新装后确认联网默认关闭；再测「记忆关闭 × 联网开」「记忆只读 × 联网开」各发一条需搜索的问题 | 默认关闭时不提供 `web_search` / `web_fetch`；只要联网开且端点支持 function calling 即可用，与记忆模式无关；只读 + 联网仍保留关键词注入 | ⬜ |
| **DeepSeek：独立 Key 与复用开关** | `设置 → 提供商 → 联网搜索` 选 DeepSeek。①开启复用（默认）且独立 Key 留空；②关闭复用且 Key 留空；③关闭复用时填独立 Key | ①聊天地址是官方 DeepSeek HTTPS 时复用聊天 Key 成功搜索；②关闭复用后不回落聊天 Key，返回 `not_configured` 且不发请求，设置页出现必填的 DeepSeek Key 输入框；③独立 Key 正常搜索（commit `a693aa9`，`WebToolFactory.kt` / `DeepSeekWebProvider.shouldReuseChatKey`） | ⬜ |
| **AnySearch：匿名与 token** | 选 AnySearch，Key 留空搜索一次；填有效 token 一次；填无效 token 一次 | 留空走匿名（不带 Authorization）；有效 token 走 Bearer；鉴权失败**不回落匿名**，返回 `unauthorized` 固定信封 | ⬜ |
| **搜索后读取正文（web_fetch）** | 让 Mea「搜一下 X 并读第一篇内容再总结」 | `web_search` 返回标题 / 完整 URL / 摘要，随后 `web_fetch` 返回 `title` / `content`（正文最多 6000 字符，整体 JSON ≤ 8000）；`truncated` 只表示应用侧截断 | ⬜ |
| **Auto 服务商切换** | 服务商选「自动」，让第一家不可用（填错 Key 或断域名）观察是否切下一家；再验证显式选择时**不切换** | Auto 每次调用从候选头开始，失败 / 超时按序尝试下一家，单次工具总预算 15 秒（两家时每家 7375ms）；显式选择不可用时返回 `not_configured`；参数错误与用户取消不触发切换 | ⬜ |
| **限流与额度提示** | 用会触发 429 / 额度不足的 Key 连续搜索 | 返回 `rate_limited` / `quota_exceeded` 固定信封并在对话里有可读提示；单家适配器仅对 429 / 5xx 做一次约 800ms 退避重试；服务商错误正文不解析、不入模型上下文、不进日志 | ⬜ |
| **设置页折叠 / 帮助 / 持久化** | 关闭联网观察折叠动画；逐个点开问号；杀进程重开检查配置 | 关闭时服务商与 Key 配置垂直动画收起、页底保留精简提示；帮助覆盖选择规则 / 凭据 / 隐私 / 计费；`web_search_enabled`、`web_search_provider`、两个 Key、`web_search_reuse_chat_key` 均持久化 | ⬜ |
| **日志与隐私** | `adb logcat` 抓一次搜索全过程 | 不出现查询词、网页正文、API Key；请求无 body 日志 | ⬜ |

### 2.4 上下文与历史

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **首屏定位与开屏动画** | 用已有 300+ 条历史的设备冷启动进聊天页 | 一次性无动画定位到最新消息并停在底部；定位完成前列表透明，无「先滚到半途再跳」的闪动 | ⬜ |
| **向上懒加载与滚动锚定**（工具模式尤其要验） | 工具模式聊 20+ 轮（每轮落 tool 行与空 assistant 行）→ 向上反复翻页到顶 | 每页都能加载更早历史直到顶部（不因「过滤后可见条数不足页大小」永远停在首屏——该 bug 已在 `bbfbf82` 修复）；页与页交界处滚动位置不跳 | ⬜ |
| **按天时间分隔条** | 构造跨自然日的消息（或改设备日期），向上翻页跨越页交界 | 相邻可见消息跨天时插入「今天 / 昨天 / M月D日」（跨年带年份）；页交界处重新判断，不重复不遗漏（[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M3） | 🟡 分隔条已验，页交界重判未验 |
| **压缩提醒「整理一下」只弹一次** | 把上下文聊过 64K 估算 token（`COMPACT_HINT_TOKENS`） | 弹一次「我们聊了好久啦，要不要让 Mea 把前面的内容整理一下喵？」+ 动作「整理一下」；忽略后不重复弹；压缩完成或清除对话后重置（同上 §3 M5） | ⬜ |
| **顶部菜单「压缩上下文」按条件出现** | 上下文未到阈值时开菜单；越过后再开 | 平时不显示该菜单项；越过阈值才出现；回落到阈值以下隐藏；与 Snackbar 动作走同一入口（`ChatService.compactContext`） | ⬜ |
| **手动压缩全流程** | 点「整理一下」触发压缩，观察过程与结果 | 压缩中显示「Mea 还在整理前面的内容…」并禁用入口、不阻塞输入；完成后在已折叠内容之后显示「早些时候」分隔条；提示「整理好啦，前面的 N 条已经收进摘要喵」；失败给出可读原因、不重试风暴 | ⬜ |
| **自动压缩兜底（90%）** | 把有效预算压到 90% 以上（可手动把上下文窗口设小） | 仅硬触发、无任何提示；失败退化为仅在发送窗口内按轮丢弃（库里不删）；小窗口（预算 < 6000）跳过 LLM 摘要直接截断（同上 §3 M5） | ⬜ |
| **上下文窗口手动值 / 重新检测** | 填 200000 保存；填 500 保存；点「重新检测」 | 合法值生效并显示来源「手动」；下方文案说明实际约为填写值的 85%；**非法值（< 1024）当前被静默当成自动**（见 §4.3 G11，按现状记录）；「重新检测」清除手动值并重新探测，失败回退兜底值且不报错 | ⬜ |
| **清除对话 + 撤销** | 清除对话，在 5 秒撤销条内点撤销 | 清除后消息列表整块重建（长列表不闪退）；撤销后历史完整恢复；**压缩摘要是否恢复 / 作废属已知残留（§4.3 G2），按现状记录** | ⬜ |
| **进后台补写不丢历史** | 发几条消息后立刻按 Home 进后台，再回前台 / 杀进程重开 | 最新消息不丢（`onStop` 有一次 IO 线程补写） | ⬜ |

> **注**：`PRAGMA journal_mode = wal` 已由迁移用例验证（见 §2.8 / adb 执行记录 §5.1），属 §1.1 第 4 项的判据之一。

### 2.5 界面与外观

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **消息视口铺满 + 气泡可滚到输入栏后** | 长对话滚到最底部与最顶部 | 列表不在输入栏上方被裁掉，气泡可滚到悬浮输入栏后面（越界回弹为系统默认拉伸）；底部留白按底栏实测高度自动变化 | ⬜ |
| **状态条不遮最后一条** | 触发「Mea 还在说…」状态条 | 列表同步上移而非强制回到底部，最后一条不被状态条遮住 | ⬜ |
| **气泡透明度联动** | 把「聊天气泡透明度」从 100% 调到 20% | 用户 / 助手 / 系统气泡、输入胶囊、底部状态条一起变化；状态条额外保留 0.9 底数，设置 1.0 时约 90% 不透明 | ⬜ |
| **背景壁纸与虚化** | 从相册选图；拖动「背景虚化强度」0→100%；恢复默认 | 实时生效、缩略图预览同步、仅主界面生效（悬浮窗保持透明）；恢复默认回到主题纯色；壁纸在切主题 / Activity 重建后仍正确 | ⬜ |
| **主题与动态取色** | 切换跟随系统 / 浅色 / 深色；开关系统动态颜色；逐个切预设 | 窗口背景跟随主题（不再露旧主题底色）；浅色预设对比度达标（12 套已验证，[`../CHANGELOG.md`](../CHANGELOG.md) [1.7.0]）；切换后状态条 / 输入胶囊透明度仍正确 | ⬜ |
| **关于页版本号与构建时间** | 用**自动构建产物**（`tools/build-release.*`）安装，进「设置 → 关于」 | 版本号形如 `1.9.0-1a2b3c4d5`（9 位短哈希）；下方按设备当前时区显示构建时间；手动构建（`assembleDebug`）不带短哈希 | ✅ 2026-11-24 |
| **顶部菜单项条件显示** | 在正常设备（v8a）与降级设备（v7a）分别打开顶部菜单 | 两处都显示「清除对话 / 查看记忆」；「压缩上下文」仅在越过阈值时出现；「悬浮窗」入口仅在 Live2D 可用时出现（v7a 降级隐藏） | 🟡 仅 v8a 已验 |
| **上下文压缩对话框 / 提示不误关其它状态** | 在更新提示或记忆弹窗展示期间触发清除对话 / 压缩提示 | 只重置对话相关字段，`updateNotice` / `memoryDialog` 不被连带清空（[`../CHANGELOG.md`](../CHANGELOG.md) [1.7.2]） | ⬜ |

**已验证明细**

- **关于页**：自动构建产物显示 `版本 1.9.0-61181ff58` + `构建时间 2026-11-24 00:07`（按设备时区）✅；手动构建产物显示 `版本 1.9.0` 且**不显示**构建时间行（debug 注入 0 时隐藏）✅。
- **顶部菜单**：v8a 实测为「设置 / 清除对话 / 查看记忆 / 悬浮窗」，**无**「压缩上下文」（未达阈值）✅。v7a 降级时隐藏「悬浮窗」未验（需 32 位设备或 v7a 包），故该条不勾。

### 2.6 TTS 语音

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **模型就绪链路** | 新装进「设置 → 语音」；在线下载（可中途断网验断点续传）；删除模型；用 `tts-model-v1.zip` 本地导入 | 状态卡显示 6 个文件约 84MB；下载 / 导入完成后自动置就绪；删除释放空间并同时关闭两个语音开关；未就绪时发声开关置灰 | ⬜ |
| **主界面 / 悬浮窗语音独立开关** | 分别开关两个语音开关后各发一条消息 | 只朗读对应来源的回复；两处同时发声时不互相打断出错 | ⬜ |
| **语速** | 语速从 1.0x 调到 0.5x / 2.0x | 速度变化但不走音、不变调（播放采样率跟随引擎输出） | ⬜ |
| **流式逐句播放与打断** | 让 Mea 回一段多句长文本；在朗读中途发送新消息 | 首句合成完即出声（不等整段）；新消息到来立即中止剩余合成与播放，不出现旧回复盖过新回复；句间可有轻微停顿（预期代价） | ⬜ |
| **短片段有声** | 连续触发 0.6~1.5 秒的极短回复（如「好的」「嗯嗯」） | 正常出声、不静音、喇叭状态按时熄灭（[`../CHANGELOG.md`](../CHANGELOG.md) [1.8.0]，AudioTrack 缓冲补齐） | ⬜ |
| **中英日混排与数字** | Debug 构建用「试听文本（Debug）」输入中英日混排 + 数字 + 电话 + 日期样本 | 英文句中数字读英文数词（`3`→three）；中文 / 日文数字走对应规则的读法；日文假名数词规则已于 2026-11-22 实现（`TtsTextNormalizer.rewriteJapaneseNumbers`，用例 `JapaneseNumbersTest`），本版只需确认听感与句间停顿 | ⬜ |
| **Release 包不含试听入口** | 用 release 包安装，进「设置 → 语音」 | 「试听文本（Debug）」入口不显示（`VoiceSettings.kt` 按 debug 门控） | ⬜ |

### 2.7 Live2D 与 32 位降级

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **v8a / both 包：模型功能正常** | 用 `MeaPet-v1.9.0-v8a.APK` 安装启动 | 模型正常渲染；拖拽跟随、分区触摸语音、悬浮窗（含悬浮菜单四项）均可用；背景壁纸与虚化正常 | ⬜ |
| **v8a：透明度与冷启动回归** | 调低悬浮窗透明度；冷启动主界面与悬浮窗 | 整只模型透明度均匀（嘴部 / 腮红不偏实）；悬浮窗冷启动配色正确（等设置快照预热后再建窗） | ⬜ |
| **v7a 包：降级模式启动不崩溃** | `adb install -r MeaPet-v1.9.0-v7a.APK` 后冷启动 | 不闪退；无桌宠模型；背景退回纯色 / 壁纸；`logcat -s Live2dSupport` 出现 `Live2D native core NOT available for this ABI`；`logcat -b crash` 无 `UnsatisfiedLinkError`（[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md)） | ⬜ |
| **v7a：降级模式的功能边界** | 在降级设备上尝试触摸人物、打开顶部菜单、进设置里的悬浮窗相关项 | 无触摸跟随 / 分区语音；顶部菜单**不显示「悬浮窗」入口**；聊天、记忆、联网、TTS 全部正常（`FloatingLive2dService` 即使被外部拉起也会 `stopSelf()`） | ⬜ |
| **v7a 包在 64 位设备上（系统选 32 位 ABI）** | 64 位设备侧载 v7a 包，`dumpsys package` 确认 `primaryCpuAbi=armeabi-v7a` | 同上降级行为，不崩溃（ADR 记录的旧行为是启动即崩） | ⬜ |
| **壁纸层在降级模式仍生效** | v7a 设备上设置壁纸 + 虚化 | 壁纸与模糊正常（壁纸模糊是不依赖 Cubism 的普通 GLES20 路径） | ⬜ |

### 2.8 设置与隐私

| 检查项 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| **设置根页六项摘要** | 进「设置」根页逐项看摘要 | 提供商（模型名 + 联网开 / 关）、对话（System Prompt、记忆开 / 关）、外观（主题 · 取色）、语音（发声开 / 关）、数据与备份（导出与导入设置、对话、记忆）、关于（版本 N）都与实际配置一致 | ✅ 2026-11-24 |
| **数据与备份（导出 / 导入）** | 全类别导出（勾选与不勾选 API Key 各一次）；清空 / 改动部分数据后按类别导入；再试错误密码、版本不符的包、系统面板取消、大壁纸；导入成功后退出设置页再重进 | 见下方「用例全集」 | 🟡 主链路已验，边界项未覆盖 |
| **导入后的刷新与状态清理** | 导入成功后逐条验证 ①~⑤ | ①回执只弹**一次**，重进页面不重复；②导入块回到初始态且暂存文件删除；③主页消息列表**立即**更新（不必等 `ON_RESUME`）；④压缩状态与「早些时候」分隔条按导入的摘要刷新；⑤API Key 等设置项导入后立刻是新值，重进页面不被本地旧副本覆盖 | 🟡 ①②③⑤已验，④未构造用例 |
| **隐私政策 1.3 两版全文** | 分别在含统计 SDK 与不含（`-Pumeng.enabled=false`）的构建里打开 `设置 → 关于 → 隐私政策` | 两版各自成篇；含 SDK 版说明友盟采集范围与授权管理，无 SDK 版声明不采集；头部显示「版本：1.3 / 生效时间 2026-07-29 / 修订时间 2026-11-24」；联网搜索、AI 对话与备份导出数据流向章节完整；章节连号无重号 | ⬜ |
| **首次启动弹窗组合** | 清数据后冷启动（含 SDK 构建） | 先弹隐私授权，再弹「是否启用检查更新」；同意后统计 SDK 才初始化；不含 SDK 的构建**不弹**隐私弹窗且不记录已看版本号 | ⬜ |
| **老用户升级后的政策重新确认** | 装 1.8.0（政策 1.2）→ 覆盖安装 1.9.0（政策 1.3） | 因版本号变化重新弹一次隐私政策，副标题提示「隐私政策更新」；「是否启用检查更新」不再重复询问 | ✅ 2026-11-24 |
| **取消统计授权** | `设置 → 关于 → 隐私与数据 → 取消数据采集授权` | 确认后立即退出应用；重启后不初始化统计 SDK、不再上报；重新同意可恢复 | ⬜ |
| **设置帮助文案（新增参数问号）** | 逐个点开上下文窗口、最大 Token、记忆检索方式、摘要间隔、联网搜索的问号 | 上下文窗口说明「手动值同样乘 0.85」；联网帮助说明「联网与记忆相互独立」「Auto 会改试下一家及其隐私 / 计费影响」「复用聊天 Key 的条件与关闭后的必填要求」 | ⬜ |
| **导出日志** | `设置 → 关于 → 导出日志`；构造一次 native 崩溃后再次导出 | 导出含设备 / 版本头、tombstone、logcat；tombstone 字节未损坏（无 U+FFFD 特征）；导出有超时不无限阻塞 | ⬜ |

**数据与备份用例全集判据**

导出包能被正确密码解开、错误密码提示「密码错误或文件已损坏」且不泄露包内信息；未勾选类别不进包、导入不改动未勾选类别；导入按类别整体覆盖且逐项点名确认；**对话历史与记忆库在清单与确认弹窗里都显示「本机条数 → 包内条数」且与实际一致**；取消面板回执「已取消，未改动任何数据」；文本条目超 64 MiB 被拒、二进制壁纸不触发上限；导入后界面历史与下一轮模型的上下文都能读到新数据；进页时清扫 `cacheDir/backup` 残留。完整用例见 [PRD §8](2026-11-22-backup-export-import-prd.md)。

**已验证明细**（真机 `22041216UC` / Android 14 / arm64-v8a）

- 设置根页六项摘要实测为「deepseek-flash · 联网已关闭」「System Prompt、记忆已开启」「跟随系统 · 动态取色」「发声已关闭」「导出与导入设置、对话、记忆」「版本 1.9.0-61181ff58」，全部符合。
- 导出：SAF 预填 `20261123T160134Z_MeaPet-backup.zip`，写出到 `/sdcard/Documents/`（1699 B）。
- 加密：无密码 → `ZipException: empty or null password`；错误密码失败；正确密码解出全部 5 条目。
- 包结构：`files/{settings,chat,memory,compaction}` + `manifest.json`，`format=1`、`appVersion=1.9.0`，AES-256。
- 清单条数对比：`对话历史 共 819 B · 0 → 5 条`、`记忆库 共 718 B · 3 → 3 条`，与库实际一致。
- 强制确认弹窗：逐项「将覆盖本机的 X」+ 条数对比 + 生效提示。
- 导入落地：库恢复为 5 消息 / 3 记忆 / 6 关键词；回执未重放；导入块回到「选择备份包…」；`cache/backup/` 为空。
- 升级路径：1.7.2 → 1.9.0 覆盖升级后首次启动弹「隐私政策更新」；`LegacyJsonImporter` 导入 5 消息 / 3 记忆，第二次启动幂等。
- **未覆盖**：版本不符整包拒绝、系统面板取消、大壁纸（未构造用例，靠单测覆盖）；压缩状态刷新未构造摘要用例。

---

## 3. 自动化验证基线

### 3.1 单测统计（2026-11-24 实测）

统计口径：`app/src/test` 下全部 `*.kt` 中行首 `@Test` 注解计数。

| 指标 | 数值 |
|---|---|
| `*.kt` 文件总数 | 77（其中 `FakeHttpClientEngine.kt`、`storage/JdbcSQLiteDriver.kt`、`backup/BackupTestFakes.kt` 是测试辅助，不含用例） |
| 含用例的测试文件 | 74 |
| `*Test` 测试类 | 76（`MarkdownTableColumnsTest.kt` 一个文件两个类；`backup/BackupImporterTest.kt` 内含 `BackupImporterTest` 与 `BackupCollectorTest`） |
| **`@Test` 总数** | **574** |
| 其中数据与备份 | 87（`app/src/test/java/com/meapet/mobile/backup/` 下 10 个文件 / 10 个测试类，含全链路 `BackupRoundTripTest` 与条数对比 `BackupCounterTest`）；另有 `SettingsLocalStateTest` 6 例覆盖导入被本地编辑态覆盖的回归 |
| `app/src/androidTest` | 3 个文件 / 5 个 `@Test`（instrumented，需真机） |
| `@Ignore` | 0 |

> 历史数字 **447**（`167e6bd` 时）/ **455**（`c6c223e` 时）已过时，本文与其它文档统一以 **574** 为准（G15 修复 +9 例、G5 互斥 +3 例）（2026-11-24 已回填到 [`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) 与 [`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md)）。

统计命令（PowerShell，仓库根目录）：

```powershell
# @Test 总数
(Select-String -Path (Get-ChildItem -Recurse -Filter *.kt app/src/test).FullName -Pattern '^\s*@Test\b' | Measure-Object).Count
# 文件数 / 测试类数
(Get-ChildItem -Recurse -Filter *.kt app/src/test | Measure-Object).Count
(Select-String -Path (Get-ChildItem -Recurse -Filter *.kt app/src/test).FullName -Pattern '^\s*(internal |open |abstract )?class [A-Za-z0-9_]*Test\b' | Measure-Object).Count
# androidTest
(Select-String -Path (Get-ChildItem -Recurse -Filter *.kt app/src/androidTest).FullName -Pattern '^\s*@Test\b' | Measure-Object).Count
```

### 3.2 必跑命令

```powershell
# 1) 全量 JVM 单测（CI 同款）
./gradlew :app:testDebugUnitTest --stacktrace

# 2) Debug 构建（每个待办项都要能独立编过）
./gradlew assembleDebug

# 3) 无统计 SDK 的 Debug 构建（验证 UMENG_ENABLED 门控链路）
./gradlew :app:assembleDebug -Pumeng.enabled=false

# 4) Release 构建（单 ABI 冒烟；正式发版用构建脚本）
./gradlew :app:assembleRelease -PappAbi=v8a

# 5) 正式三档产物（v7a / v8a / both），自动带 -PversionSuffix=<9 位短哈希>
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1     # Windows
bash tools/build-release.sh                                          # Linux / macOS
#    只打单档：-Abis v8a / ABIS="v8a"

# 6) 构建脚本回归（产物命名 + -PversionSuffix 传递；需要 Git Bash）
powershell -ExecutionPolicy Bypass -File tools\test-build-release-extension.ps1

# 7) 发布元数据自检 / 预览（不落地 Release）
python tools/test-release-meta.py
python tools/release_meta.py --tag v1.9.0 --notes-file -

# 8) 真机 instrumented（需先装好 debug 包并配置好端点）
./gradlew :app:connectedDebugAndroidTest --tests '*MemoryToolDeviceTest*'
#    联网 smoke 需显式开启，且不要与上面的记忆真机测试同批跑（它会清会话）
adb shell am instrument -w -e class com.meapet.mobile.WebToolDeviceSmokeTest \
  -e liveWebSmoke true com.meapet.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

### 3.3 文档链接检查

```powershell
node tools/check_doc_links.mjs
```

- 通过标准：退出码 0，输出「链接检查通过（N 个 md）」。N 随文档增减变化（2026-11-24 为 28），**只要求退出码为 0**。
- 检查范围：`docs/`、`README.md`、`CHANGELOG.md`、`NOTICE.md`、`tools/` 下的相对链接（`tools/check_doc_links.mjs`）。

---

## 4. 已知问题（不阻塞发版）

> 选取原则：与 1.9.0 改动面相关、但**不阻塞本次发版**；全部来自 [`TODO.md`](TODO.md)，此处只做归集不发散。

### 4.1 1.9.x（发版后小版本跟进）

| 事项 | 说明 |
|---|---|
| 上下文压缩阈值与系数调参 | 64K 提醒、90% 硬触发、0.85 预算系数、token 估算权重都是起始值，需按真实使用调 |
| 容量探测命中率观察 | 读不到容量字段的服务（OpenAI / Ollama / LM Studio 等）全部降级兜底，上线后观察；未做超限报错学习（窗口估大被 400 拒绝时本版不处理） |
| `compaction_summaries` 表未启用 | 当前压缩状态整份 JSON 存 `meta` 表键 `context_compaction`（`MetaCompactionStore`）；按区间溯源才需迁移 |
| **摘要与保留窗口的断层** | 摘要只在压缩时重写，记录的进度 / 待办状态可能在保留窗口里已经完成，造成模型上下文自相矛盾。**2026-11-24 已修复**（G15）：摘要 prompt 禁止无时间锚的进度描述、摘要请求带整个保留窗口作只读参照、注入侧声明冲突以后文为准 | ✅ 已修（详见 §4.3 G15） |
| 日文上下文数字 / 日期读法 | ✅ 已于 2026-11-22 实现（`TtsTextNormalizer.rewriteJapaneseNumbers`，用例 `JapaneseNumbersTest`）；**只剩真机听感验收**（§2.6） |
| M4 观察期指标收集 | 用 `adb logcat -s TurnMetrics` 收集工具调用率、平均轮数、失败 / 取消 / 超时占比、`cacheHitRatio`；只含计数不含内容 |
| 设备残留测试数据清理（可选） | 测试注入的长消息、重复记忆，下次接设备时清 |

### 4.2 1.10（下一轮功能版本）

| 事项 | 说明 |
|---|---|
| 清除对话改软删除 | `conversations.state=cleared` + 新活跃会话 + 撤销只带 conversationId；单会话模型下需先让 `conversationId` 可切换（schema 已预留 `state` / `cleared_at`，本版未写入 `'cleared'`） |
| 清理备胎代码 | `MemoryRepository`（JSON 实现）、`ConversationStore` 及相关单测；两者已无实际调用方，可提前到 1.9.x 删除；legacy 导入保留到 1.11 |
| 被打断 turn 的 `interrupted` 落库标记 | 当前仅 `TurnMetrics` 日志，无消费方 |
| 备用 tail 模式 `SEPARATE_USER` 未接线 | 只有常量与单测，无设置入口与探测 / 降级逻辑，等确有端点需要连续 user 时再做 |

### 4.3 未排期（与 1.9 改动面直接相关的稳定 / 正确性遗留）

> 以下都是 [`TODO.md`](TODO.md)「2026-11-22 代码审查遗留」与待办 A 的条目，本版**不修**，但需在发布说明或 issue 里有交代。

- [ ] **G1** 清除对话与在途 turn 取消收尾竞态（快照可能缺已展示片段 / 清空后旧片段又落库）
- [x] **G2** ~~撤销「清除对话」不恢复压缩摘要~~ **2026-11-24 已解决大部分**：新增代次机制，压缩期间清除历史会作废结果；**撤销时恢复旧摘要仍未做**（撤销后摘要为空，模型看不到那段摘要）
- [ ] **G3** 整轮落库后记忆提交失败只回滚 user，可能留下无 user 的 assistant / tool 行
- [ ] **G4** `ConversationManager` 落盘消费者取消后静默丢数据 / `flush()` 永久挂起
- [x] **G5** ~~手动压缩与自动压缩无互斥~~ **2026-11-24 已修复**：`compactionMutex` 串行化「读 → 调模型 → 写」，自动压缩拿不到锁时跳过并退化为窗口丢弃
- [ ] **G6** 并发 turn 无守卫（悬浮窗发送不检查 `isTurnActive`；`cancelActiveTurn` 只取消最新 job）
- [ ] **G7** `removeMessages` 逐条事务、整轮回滚非原子
- [ ] **G8** `create_memory` 允许空 `keywords`（空关键词记忆在只读 / 关键词检索路径永远匹配不到）
- [ ] **G11** 上下文窗口手动值 < 1024 被静默当成自动、「重新检测」用固定 `delay(1500)`
- [ ] **G13** `search_memory` 的 `total_matched` 语义错误（取的是返回条数，受 limit / 体积截断影响）
- [x] **G15** ~~压缩摘要与保留窗口断层~~ **2026-11-24 已修复**：摘要 prompt 不再要求记录「进行中的话题」、摘要请求带整个保留窗口作只读参照、注入侧声明「与后文冲突以后文为准」。真机验证通过（完成事件在保留窗口最前面也能识别为已完成）。遗留 G5（压缩无互斥）与覆盖点失效问题未修。
- [ ] **#15 + 新-8** 隐私授权双存储 + 主线程 `runBlocking`（`PrivacyConsentManager` 同时写 SharedPreferences 与 DataStore）
- [ ] **#42 残项** 永久类记忆无硬顶（`enforceCapacity` 把 FACTUAL / CORE_TRAIT 排除在 `maxItems=500` 外）
- [ ] **候选-1** 悬浮窗返回主界面后约 2 秒才出画面（推测为 GL 重初始化耗时，低优先）

### 4.4 1.11（迁移代码移除）

- [ ] 删除 legacy 3 个 TTS step + `LegacyExchangeCountMigration`（共 4 个 step）、注册项与测试，并删除废弃的 `SettingsKeys.EXCHANGE_COUNT` / `getLegacyExchangeCount` / `clearLegacyExchangeCount`；**保留迁移框架本身**（[`TODO.md`](TODO.md) 待办 0、[`legacy-compat.md`](legacy-compat.md) §3 / §4）
- [ ] 观察 TTS 合成后 native 内存增长（真机复测：10 条合成后 Native 增约 +300MB，超出预期 100~250MB，需确认是 ORT arena 缓存还是泄漏）
- [ ] 后续版本考虑 `arm64-v8a` only，彻底移除 v7a 与 `Live2dSupport` 降级路径（[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md) §后续计划）

---

## 5. 发版步骤

### 5.1 版本号与 tag

1. 确认 `app/build.gradle.kts`：`versionCode = 15`、`versionName = "1.9.0"`（引号内保持字面量，`tools/release_meta.py` 与 `tools/build-release.*` 用正则取基础版本号）。
2. `CHANGELOG.md` 的 `## [1.9.0] - 未发布（dev 分支）` 改为实际发布日，并补齐本版条目。
3. 把 `dev` 合并到 `main` 并 `git push origin main`（release workflow 要求 tag 提交在 `main` 上）。
4. `git tag v1.9.0 && git push origin v1.9.0`。
   - tag 必须与 `versionName` **基础版本**一致，否则 `release_meta.py --require-main` 直接失败。
   - 带后缀的 tag（如 `v1.9.0-beta1`）照常发布但标为 pre-release：不占 Latest，也不影响「检测更新」。
   - 重跑幂等：已存在的 Release 会更新标题 / 正文并覆盖 APK。

### 5.2 构建脚本与参数

| 参数 | 作用 | 默认 |
|---|---|---|
| `-PappAbi=v7a\|v8a\|both` | 过滤打包的原生库 ABI（`abiFilters`）；`build-release.*` 依次传三档 | 缺省 `both` = `arm64-v8a` + `armeabi-v7a` |
| `-PversionSuffix=<7~40 位十六进制>` | 把当前提交短哈希拼到 `versionName` 末尾（形如 `1.9.0-1a2b3c4d5`）；非十六进制直接报错 | 无（手动构建不带） |
| `-Pumeng.enabled=false` | 统计 SDK 退化为 `compileOnly`，不打包；隐私弹窗与授权管理链路失效 | `true` |

- 脚本用 `git rev-parse --short=9 HEAD` 取短哈希并经 `-PversionSuffix` 传入；非 git 工作区只告警、不带后缀继续构建。
- `dist/` 产物文件名使用**基础版本号**（不带短哈希）：`MeaPet-v1.9.0-<ABI>.APK`。
- 签名证书来自根目录 `keystore.properties`（或 4 个 `KEYSTORE_*` 环境变量）；产物若为 `app-release-unsigned.apk` 脚本直接报错退出。
- 签名校验：对 `dist/*.APK` 逐个 `apksigner verify --verbose --print-certs`，要求输出含 `Verifies`，且**三档产物证书 SHA-256 一致**。本工程 APK 仅 v2 方案签名，按 `V<方案号> Signer: certificate SHA-256 digest:` 格式匹配。

### 5.3 产物命名与 ABI 差异

| 产物 | 含原生库 | Live2D 模型 | 适用设备 |
|---|---|---|---|
| `MeaPet-v1.9.0-v8a.APK` | `arm64-v8a` only | ✅ 正常 | 绝大多数现役 64 位设备（推荐主推） |
| `MeaPet-v1.9.0-both.APK` | `arm64-v8a` + `armeabi-v7a` | ✅ 64 位设备正常；仅 32 位设备降级 | 通用包，体积最大 |
| `MeaPet-v1.9.0-v7a.APK` | `armeabi-v7a` only | ❌ **必然降级** | 仅 Android 8 时代 32 位存量设备 |

- **v7a 缺模型的原因**：Live2D Cubism Core 官方 AAR 只提供 `arm64-v8a` / `x86` / `x86_64`，没有 `armeabi-v7a`；v7a 包内不存在 `libLive2DCubismCoreJNI.so`，启动时探测失败即进入降级（纯聊天 + 背景色 / 壁纸、无触摸语音、主界面隐藏「悬浮窗」入口）。见 [`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md)。
- 保留 v7a 是为兼容 Android 8 时代约三成仅 32 位的存量设备；ADR 已记录「后续考虑改 `arm64-v8a` only」的迁移条件。
- 体积基线：1.9.0 的 v8a **33.8 MB**（`MeaPet-v1.9.0-v8a.APK`）；参考 1.8.0 v8a 33.7 MB、both 51.8 MB。
- v7a 包在 64 位设备侧载时，系统可能仍选 32 位 ABI（`primaryCpuAbi=armeabi-v7a`），此时同样走降级，不会崩溃。

### 5.4 发布后核对

- [ ] GitHub Release 标题形如 `v1.9.0 / 2026.11.22`，正文由 `tools/release_meta.py` 生成。
- [ ] 三档 APK 都已上传；`dist/` 与 Release 资产一致。
- [ ] 「关于」页显示 `1.9.0-<短哈希>`，短哈希与 tag 提交一致。
- [ ] 「检测更新」在装着 1.9.0 的设备上不再提示更新（不误判）。

---

## 6. 回滚与升级注意

### 6.1 数据迁移：JSON → SQLite

- **一次性导入**：1.9.0 首次启动时 `LegacyJsonImporter` 把旧版 JSON 会话与记忆导入 SQLite（单事务、幂等、读回校验；标记 `meta.legacy_json_import` 与数据同事务）。**旧 JSON 文件原样保留，不删除、不移动、不改名**；导入失败下次启动重试。
- **历史长度限制**：旧 JSON 只含约 27~35 条消息，「完整历史」从升级后才开始累积 —— 发布说明需写明。
- **废弃字段**：历史 JSON 中已废弃的记忆操作附加字段在读取时忽略；开发期旧库里同名的 nullable 列保持不动、不读不写，**不做破坏性迁移**；新建库不含该列。
- **不支持降级（重要）**：SQLite 库版本 `user_version` 高于应用已知版本时**拒绝打开**（`Migrations` 的 `check(current <= CURRENT_VERSION)`，当前 `CURRENT_VERSION = 1`）。
- **回滚到 1.8.0 的后果**：1.8.0 没有 SQLite 存储路径，回滚后只能读到仍保留的旧 JSON（升级前的内容）；升级后产生的新对话只存在于 `meapet.db`，1.8.0 看不到。若要在回滚时保留新数据，需另行导出备份 `meapet.db` → **发布说明里建议升级前备份**。
- **备份建议**：1.9.0 起应用内提供「设置 → 数据与备份」，可直接导出密码加密的备份包（设置 / 对话 / 记忆 / 压缩状态 / 壁纸，API Key 可选），这是升级前备份的**推荐方式**（[PRD](2026-11-22-backup-export-import-prd.md)）。手工兜底仍是 `files/meapet_conversation.json`、`files/meapet_memories.json`、`databases/meapet.db`（+ `-wal` / `-shm`）。
- **备份包的适用范围**：应用内备份包是**跨版本互认的业务数据**（`format` / `schema` 两级校验），但 1.8.0 没有导入功能，回滚到 1.8.0 用不上它；仅用于 1.9.0 及以后的换机 / 重装恢复。

### 6.2 隐私政策 1.3 的重新确认

- 政策版本常量硬编码（`PRIVACY_POLICY_VERSION = "1.3"`，生效 2026-07-29 / 修订 2026-11-23），修订必须改代码文案并随代码提交，避免配置漏改导致老用户不重新确认。
- **仅含统计 SDK 的构建**会触发重新确认：`UMENG_ENABLED && getPrivacyVersionShown() != currentPrivacyVersion` → 弹窗并标记「隐私政策更新」。不含 SDK 的构建既不弹、也**不记录**已看版本号（换回含 SDK 构建时仍会弹）。
- 1.9.0 新增的「复用聊天 Key」开关改变了政策 §五描述的前提（默认开、可关闭），发布前需按 §1.2 复核文案；1.3 补充的「四、备份与导出」小节说明备份写到哪里、密码加密与不保存、Key 默认不进包、不上传不保留副本；2026-11-24 更正「工具结果是否存本机」的表述（会存，见「三、保存在本机的数据」），并新增「二、授权范围」明确「同意／不同意」只针对统计 SDK、继续使用即表示同意其余章节；版本号保持 1.3。
- 已看版本号记在 DataStore `privacy_version_shown`；授权状态另记 SharedPreferences（双存储是已知问题 #15，见 §4.3）。

### 6.3 版本号带短哈希对「检测更新」的影响

- 自动构建的 `versionName` 形如 `1.9.0-1a2b3c4d5`；`AppInfo.baseVersion()` 用 `[-+][0-9a-fA-F]{7,40}$` 剥离后缀后再比较，避免把数字开头的哈希当成额外版本段（旧实现会把 `1.9.0-1a2b3c4d5` 解析成 `1.9.0.1` 而误判需要更新）。
- 后缀只匹配 7~40 位十六进制，`1.9.0-beta1` 这类预发布标识不会被误剥离。
- `dist/` 产物名**不带**后缀，后缀只进 APK 内部的 `versionName`；从安装包反查提交看「关于」页或 `dumpsys package`。
- Release 的 tag 仍是干净的 `v*.0`；带后缀的 tag（`v1.9.0-beta1`）标为 pre-release，`/releases/latest` 不会取到它，因此不影响更新检查。
- 手动构建（`assembleDebug` / Android Studio）不带后缀，`versionName` 保持 `1.9.0`。

### 6.4 其他升级面

- **TTS**：1.9.0 未更换模型包（仍是 1.8.0 的 6 文件约 84MB）；从 1.7.x 升上来的用户仍会触发 `LegacyTtsModelCleanup` 三步（删旧模型目录 → 删旧 native 库 → 关闭两个语音开关），需要重新下载或本地 zip 导入。
- **覆盖安装**：`applicationId` 与签名不变，可覆盖安装。
- **旧数据不会被清空**：升级不会清空现有记忆或会话（只读化 / 忽略废弃字段，不删数据）。

---

## 7. 与 `TODO.md` 的核对结论（2026-11-24）

本文整理时逐条比对了 [`TODO.md`](TODO.md) 的描述与代码。**数字 / 行号漂移已当场改进 `TODO.md`**（下表留档说明改了什么），仍成立的条目不动：

| 条目 | 核对结果 | 处理 |
|---|---|---|
| `#21 saveIfChanged` | 实测 **6 处**（原写 5 处，漏了 1.9 新增的 `saveContextWindow`，`SettingsViewModel.kt:287`） | ✅ 已改 `TODO.md` |
| `#28 清死代码` | `ApiRequest` 仅测试调用的方法实测 **5 个**（原写 3 个），已列出方法名 | ✅ 已改 `TODO.md` |
| `#48 / #35 拆超长方法` | `MainActivity.onCreate` 实测 81–232 行约 **152 行**（原写 220 行）；文件共 485 行 | ✅ 已改 `TODO.md` |
| `#8 拆 Live2dDelegate` | 实测 **372 行**（原写 338 行） | ✅ 已改 `TODO.md` |
| `#23 文案抽 strings.xml` | `stringResource(` 仍为 0；「387 处」的统计口径未记录，无法复算（按双引号含中文字面量口径约 669 处） | ✅ 已加口径说明 |
| `#22 魔法数字` | 清单大部分仍成立；但 `SettingsViewModel` 的 `8` / `4` 现指 API Key 遮罩（`"****"` 长度 8、`take(4)` / `takeLast(4)`），与待办原意可能不是同一处 | ✅ 已加复核备注 |
| 待办 E「1.10 清理备胎代码」 | `MemoryRepository` 已无实例化点、`ConversationStore` 只剩无人读取的 `lazy` 属性 | ✅ 已补「可提前到 1.9.x 删除」 |
| 待办 D「日文数字 / 日期读法」 | 已实现（`rewriteJapaneseNumbers` + `JapaneseNumbersTest` 13 例） | ✅ 该节已标完成，只剩 §2.6 听感验收 |
| 待办 E「数据与备份真机验收」 | 单测实为 **87 例**（原写 85 例）；主链路已由 adb 自动化覆盖 | ✅ 已更新进度与剩余项 |
| **压缩摘要与保留窗口断层** | 新发现的行為缺陷（真机反馈），根因与修法已定位 | ✅ 已登记为 **G15**（`TODO.md` 待办 A） |
| 候选-1 / 待办 0 / `SEPARATE_USER` / TTS native 内存 | 复核后**均仍成立**，未被 1.9 改动影响 | 不动 |
| 447 / 455 / 550 等单测数字 | 当前为 **574** | ✅ 已在两篇 plan 文档回填 |
| PRD「10 个文件 / 9 个测试类」 | 实为 **10 个文件 / 10 个测试类**（`BackupImporterTest.kt` 含两个类） | ✅ 已改 PRD |
| PRD「默认文件名对齐参考版」 | 参考版是 `_MeaPet-Android.zip`，本项目是 `_MeaPet-backup.zip`，并非对齐 | ✅ 已改 PRD 与互通审计 |
| `2026-11-18-sqlite-context-plan.md` 记录「日文数字排入 1.9.0」 | 已实现，该记录已过时 | ✅ `TODO.md` 与本文已按实现更新 |

---

## 附：本文核对的命令

```powershell
git log --oneline -20
git status --short
git rev-list --left-right --count origin/main...dev   # 0  78（origin/main 落后 dev 78 个提交）
# 单测统计 / @Test 计数 / androidTest 计数：见 §3.1
node tools/check_doc_links.mjs
```
