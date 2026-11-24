# 联网工具：web_search 与 web_fetch

> 状态：代码接入完成，待真机验收（2026-11-21）。S4（联网独立于记忆模式）已实施。

## 当前范围

- 两个独立的 function tool：`web_search(query, limit)` 搜索公开网页；`web_fetch(url)` 读取已知网页正文。
- 设置位于「提供商 → 联网搜索」，排在「模型参数」之后，默认关闭。可选自动、DeepSeek、AnySearch。两家均可填写独立 Key；DeepSeek 留空时，在「复用聊天 Key」开关开启（默认）且聊天地址为官方 DeepSeek 的前提下复用聊天 Key，关闭该开关后不再复用、必须单独填写；AnySearch 留空时使用匿名模式。
- 联网开关独立于记忆设置：记忆「自动 / 工具 / 只读 / 关闭」以及记忆总开关的任意组合下，只要联网开关打开且聊天端点支持 function calling，就下发 `web_search` / `web_fetch`。记忆「只读」模式仍注入关键词命中；「关闭」或记忆总开关关闭时不注入记忆。
- 一轮开始时按当轮资格组合工具（记忆与联网互不依赖），设置变化从下一轮生效。Auto 的每次搜索/抓取调用都从候选列表头开始，失败时按顺序尝试下一家，不缓存故障状态。
- 关闭联网时，服务商和 Key 配置使用与动态取色相同的垂直动画收起；页底保留精简提示，详细的选择规则、凭据、隐私与计费说明放进「服务商」问号。上下文窗口、最大 Token、记忆检索方式、摘要间隔也提供问号帮助。

## 代码结构

模型调用 → `ChatToolLoop` → `ToolRegistry` → `WebSearchTool` / `WebFetchTool` → `WebProvider` → 官方 HTTP 接口。

| 文件 / 类 | 职责 |
|---|---|
| `search/WebTools.kt` | 固定 schema、参数校验、超时、输出体积与错误信封 |
| `search/WebProvider.kt` | 搜索结果、网页正文及错误模型 |
| `search/DeepSeekWebProvider.kt` | DeepSeek Search / Fetch 参数与响应映射 |
| `search/AnySearchWebProvider.kt` | AnySearch Search / Extract，匿名或 Bearer Key |
| `search/WebProviderHttp.kt` | HTTP 状态归一化、受限重试与响应解析 |
| `search/WebProviderResolver.kt` | 按显式设置或聊天地址选择可用适配器 |
| `client/JsonHttpClient.kt` | 基于现有 HTTP 引擎发 JSON，不记录 body、不抛聊天 `ApiException` |
| `search/WebToolFactory.kt` | 按一轮的配置快照选择服务商并固定凭据，生成两个工具 |
| `app/AppContainer.kt` | 开关判断、配置读取与工具循环装配 |

## 工具行为

### web_search

- 必填 `query`，去掉首尾空白后长度为 1–128 个 Unicode 码点。
- 可选整数 `limit`，默认 5，执行时夹到 1–10。
- 成功返回 `ok`、`results`、`returned`；每条结果含标题、完整 URL、摘要及可选网站名和日期。
- 标题最多 120 个 UTF-16 单元，摘要 200，网站名 40，日期 32；避免截断 Unicode 代理对。URL 保持完整，仅保留合法 HTTP(S) 链接。
- **序列化后的 JSON 总长不超过 1500 个 UTF-16 单元**，包括转义字符和字段开销。放不下的结果省略，`returned` 以实际条数为准。

### web_fetch

- 必填完整的 HTTP(S) `url`，最多 8192 个 UTF-16 单元，不接受账号密码或缺少 host 的 URL。
- 请求由适配器发往固定服务商接口；本机不直接访问模型提交的 URL。用于服务商请求的专用 HTTP 引擎关闭重定向。
- 成功返回 `ok`、`title`、`content`、`truncated`。正文最多 6000 个 UTF-16 单元，**整个 JSON 不超过 8000**，转义开销计入预算。
- `truncated` 只表示应用侧是否截断；不能据此判断服务商是否返回了完整原文。
- 两个 schema 都说明外部内容仅作为资料，正文中的指令不能当作系统指令。

## 适配器与配置

- DeepSeek：固定 `https://api.deepseek.com/v1/tools/search` 与 `/fetch`。优先使用可选的独立 Key；独立 Key 留空时，只有在「复用聊天 Key」开关开启（设置项 `web_search_reuse_chat_key`，默认开启）**且**聊天连接使用官方 HTTPS host、默认或 443 端口且不含用户信息时，才复用聊天 Key。开关关闭后一律只用独立 Key，未填写则 DeepSeek 服务商不可用——这样「把中转/聚合服务的 Key 发给 DeepSeek」必须由用户显式同意。URL 与聊天 Key 从同一份设置快照读取，避免跨配置组合凭据。
- AnySearch：固定 `https://api.anysearch.com/v1/search` 与 `/extract`。Key 为空时不带 Authorization，有 Key 时发 Bearer，鉴权失败不回落匿名。
- Auto 先列出 `endpointHost` 匹配聊天 host 的可用适配器，再按装配顺序列出其余可用适配器。每次搜索/抓取失败或超时会尝试下一家；参数错误直接返回、用户取消继续传播。成功即停止，空搜索结果也算成功；全部失败则返回最后一个规范化错误。显式选择不切换服务商，不可用时返回 `not_configured`。
- 持久设置：`web_search_enabled`（默认 false）、`web_search_provider`（默认 auto）、`web_search_anysearch_key`（默认空）、`web_search_deepseek_key`（默认空）。

## 错误、延迟与隐私

- 单次工具总预算 15 秒，涵盖 HTTP、重试与 Auto 切换。Auto 给每个候选分配 `(15000−250)/候选数` 毫秒，两个候选时每家最多 7375ms，保证第一家超时后仍有机会尝试下一家。单家适配器仅对 HTTP 429 / 5xx 做一次约 800ms 退避重试，包含在该家的预算内；网络异常、解析失败及超时不在同一家重试。
- `invalid_request`、`unauthorized`、`quota_exceeded`、`rate_limited`、`unavailable`、`not_configured`、`not_found`、`access_denied` 均返回固定的工具错误信封。
- 服务商错误正文不解析、不记录、不进入模型上下文，特别是可能携带自动生成凭据的 AnySearch 402。
- Tool 捕获异常，只有取消继续传播。搜索 / 抓取失败不会变成聊天端点不支持 tools 的判定。
- 不记录查询、网页、正文或 API Key；不添加请求内容日志。

## 测试与待验证项

自动化用例覆盖适配器请求映射、匿名 / Key、HTTP 错误与重试、响应解析、URL 与预算、超时 / 取消、每轮固定工具列表、下一轮关闭联网、工具错误隔离及设置持久化；S4 另覆盖记忆模式 × 联网开关矩阵、记忆总开关、只读注入、旧工具历史回放与端点拒绝后的降级副作用。本轮本地验证为 447 条单测通过、debug / androidTest 构建通过、Markdown 链接检查通过（**2026-11-24 复核时全量为 571 条**）。设备验证仍待完成：AnySearch token、异常网络、限流和额度提示，以及折叠 / 帮助和自动切换等待行为。

## S4 实施结果（已完成，2026-11-21）

联网开关已与记忆模式、记忆总开关解耦：

1. `ChatService` 每轮分别计算「记忆工具资格」（`config.enableMemoryTools` ∧ 记忆总开关 ∧ 模式 `usesTools`）与「联网资格」（`getWebSearchEnabled()`），二者任一成立就尝试工具循环。
2. `AppContainer` 按当轮资格组合注册表：仅记忆 / 仅联网 / 两者都有，沿用同一 `ToolRegistry`；不把资格 lazy 缓存在字段里，动态设置不会被捕获成过期值。`MemoryToolExecutor` 的执行与提交门控绑在记忆总开关上，模式切换下一轮生效。
3. 上下文与历史跟随「实际拿到的工具循环」：工具循环存在时回放结构化工具历史；`TEXT + web` 仍注入只读关键词命中，`OFF` 或记忆总开关关闭不注入；端点拒绝工具后的纯文本回退过滤工具历史。
4. 记忆提交与摘要只在「记忆工具模式且本轮成功走完工具循环」的轮次发生（`memoryToolsUsed = memoryToolsActive && toolsUsed`）；仅联网（记忆不合格）或只读 / 关闭模式的轮次零记忆副作用。注意：记忆与联网同时启用时，模型只调用联网工具的轮次仍算记忆工具模式轮次、会推进摘要计数（`ChatServiceToolEligibilityTest` 有断言），但不会产生记忆内容写入。
5. 端点能力负缓存按 `url + key + model` 快照失效；拒绝判定收紧到能力类文案与顶层参数拒绝，`tools[].function.parameters` 这类 schema 错误只当普通请求失败。
6. 设置帮助文案已改为「联网与记忆相互独立」。

回归矩阵覆盖四种记忆模式 × 联网开关、记忆总开关关闭、旧工具历史回放、端点拒绝降级无记忆副作用。工具循环已产生记录后再遇到 HTTP 错误，会整轮失败并丢弃暂存记忆，不再中途降级或误写能力负缓存。

## 官方资料

- [DeepSeek Search](https://api-docs.deepseek.com/api/search/)
- [DeepSeek Fetch](https://api-docs.deepseek.com/api/fetch/)
- [AnySearch Search](https://www.anysearch.com/docs/api-endpoints/v1-search)
- [AnySearch Extract](https://www.anysearch.com/docs/api-endpoints/v1-extract)
- [AnySearch 鉴权](https://www.anysearch.com/docs/auth)
