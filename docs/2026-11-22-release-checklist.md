# 1.9.0 发版收尾与测试检查单

> **整理时间**：2026-11-22（工作区 `dev` 分支，HEAD `c6c223e`；汇总期间并行落地的 3 个提交 `f7eefa7` / `a693aa9` / `c6c223e` 已反映在本文中）
> **适用版本**：1.9.0（`versionCode = 15`，`versionName = "1.9.0"`，见 `app/build.gradle.kts`）
> **来源说明**：本文由 [`TODO.md`](TODO.md)（唯一在维护的待办清单）与各实现参考文档汇总而成，**不替代**它们：
> 待办的权威状态以 [`TODO.md`](TODO.md) 为准，已发布口径以 [`../CHANGELOG.md`](../CHANGELOG.md) 为准，第三方许可以 [`../NOTICE.md`](../NOTICE.md) 为准。
> 本文只做「发版收尾 + 验收」的可执行归集；逐条来源在各条目「来源」列中标出，无法从文档/代码确认的一律标「待确认」，不做推测性补全。
>
> 汇总时交叉核对的数据源：[`TODO.md`](TODO.md)、[`../CHANGELOG.md`](../CHANGELOG.md) 的 `[1.9.0]` 段、[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §5、[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M3 / §6、[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §「测试与待验证项」、[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md)、[`../NOTICE.md`](../NOTICE.md) §「Pending confirmation」、[`legacy-compat.md`](legacy-compat.md)、`git log --oneline -20` 与 `git status`。

---

## 1. 发版前必须完成（阻塞发版）

### 1.1 真机验收类

> 容器内无设备，以下全部必须在真机上执行；详细步骤见 §2。

- [ ] **1.9 整体真机验收** —— 覆盖连续回复 / 插队 / 停止、工具消息落库后重启续聊、记忆四档、只读回退、摘要触发范围、turn 超时、压缩分隔条与手动压缩、向上懒加载锚定。
  - 来源：[`TODO.md`](TODO.md) 待办 E 第 1 条
  - 判据：§2 全部条目勾选完成；异常项附机型 / SoC / Android 版本 / `ro.product.cpu.abilist` 与 `logcat` 片段。
- [ ] **联网工具真机验收** —— DeepSeek 官方 Key、AnySearch 匿名与 token、搜索后读取正文、设置切换、限流与额度提示、折叠 / 帮助与自动切换等待行为。
  - 来源：[`TODO.md`](TODO.md) 待办 E；[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §「测试与待验证项」
  - 判据：§2.3 全部条目勾选完成；失败时确认返回固定错误信封而非崩溃 / 卡死。
- [ ] **32 位 ARM 降级真机验收（v7a 包）** —— 确认 v7a 包不再启动闪退，降级为「纯聊天 + 背景色/壁纸」。
  - 来源：[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md) §后果；[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Added「32 位 ARM 设备降级」
  - 判据：§2.7 的降级条目全部勾选；`logcat -s Live2dSupport` 出现 `NOT available for this ABI` 且无 `UnsatisfiedLinkError` 崩溃。
- [ ] **上下文压缩 / 容量探测真机验收** —— 向上懒加载滚动锚定、WAL 行为、工具消息往返、插队/停止、压缩分隔条。
  - 来源：[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M3「[待做] 真机验证」、§6 风险与未核实项
  - 判据：§2.4 全部条目勾选；`PRAGMA journal_mode` 为 `wal`（`adb shell` 检查库文件）。
- [ ] **v7a 包在 64 位设备上的 ABI 选择** —— 确认系统选中 32 位 ABI 时走降级而非崩溃（ADR 记录了此前 100% 闪退的路径）。
  - 来源：[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md) §背景
  - 判据：`adb shell dumpsys package com.meapet.mobile | grep primaryCpuAbi` 显示 `armeabi-v7a` 时应用可正常启动、可聊天。

### 1.2 合规类

- [ ] **NOTICE「Pending confirmation」8 项逐条确认**（未确认前不得宣称这些资源是 MIT/Apache-2.0）。
  - 来源：[`../NOTICE.md`](../NOTICE.md) §「Pending confirmation (待确认)」1–8
  - 判据：每一项都有结论（确认 / 换成可再分发资源 / 从 APK 移除）并回写 `NOTICE.md`；子项：
    - [ ] 1. 触摸语音素材 `app/src/main/assets/voice/**`（21 个 Opus，无来源/作者/许可记录）
    - [ ] 2. Mea Live2D 模型资源（B 站社区作品，无明确许可；另含未被 `mea.model3.json` 引用的 `橙色猫猫.can3`）
    - [ ] 3. `ic_menu_compact.xml`（MDI 风格图标，未登记在 `tools/icons.txt`）
    - [ ] 4. JLaTeXMath-Android 的 GPL-2.0+ / classpath exception 是否覆盖本 Android 分支（唯一 copyleft 组件）
    - [ ] 5. Umeng SDK 的 POM「Apache-2.0」与专有条款矛盾
    - [ ] 6. Live2D Cubism SDK / Cubism Core 版本未记录（Core AAR 被 git 忽略且无版本串）
    - [ ] 7. `jlm_special.ttf` 未列入上游字体许可清单
    - [ ] 8. Mea VITS2 模型与语音训练数据的权利归属（`tts-model-v1.zip` 在仓库外分发）
- [ ] **隐私政策 1.3 与实现一致性复核（含新增「备份与导出」小节与「复用聊天 Key」开关）**
  - 来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Changed「隐私政策补充「备份与导出」说明」；`app/src/main/java/com/meapet/mobile/ui/component/PrivacyPolicyContent.kt` §五（第 95 / 200 行）；commit `a693aa9` `change(search): DeepSeek Key 复用改为开关控制，默认开启`
  - 背景：政策 §五现写「DeepSeek 独立 Key 留空、且『API 配置』使用 DeepSeek 官方地址时，会复用其中的聊天 Key」（无条件表述），而 `a693aa9` 已引入**用户可关闭**的复用开关 `web_search_reuse_chat_key`（默认开）。开关关闭后政策这句描述与实际行为不再一致，且政策未告知用户「复用可在设置中关闭」。
  - 判据：政策 §五（两份全文各一处）与设置帮助在「可关闭复用」这一前提下仍然准确——补一句「可在设置中关闭复用」，或明确说明关闭后需单独填写 Key；新增的「三、备份与导出」小节与实际行为一致（导出需用户主动发起、AES-256 密码加密、Key 默认不进包、不上传不保留副本、导入按类别整体覆盖）；`CHANGELOG.md` [1.9.0] 同步登记该开关与备份功能。
- [ ] **统计 SDK 构建的隐私重新确认路径** —— 含 SDK 的构建中老用户会因政策版本 1.3 变化（相对 1.8.0 的 1.2）重新确认一次。
  - 来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Changed「隐私政策补充「备份与导出」说明」；`ui/MainActivity.kt` 的 `UMENG_ENABLED && getPrivacyVersionShown() != currentPrivacyVersion` 分支
  - 判据：发布构建用到的 `UMENG_APP_KEY` secret 已配置（否则 `umeng.enabled` 虽为 true 但 AppKey 为空，统计与隐私弹窗链路与预期不符——CI 只打 warning）；升级安装后老用户确实看到一次政策更新弹窗。
- [ ] **`NOTICE.md` 版本 / 依赖清单与本版一致** —— 1.9.0 新增 AndroidX SQLite（`androidx.sqlite` 2.7.1）等依赖已声明，`NOTICE.md` 审计基准写的是 `dist/MeaPet-v1.8.0-v8a.APK`。
  - 来源：[`../NOTICE.md`](../NOTICE.md) 第 213–221 行的审计说明
  - 判据：按 1.9.0 release APK 复核一遍「Bundled in the APK」结论，或明确标注该节仍是 1.8.0 口径（待确认）。

### 1.3 构建配置类

- [ ] **版本号锁定** —— `versionCode = 15`、`versionName = "1.9.0"`（后者的引号内字面量是 `tools/build-release.*` 与 `tools/release_meta.py` 的正则解析锚点，不可改成变量）。
  - 来源：`app/build.gradle.kts`（versionCode/versionName 附近注释）；[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Notes
  - 判据：`grep -n 'versionCode\|versionName' app/build.gradle.kts` 结果与 tag 一致；`versionCode` 相对 1.8.0 已递增（14 → 15，已满足）。
- [ ] **工作区干净、改动已提交** —— 汇总期间工作区的改动已被并行提交（`f7eefa7` 32 位降级、`a693aa9` 复用 Key 开关、`c6c223e` 备份 PRD/LLD）；发版前仍需确认没有新的未提交改动。
  - 来源：`git status --short`（本次核对末尾仅剩本文一个 untracked 文件）
  - 判据：`git status --short` 为空或只剩本文档；`gradle.properties` / `local.properties` / `gradlew` 不在暂存区（[`TODO.md`](TODO.md)「验证要求与提交约定」）。
- [ ] **dev → main 合并** —— 发布 workflow 要求 tag 提交在 `main` 上（`--require-main`）。
  - 来源：`.github/workflows/release.yml`「校验 tag（须在 main 上、与 versionName 一致）」；`git rev-list` 显示 `origin/main` 落后 `dev` **60** 个提交
  - 判据：`main` 已包含 `dev` 全部提交，且 `git tag v1.9.0` 指向 `main` 上的该提交。
- [ ] **全量单测通过（本版基线 554 条）** —— 见 §3。
  - 来源：[`../README.md`](../README.md)「发布」；`.github/workflows/ci.yml`
  - 判据：`./gradlew :app:testDebugUnitTest --stacktrace` 成功，`app/build/reports/tests/testDebugUnitTest/` 显示 554 tests / 0 failed。
- [ ] **debug / release 构建各通过一次** —— 每个待办项都要能独立编过（bisect safe）。
  - 来源：[`TODO.md`](TODO.md)「验证要求与提交约定」
  - 判据：`./gradlew :app:assembleDebug` 与 `./gradlew :app:assembleRelease -PappAbi=v8a` 均成功。
- [ ] **三档 ABI 产物齐全且签名一致** —— `dist/` 应有 `MeaPet-v1.9.0-{v7a,v8a,both}.APK`（当前 `dist/` 只有 1.7.2 与 1.8.0 的包）。
  - 来源：`tools/build-release.ps1` / `.sh`（`-Abis` 默认 `v7a,v8a,both`）；`.github/workflows/release.yml`「校验 APK 签名」
  - 判据：三个文件都在且签名证书 SHA-256 摘要一致；`v7a` 包体积明显小于 `v8a`（缺 Cubism Core so）。
- [ ] **`CHANGELOG.md` 定稿** —— `## [1.9.0] - 未发布（dev 分支）` 改为实际发布日；补登 `a693aa9` 新增的「复用聊天 Key 开关」条目（32 位降级条目已在 `f7eefa7` 补入）。
  - 来源：[`../CHANGELOG.md`](../CHANGELOG.md) 第 15 行；`git show --stat a693aa9`（该提交未改 `CHANGELOG.md`）
  - 判据：[1.9.0] 段无「未发布」字样；本版所有用户可见改动都在段内；日期与 tag 日期一致。
- [ ] **文档链接检查通过** —— `node tools/check_doc_links.mjs`。
  - 来源：[`README.md`](README.md) §5 维护规则第 6 条
  - 判据：退出码 0，输出「链接检查通过（N 个 md）」。
- [ ] **把本文登记进 [`README.md`](README.md) §2.4「活跃实现参考」**（按该页维护规则，活跃文档需在索引里可查；本文整理时按要求只写了本文件，未改 `docs/README.md`）。
  - 来源：[`README.md`](README.md) §2.4 / §5 第 1、2 条
  - 判据：`docs/README.md` §2.4 表格出现本文一行；链接检查仍退出码 0。

---

## 2. 功能真机验收清单

> 执行前置：安装对应档位 APK（`adb install -r dist/MeaPet-v1.9.0-v8a.APK` 或 debug 包），配置一个支持 function calling 的聊天端点（如 DeepSeek 官方），并在 `设置 → 语音` 让模型就绪（约 84MB，可在线下载或本地 zip 导入）。
> 记录口径：每条勾选时注明机型 / SoC / Android 版本 / `ro.product.cpu.abilist`；异常附 `logcat` 片段。

### 2.1 对话与工具

- [ ] **首条回复前的等待态**
  - 步骤：发一条需要较长生成的消息；在回复出现前观察输入栏与列表中央。
  - 预期：输入栏禁用并显示「Mea 正在思考...」；此时不应出现底部状态条（实际文案「Mea 还在说…」，`ChatScreen.kt`；CHANGELOG 简写为「Mea 还在…」）（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Fixed「首条回复等待与状态条时机」）。
- [ ] **连续回复分段显示 + 逐条朗读**
  - 步骤：开启主界面语音，发一条会触发多轮工具调用的问题（如「先搜一下 X 再总结」并开启联网）。
  - 预期：工具调用期间助手片段逐条出现（不是等全部结束才一次性出），每条片段都朗读；最终回复接在片段之后。
- [ ] **状态条与「停止」**
  - 步骤：首条回复出现后观察底部状态条；点「停止」。
  - 预期：状态条文案为「Mea 还在说…」，带「停止」；点击后当前轮次中止，**已显示的片段保留**，输入栏恢复可用，不出现错误卡片。
- [ ] **插队发送（首条回复后）**
  - 步骤：首条回复出现后，输入新消息并立即发送。
  - 预期：旧轮次被取消并开始新一轮；旧轮次已展示的片段留在列表，不重复、不丢；历史里旧轮按已完成部分落库（重启后仍在）。
- [ ] **等待首条回复期间禁止插队**
  - 步骤：发消息后在「Mea 正在思考...」期间尝试输入。
  - 预期：输入栏禁用，无法插队（此阶段插队无意义，来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Added「连续回复与中断控制」）。
- [ ] **工具轮次上限（12 轮）**
  - 步骤：用一个容易让模型反复调工具的提示，直到提示「x/12」出现在工具结果里。
  - 预期：达到 12 轮后工具调用**不再执行**，模型收到「已达上限」提示并用文字收尾；轮次计数不写进会话历史；极端情况下才整轮失败（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Added「工具轮次上限」、`chat/tools/ChatToolLoop.kt` 的 `MAX_TOOL_ROUNDS = 12` / `MAX_TOOL_GRACE_ROUNDS = 2`）。
- [ ] **请求总超时（180 秒）**
  - 步骤：切到极慢网络（或飞行模式中间切换）发起带工具轮次的请求，等待超过 180 秒。
  - 预期：提示「请求超时（180 秒），请检查网络后重试」；该轮回滚（无残留 user/assistant 行）；不会无限转圈（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Added「请求总超时」；`AppConfig.turnTimeoutMs = 180_000`）。
- [ ] **工具消息落库后重启续聊**
  - 步骤：完成一轮包含工具调用的对话 → 杀进程 → 重开 → 继续同一话题。
  - 预期：历史里工具轮次完整（assistant `tool_calls` + `role=tool` 行不丢、不乱序）；续聊时端点支持工具则回放结构化历史，不支持则过滤后纯文本发送（来源：[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §4；[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Added「上下文管理」）。
- [ ] **失败 / 取消回滚**
  - 步骤：用错误 API Key 发一条消息；再用正确 Key 发一条并在中途「停止」。
  - 预期：失败轮整轮回滚、不写历史、错误卡片可重试；停止轮已展示片段保留并落库，不产生无 user 的孤儿 assistant 行（已知残留风险见 §4 G3）。
- [ ] **重试整轮**
  - 步骤：对失败的 user 消息点重试。
  - 预期：该 user 及其后（旧轮的片段 / tool 残留）一起移除后重发，不出现重复气泡或重复历史行。

### 2.2 记忆

- [ ] **四种检索方式（自动 / 工具 / 只读 / 关闭）**
  - 步骤：`设置 → 对话 → 记忆检索方式` 依次切换四档，每档各发 2~3 轮相关话题。
  - 预期：自动/工具由模型按需调用记忆工具（端点支持时）；端点不支持时两者都退回只读关键词注入；只读只注入、不改记忆；关闭既不注入也不检索，**已保存记忆仍在**（来源：[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §1）。
- [ ] **记忆总开关**
  - 步骤：关闭「启用记忆」，再发消息；随后在一轮工具调用中途关闭总开关。
  - 预期：关闭后不提供记忆工具、不注入记忆上下文；在途轮次的后续记忆操作不执行、暂存变更不提交（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Fixed「记忆总开关」）。
- [ ] **工具写入：创建 / 更新 / 删除 + 撤销**
  - 步骤：让 Mea 记住一件事（`create_memory`）→ 查看看记忆 → 让 Mea 修改它（`update_memory`）→ 删除一条 → 底部撤销条出现后点撤销。
  - 预期：写入按轮次暂存，轮次成功后才落库；失败/取消/超时不落库；删除后撤销可恢复原条目（撤回内容与删除前一致）。
- [ ] **永久类记忆删除确认护栏**
  - 步骤：让 Mea 删除一条 `FACTUAL` / `CORE_TRAIT` 记忆。
  - 预期：需要用户明确要求（`confirm_permanent` + `reason`）才执行；模型把永久类**改类型**被拒（「事实/特质类记忆的类型不能修改」），不能靠「先降级为短期再删」绕过（来源：`memory/tools/MemoryToolExecutor.kt`；commit `bbfbf82`）。
- [ ] **只读模式无记忆内容变更**
  - 步骤：切到「只读」，聊几轮、让模型尝试「记住这件事」。
  - 预期：记忆条目数与内容不变；不触发自动摘要；只读命中不更新访问计数/时间；注入内容不写回会话历史（来源：[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §2；[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Fixed「记忆上下文访问记录」）。
- [ ] **端点拒绝工具后的只读回退**
  - 步骤：换一个不支持 function calling 的端点（或模型），发一条消息。
  - 预期：仅在该端点**明确拒绝工具能力**时回退为只读关键词注入并记录负缓存；普通 HTTP 错误不据此禁用工具；换 API 地址/Key/模型后下一轮重新尝试工具，无需重启（来源：[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §5；[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Fixed「工具能力缓存与模型切换」）。
- [ ] **自动摘要只在工具模式成功轮次触发**
  - 步骤：开启「自动摘要」、摘要轮次设小（如 3），分别在工具 / 只读 / 关闭 / 仅联网四种情况各聊够轮数。
  - 预期：只有「记忆工具模式且本轮成功走完工具循环」才推进摘要并生成长期记忆；只读 / 关闭 / 只读回退 / 仅联网轮次零记忆副作用（来源：[`2026-11-18-web-search-plan.md`](2026-11-19-web-search-plan.md) §S4 第 4 条；[`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) §3）。

### 2.3 联网搜索

- [ ] **默认关闭 + 独立于记忆设置**
  - 步骤：新装后确认联网默认关闭；然后测试「记忆关闭 × 联网开」「记忆只读 × 联网开」两种组合各发一条需要搜索的问题。
  - 预期：默认关闭时不提供 `web_search`/`web_fetch`；只要联网开且端点支持 function calling，二者即可用，与记忆模式、记忆总开关无关；只读 + 联网仍保留关键词注入（来源：[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §当前范围、§S4）。
- [ ] **DeepSeek：独立 Key 与「复用聊天 Key」开关**
  - 步骤：`设置 → 提供商 → 联网搜索`，服务商选 DeepSeek。①开启「复用『API 配置』中的 Key」（默认）且独立 Key 留空；②关闭复用且独立 Key 留空；③关闭复用时单独填 DeepSeek Key。
  - 预期：①聊天地址是官方 DeepSeek HTTPS 时复用聊天 Key 成功搜索；②关闭复用后不回落聊天 Key，工具返回 `not_configured` 且不发请求，设置页出现必填的 DeepSeek Key 输入框；③用独立 Key 正常搜索（来源：commit `a693aa9`；`search/WebToolFactory.kt`、`search/DeepSeekWebProvider.shouldReuseChatKey`、`WebToolFactoryTest`）。
- [ ] **AnySearch：匿名与 token**
  - 步骤：服务商选 AnySearch，Key 留空搜索一次；再填入有效 token 搜索一次；最后填无效 token 搜索一次。
  - 预期：留空走匿名模式（不带 Authorization）；有效 token 走 Bearer；鉴权失败**不回落匿名**，返回 `unauthorized` 固定信封（来源：[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §适配器与配置）。
- [ ] **搜索后读取正文（web_fetch）**
  - 步骤：让 Mea「搜一下 X 并读第一篇的内容再总结」。
  - 预期：`web_search` 返回标题/完整 URL/摘要，随后 `web_fetch` 返回 `title`/`content`（正文最多 6000 字符，整体 JSON ≤8000）；`truncated` 只表示应用侧截断。
- [ ] **Auto 服务商切换**
  - 步骤：服务商选「自动」，让第一家不可用（填错 Key 或断掉该域名），观察是否切到下一家；再验证显式选择时**不切换**。
  - 预期：Auto 每次调用从候选列表头开始，失败/超时按顺序尝试下一家，单次工具总预算 15 秒（两家时每家 7375ms）；显式选择不可用时返回 `not_configured` 不切换；参数错误与用户取消不触发切换（来源：[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §错误、延迟与隐私）。
- [ ] **限流与额度提示**
  - 步骤：用会触发 429 / 额度不足的 Key 连续搜索。
  - 预期：返回 `rate_limited` / `quota_exceeded` 固定信封并在对话里有可读提示；单家适配器仅对 429/5xx 做一次约 800ms 退避重试；服务商错误正文不解析、不入模型上下文、不进日志。
- [ ] **设置页折叠 / 帮助 / 持久化**
  - 步骤：关闭联网开关观察折叠动画；逐个点开问号帮助；杀进程重开检查配置。
  - 预期：关闭时服务商与 Key 配置垂直动画收起、页底保留精简提示；帮助覆盖选择规则/凭据/隐私/计费；`web_search_enabled`、`web_search_provider`、两个 Key、复用开关均持久化。
- [ ] **日志与隐私**
  - 步骤：`adb logcat` 抓一次搜索全过程。
  - 预期：不出现查询词、网页正文、API Key；请求无 body 日志（来源：[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §错误、延迟与隐私）。

### 2.4 上下文与历史

- [ ] **首屏定位与开屏动画**
  - 步骤：用已有 300+ 条历史的设备冷启动进聊天页。
  - 预期：一次性无动画定位到最新消息，停顿在底部；定位完成前列表透明，无「先滚到半途再跳」的闪动（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Changed「首屏定位与开屏动画」）。
- [ ] **向上懒加载与滚动锚定（工具模式下尤其要验）**
  - 步骤：工具模式聊 20+ 轮（每轮都会落 tool 行与空 assistant 行）→ 向上反复翻页到顶。
  - 预期：每页都能加载更早历史直到顶部（不因「过滤后可见条数不足页大小」而永远停在首屏——该 bug 已在 `bbfbf82` 修复）；页与页交界处滚动位置不跳。
- [ ] **按天时间分隔条**
  - 步骤：构造跨自然日的消息（或改设备日期），向上翻页跨越页交界。
  - 预期：相邻可见消息跨天时插入「今天 / 昨天 / M月D日」（跨年带年份）；页交界处重新判断，不重复、不遗漏（来源：[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M3「按天轻时间分隔条」）。
- [ ] **压缩提醒「整理一下」只弹一次**
  - 步骤：把上下文聊到超过 64K 估算 token（`COMPACT_HINT_TOKENS`）。
  - 预期：弹出一次「我们聊了好久啦，要不要让 Mea 把前面的内容整理一下喵？」+ 动作「整理一下」；忽略后不重复弹；压缩完成或清除对话后重置（来源：[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M5）。
- [ ] **顶部菜单「压缩上下文」按条件出现**
  - 步骤：上下文未到阈值时打开顶部菜单；越过阈值后再开。
  - 预期：平时不显示该菜单项；越过阈值才出现；回落到阈值以下隐藏；与 Snackbar 动作走同一入口（`ChatService.compactContext`）。
- [ ] **手动压缩全流程**
  - 步骤：点「整理一下」触发压缩，观察过程与结果。
  - 预期：压缩中显示「Mea 还在整理前面的内容…」并禁用入口、不阻塞输入；完成后在已折叠内容之后显示「早些时候」分隔条；提示「整理好啦，前面的 N 条已经收进摘要喵」；失败给出可读原因、不重试风暴。
- [ ] **自动压缩兜底（90%）**
  - 步骤：把有效预算压到 90% 以上（可手动把上下文窗口设小）。
  - 预期：仅硬触发、无任何提示；失败退化为仅在发送窗口内按轮丢弃（库里不删）；小窗口（预算 < 6000）跳过 LLM 摘要直接截断（来源：[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M5）。
- [ ] **上下文窗口手动值 / 重新检测**
  - 步骤：填入 200000 保存；填入 500 保存；点「重新检测」。
  - 预期：合法值生效并显示来源「手动」；下方文案说明实际约为填写值的 85%；**非法值（<1024）当前会被静默当成自动**（已知问题见 §4 G11，验收时按现状记录）；「重新检测」清除手动值并重新探测，探测失败回退兜底值且不报错（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Added「上下文窗口『重新检测』」）。
- [ ] **清除对话 + 撤销**
  - 步骤：清除对话，在 5 秒撤销条内点撤销。
  - 预期：清除后消息列表整块重建（长列表不闪退）；撤销后历史完整恢复；**压缩摘要是否恢复/作废属已知残留（见 §4 G2），按现状记录**。
- [ ] **进后台补写不丢历史**
  - 步骤：发几条消息后立刻按 Home 进后台，再回前台 / 杀进程重开。
  - 预期：最新消息不丢（`onStop` 有一次 IO 线程补写）（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.8.0] Fixed「进后台可能丢失最新会话历史」）。

### 2.5 界面与外观

- [ ] **消息视口铺满 + 气泡可滚到输入栏后**
  - 步骤：长对话滚动到最底部与最顶部。
  - 预期：列表不在输入栏上方被裁掉，气泡可滚到悬浮输入栏后面（越界回弹为系统默认拉伸）；底部留白按底栏实测高度自动变化。
- [ ] **状态条不遮最后一条**
  - 步骤：触发「Mea 还在说…」状态条。
  - 预期：列表同步上移而非强制回到底部，最后一条不被状态条遮住（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Changed「消息列表视口铺满」）。
- [ ] **气泡透明度联动**
  - 步骤：把「聊天气泡透明度」从 100% 调到 20%。
  - 预期：用户/助手/系统气泡、输入胶囊、底部状态条（「Mea 还在说…」）一起变化；状态条额外保留 0.9 底数，设置 1.0 时约 90% 不透明。
- [ ] **背景壁纸与虚化**
  - 步骤：从相册选图；拖动「背景虚化强度」0→100%；恢复默认。
  - 预期：实时生效、缩略图预览同步、仅主界面生效（悬浮窗保持透明）；恢复默认回到主题纯色；壁纸在切主题 / Activity 重建后仍正确。
- [ ] **主题与动态取色**
  - 步骤：切换跟随系统 / 浅色 / 深色；开关系统动态颜色；逐个切预设。
  - 预期：窗口背景跟随主题（不再露旧主题底色）；浅色预设对比度达标（12 套已验证，来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.7.0]）；切换后状态条/输入胶囊透明度仍正确。
- [ ] **关于页版本号与构建时间**
  - 步骤：用**自动构建产物**（`tools/build-release.*`）安装，进「设置 → 关于」。
  - 预期：版本号形如 `1.9.0-1a2b3c4d5`（9 位短哈希）；版本号下方按设备当前时区显示构建时间；手动构建（`assembleDebug`）不带短哈希。
- [ ] **顶部菜单项条件显示**
  - 步骤：在正常设备（v8a）与降级设备（v7a）分别打开主界面顶部菜单。
  - 预期：两处都显示「清除对话 / 查看记忆」；「压缩上下文」仅在上下文越过阈值时出现；「悬浮窗」入口仅在 Live2D 可用时出现（v7a 降级时隐藏）。
- [ ] **上下文压缩对话框/提示不误关其它状态**
  - 步骤：在更新提示或记忆弹窗展示期间触发清除对话 / 压缩提示。
  - 预期：只重置对话相关字段，`updateNotice` / `memoryDialog` 不被连带清空（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.7.2] Fixed「清空对话不再误关更新提示与记忆弹窗」）。

### 2.6 TTS 语音

- [ ] **模型就绪链路**
  - 步骤：新装进「设置 → 语音」；在线下载（可中途断网验证断点续传）；删除模型；用 `tts-model-v1.zip` 本地导入。
  - 预期：状态卡显示 6 个文件约 84MB；下载/导入完成后自动置就绪；删除释放空间并同时关闭两个语音开关；未就绪时发声开关置灰。
- [ ] **主界面 / 悬浮窗语音独立开关**
  - 步骤：分别开关两个语音开关后各发一条消息。
  - 预期：只朗读对应来源的回复；两处同时发声时不互相打断出错。
- [ ] **语速**
  - 步骤：语速从 1.0x 调到 0.5x / 2.0x。
  - 预期：速度变化但不走音、不变调（播放采样率跟随引擎输出）。
- [ ] **流式逐句播放与打断**
  - 步骤：让 Mea 回一段多句长文本；在朗读中途发送新消息。
  - 预期：首句合成完即出声（不等整段）；新消息到来立即中止剩余合成与播放，不出现旧回复盖过新回复；句间可有轻微停顿（预期代价）。
- [ ] **短片段有声**
  - 步骤：连续触发 0.6~1.5 秒的极短回复（如「好的」「嗯嗯」）。
  - 预期：正常出声、不静音、喇叭状态按时熄灭（来源：[`../CHANGELOG.md`](../CHANGELOG.md) [1.8.0] Changed「短片段（≤2 秒）播放前补齐 AudioTrack 缓冲」）。
- [ ] **中英日混排与数字**
  - 步骤：Debug 构建用「试听文本（Debug）」输入中英日混排 + 数字 + 电话 + 日期样本。
  - 预期：英文句中数字读英文数词（`3`→three）；中文/日文数字走 `ruleFsts`；**日文句中的数字仍读中文数词（`15 時`→shí wǔ shí）属已知边界，本条只记录不判失败**（见 §4）。
- [ ] **Release 包不含试听入口**
  - 步骤：用 release 包安装，进「设置 → 语音」。
  - 预期：「试听文本（Debug）」入口不显示（`VoiceSettings.kt` 按 debug 门控）。

### 2.7 Live2D 与 32 位降级

- [ ] **v8a / both 包：模型功能正常**
  - 步骤：用 `MeaPet-v1.9.0-v8a.APK` 安装启动。
  - 预期：模型正常渲染；拖拽跟随、分区触摸语音、悬浮窗（含悬浮菜单四项）均可用；背景壁纸与虚化正常。
- [ ] **v8a：透明度与冷启动回归**
  - 步骤：调低悬浮窗透明度；冷启动主界面与悬浮窗。
  - 预期：整只模型透明度均匀（嘴部/腮红不偏实）；悬浮窗冷启动配色正确（等设置快照预热后再建窗）。
- [ ] **v7a 包：降级模式启动不崩溃**
  - 步骤：`adb install -r MeaPet-v1.9.0-v7a.APK` 后冷启动。
  - 预期：不闪退；无桌宠模型；背景退回纯色/壁纸；`logcat -s Live2dSupport` 出现 `Live2D native core NOT available for this ABI`；`logcat -b crash` 无 `UnsatisfiedLinkError`（来源：[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md) §决策）。
- [ ] **v7a：降级模式的功能边界**
  - 步骤：在降级设备上尝试触摸人物、打开顶部菜单、尝试进入设置里的悬浮窗相关项。
  - 预期：无触摸跟随/分区语音（没有模型就没有可点区域）；顶部菜单**不显示「悬浮窗」入口**；聊天、记忆、联网、TTS 全部正常（`FloatingLive2dService` 即使被外部拉起也会 `stopSelf()`，不留透明僵尸窗）。
- [ ] **v7a 包在 64 位设备上（系统选 32 位 ABI）**
  - 步骤：64 位设备侧载 v7a 包，`dumpsys package` 确认 `primaryCpuAbi=armeabi-v7a`。
  - 预期：同上降级行为，不崩溃（ADR 记录的旧行为是启动即崩）。
- [ ] **壁纸层在降级模式仍生效**
  - 步骤：v7a 设备上设置壁纸 + 虚化。
  - 预期：壁纸与模糊正常（壁纸模糊是不依赖 Cubism 的普通 GLES20 路径）。

### 2.8 设置与隐私

- [ ] **设置根页六项摘要**
  - 步骤：进「设置」根页逐项看摘要。
  - 预期：提供商（模型名 + 联网开/关）、对话（System Prompt、记忆开/关）、外观（主题 · 取色）、语音（发声开/关）、数据与备份（导出与导入设置、对话、记忆）、关于（版本 N）都与实际配置一致。
- [ ] **数据与备份（导出 / 导入）**
  - 步骤：`设置 → 数据与备份`，全类别导出（含与不含 API Key 各一次）；清空/改动部分数据后按类别导入；再试错误密码、版本不符的包、系统面板取消、大壁纸。
  - 预期：导出包能被正确密码解开、错误密码提示「密码错误或文件已损坏」且不泄露包内信息；未勾选类别不进包、导入不改动未勾选类别；导入按类别整体覆盖且逐项点名确认；**对话历史与记忆库在确认弹窗里显示「本机条数 → 包内条数」且与实际一致**；取消面板回执「已取消，没有写出/改动任何文件」；文本条目超 64 MiB 被拒、二进制壁纸不触发上限；导入后界面历史与下一轮模型的上下文都能读到新数据；进页时清扫 `cacheDir/backup` 残留。用例全集见 [PRD §8](2026-11-22-backup-export-import-prd.md)。
- [ ] **隐私政策 1.3 两版全文**
  - 步骤：分别在含统计 SDK 与不含 SDK（`-Pumeng.enabled=false`）的构建里打开 `设置 → 关于 → 隐私政策`。
  - 预期：两版各自成篇；含 SDK 版说明友盟采集范围与授权管理，无 SDK 版声明不采集；头部显示「版本：1.3 / 生效时间 2026-07-29 / 修订时间 2026-11-23」；联网搜索、AI 对话与备份导出数据流向章节完整；章节连号无重号。
- [ ] **首次启动弹窗组合**
  - 步骤：清数据后冷启动（含 SDK 构建）。
  - 预期：先弹隐私授权，再弹「是否启用检查更新」；同意后统计 SDK 才初始化；不含 SDK 的构建**不弹**隐私弹窗且不记录已看版本号。
- [ ] **老用户升级后的政策重新确认**
  - 步骤：装 1.8.0（政策 1.2）→ 覆盖安装 1.9.0（政策 1.3）。
  - 预期：因版本号变化重新弹一次隐私政策，副标题提示「隐私政策更新」；「是否启用检查更新」不再重复询问（与政策版本号解耦）。
- [ ] **取消统计授权**
  - 步骤：`设置 → 关于 → 隐私与数据 → 取消数据采集授权`。
  - 预期：确认后立即退出应用；重启后不初始化统计 SDK、不再上报；重新同意可恢复。
- [ ] **设置帮助文案（新增参数问号）**
  - 步骤：逐个点开上下文窗口、最大 Token、记忆检索方式、摘要间隔、联网搜索的问号。
  - 预期：上下文窗口说明「手动值同样乘 0.85」；联网帮助说明「联网与记忆相互独立」「Auto 会改试下一家及其隐私/计费影响」「复用聊天 Key 的条件与关闭后的必填要求」。
- [ ] **导出日志**
  - 步骤：`设置 → 关于 → 导出日志`；构造一次 native 崩溃后再次导出。
  - 预期：导出含设备/版本头、tombstone、logcat；tombstone 字节未损坏（无 U+FFFD 特征）；导出有超时不无限阻塞。

---

## 3. 自动化验证基线

### 3.1 当前单测数量（2026-11-23 自行统计）

统计口径：`app/src/test` 下全部 `*.kt` 文件中行首 `@Test` 注解计数（命令见下）。

| 指标 | 数值 |
|---|---|
| `app/src/test` 下 `*.kt` 文件 | 75（其中 `FakeHttpClientEngine.kt`、`storage/JdbcSQLiteDriver.kt`、`backup/BackupTestFakes.kt` 是测试辅助，不含用例） |
| 含用例的测试文件 / 测试类 | 74 个 `*Test` 测试类（`MarkdownTableColumnsTest.kt` 一个文件里有 2 个类；`backup/BackupImporterTest.kt` 内含 `BackupImporterTest` 与 `BackupCollectorTest` 两个类） |
| **`@Test` 总数（当前 `dev` HEAD 含备份功能）** | **554** |
| 其中数据与备份新增 | 85（`app/src/test/java/com/meapet/mobile/backup/` 下 10 个测试类，含全链路 `BackupRoundTripTest` 与条数对比 `BackupCounterTest`） |
| 更早的基线（`c6c223e`） | 455 |
| `app/src/androidTest` | 3 个文件 / 5 个 `@Test`（instrumented，需真机） |
| `@Ignore` | 0 |

> 文档里出现的旧数字 **447 / 455**（[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §1、[`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §测试与待验证项）**已过时**：分别对应 `167e6bd` 与 `c6c223e` 时的数量，当前为 **554**。以全量跑完后的报告数字为准。

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
# 1) 全量 JVM 单测（CI 同款，含 --stacktrace）
./gradlew :app:testDebugUnitTest --stacktrace

# 2) Debug 构建（每个待办项都要能独立编过）
./gradlew assembleDebug

# 3) 无统计 SDK 的 Debug 构建（验证 UMENG_ENABLED 门控链路）
./gradlew :app:assembleDebug -Pumeng.enabled=false

# 4) Release 构建（单 ABI 冒烟；正式发版用构建脚本）
./gradlew :app:assembleRelease -PappAbi=v8a

# 5) 正式三档产物（v7a / v8a / both），自动带 -PversionSuffix=<9 位短哈希>
#    Windows
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
#    Linux / macOS
bash tools/build-release.sh
#    只打单档：powershell -ExecutionPolicy Bypass -File tools\build-release.ps1 -Abis v8a
#              ABIS="v8a" bash tools/build-release.sh

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

- 通过标准：退出码 0，输出「链接检查通过（N 个 md）」。本文写完时的一次实际运行结果是「链接检查通过（26 个 md）」——N 会随新增文档变化，**只要求退出码为 0**，不要把这个数字当断言。
- 检查范围：`docs/`、`README.md`、`CHANGELOG.md`、`NOTICE.md`、`tools/` 下的相对链接（来源：[`README.md`](README.md) §5 第 6 条；`tools/check_doc_links.mjs`）。

---

## 4. 已知问题 / 不阻塞发版

> 选取原则：与 1.9.0 改动面相关、但**不阻塞本次发版**；均来自 [`TODO.md`](TODO.md)，本表只挑不发散。
> 标注的版本是 [`TODO.md`](TODO.md) 现有措辞；未标版本的条目 TODO 里尚未排期，按「待确认」处理。

### 4.1 1.9.x（1.9.0 发版后的小版本跟进）

| 事项 | 来源 | 说明 |
|---|---|---|
| 上下文压缩阈值与系数调参 | [`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M5 待办 | 64K 提醒、90% 硬触发、0.85 预算系数、token 估算权重都是起始值，需按真实使用调 |
| 容量探测命中率观察 | 同上 §6 风险与未核实项 | 读不到容量字段的服务（OpenAI / Ollama / LM Studio 等）全部降级兜底，命中率上线后观察；未做超限报错学习（窗口估大被 400 拒绝时 1.9 不处理） |
| `compaction_summaries` 表未启用 | 同上 §3 M5 待办 | 当前压缩状态整份 JSON 存 `meta` 表键 `context_compaction`（`MetaCompactionStore`）；按区间溯源才需要迁移 |
| 日文上下文数字/日期读法 | [`TODO.md`](TODO.md) 待办 D；[`tts/architecture.md`](tts/architecture.md) 「已知边界」 | 日文句里的阿拉伯数字走 sherpa 中文 `ruleFsts`（`15 時`→shí wǔ shí）；`docs/tts/architecture.md` 写的是「排在 1.9.0」，但代码中尚无假名数词/日期规则 —— **本版按已知边界记录，若坚持 1.9.0 内修完则本条升级为阻塞项（待确认）** |
| M4 观察期指标收集 | [`TODO.md`](TODO.md) 待办 E | 用 `adb logcat -s TurnMetrics` 收集工具调用率、平均轮数、失败/取消/超时占比、`cacheHitRatio`，评估容量/摘要参数；只含计数不含内容 |
| 设备残留测试数据清理（可选） | [`TODO.md`](TODO.md) 待办 E | 测试注入的长消息、重复记忆 `mem_ln3glfhc` / `mem_39ypzmxj`，下次接设备时清 |

### 4.2 1.10（下一轮功能版本）

| 事项 | 来源 |
|---|---|
| 清除对话改软删除（`conversations.state=cleared` + 新活跃会话 + 撤销只带 conversationId；单会话模型下需先让 `conversationId` 可切换） | [`TODO.md`](TODO.md) 待办 E；[`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §3 M3 |
| 清理备胎代码（`MemoryRepository` JSON 实现、`ConversationStore` 及相关单测）；legacy 导入保留到 1.11 | [`TODO.md`](TODO.md) 待办 E；同上 §3 M6 |
| 被 `interrupted` 打断的 turn 落库标记（当前仅 `TurnMetrics` 日志，无消费方） | [`TODO.md`](TODO.md) 待办 E |
| 备用 tail 模式 `SEPARATE_USER` 未接线（确认端点需连续 user 时再做，含探测/降级逻辑） | [`TODO.md`](TODO.md) 待办 E |

### 4.3 未排期（与 1.9 改动面直接相关的稳定 / 正确性遗留）

> 以下都是 [`TODO.md`](TODO.md)「2026-11-22 代码审查遗留」与待办 A 的条目，本版**不修**，但需在发布说明或 issue 里有交代。

- [ ] **G1** 清除对话与在途 turn 取消收尾竞态（快照可能缺已展示片段 / 清空后旧片段又落库）—— `TODO.md` 待办 A
- [ ] **G2** 撤销「清除对话」不恢复压缩摘要（`clearHistory` 异步清 `MetaCompactionStore`）—— 同上（§2.4 验收时按现状记录）
- [ ] **G3** 整轮落库后记忆提交失败只回滚 user，可能留下无 user 的 assistant/tool 行 —— 同上
- [ ] **G4** `ConversationManager` 落盘消费者取消后静默丢数据 / `flush()` 永久挂起 —— 同上
- [ ] **G5** 手动压缩与自动压缩无互斥、`compactionState` 读改写互相覆盖 —— 同上
- [ ] **G6** 并发 turn 无守卫（悬浮窗发送不检查 `isTurnActive`；`cancelActiveTurn` 只取消最新 job；`sendFromOverlay` 直接调 `sendMessage`）—— 同上
- [ ] **G7** `removeMessages` 逐条事务、整轮回滚非原子（`ConversationManager.removeMessages` 逐条入队 `PersistOp.Remove`）—— 同上
- [ ] **G8** `create_memory` 允许空 `keywords`（空关键词记忆在只读/关键词检索路径永远匹配不到）—— 同上
- [ ] **G11** 上下文窗口手动值 <1024 被静默当成自动、「重新检测」用固定 `delay(1500)` —— 同上
- [ ] **G13** `search_memory` 的 `total_matched` 语义错误（取的是返回条数，受 limit/体积截断影响）—— 同上
- [ ] **#15 + 新-8** 隐私授权双存储 + 主线程 `runBlocking`（`PrivacyConsentManager` 同时写 SharedPreferences 与 DataStore）—— `TODO.md` 待办 A
- [ ] **#42 残项** 永久类记忆无硬顶（`enforceCapacity` 把 FACTUAL/CORE_TRAIT 排除在 `maxItems=500` 外）—— 同上
- [ ] **候选-1** 悬浮窗返回主界面后约 2 秒才出画面（推测为 GL 重初始化耗时，低优先）—— `TODO.md`「待调查」

### 4.4 1.11（迁移代码移除）

- [ ] 删除 legacy 3 个 TTS step + `LegacyExchangeCountMigration`（共 4 个 step）、注册项与测试，并删除废弃的 `SettingsKeys.EXCHANGE_COUNT` / `getLegacyExchangeCount` / `clearLegacyExchangeCount`；**保留迁移框架本身** —— 来源：[`TODO.md`](TODO.md) 待办 0；[`legacy-compat.md`](legacy-compat.md) §3/§4
- [ ] 观察 TTS 合成后 native 内存增长（真机复测：10 条合成后 Native 增约 +300MB，超出预期 100~250MB，需确认是 ORT arena 缓存还是泄漏）—— 来源：[`TODO.md`](TODO.md) 待办 0
- [ ] 后续版本考虑 `arm64-v8a` only，彻底移除 v7a 与 `Live2dSupport` 降级路径 —— 来源：[`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md) §后续计划

---

## 5. 发版步骤

### 5.1 版本号与 tag

1. 确认 `app/build.gradle.kts`：`versionCode = 15`、`versionName = "1.9.0"`（引号内保持字面量，`tools/release_meta.py` 与 `tools/build-release.*` 用正则从这个字面量取基础版本号）。
2. `CHANGELOG.md` 的 `## [1.9.0] - 未发布（dev 分支）` 改为实际发布日，并补齐本版条目。
3. 把 `dev` 合并到 `main`（release workflow 要求 tag 提交在 `main` 上），`git push origin main`。
4. 打 tag 并推送：`git tag v1.9.0 && git push origin v1.9.0`。
   - tag 必须与 `versionName` **基础版本**一致（`v1.9.0` ↔ `1.9.0`），否则 `release_meta.py --require-main` 直接失败。
   - 带后缀的 tag（如 `v1.9.0-beta1`）会照常发布但标为 pre-release：不占 Latest，也不影响「检测更新」。
   - 重跑幂等：已存在的 Release 会更新标题/正文并覆盖 APK。

### 5.2 构建脚本与参数

| 参数 | 作用 | 默认 |
|---|---|---|
| `-PappAbi=v7a\|v8a\|both` | 过滤打包的原生库 ABI（`abiFilters`）；`build-release.*` 依次传三档 | 缺省 `both` = `arm64-v8a` + `armeabi-v7a` |
| `-PversionSuffix=<7~40 位十六进制>` | 把当前提交短哈希拼到 `versionName` 末尾（形如 `1.9.0-1a2b3c4d5`）；非十六进制直接报错 | 无（手动构建不带） |
| `-Pumeng.enabled=false` | 统计 SDK 退化为 `compileOnly`，不打包；隐私弹窗与授权管理链路失效 | `true` |

- 脚本会用 `git rev-parse --short=9 HEAD` 取短哈希并通过 `-PversionSuffix` 传入；非 git 工作区只告警、不带后缀继续构建。
- `dist/` 产物文件名使用**基础版本号**（不带短哈希）：`MeaPet-v1.9.0-<ABI>.APK`。
- 签名证书来自根目录 `keystore.properties`（或 4 个 `KEYSTORE_*` 环境变量）；产物若为 `app-release-unsigned.apk` 脚本会直接报错退出。
- 签名校验（CI 步骤）：对 `dist/*.APK` 逐个 `apksigner verify --verbose --print-certs`，要求输出含 `Verifies`，且**三档产物证书 SHA-256 摘要一致**。本工程 APK 仅 v2 方案签名，解析按 `V<方案号> Signer: certificate SHA-256 digest:` 格式匹配。

### 5.3 产物命名与 ABI 差异

| 产物 | 含原生库 | Live2D 模型 | 适用设备 |
|---|---|---|---|
| `MeaPet-v1.9.0-v8a.APK` | `arm64-v8a` only | ✅ 正常 | 绝大多数现役 64 位设备（推荐主推） |
| `MeaPet-v1.9.0-both.APK` | `arm64-v8a` + `armeabi-v7a` | ✅ 在 64 位设备正常；在仅 32 位设备降级 | 通用包，体积最大 |
| `MeaPet-v1.9.0-v7a.APK` | `armeabi-v7a` only | ❌ **必然降级** | 仅 Android 8 时代 32 位存量设备 |

- **v7a 缺模型的原因**：Live2D Cubism Core 官方 AAR 只提供 `arm64-v8a` / `x86` / `x86_64`，没有 `armeabi-v7a`；v7a 包内不存在 `libLive2DCubismCoreJNI.so`，启动时探测失败即进入降级（纯聊天 + 背景色/壁纸，无桌宠模型、无触摸语音、主界面隐藏「悬浮窗」入口）。见 [`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md)。
- 保留 v7a 是为兼容 Android 8 时代约三成仅 32 位的存量设备；ADR 已记录「后续考虑改 `arm64-v8a` only」的迁移条件。
- 引用 1.8.0 的体积基线作参考：v8a 单 ABI **33.7MB**、双 ABI **51.8MB**（当时数据，1.9.0 的实际体积以本次 `dist/` 为准）。
- v7a 包在 64 位设备上侧载时，系统可能仍选 32 位 ABI（`primaryCpuAbi=armeabi-v7a`），此时同样走降级，不会崩溃。

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
- **废弃字段**：历史 JSON 中已废弃的记忆操作附加字段在读取时忽略，不参与导入后的请求或记忆处理；开发期旧库里同名的 nullable 列保持不动、不读不写，**不做破坏性迁移**；新建库不含该列。
- **不支持降级（重要）**：SQLite 库版本 `user_version` 高于应用已知版本时**拒绝打开**（`Migrations` 的 `check(current <= CURRENT_VERSION)`，当前 `CURRENT_VERSION = 1`）。
- **回滚到 1.8.0 的后果（待确认）**：1.8.0 没有 SQLite 存储路径，回滚后只能读到仍保留的旧 JSON（升级前的内容）；升级后产生的新对话只存在于 `meapet.db`，1.8.0 看不到。若要在回滚时保留新数据，需要另行导出/备份 `meapet.db` → **发布说明里建议升级前备份**（[`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] 已写「建议升级前按需备份」）。
- **备份建议**：`files/meapet_conversation.json`、`files/meapet_memories.json`、`databases/meapet.db`（+ `-wal` / `-shm`）。

### 6.2 隐私政策 1.3 的重新确认

- 政策版本常量在代码里硬编码（`PRIVACY_POLICY_VERSION = "1.3"`，生效 2026-07-29 / 修订 2026-11-23），修订必须改代码文案并随代码一并提交，避免配置漏改导致老用户不重新确认。
- **仅含统计 SDK 的构建**会触发重新确认：`UMENG_ENABLED && getPrivacyVersionShown() != currentPrivacyVersion` → 弹窗并标记「隐私政策更新」。不含 SDK 的构建既不弹、也**不记录**已看版本号（换回含 SDK 构建时仍会弹）。
- 1.9.0 新增的「复用聊天 Key」开关改变了政策 §五描述的前提（默认开、可关闭），发布前需按 §1.2 复核文案；1.3 补充的「三、备份与导出」小节（版本号不变，不额外触发重新确认）说明备份写到哪里、密码加密与不保存、Key 默认不进包、不上传不保留副本。
- 已看版本号记在 DataStore `privacy_version_shown`；授权状态另记 SharedPreferences（双存储是已知问题 #15，见 §4.3）。

### 6.3 版本号带短哈希对「检测更新」的影响

- 自动构建的 `versionName` 形如 `1.9.0-1a2b3c4d5`；`AppInfo.baseVersion()` 用 `[-+][0-9a-fA-F]{7,40}$` 剥离后缀后再比较，避免把数字开头的哈希当成额外版本段（旧实现会把 `1.9.0-1a2b3c4d5` 解析成 `1.9.0.1` 而误判需要更新）。
- 后缀只匹配 7~40 位十六进制，`1.9.0-beta1` 这类预发布标识不会被误剥离。
- `dist/` 产物名**不带**后缀（`MeaPet-v1.9.0-v8a.APK`），后缀只进 APK 内部的 `versionName`；从安装包反查提交看「关于」页或 `dumpsys package`。
- Release 的 tag 仍是干净的 `v*.0`；带后缀的 tag（`v1.9.0-beta1`）标为 pre-release，`/releases/latest` 不会取到它，因此不影响更新检查。
- 手动构建（`assembleDebug` / Android Studio）不带后缀，`versionName` 保持 `1.9.0` —— 对比更新检查行为时注意区分。

### 6.4 其他升级面

- **TTS**：1.9.0 未更换模型包（仍是 1.8.0 的 6 文件约 84MB）；从 1.7.x 升上来的用户仍会触发 `LegacyTtsModelCleanup` 三步（删旧模型目录 → 删旧 native 库 → 关闭两个语音开关），需要重新下载或本地 zip 导入。
- **覆盖安装**：`applicationId` 与签名不变，可覆盖安装。
- **旧数据不会被清空**：升级不会清空现有记忆或会话（只读化 / 忽略废弃字段，不删数据）。

---

## 7. 疑似已过时条目（供人工确认）

> 逐个对照了代码。**下列条目是「文档描述与当前代码不一致」**，需要人工判断是「已修好但没勾掉」还是「只是数字/行号漂移」。为避免误删，本文不做任何修改，仅列出证据。

### 7.1 行号 / 数字漂移（描述仍成立，数字需更新）

| 条目 | TODO 写的 | 代码实测 | 建议 |
|---|---|---|---|
| [`TODO.md`](TODO.md) 待办 C **#23** 文案抽 `strings.xml` | 「实测中文 **387 处**、`stringResource` **0 次**」 | `stringResource(` = **0**（一致）。中文文案量按**双引号字符串字面量**口径重测约 **669** 处（含中文的代码行约 4205 行）——与 TODO 的 387 口径不同，无法直接对比 | 「387」的统计口径未记录，建议重测并在 TODO 里写明口径；趋势是文案量在增长。`strings.xml` 目前只有 `app_name` 一条 |
| [`TODO.md`](TODO.md) 待办 C **#21** `saveIfChanged` | 「**5 处**『读旧值-比较-写』」，列举 `saveApiKey`/`saveApiUrl`/`saveModel`/`saveSystemPrompt`/`selectModel` | 实测 **6 处**：上述 5 处 + `saveContextWindow`（`SettingsViewModel.kt:290`，1.9 新增的上下文窗口手动值） | 抽公共方法的收益面变大了，清单需补 `saveContextWindow` |
| [`TODO.md`](TODO.md) 待办 C **#28** 清死代码 | 「`ApiRequest` **三个**仅测试调用的方法」 | 实测 **5 个**：`visionMessage` / `transcriptionParts` / `speech` / `imageUrlContent` / `textContent`（`main` 调用 0 次，仅测试引用） | 数量过时；另外 `Live2dSprite` 的 `textureId` 构造参数被 `renderImmediate(textureId:)` 同名形参遮蔽、`Live2dView.setClearColor()` 无调用方、`Color.kt` 6 个 `Purple*/Pink*` 常量零引用、`MeaPetApplication.onTerminate()` 只剩日志 —— 这些**仍然成立** |
| [`TODO.md`](TODO.md) 待办 C **#48/#35** 拆超长方法 | 「`MainActivity.onCreate`（约 **220 行**）」 | 实测 `onCreate` 从第 81 行到第 435 行（下一个函数），约 **350 行** | 数字过时，实际更长 |
| [`TODO.md`](TODO.md) 待办 B **#8** 拆 `Live2dDelegate` | 「**338 行**/7 项职责」 | 实测 **372 行**（1.9.0 的降级门控又加了一层分支，职责更多） | 数字过时 |
| [`TODO.md`](TODO.md) 待办 C **#22** 魔法数字 | 「原 8 处，余 **7 处**」，其中含 `SettingsViewModel` 的 `8`/`4` | 清单里大部分仍成立（`takeLast(5)`、`300_000`/`30_000`、`0x14`/`0xF7`/`0xFFF7F7F7`、`30f`/`400L`/`±0.45f`/`-0.44f`/`0.66f`、`dropLast(5)`、`100.dp`）；但 `SettingsViewModel` 的 `8`/`4` 现在指的是 API Key 遮罩（`"****"` 长度 8、`take(4)`/`takeLast(4)`），与待办原意可能不是同一处 | 建议复核「7 处」的构成，避免把 Key 遮罩当成待提常量 |

### 7.2 描述已与代码不符（可能是「已完成未勾掉」或「备胎已死」）

| 条目 | TODO 写的 | 代码实测 | 判断 |
|---|---|---|---|
| [`TODO.md`](TODO.md) 待办 E「**1.10：清理备胎代码** — `MemoryRepository`（JSON 实现）、`ConversationStore`」 | 当作 1.10 待办 | `MemoryRepository` 在 `app/src/main` **已无任何实例化点**（唯一出现是 `class MemoryRepository(` 自身声明与若干 KDoc 提及）；`ConversationStore` 的唯一引用是 `AppContainer.conversationStore` 这个 `lazy` 属性本身，**没有读取方**（legacy 导入走 `LegacyJsonImporter(dir)`，不用它） | 两者实际已是死代码（`G12` 也这么记）。可以**提前到 1.9.x 直接删**，与 1.10 删等价；若确认，本条应从「1.10 待办」改为「现在即可删」 |
| [`TODO.md`](TODO.md) 待办 E「1.10：**清除对话改软删除**（M3 遗留）」 | 待 1.10 | schema 已就绪：`conversations.state TEXT ... CHECK(state IN ('active','cleared'))` + `cleared_at`，但代码里**没有任何地方写入 `'cleared'`** | 条目本身仍然成立（功能未做），但可补一句「schema 已预留，1.9 未使用」 |
| [`TODO.md`](TODO.md) 待办 C「**G12** … `shouldHintCompaction`/`currentContextTokens` 仅测试用」 | 仅测试用 | `currentContextTokens()` 仅被 `ChatServiceCompactionTest` 调用；`shouldHintCompaction()` **除自身定义外零调用**（UI 侧在 `ChatViewModel.refreshCompactionUi` 里直接用 `tokens >= COMPACT_HINT_TOKENS` 判断） | 描述准确，但可以更明确：`shouldHintCompaction()` 可直接删（连测试都没有），`currentContextTokens()` 的消费方只有测试 |

### 7.2b 已逐条复核、确认**不属于**过时（供对照，避免重复怀疑）

| 条目 | 复核结论 |
|---|---|
| [`TODO.md`](TODO.md)「🔭 待调查 · 候选-1」悬浮窗返回主界面约 2 秒才出画面 | 现状无变化，未被 1.9 改动影响；条目仍开放（TODO 已写下下一步：加时间戳打点分段计时） |
| [`TODO.md`](TODO.md) 待办 0「1.11.0 删除 legacy 迁移步骤」 | `legacy/steps/` 下 4 个文件都还在，`LegacyMigration.STEPS` 仍注册 —— 按计划 1.11 删，未过时 |
| [`TODO.md`](TODO.md) 待办 E「备用 tail 模式（`SEPARATE_USER`）未接线」 | `TailMode.kt` 有 `FALLBACK_TAIL_MODE` 常量（`internal`、无外部引用）+ `TailModeTest` 一条用例 —— 描述准确 |
| [`TODO.md`](TODO.md) 待办 0「观察 TTS 合成后 native 内存增长」 | 仍开放；最近一次真机数据仍是 2026-11-15 的复测值 |

### 7.3 只有在其它文档里过时的数字（本文不改，供发版时一并回填）

| 文档 / 位置 | 写的 | 实测 |
|---|---|---|
| [`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) §1 验证 | 「2026-11-22 全量 **447** 条单测通过」 | 当前 `dev` HEAD **550**（447 是 `167e6bd` 时的数字，455 是 `c6c223e` 时的数字） |
| [`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §测试与待验证项 | 「本轮本地验证为 **447** 条单测通过」 | 同上，554 |
| [`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) §适配器与配置 | 持久设置只列了 `web_search_enabled` / `web_search_provider` / 两个 Key；§当前范围写「DeepSeek 留空时保留官方聊天 Key 的安全复用」 | `a693aa9` 新增了 `web_search_reuse_chat_key`（默认开）+ 设置页开关 + `WebToolFactory(reuseChatKey=)` + `DeepSeekWebProvider.shouldReuseChatKey()`；文档未更新 |
| [`../CHANGELOG.md`](../CHANGELOG.md) [1.9.0] Added「联网工具的前提与转发语义」 | 「DeepSeek 独立 Key 留空时，官方聊天端点会复用聊天 Key」（无条件） | 未提「复用可关闭」；`a693aa9` 新增的开关未登记（发版前需补，见 §1.2） |
| `app/src/main/java/com/meapet/mobile/ui/component/PrivacyPolicyContent.kt` §五（第 95 / 200 行） | 「DeepSeek 独立 Key 留空、且……会复用其中的聊天 Key」（无条件） | 同上，未提复用可关闭；政策版本仍是 1.3（2026-11-23 补充「备份与导出」小节，版本号未变、不额外触发重新确认） |
| `local.properties.example` TTS 段注释 | 「语音模型 **4 个** onnx 所在目录」 | 1.8.0 起已是 **6 个文件**（`model.onnx` / `tokens.txt` / `lexicon.txt` / `fsts/*.fst`） |
| [`../NOTICE.md`](../NOTICE.md) 审计说明 | 审计基准是 `dist/MeaPet-v1.8.0-v8a.APK` | `dist/` 目前仍只有 1.7.2 / 1.8.0；1.9.0 产物尚未构建 |

---

## 附：本次核对用到的命令

```powershell
git log --oneline -20
git status --short
git rev-list --left-right --count origin/main...dev   # 0  60（origin/main 落后 dev 60 个提交）
# 单测统计 / @Test 计数 / androidTest 计数：见 §3.1
node tools/check_doc_links.mjs
```
