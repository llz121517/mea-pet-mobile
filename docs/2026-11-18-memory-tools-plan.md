# 1.9 计划：记忆工具化 + 记忆检索工具（修订版）

> 状态：**已实施（dev 分支，1.9.0）**。2026-11-19 完成 M1–M3 与 §10 全部改造，待真机验收与 M4 观察期数据。本版在 2026-11-18 调查报告基础上，按 M0 探针、已落地的存储层、多 system 兼容性调研及最新已确认决策修订。

## 实施状态（2026-11-19）

| 项 | 状态 | 说明 |
|---|---|---|
| M0 请求/响应层探针 | ✅ | tools/tool_choice/tool_calls/reasoning_content 均已按 M0 结论实现 |
| 请求/响应层（tools、toolMessage、assistant 原样透传） | ✅ | `ApiRequest` / `ApiResponse.chatCompletionMessage` |
| 工具循环 `ChatToolLoop` | ✅ | 轮数上限取 **12**（为后续 web 搜索预留，原计划 4），单轮 8 调用，末轮 `tool_choice=none`、tools 字段不变 |
| 4 个工具 + 统一信封 + 护栏 | ✅ | 暂存提交、永久类删除护栏、写入上限；`LONG_TERM` 不可直接创建 |
| search_memory 检索 | ✅ | 内存打分（权重为实测修订值，见 §11.1） |
| 工具消息入历史 + `turn_id` | ✅ | 一 turn 一事务（`appendAll`）；失败整轮不写；被取消按已完成部分提交 |
| 连发多条气泡 + TTS 逐气泡 | ✅ | `ChatToolLoop.onVisibleFragment` → `ChatService.sendMessage(onFragment)` → ViewModel 逐条插入并朗读 |
| 插队发送 / 停止 | ✅ | `turnPhase` 驱动输入框与状态条；`ChatService.isTurnActive` / `cancelActiveTurn()` |
| 记忆检索模式（AUTO/TOOLS/TEXT/OFF） | ✅ | 设置页「记忆系统 → 检索方式」；端点不支持 tools 时同 turn 降级文本协议 |
| 整个 turn 的 `withTimeout` | ✅ | `AppConfig.turnTimeoutMs`（默认 180s），超时归类为失败并回滚 |
| M4 观察指标 | ✅ | `TurnMetrics`：只记计数/轮数/耗时/结果，不落内容；观察期结束后据此决定文本兜底策略 |
| 切点按轮对齐 | ✅ | 压缩/丢弃切点按 user 边界；`buildApiMessages` 还会清洗孤立 tool 消息 |
| M4 清理决策（删文本协议） | ⏳ | 待观察期数据，1.10/1.11 再定 |
| 被打断 turn 的 `interrupted` 落库标记 | ⏳ 未做 | 当前仅在 `TurnMetrics` 日志里记 `CANCELLED`，重试/清除用「该 user 之后全部移除」已覆盖语义；无消费方，等有分析需求再补列或 meta 记录 |
| 读类工具并发执行 | ⏳ 未做 | 记忆检索是内存毫秒级，串行不会成为瓶颈；并发会与暂存写状态交织，收益低风险高，暂不引入 |

## 0. 结论

- 可行，建议做，风险中偏高。分阶段推进，文本协议在 1.9 内保留作降级路径。
- 核心决策：
  1. 工具循环的中间消息（assistant tool_calls、role=tool）**入历史并保留**（2026-11-18 已确认，推翻此前「不入历史」的决定）：1.9 已迁移到 SQLite 且旧 JSON 保留，降级后旧版读旧 JSON，不会碰到 `role:"tool"`。同一轮共用同一个 `turn_id`，整轮回滚、重试、清除按 `turn_id` 处理。详见第 10 节。
  2. 写类工具先暂存，所属 turn 成功后统一提交；失败、取消、被打断、重试不产生重复记忆。
  3. 检索采用内存打分：bigram 双向召回 + 加权打分（关键词 1.0、内容 bigram 0.6、重要度 0.3、新近度 0.1），不引入 FTS 与向量。详见第 11 节。
  4. 人设事实（FACTUAL / CORE_TRAIT）仍全量注入，SHORT_TERM / LONG_TERM 的相关回忆改由 search_memory 检索。
  5. 摘要流程保持独立 LLM 调用，不工具化。
  6. 每轮动态内容（tail）目前只含当前时间，并入最后一条 user，不进历史，不使用尾部 system。详见第 9 节。
  7. 每轮响应回来立即显示该轮文字，TTS 对每个有文字的气泡都朗读；首条气泡出现后允许插队发送，发送即打断，不弹提示。详见第 10 节。

## 1. M0 探针结论（DeepSeek，真实请求，已核实）

- 标准 `tools` / `tool_calls` 可用，`tool_choice: auto` 与 `none` 可用，且模型会在一轮里并行返回多个 tool_calls，执行器必须支持多调用并按 tool_call_id 一一回填。
- `reasoning_content` 回传非必需（去掉后第二轮也成功），但仍建议原样透传 assistant message，兼容思考模式的端点。
- 以 `tool_choice: none` 收尾可用；保持 tools 字段不变以稳定前缀。
- usage 含 `prompt_cache_hit_tokens`，可用来验证工具化后的缓存收益；改了 tools 字段的位置或内容会使缓存失效一次。
- 仍未核实：`required` / 指定函数名与思考模式的冲突（本方案不使用）；content 与写类 tool_calls 同时出现的频率。

## 2. 与已落地存储层的关系

- `MemoryStore` 接口已存在，`AppContainer` 已切到 `SqliteMemoryStore`；`MemoryManager`、`MemoryService` 已依赖 `MemoryStore`，无需再做抽象工作。
- 本计划需要在接口上新增：`query(MemoryQuery)`（types、limit、sort，返回 items 与 totalMatched）。排序打分放在接口之上，召回在内存中完成（第 11 节）。

## 3. 工具集（4 个）

- search_memory(query, types?, limit 1-10 默认 5, sort relevance|recent|importance)：只读。
- create_memory(type 仅 SHORT_TERM|CORE_TRAIT|FACTUAL, content, importance, keywords)：禁止记录模型自己的提议或猜测。
- update_memory(id, content, type?, importance?, keywords?)：目标不存在返回错误，不再退化为新建。
- delete_memory(id, reason?)：删除永久类需 confirm_permanent=true 且 reason 非空，保留墓碑快照供撤销；单 turn 删除永久类超过 2 条拒绝。
- 返回统一信封 JSON（ok、results、error、message），错误一律作为 tool 消息返回而不抛异常；search 结果总长约 1500 字符上限，单条 content 截断 200 字，用 age_days 代替时间戳。
- 校验沿用现有规则：importance 夹到 0..1，keywords 去重去空最多 8 个，content 上限 500 字，单 turn 写操作上限 6 条。
- schema 必须是常量、字节级稳定，description 不含任何动态内容；不使用 strict。

## 4. 请求/响应层与循环

- ApiRequest 增加可选 tools、toolChoice（为 null 时不输出字段）；透传 assistant 原始 message；新增 toolMessage。
- ApiResponse 新增 `chatCompletionMessage`，保留 `chatCompletionContent`；arguments 为 JSON 字符串，需二次解析，失败转为错误 tool 消息。空内容判定改为「循环结束后既无 content 也无 tool_calls 才失败」。
- 新增 `ChatToolLoop`：流程与限制见第 11.2 节；整个 turn 另设 withTimeout；读类可并发，写类按序且合并为一次 applyChanges。
- 非流式即可，流式作为后续优化。
- 写入与回滚：
  - 写类工具的变更暂存于 PendingChanges，所属 turn 成功后沿用现有 postProcessScope 提交；失败、取消、被打断的 turn 丢弃暂存。
  - 历史消息（含 tool 消息）与该 turn 的 user、最终 assistant 在同一事务里按同一 `turn_id` 写入；失败整轮不写；被打断时按已完成部分提交（见第 10.1 节）。
  - `retryMessage` 与清除对话按 `turn_id` 整轮处理（须随工具入历史改造，原「无需改动」的表述作废）。
  - 被打断时未完成的 tool_calls（无对应结果）整组丢弃，已展示的文字气泡保留，该 `turn_id` 标记为 interrupted。

## 5. 提示词与缓存

- 首条 system 保留人设与用户人设；记忆协议说明改为几行固定的工具使用说明；删除收尾提醒与相关回忆注入。
- 每轮动态内容（tail）目前只含当前时间，并入最后一条 user，不进历史（第 9 节）。
- 提示词引导：调用工具前的文字应简短，避免终答重复。
- 缓存：P2（协议块回贴窗口滑动）消失；P1 由压缩计划处理；P3（人设变化使前缀失效）不变，可选方案是人设快照仅在压缩边界刷新。

## 6. 兼容与降级

- 新增 memoryMode：AUTO（默认）/ TOOLS / TEXT / OFF。端点拒绝 tools（400/422）时标记该 baseUrl+model 不支持并同一 turn 不带 tools 重发，之后自动走 TEXT；TEXT 即现有文本协议链路（含 echo 与 memoryOpsBlock）。
- 端点静默忽略 tools 无法用错误检测，需靠「连续 N 个 turn 零工具调用」启发式或显式设置。
- 1.9 内不删除 MemoryOpsProtocol、echo、memoryOpsBlock；最终是否移除取决于使用数据。

## 7. 测试

- 保留：MemoryOpsProtocolTest 等文本协议用例；MemoryManagerTest 增加工具模式断言。
- 新增：
  - MemoryToolExecutorTest；
  - ChatToolLoop 用 FakeHttpClientEngine 按请求序号脚本化多轮响应；
  - OpenAiCompatibleClientTest 对 tools 数组做字节快照；
  - ChatServiceFailureRollbackTest：第二轮失败、取消、重试按 `turn_id` 整轮回滚且不重复写入；
  - 工具消息入历史、插队发送、UI 过滤相关用例见第 9.4 节与第 10.4 节。

## 8. 里程碑

| 里程碑 | 内容 | 估时 |
|---|---|---|
| M0 | 探针 [已完成]；MemoryStore 抽象 [已完成] | 0 |
| M1 | 请求/响应层 + ChatToolLoop + 只读 search_memory + MemoryStore.query；`ChatRole.tool` 与 `ChatMessage` 工具字段；tool 消息按 `turn_id` 入历史；窗口裁剪与压缩切点按轮对齐；UI 过滤 tool 与空 assistant 气泡；每轮响应立即显示、TTS 逐气泡朗读；TailMode（MERGE_USER，仅含时间） | 约 4 天 |
| M2 | create/update/delete + 暂存提交 + 永久类删除护栏；`retryMessage` 与清除按 `turn_id` 整轮处理；被打断轮的暂存丢弃 | 约 3 天 |
| M3 | 提示词收敛、memoryMode 降级与能力缓存、设置项；插队发送（`isTurnActive` / `cancelCurrentTurn()`、ViewModel 状态区分、状态条「Mea 还在…」+「停止」） | 约 2 天 |
| M4 | 观察期（工具调用率、轮数、失败率、降级触发率，仅记计数与长度）与清理决策 | 约 1 到 2 天 |

回滚：memoryMode 默认值切回 TEXT 即恢复当前行为；记忆与会话数据格式不变，无需数据回滚（已入历史的 tool 消息保留在 SQLite，旧版读旧 JSON，不受影响）。

## 9. TailMode：每轮动态内容的发送方式（文件级常量 + KDoc）

> 已定决策（2026-11-18）：**放弃尾部 system**，全面转用「并入最后一条 user」；额外保留「独立一条 user 消息」作备用。tail 只放每轮变化的动态内容，**不进历史**。
> 依据：兼容性取决于后端与聊天模板；llama.cpp 对模板异常返回 500，vLLM/SGLang 返回 400，Gemma/Mistral 严格交替模板会直接报错；Anthropic 兼容层与 DeepSeek-V3 模板会把 system 提升到最前。

### 9.1 定义位置与形式

- 新建文件 `chat/TailMode.kt`，所有 tail 策略值提升为**文件级（top-level）常量**，不放进 `AppConfig` 或散落在 `ConversationManager`，调用方只引用常量名。
- 每个常量与枚举都必须带 KDoc，写明含义、取值依据、降级关系和修改后果（影响前缀缓存）。

```kotlin
/**
 * 每轮变化的附加上下文（时间等）在请求中的发送方式。tail 只承载动态内容，不写入会话历史。
 *
 * - [MERGE_USER]：并入最后一条 user 消息（默认）。兼容性最好，不引入新角色、不破坏
 *   user/assistant 交替，所有聊天模板通用。
 * - [SEPARATE_USER]：作为带标签的独立 user 消息（备用）。会产生连续两条 user，
 *   对 Gemma/Mistral 等严格交替模板会报错，仅在探测确认接受连续 user 后启用。
 *
 * 已弃用尾部 system：llama.cpp 模板拒绝返回 500，vLLM/SGLang 返回 400，
 * Gemma 2/Mistral 旧模板抛异常，Anthropic 兼容层与 DeepSeek-V3 模板会把它提升到最前。
 *
 * 修改默认值会使所有用户的前缀缓存失效一次，应视为版本事件。
 */
enum class TailMode { MERGE_USER, SEPARATE_USER }

/** 默认方式：并入最后一条 user。 */
internal val DEFAULT_TAIL_MODE: TailMode = TailMode.MERGE_USER

/** 备用方式：独立 user 消息（仅探测确认接受连续 user 时可用）。 */
internal val FALLBACK_TAIL_MODE: TailMode = TailMode.SEPARATE_USER

/** 并入或独立时使用的标签（固定字符串，保证缓存前缀稳定）。 */
internal const val TAIL_LABEL: String = "[系统提示]"
```

### 9.1b tail 的内容与结构（已确认）

- 目前 tail 只放一项：**当前时间**（沿用 `TimeContext.describe()`，格式 `【当前时间】yyyy-MM-dd HH:mm EEEE(zone,UTC±hh:mm)`，精度到分钟）。相关回忆改由 `search_memory` 检索，收尾提醒删除，因此不再进入 tail。
- tail 为**有序字符串片段列表**，按固定顺序拼接后并入最后一条 user，前缀为 `TAIL_LABEL`。新增动态内容只需追加片段，不改 `ConversationManager`。
- 时间精度到分钟：tail 不进历史，每轮该条 user 本就重新生成，对缓存没有额外影响。

### 9.2 策略

| 场景 | 方式 | 说明 |
|---|---|---|
| 所有端点（含回环、私网、云端） | MERGE_USER | 不做端点区分，兼容性最稳 |
| DeepSeek 官方端点（可选优化） | 时间/语言/平台映射为 `latest_reminder`，其余走 MERGE_USER | 仅限 `日期,星期,地点,平台,语言` 五字段，须紧跟 user；格式非法返回 400；需随历史回传，与「tail 不进历史」冲突，故首版不启用，后续按实测决定 |
| 备用 SEPARATE_USER | 仅在能力探测确认接受连续 user 时 | 严格交替模板禁用 |

### 9.3 要点

- tail 不进历史：每轮重新生成并并入当前最后一条 user 的发出副本，会话历史里保存的仍是原始用户文本。因此上一轮含 tail 的 user 在下一轮会不含 tail，分叉点就在该 user，之前的前缀保持稳定。此缓存影响为推断，未实测，用 `prompt_cache_hit_tokens` 验证。
- 压缩摘要并入首条 system 末尾，不作为第二条 system；`buildApiMessages` 会过滤历史中的 system，摘要须单独传入。
- 工具循环内，tail 只并入本轮最后一条 user；当最后一条消息是 tool 或带 tool_calls 的 assistant 时，不得把 tail 夹在工具调用与结果之间。
- 降级：并入 user 方案兼容性最好，原则上无需降级；若备用方式返回 400/500 且正文命中 `Conversation roles must alternate`、`Jinja Exception` 等，则退回 MERGE_USER，并按 `baseUrl+model` 缓存结果。

### 9.4 新增测试

MERGE_USER 与 SEPARATE_USER 的消息构造、严格交替校验、tail 不进历史（历史存原文）、前缀逐字节稳定、最后一条为 tool 的边界、摘要并入首条 system、遵从度回归（并入 user 对比独立 user）。

## 10. 工具消息入历史、连发多条气泡与插队发送（已确认，2026-11-18）

### 10.1 工具消息入历史

- 工具循环的中间消息（assistant 的 tool_calls 与 role=tool 的结果）入历史并保留。理由见第 0 节。
- 存储：`messages` 表 V1 已预留 `role='tool'`、`tool_calls`、`tool_call_id`、`tool_name`、`extra_json`，无需新迁移。assistant 的 `tool_calls` 与 `reasoning_content` 原样存入 `tool_calls` / `extra_json`。
- 模型：`ChatRole` 新增 `tool`；`ChatMessage` 新增 `toolCalls`、`toolCallId`、`toolName`（均带默认值）。
- 同一轮（user → assistant(tool_calls) → tool… → 最终 assistant）共用同一个 `turn_id`，在同一事务里写入。失败整轮不写；被打断（插队或点「停止」）时，按已完成部分提交一次：落库 user 与已展示的气泡，`turn_id` 标记为 interrupted，未完成的 tool_calls 整组丢弃，写类暂存丢弃。`retryMessage` 与清除按 `turn_id` 整轮处理（清除对话为会话级软删除，二者不冲突）。
- 降级到 TEXT 模式或端点不支持 tools 时，发送前统一过滤历史：丢弃 `role=tool` 消息，assistant 消息去掉 tool_calls，只保留文字内容（通用逻辑，不针对具体服务）。
- 窗口裁剪与压缩切点必须按轮对齐，保证 tool_calls 与对应 tool 结果成对，否则服务端会因孤立的 `tool_call_id` 返回 400。压缩摘要输入里 tool 结果截断或概括。
- tool 结果入历史沿用第 3 节的同一上限（单条 200 字、总长约 1500 字符），不另设。
- tail 规则见第 9.3 节；写类工具仍先暂存、turn 成功后统一提交。

### 10.2 连发多条气泡

- 模型可能先输出一段文字并调用工具，工具执行后再输出下一段，用户看到的是助手连发多条消息。DeepSeek 实测调用工具时 content 常为空，代码须同时支持有片段与无片段。
- 非流式下，每轮响应一回来就立即显示该轮 content，再执行工具并进入下一轮，不等整个循环结束。
- UI 不展示 `tool` 角色消息，也不展示 content 为空且只含 tool_calls 的 assistant 消息，避免空气泡。
- TTS 对每个有文字的气泡都朗读。

### 10.3 插队发送

- 本轮出现第一条助手气泡后，输入框恢复可输入、可发送；在此之前保持现状（输入框禁用、发送键转圈）。
- 输入栏顶部固定一条细长状态条（不是悬浮按钮，不遮挡消息列表）：左侧小循环指示与文案「Mea 还在…」，右侧文字按钮「停止」；循环结束后淡出。草图见 `docs/2026-11-18-interject-ui-mock.html`。
- 「停止」：取消当前循环，保留已显示的气泡，丢弃未提交的写类工具暂存。
- 循环中发送新消息：先取消当前循环的后续请求，不弹提示，直接把新消息作为新一轮发送。
- 被打断时的历史处理见第 4 节（未完成的 tool_calls 整组丢弃；已展示气泡保留；`turn_id` 标记 interrupted；写类暂存丢弃）。
- 实现：`ChatService` 暴露 `isTurnActive` 与 `cancelCurrentTurn()`，`sendMessage` 检测到在途 turn 时先取消再发；ViewModel 区分「等待首条」（禁用输入）与「已有首条、仍在继续」（允许输入并显示状态条）；取消经协程传到请求层，保证取消后不落库半条 turn。

### 10.4 新增测试

历史含工具消息的往返读写、`turn_id` 整轮回滚、切点成对、UI 过滤（tool 与空 assistant）、插队取消后无孤立 tool_calls、停止后写类暂存不提交、连发气泡按轮立即显示、TTS 逐气泡朗读。

## 11. 检索设计与多轮循环（已确认）

### 11.1 检索（search_memory，不引入 FTS 与向量）

- 候选召回：对 content 与 keywords 同时做字符二元组（bigram）重叠，中文天然适用，英文按词切分；匹配双向（query 含 keyword，或 keyword/content 含 query 片段）。
- 打分：`0.3·关键词命中率 + 0.1·内容 bigram 重叠率 + 0.1·重要度 + 0.1·新近度（按 lastAccessedAt 衰减）`，权重放常量，起始值上线后按真实使用调整。**实施时已从 1.0/0.6/0.3/0.1 修订为上述值**：原权重让关键词命中一项就压过其余全部信号，实际检索几乎只回一条。
- 相关度排序**不再丢弃零分候选**：按得分排序后取前 `limit` 条，命中不足时用未命中条目补齐（记忆库通常只有几条，严格过滤会导致「只返回一条」甚至空结果）；候选池 = 已落库 + 本 turn 暂存的合并视图，同一轮 create/update 之后立刻 search 也能看到。
- 参数 `types`、`limit`、`sort`（relevance/recent/importance）；模型可多次换词检索。
- bigram 集合按记忆 id 在内存缓存，写入时失效。
- 本版不引入 FTS。记忆涨到几千条后再评估把候选召回换成 FTS4（须走 `user_version=2` 迁移并真机验证；JVM 的 sqlite-jdbc 带 FTS5 而 Android 框架 SQLite 没有），打分层不变。

### 11.2 多轮循环

- 流程：请求（带 tools）→ 有 tool_calls 则全部执行并按 `tool_call_id` 一一回填 → 追加 assistant 原始 message 与各 tool 消息 → 再请求，直到无 tool_calls。
- `maxToolRounds = 12`（原计划 4，实测多步记忆操作常 5~6 轮，且要为后续 web 搜索工具预留），最后一轮 `tool_choice:"none"` 且 tools 字段不变；单轮最多 8 个调用；写类按序执行并合并为一次 `applyChanges`；整个 turn 设 `withTimeout`（`AppConfig.turnTimeoutMs`）。
- assistant 消息原样透传（含 `reasoning_content`）。
- 本轮 content 不当作最终回复；content 非空且只含写类调用时直接结束的「终结型写入」仅为可选优化，需先统计频率。

## 12. 未核实项

- deepseek-flash 默认是否开启思考模式及关闭参数的确切形态。
- 写类 tool_calls 同时给出 content 的频率。
- tools 字段在 DeepSeek 前缀缓存中的位置（可用 usage 的 cache_hit 字段在 M1 观察）。
- 并入最后一条 user 与独立 user 的指令遵从度没有直接实测。
- DeepSeek 官方端点对多条/末尾 system 的行为未核实（已弃用 tail system，影响降低）。
