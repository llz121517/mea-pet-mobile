# 1.9 计划：SQLite 存储 + 上下文压缩 + 模型容量感知（修订版）

> 状态：进行中（Dev 分支）。本版在 2026-11-18 调查报告基础上，按已落地代码与 M0 探针结果修订。
> 已完成项标注 [已完成]，其余为待办。

## 0. 结论与已定决策

- 总体可行，风险中高；压缩质量与缓存收益需实测。
- 已定决策：
  1. 存储选型：`androidx.sqlite` 2.7.1 驱动 API + 薄 DAO，不引入 Room/KSP；JVM 单测用 `sqlite-jdbc` 3.49.1.0 经自写 `JdbcSQLiteDriver` 适配（仅 testImplementation）。
  2. legacy 导入允许侵入启动流程：`LegacyJsonImporter` 在 `AppContainer.warmUp` 中同步前置，不挂入 `LegacyMigration.STEPS`。
  3. 导入成功后旧 JSON 原样保留（不删除、不移动、不改名），清理策略另行决定。
  4. 悬浮窗服务未声明 `android:process`，与主界面同进程，无需处理多进程 WAL。

## 1. 已落地（commit f2569a1）

- [已完成] M0：依赖解析、JDBC 测试驱动、spike 测试（事务、外键级联、WAL、部分唯一索引、user_version、Unicode、keyset 分页）。
- [已完成] M1：`storage/SqliteDatabase`（单连接 + Mutex、BEGIN IMMEDIATE 事务、`user_version` 迁移、V1 schema，已预留 tool_*、extra_json、compaction_summaries、model_capacity 之外的表位）；`ConversationRepository`（SQLite/内存实现）；`MemoryStore` 接口 + `SqliteMemoryStore`，`MemoryRepository`（JSON）实现同一接口作备胎；`ConversationManager` 改为内存窗口 + 有序异步写队列，完整历史留在仓库。
- [已完成] M2：`LegacyJsonImporter`（单事务、幂等、读回校验、标记 meta.legacy_json_import 与数据同事务）。
- [已完成] UI 解耦首版：首屏读仓库最近 50 条，向上 keyset 懒加载；清除对话的撤销快照改用仓库完整历史。
- 测试：全量 JVM 单测 264 个通过，`assembleDebug` 成功。

注意：旧 JSON 只含约 27 到 35 条消息，「完整历史」只能从升级后开始累积，发布说明需写明。

## 2. M0 探针结论（DeepSeek，真实请求）

- `GET /models` 返回 `context_window`（1048576，输入输出合计）与 `max_output_tokens`（393216）。
- 注意模型 id 不一致：`/models` 中为 `deepseek-flash`、`deepseek-v4-pro`，项目默认为 `deepseek-v4-flash`，请求可成功。容量查找需做 id 别名或前缀回退，找不到时走默认值。
- 响应 `usage` 含 `prompt_tokens`、`prompt_cache_hit_tokens`、`prompt_cache_miss_tokens`；第二轮前缀命中 384/592，可用于校验缓存收益与 token 估算校准。
- 当前 `ApiResponse` 尚未解析 `usage` 与容量字段，待 M4 补齐。

## 3. 待办里程碑

### M3 UI 解耦收尾
- 清除对话改为软删除（conversations.state = cleared + 新建活跃会话），撤销只携带 conversationId，到期或下次启动物理清除；当前仍是内存快照方案，大历史下需替换。
- 失败回滚由「写后删」改为「成功后同事务写入」；retryMessage 按 turn 处理。
- 真机验证：向上懒加载的滚动锚定、悬浮窗与主界面同步、WAL 行为。
- 可选：压缩分隔条展示。

### M4 容量获取
- `ApiResponse` 新增 `modelCapacity` 与 `usage` 解析，字段名顺序：context_window、context_length、max_context_length、max_context、max_input_tokens、context_size；值需为合理正整数。
- 探测顺序：用户手动覆盖 → 缓存（TTL 24h，失败负缓存 10 分钟）→ `/models` 条目 → Ollama `/api/ps`、`/api/show` → LM Studio `/api/v0/models/{id}` → 静态表 → 默认 32768。
- 客户端需支持服务根路径请求；设置页增加手动覆盖并显示来源。
- 有效预算 = 容量 − max_tokens − 5% 余量。

### M5 上下文压缩
- 取消批量裁剪，窗口追加式增长，达到阈值才压缩（起始值 软触发 70%、硬触发 90%、压缩后约 35%，均需实测）。
- 滚动摘要放历史最前，仅触发时重写；切点按轮对齐；摘要落 compaction_summaries，记录覆盖区间与 prev_summary_id，唯一约束保证幂等。
- 失败阶梯：保留旧摘要并退避 → 仅在发给模型的窗口内按轮丢弃 → 开关退回纯窗口模式。
- token 估算以本地估算加 usage.prompt_tokens 的 EMA 校准；小窗口（预算小于约 6k）只做硬截断。
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
