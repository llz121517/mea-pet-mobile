# 1.9 计划：SQLite 存储 + 上下文压缩 + 模型容量感知（修订版）

> 状态：进行中（Dev 分支）。本版在 2026-11-18 调查报告基础上，按已落地代码、M0 探针与两轮补充调研（容量字段、多 system 兼容性）修订。
> 已完成项标注 [已完成]，其余为待办。

## 0. 结论与已定决策

- 总体可行，风险中高；压缩质量与缓存收益需实测。
- 已定决策：
  1. 存储选型：`androidx.sqlite` 2.7.1 驱动 API + 薄 DAO，不引入 Room/KSP；JVM 单测用 `sqlite-jdbc` 3.49.1.0 经自写 `JdbcSQLiteDriver` 适配（仅 testImplementation）。
  2. legacy 导入允许侵入启动流程：`LegacyJsonImporter` 在 `AppContainer.warmUp` 中同步前置，不挂入 `LegacyMigration.STEPS`。
  3. 导入成功后旧 JSON 原样保留（不删除、不移动、不改名），清理策略另行决定。
  4. 悬浮窗服务未声明 `android:process`，与主界面同进程，无需处理多进程 WAL。
  5. 有效预算系数固定为 0.85（见 M4）。
  6. 私网地址与公网同样处理，不做特殊对待；仅回环地址有独立兜底。
  7. 探测逻辑在所有模式下共用，全量探测（见 M4）。
  8. 压缩摘要并入首条 system 末尾（见 M5）。
  9. tail 只放每轮动态内容，不进历史（见 memory-tools-plan 第 9 节）。

## 1. 已落地（commit f2569a1）

- [已完成] M0：依赖解析、JDBC 测试驱动、spike 测试（事务、外键级联、WAL、部分唯一索引、user_version、Unicode、keyset 分页）。
- [已完成] M1：`storage/SqliteDatabase`（单连接 + Mutex、BEGIN IMMEDIATE 事务、`user_version` 迁移、V1 schema，已预留 tool_*、extra_json、compaction_summaries 等表位）；`ConversationRepository`（SQLite/内存实现）；`MemoryStore` 接口 + `SqliteMemoryStore`，`MemoryRepository`（JSON）实现同一接口作备胎；`ConversationManager` 改为内存窗口 + 有序异步写队列，完整历史留在仓库。
- [已完成] M2：`LegacyJsonImporter`（单事务、幂等、读回校验、标记 meta.legacy_json_import 与数据同事务）。
- [已完成] UI 解耦首版：首屏读仓库最近 50 条，向上 keyset 懒加载；清除对话的撤销快照改用仓库完整历史。
- 测试：全量 JVM 单测 264 个通过，`assembleDebug` 成功。

注意：旧 JSON 只含约 27 到 35 条消息，「完整历史」只能从升级后开始累积，发布说明需写明。

## 2. M0 探针结论（DeepSeek，真实请求）

- `GET /models` 返回 `context_window`（1048576，输入输出合计）与 `max_output_tokens`（393216）。官方文档声明这些元数据字段可能缺省，故保留内置表作回退。
- 模型 id：`/models` 中为 `deepseek-flash`、`deepseek-v4-pro`；项目默认模型已改为 `deepseek-flash`。用户自填的旧 id（如 `deepseek-v4-flash`）容量查找需做别名或前缀回退。
- 响应 `usage` 含 `prompt_tokens`、`prompt_cache_hit_tokens`、`prompt_cache_miss_tokens`；第二轮前缀命中 384/592。
- 当前 `ApiResponse` 尚未解析 `usage` 与容量字段，待 M4 补齐。

## 3. 待办里程碑

### M3 UI 解耦收尾
- 清除对话改为软删除（conversations.state = cleared + 新建活跃会话），撤销只携带 conversationId，到期或下次启动物理清除；当前仍是内存快照方案，大历史下需替换。
- 失败回滚由「写后删」改为「成功后同事务写入」；retryMessage 按 turn 处理。
- 真机验证：向上懒加载的滚动锚定、悬浮窗与主界面同步、WAL 行为。
- 可选：压缩分隔条展示。

### M4 容量获取

**有效预算**
- `有效预算 = 容量 × 0.85 − max_tokens`，下限不低于容量的 25%。系数固定 0.85，对 API 读到的值、手动覆盖值、兜底值统一生效。
- `max_tokens` 是请求参数（本轮输出上限，默认 4096），不是容量字段；上下文窗口是输入输出合计（DeepSeek 报错证实 `max_tokens` 计入窗口），所以要减去。`max_tokens` 应夹到模型 `max_output_tokens` 与容量约 25% 之内。
- 推理 token 也可能占用窗口，余量需保留。

**兜底容量（统一写原始容量，再乘系数）**
- 云端与私网（与公网同样处理）：原始容量 131072，乘 0.85 约 111411。
- 回环地址（`localhost`、`127.0.0.0/8`、`::1`）：原始容量 32768，乘 0.85 约 27853。
- 兜底只在探测与学习都无结果时使用。

**字段解析**
- `ApiResponse` 新增 `modelCapacity` 与 `usage` 解析。字段白名单：`context_window`、`context_length`、`max_context_length`、`max_model_len`、`top_provider.context_length`、`meta.n_ctx`、`meta.n_ctx_train`、`inputTokenLimit`、`max_input_tokens`（0 或 null 视为未知）、`contextLength`。值需为 1024 到 10,000,000 的正整数。
- 同一条目有多个值时取生效值，并取较小者。响应可能是 `{data:[...]}`，也可能是顶层数组（Together）。

**探测逻辑（与云端模式共用，全量探测）**
1. 超限报错学到的值（最高）。
2. 用户手动覆盖。
3. 缓存（TTL 24h，失败负缓存 10 分钟）。
4. `GET {base}/models` 条目（白名单字段）。
5. 原生接口，全部尝试，失败即跳过：Ollama `/api/ps`（运行时值）与 `/api/show`；LM Studio `/api/v1/models` 的 `loaded_instances[].config.context_length`，否则 `max_context_length`；llama.cpp `/props` 的 `default_generation_settings.n_ctx`（每 slot，多 slot 时为总量除以 slot 数）；LiteLLM `/v1/model/info`。
6. 内置表（DeepSeek、Kimi 等）。
7. 兜底默认值。
- 客户端需支持服务根路径请求；设置页增加手动覆盖并显示来源。

**运行时学习逻辑**
- 触发：HTTP 400 或 413，且响应体匹配“超出上下文”特征。不能只看状态码（Anthropic 的欠费与超长都是 400）。
- 解析：取 maximum 一侧的数，不取 requested 的数；同一正文既有 requested 又有 maximum 时取 maximum；忽略小于 1024 或大于 1000 万的值。
- 宽松正则（忽略大小写）：
  - `(maximum|max(imum)?)\s+context\s+length\s+(is|of)\s+(\d{3,8})`
  - `exceeds?\s+the\s+available\s+context\s+size\s*\((\d{3,8})`
  - `n_ctx"?\s*[:=]\s*(\d{3,8})`
  - `prompt\s+is\s+too\s+long:\s*(\d+)\s*tokens\s*>\s*(\d{3,8})`
  - `exceeded\s+model\s+token\s+limit:\s*(\d{3,8})`
  - `Range of input length should be \[1,\s*(\d{3,8})\]`
  - `maximum number of tokens allowed\s+(\d{3,8})`
- 学到后：立即触发强制压缩并重试一次；写入缓存（`source=learned`，按 baseUrl+model 持久化），优先级最高；服务重启或设置变更后允许重新学习。
- Ollama 超长时静默截断、不报错：能发送时带上 `options.num_ctx`，或在设置中提示用户。

### M5 上下文压缩
- 取消批量裁剪，窗口追加式增长，达到阈值才压缩（起始值 软触发 70%、硬触发 90%、压缩后约 35%，均需实测）。
- 摘要位置决策：**并入首条 system 末尾**，不做首条 system 之后的独立 user，也不做第二条 system。理由：只在压缩时变化，缓存代价与独立消息相同；不破坏 user/assistant 交替，兼容 Gemma、Mistral 等严格模板；Anthropic 兼容层与 DeepSeek-V3 模板本来就会把 system 合并到最前。注意 `buildApiMessages` 会过滤历史中的 system，摘要须单独传入。
- 滚动摘要仅触发时重写；切点按轮对齐；摘要落 compaction_summaries，记录覆盖区间与 prev_summary_id，唯一约束保证幂等。
- 失败阶梯：保留旧摘要并退避 → 仅在发给模型的窗口内按轮丢弃 → 开关退回纯窗口模式。
- token 估算以本地估算加 usage.prompt_tokens 的 EMA 校准；预算小于约 6k 时只做硬截断。
- 与记忆摘要（summarizeShortTermMemories）分开实现，仅共用单次 LLM 调用工具，并共享低优先级互斥。

### M6 收尾
- 文档、发布说明、备胎代码（JsonMemoryRepository、ConversationStore）的 1.10 清理登记。

## 4. 与记忆工具化计划的接口约束

- `MemoryStore` 接口已落地，工具执行体只依赖接口；`applyChanges` 必须原子；`getPersonaFacts` 保持 createdAt 升序（SQLite 实现已加 id 作次级键）。
- 会话表不写入工具中间消息，只存 user/assistant（schema 预留的 tool 列暂不使用）。
- 若需 FTS 或新列，走 `user_version = 2`，由两边协调迁移归属。

## 5. 风险与未核实项

- 压缩质量、阈值与估算系数均为起始假设。
- Android 框架 SQLite 的 FTS5 不可用（FTS4 可用），中文检索 v1 不依赖 FTS。
- 真机行为（WAL、并发写、滚动）尚未验证。
- 容量字段未核实：国内各云（硅基、百炼、智谱、方舟、MiniMax）、one-api、new-api、MNN、MLC、KoboldCpp、LM Studio OpenAI 兼容 `/v1/models`。
- OpenAI 的超限报错真实文案未取得。
