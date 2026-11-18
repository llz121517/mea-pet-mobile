# 1.9 计划：记忆工具化 + 记忆检索工具（修订版）

> 状态：待实施（Dev 分支）。本版在 2026-11-18 调查报告基础上，按 M0 探针与已落地的存储层修订。

## 0. 结论

- 可行，建议做，风险中偏高。分阶段推进，文本协议在 1.9 内保留作降级路径。
- 核心决策：
  1. 工具循环的中间消息（assistant tool_calls、role=tool）只在单个 turn 内存在，不入历史，不新增 ChatRole.tool（避免用户降级时旧版本读到未知枚举而丢弃整个会话）。
  2. 写类工具先暂存，turn 成功后统一提交；失败、取消、重试不产生重复记忆。
  3. 检索先用内存打分（关键词 + 中文 bigram + 重要度 + 新近度），不引入 FTS 与 embedding。
  4. 人设事实（FACTUAL / CORE_TRAIT）仍全量注入，SHORT_TERM / LONG_TERM 的相关回忆改由 search_memory 检索。
  5. 摘要流程保持独立 LLM 调用，不工具化。

## 1. M0 探针结论（DeepSeek，真实请求，已核实）

- 标准 `tools` / `tool_calls` 可用，`tool_choice: auto` 与 `none` 可用，且模型会在一轮里并行返回多个 tool_calls，执行器必须支持多调用并按 tool_call_id 一一回填。
- `reasoning_content` 回传非必需（去掉后第二轮也成功），但仍建议原样透传 assistant message，兼容思考模式的端点。
- 以 `tool_choice: none` 收尾可用；保持 tools 字段不变以稳定前缀。
- usage 含 `prompt_cache_hit_tokens`，可用来验证工具化后的缓存收益；注意改了 tools 字段的位置或内容会使缓存失效一次。
- 仍未核实：`required` / 指定函数名与思考模式的冲突（本方案不使用）；content 与写类 tool_calls 同时出现的频率（决定「终结型写入」优化是否可用，需统计）。

## 2. 与已落地存储层的关系

- `MemoryStore` 接口已存在（save、saveAll、applyChanges、findById、getAll、getByType、getPersonaFacts、delete、deleteAll、clear、search、getRelevant、markAccessed、getStats、trimCache、loadFromDisk），`AppContainer` 已切到 `SqliteMemoryStore`。
- 本计划需要在接口上新增：`query(MemoryQuery)`（types、limit、sort，返回 items 与 totalMatched）。排序打分放在接口之上，FTS 仅作候选召回加速。
- `MemoryManager`、`MemoryService` 已改依赖 `MemoryStore`，无需再做抽象工作。

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
- 新增 `ChatToolLoop`：maxToolRounds = 4，最后一轮 tool_choice 为 none，单轮最多执行 8 个调用，整个 turn 另设 withTimeout；读类可并发，写类按序且合并为一次 applyChanges。
- 非流式即可，流式作为后续优化。
- 失败回滚：写入暂存于 PendingChanges，turn 成功后沿用现有 postProcessScope 提交；retryMessage 逻辑无需改动。

## 5. 提示词与缓存

- 首条 system 保留人设与用户人设；记忆协议说明改为几行固定的工具使用说明；删除尾部收尾提醒与相关回忆注入，尾部只剩时间。
- 缓存：P2（协议块回贴窗口滑动）消失；P1 由压缩计划处理；P3（人设变化使前缀失效）不变，可选方案是人设快照仅在压缩边界刷新。

## 6. 兼容与降级

- 新增 memoryMode：AUTO（默认）/ TOOLS / TEXT / OFF。端点拒绝 tools（400/422）时标记该 baseUrl+model 不支持并同一 turn 不带 tools 重发，之后自动走 TEXT；TEXT 即现有文本协议链路（含 echo 与 memoryOpsBlock）。
- 端点静默忽略 tools 无法用错误检测，需靠「连续 N 个 turn 零工具调用」启发式或显式设置。
- 1.9 内不删除 MemoryOpsProtocol、echo、memoryOpsBlock；最终是否移除取决于使用数据。

## 7. 测试

- 保留：MemoryOpsProtocolTest 等文本协议用例；MemoryManagerTest 增加工具模式断言。
- 新增：MemoryToolExecutorTest；ChatToolLoop 用 FakeHttpClientEngine 按请求序号脚本化多轮响应；OpenAiCompatibleClientTest 对 tools 数组做字节快照；ChatServiceFailureRollbackTest 增加第二轮失败、取消、重试不重复写入的用例。
- JVM 单测里的 SQLite 实现测试已具备 `JdbcSQLiteDriver` 基础设施。

## 8. 里程碑

| 里程碑 | 内容 | 估时 |
|---|---|---|
| M0 | 探针 [已完成]；MemoryStore 抽象 [已完成] | 0 |
| M1 | 请求/响应层 + ChatToolLoop + 只读 search_memory + MemoryStore.query | 约 4 天 |
| M2 | create/update/delete + 暂存提交 + 永久类删除护栏 | 约 3 天 |
| M3 | 提示词收敛、memoryMode 降级与能力缓存、设置项 | 约 2 天 |
| M4 | 观察期（工具调用率、轮数、失败率、降级触发率，仅记计数与长度）与清理决策 | 约 1 到 2 天 |

回滚：memoryMode 默认值切回 TEXT 即恢复当前行为；记忆与会话数据格式不变，无需数据回滚。

## 9. 未核实项

- deepseek-v4-flash 默认是否开启思考模式及关闭参数的确切形态。
- 写类 tool_calls 同时给出 content 的频率。
- tools 字段在 DeepSeek 前缀缓存中的位置（可用 usage 的 cache_hit 字段在 M1 观察）。
