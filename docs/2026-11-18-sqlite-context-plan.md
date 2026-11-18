# 1.9 计划：SQLite 存储 + 上下文压缩 + 模型容量感知（修订版）

> 状态：进行中（Dev 分支）。本版在 2026-11-18 调查报告基础上，按已落地代码、M0 探针与补充调研（容量字段、多 system 兼容性）及最新已确认决策修订。
> 已完成项标注 [已完成]，其余为待办。

## 0. 结论与已定决策

- 总体可行，风险中高；压缩质量与缓存收益需实测。
- 已定决策：
  1. 存储选型：`androidx.sqlite` 2.7.1 驱动 API + 薄 DAO，不引入 Room/KSP；JVM 单测用 `sqlite-jdbc` 3.49.1.0 经自写 `JdbcSQLiteDriver` 适配（仅 testImplementation）。
  2. legacy 导入允许侵入启动流程：`LegacyJsonImporter` 在 `AppContainer.warmUp` 中同步前置，不挂入 `LegacyMigration.STEPS`。
  3. 导入成功后旧 JSON 原样保留（不删除、不移动、不改名），清理策略另行决定。
  4. 悬浮窗服务未声明 `android:process`，与主界面同进程，无需处理多进程 WAL。
  5. 有效预算系数固定为 0.85（见 M4），手动值同样适用。
  6. 私网地址与公网同样处理，不做特殊对待；仅回环地址有独立兜底 32768。
  7. 容量获取原则：尽量用通用逻辑，不为各服务单独写 if；不设内置表、不设白名单、不做运行时学习，探测失败直接降级到兜底值。
  8. 压缩摘要并入首条 system 末尾（见 M5）。
  9. tail 目前只含当前时间，并入最后一条 user，不进历史，不使用尾部 system（见 memory-tools-plan 第 9 节）。
  10. 工具循环消息（role=tool）入历史并保留，同轮共用 turn_id，整轮回滚、重试、清除按 turn_id 处理（见 memory-tools-plan 第 10 节）。

## 1. 已落地（commit f2569a1）

- [已完成] M0：依赖解析、JDBC 测试驱动、spike 测试（事务、外键级联、WAL、部分唯一索引、user_version、Unicode、keyset 分页）。
- [已完成] M1：`storage/SqliteDatabase`（单连接 + Mutex、BEGIN IMMEDIATE 事务、`user_version` 迁移、V1 schema，已预留 tool_*、extra_json、compaction_summaries 等表位）；`ConversationRepository`（SQLite/内存实现）；`MemoryStore` 接口 + `SqliteMemoryStore`，`MemoryRepository`（JSON）实现同一接口作备胎；`ConversationManager` 改为内存窗口 + 有序异步写队列，完整历史留在仓库。
- [已完成] M2：`LegacyJsonImporter`（单事务、幂等、读回校验、标记 meta.legacy_json_import 与数据同事务）。
- [已完成] UI 解耦首版：首屏读仓库最近 50 条，向上 keyset 懒加载；清除对话的撤销快照改用仓库完整历史。
- 测试：全量 JVM 单测 264 个通过，`assembleDebug` 成功。

注意：旧 JSON 只含约 27 到 35 条消息，「完整历史」只能从升级后开始累积，发布说明需写明。

## 2. M0 探针结论（DeepSeek，真实请求）

- `GET /models` 返回 `context_window`（1048576，输入输出合计）与 `max_output_tokens`（393216）。官方文档声明这些元数据字段可能缺省，缺省时直接降级到兜底。
- 模型 id：`/models` 中为 `deepseek-flash`、`deepseek-v4-pro`；项目默认模型已改为 `deepseek-flash`。找不到当前模型条目时同样降级到兜底（模型换代丢一次缓存即可，不处理别名漂移）。
- 响应 `usage` 含 `prompt_tokens`、`prompt_cache_hit_tokens`、`prompt_cache_miss_tokens`；第二轮前缀命中 384/592。
- 当前 `ApiResponse` 尚未解析 `usage`，待 M4 补齐。

## 3. 待办里程碑

### M3 UI 解耦收尾
- 清除对话改为软删除（conversations.state = cleared + 新建活跃会话），撤销只携带 conversationId，到期或下次启动物理清除；当前仍是内存快照方案，大历史下需替换。
- 失败回滚由「写后删」改为「成功后同事务写入」；retryMessage、清除按 turn_id 整轮处理（工具消息同轮入历史，见 memory-tools-plan 第 10 节）。
- 真机验证：向上懒加载的滚动锚定、悬浮窗与主界面同步、WAL 行为。
- 可选：压缩分隔条展示。

### M4 容量获取

**有效预算**
- `有效预算 = 容量 × 0.85 − max_tokens`，下限不低于容量的 25%。系数固定 0.85，对探测值、手动值、兜底值统一生效。
- `max_tokens` 是请求参数（本轮输出上限，默认 4096），不是容量字段；上下文窗口是输入输出合计，所以要减去。`max_tokens` 应夹到容量约 25% 之内。
- 推理 token 也可能占用窗口，余量需保留。

**兜底容量（原始容量，再乘系数）**
- 云端与私网（与公网同样处理）：原始容量 131072，乘 0.85 约 111411。
- 回环地址（`localhost`、`127.0.0.0/8`、`::1`）：原始容量 32768，乘 0.85 约 27853。
- 兜底只在探测无结果时使用。

**单一适配器（`ContextCapacityProbe`，独立抽出）**
- 只发 `GET {baseUrl}/models`，不做各服务原生接口分支，不设字段白名单，不设内置表。
- 通用解析：在响应里找到 id 等于当前模型的条目（忽略大小写；响应可能是 `{data:[...]}` 或顶层数组），在该条目内递归查找名字含 `context` 且值为 1024 到 10,000,000 之间正整数的字段（如 `context_window`、`context_length`、`max_context_length`、`max_model_len`）；多个命中取较小者。
- 找不到条目、找不到字段、请求失败、406 等一律降级到兜底值，不报错、不重试风暴。
- 读不到的服务（OpenAI、Ollama、LM Studio 等）直接降级；本地推理服务的实际加载窗口请用户手动填写。
- 备选：若实测降级命中率太低，再引入 models.dev 的表。

**获取契机（统一入口，云端与本地共用同一套逻辑与频率）**
1. 保存或切换服务商设置（baseUrl、API Key、模型）时。
2. 应用首次启动时。
3. 首次发送且内存没有缓存时（本次用兜底值，探测完成后下一轮生效，不阻塞）。
4. 缓存过期时：每 5 分钟检查一次，过期则后台刷新；失败做短期负缓存，避免重复请求。
- 设置页拉取模型列表时，从同一次 `/models` 响应顺带解析，不多发请求。

**优先级与存储**
- 应用先检查 DataStore 里的手动值：有值就直接使用，不再使用缓存值。
- 缓存值仅内存态，不落盘；换模型、换服务商或进程重启即失效。
- 最终来源标记：手动 / 探测 / 兜底。

**设置页**
- 新增「上下文窗口（token）」，使用 `OutlinedTextField`，右侧 `trailingIcon` 用于重置（清除 DataStore 值，回到自动）。
- 值写入 DataStore；留空或重置表示自动。
- 旁边显示当前生效值与来源（手动 / 探测 / 兜底）。
- 文案写明：手动值同样乘 0.85，实际使用约为填写值的 85%；本地推理服务请按实际加载的窗口填写。
- `max_tokens` 保持现有独立设置，不与它合并。

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
- 会话表存储工具循环的中间消息（role=tool 及 assistant 的 tool_calls），使用 V1 已预留的 tool_* 与 extra_json 列，同轮共用 turn_id，在同一事务写入（见 memory-tools-plan 第 10 节）；无需新迁移。
- 窗口裁剪与压缩切点按轮对齐，保证 tool_calls 与 tool 结果成对；压缩摘要输入中 tool 结果截断或概括。
- 检索不依赖 FTS（见 memory-tools-plan 第 11 节）；若日后需 FTS 或新列，走 `user_version = 2`，由两边协调迁移归属。

## 5. 风险与未核实项

- 压缩质量、阈值与估算系数均为起始假设。
- Android 框架 SQLite 的 FTS5 不可用（FTS4 可用），中文检索 v1 不依赖 FTS。
- 真机行为（WAL、并发写、滚动）尚未验证。
- 通用解析对读不到容量字段的服务（OpenAI、Ollama、LM Studio 等）全部降级到兜底，命中率需上线后观察；本地推理服务的实际窗口可能小于兜底值，依赖用户手动填写。
- 未做超限报错学习：窗口估大时请求可能被服务端拒绝，1.9 不处理，待有真实报错样本后再评估。
