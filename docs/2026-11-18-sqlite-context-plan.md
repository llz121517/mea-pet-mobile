# 1.9 计划：SQLite 存储 + 上下文压缩 + 模型容量感知（修订版）

> 状态：**已实施（dev 分支，1.9.0）**，M0–M5 全部落地；M3 的「清除对话软删除」与真机验收留待 1.10 / 发布前（见 §3 M3）。本版在 2026-11-18 调查报告基础上，按已落地代码、M0 探针与补充调研（容量字段、多 system 兼容性）及最新已确认决策修订。
> 已完成项标注 [已完成]，其余为待办。

## 0. 结论与已定决策

- 总体可行，风险中高；压缩质量与缓存收益需实测。
- 已定决策：
  1. 存储选型：`androidx.sqlite` 2.7.1 驱动 API + 薄 DAO，不引入 Room/KSP；JVM 单测用 `sqlite-jdbc` 3.49.1.0 经自写 `JdbcSQLiteDriver` 适配（仅 testImplementation）。
  2. legacy 导入允许侵入启动流程：`LegacyJsonImporter` 在 `AppContainer.warmUp` 中同步前置，不挂入 `LegacyMigration.STEPS`。
  3. 导入成功后旧 JSON 原样保留（不删除、不移动、不改名），清理策略另行决定。
  4. 悬浮窗服务未声明 `android:process`，与主界面同进程，无需处理多进程 WAL。
  5. 有效预算系数固定为 0.85（见 M4），手动值同样适用。
  6. 私网地址与公网同样处理，不做特殊对待；仅回环地址保留独立常量（当前与云端同为 32K）。
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
- [已完成 2026-11-19] `ApiResponse` 已解析 `usage.prompt_tokens`（校准估算）与 `usage.prompt_cache_hit_tokens`（`TurnMetrics` 观察前缀缓存命中率），见 M4。

## 3. 待办里程碑

### M3 UI 解耦收尾
- 清除对话改为软删除（conversations.state = cleared + 新建活跃会话），撤销只携带 conversationId，到期或下次启动物理清除；当前仍是内存快照方案，大历史下需替换。**[1.10 待做]**：单会话模型下要把 `conversationId` 变成可切换状态（仓库/压缩状态/当前会话指针），牵涉面大，1.9 保持现状（撤销快照已能工作）。
- [已完成 2026-11-19] 失败回滚由「写后删」改为「成功后同事务写入」；retryMessage 按整轮处理：一个 turn 的 user + assistant 片段 + tool 消息收齐后经 `appendAll` **一个事务**落库，失败/超时不写，取消时按已完成部分提交；`retryMessage` 移除该 user 及其后全部消息（工具消息同轮入历史，见 memory-tools-plan 第 10 节）。
- [待做] 真机验证：向上懒加载的滚动锚定、悬浮窗与主界面同步、WAL 行为、工具消息往返、插队/停止、压缩分隔条（本轮按要求暂不操作设备）。
- [已完成] 压缩分隔条：样式 `──────── 早些时候 ────────`（淡色），显示在 `seq == active_summary.covered_to_seq` 之后，即已压缩进摘要的消息与仍逐字发送给模型的消息之间；点击可展开摘要全文（`compaction_summaries` 已有溯源，无需额外存储）。
  - 实现注记：为免迁移，压缩状态未写入 `compaction_summaries` 表，而是整份 JSON 存进 `meta` 表键 `context_compaction`（`MetaCompactionStore`）；分隔条按「已被覆盖的最后一条消息 id」定位（`ChatUiState.compactedUpToMessageId`），未做点击展开摘要（留作后续）。
- [已完成] 按天轻时间分隔条：相邻两条可见消息跨自然日（设备本地时区）时插入，文案 `──────── 今天 ────────`、`昨天`、`M月D日`，跨年带年份。纯 UI 派生项，由消息 `timestamp` 在列表渲染时计算，不入库、不进模型上下文；与压缩分隔条复用同一个分隔条组件，仅文案不同；两者位置重合时先显示日期条再显示「早些时候」；判断相邻消息时跳过 tool 消息与空 assistant 消息，触摸系统气泡不参与；向上懒加载时在页与页交界处按相邻消息重新判断，避免重复或遗漏（首条无前驱时总给日期条）。

### M4 容量获取

**有效预算**
- `有效预算 = 容量 × 0.85 − max_tokens`，下限不低于容量的 25%。系数固定 0.85，对探测值、手动值、兜底值统一生效。
- `max_tokens` 是请求参数（本轮输出上限，默认 4096），不是容量字段；上下文窗口是输入输出合计，所以要减去。`max_tokens` 应夹到容量约 25% 之内。
- 推理 token 也可能占用窗口，余量需保留。

**兜底容量（原始容量，再乘系数）**
- 云端、私网、回环统一使用**原始容量 32768**（乘 0.85 约 27852）；回环常量保留独立定义，当前与云端同值。
- 为什么是 32K 而不是 128K：**估大的代价是请求被服务端 400 拒绝**（还白等一次往返），
  估小只是提前压缩/截断。两害相权取其小，所以兜底按小窗口假设；探测值或用户手动填写可覆盖。
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

- [已完成 2026-11-19] **usage 解析与观察指标**：`ApiResponse` 解析 `prompt_tokens`（EMA 校准估算）与 `prompt_cache_hit_tokens`；`TurnMetrics` 每次 turn 记一行（模式/结果/轮数/调用数/耗时/缓存命中），累计命中率进 `snapshot()`，只含计数、不含内容。观察期用 `adb logcat -s TurnMetrics` 统计。

### M5 上下文压缩 [已完成]
- [已完成] 取消批量裁剪：`ConversationManager(autoTrim = false)` —— 内存窗口**追加式增长**、不再批量裁剪；启动恢复时装入**完整历史**（否则「已压缩覆盖点」可能落在窗口外，导致摘要与原文重复）；发送侧在压缩启用时不再用 `maxHistoryMessages` 截断（`maxMessages = Int.MAX_VALUE`），改由「压缩摘要 + 发送预算」控制。纯窗口模式（压缩关闭）仍保留 `maxSize/trimBatch` 裁剪。
- [已完成] **手动压缩为主路径**：下一次请求预计发送的上下文 token（本地估算，有 usage.prompt_tokens 后用校准值）超过文件级常量 `COMPACT_HINT_TOKENS = 65536` 时，弹一次 Snackbar，带「整理一下」动作；同一会话只弹一次，忽略后不重复，压缩完成或清除对话后重置。顶部菜单平时不显示「压缩上下文」，越过阈值后才出现，回落到阈值以下隐藏；Snackbar 动作与菜单项走同一个压缩入口（`ChatService.compactContext`），压缩期间显示「整理中…」并禁用，不阻塞输入。
- [已完成] Snackbar 文案口语化：`我们聊了好久啦，要不要让 Mea 把前面的内容整理一下喵？` / 动作 `整理一下`。
- [已完成] **自动压缩只做兜底**：仅保留硬触发（上下文逼近有效预算 90% 时同步压缩），不弹任何提示；失败退化为仅在发给模型的窗口内按轮丢弃。小窗口（预算 < 6000）跳过 LLM 摘要直接截断。
- [已完成] 摘要位置：**并入首条 system 末尾**（`──────── 对话摘要 ────────` 段），不做独立 system/user 消息。
- [已完成] 滚动摘要：新摘要 = LLM(旧摘要 + 本次折叠的消息)；切点按轮对齐，最近最少 4 轮原文保留；失败返回 null 由调用方兜底。
- [已完成] token 估算：CJK 1.0/字、拉丁 0.25/字符、其他 0.5、每条 +4；用 `usage.prompt_tokens` 做 EMA（0.3）校准，比值夹在 0.5~2.0。
- [已完成] 存储：压缩状态整体 JSON 存 `meta` 表键 `context_compaction`（`MetaCompactionStore`），无需 schema 迁移；清除对话时一并清除。
- [已完成] 单测：`TokenEstimatorTest`、`ContextCompactorTest`（切点/丢弃/摘要请求/失败）、`MetaCompactionStoreTest`、`ChatServiceCompactionTest`（摘要注入首条 system、已覆盖消息不再发送、手动压缩、硬触发兜底、清除复位）。
- 待办：
  - ~~点击「早些时候」展开摘要全文~~ —— **已否决（不做）**：分隔条只作模型视角提示，摘要正文无需暴露给用户；
  - `compaction_summaries` 表未启用（当前用 `meta` 表 JSON 存储整份状态），若后续需要按区间溯源再迁移；
  - 阈值与系数（64K 提醒、90% 硬触发、0.85 预算系数、token 估算权重）尚为起始值，需按真实使用调参。

### M6 收尾
- 文档、发布说明、备胎代码（JsonMemoryRepository、ConversationStore）的 1.10 清理登记。

## 4. 存储铁律与连接模型（已落地）

### 4.1 铁律：事务内绝不 await 网络或 LLM

写事务持写锁期间，所有读要排队。**禁止在事务里发 HTTP、调 LLM、等待信号量或任何挂起操作**——
一次 LLM 往返可能数秒，会把读路径（首屏、上滑分页）整个堵死。

- 类型兜底：`SqliteDatabase.transaction { block }` 的 block 是**非挂起函数**，编译器不允许在事务里 await。
- 正确姿势：网络/LLM 的结果先在事务外取好，再把纯数据传进事务写入。
  已落地的「压缩摘要」「legacy JSON 导入」「记忆 applyChanges」都遵此约定，均为短事务。

### 4.2 连接模型：一写多读

WAL 支持「一个写者 + 多个读者」并发，因此 `SqliteDatabase` 拆成：

| 角色 | 数量 | 用途 | 互斥 |
|---|---|---|---|
| 写连接 | 1（单例） | `BEGIN IMMEDIATE` 事务 | `writeMutex` 串行 |
| 读连接 | 默认 2（池） | 首屏 50 条、keyset 上滑分页、记忆/压缩状态读取 | `Semaphore(2)`，与写互不阻塞 |

- 首屏读、向上懒加载、后台压缩写入不再排在一条连接上；某个事务稍长时滚动也不顿。
- 读拿到的是提交前的一致快照（符合预期，UI 侧有 flush 收敛）。
- `PRAGMA foreign_keys` 是连接级开关，每条连接单独设置；迁移只在首次初始化写连接时执行一次。
- 单测：`SqliteDatabaseConcurrencyTest`（4 读分页 + 2 读记忆 + 1 写记忆并发，最终一致）。

## 5. 与记忆工具化计划的接口约束

- `MemoryStore` 接口已落地，工具执行体只依赖接口；`applyChanges` 必须原子；`getPersonaFacts` 保持 createdAt 升序（SQLite 实现已加 id 作次级键）。
- 会话表存储工具循环的中间消息（role=tool 及 assistant 的 tool_calls），使用 V1 已预留的 tool_* 与 extra_json 列，同轮共用 turn_id，在同一事务写入（见 memory-tools-plan 第 10 节）；无需新迁移。
- 窗口裁剪与压缩切点按轮对齐，保证 tool_calls 与 tool 结果成对；压缩摘要输入中 tool 结果截断或概括。
- 检索不依赖 FTS（见 memory-tools-plan 第 11 节）；若日后需 FTS 或新列，走 `user_version = 2`，由两边协调迁移归属。

## 6. 风险与未核实项

- 压缩质量、阈值与估算系数均为起始假设。
- Android 框架 SQLite 的 FTS5 不可用（FTS4 可用），中文检索 v1 不依赖 FTS。
- 真机行为（WAL、并发写、滚动）尚未验证。
- 通用解析对读不到容量字段的服务（OpenAI、Ollama、LM Studio 等）全部降级到兜底，命中率需上线后观察；本地推理服务的实际窗口可能小于兜底值，依赖用户手动填写。
- 未做超限报错学习：窗口估大时请求可能被服务端拒绝，1.9 不处理，待有真实报错样本后再评估。
