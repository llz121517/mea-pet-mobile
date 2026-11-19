# 计划：web 搜索工具（通用映射 + 适配器层）

> 状态：**调研完成，待实施**（2026-11-19）。首批适配器：DeepSeek 官方 Search API（复用聊天 API Key）、AnySearch（匿名档 / token 档）。
> 前置依赖：[`TODO.md`](TODO.md) 待办 E 的 **S4：拆 `ChatService.wantsTools`**。
> 本版只记录已核实的事实与设计；标注「未核实」的点在实施前需真机/官方文档确认。

## 0. 结论

- 可行。做法是**对模型只暴露一个普通 function tool `web_search`**，下面接「通用映射 + 各家适配器」。
- 不采用 DeepSeek 服务端内置工具 `deepseek_web_search`：它只支持 `deepseek-flash`、`tool_choice` 不能指定、由服务端执行；而普通 function tool 复用现有 `ToolRegistry` / `ChatToolLoop` 链路，对所有模型与适配器通用（符合 `rules.md` 通用逻辑优先）。
- 首批只做 `web_search`；`web_fetch`（DeepSeek `/v1/tools/fetch`、AnySearch `/v1/extract`）列为后续。

## 1. 接口调研结论（已核实）

### 1.1 DeepSeek Search API（与聊天同 Key）

- 端点：`POST https://api.deepseek.com/v1/tools/search`，`Authorization: Bearer <DeepSeek API Key>`，`Content-Type: application/json`。
- 请求：`{"query": "1~128 字", "count": 1~50（默认 10）, "ori_question": 可选 1~128 字, "need_content": 默认 false}`；请求体未知字段被忽略。
- 响应（HTTP 200）：`{"results":[{"rerank_score","title","url","snippet","summary","content","site_name","site_icon","date","authority"}]}`，无匹配返回空数组。
  - `snippet` 约 100~200 字（适合展示/入上下文）；`summary` 约 600~800 字；`content` 需 `need_content=true` 且正文可用。
  - `rerank_score` 0~3（≥1.5 视为高质量）；`authority` 0~4（-1 未知）；`date` 为 RFC3339 UTC。
- 抓取：`POST /v1/tools/fetch`，`{"url"}` → `{"title","content"(Markdown)}`，URL ≤ 8192 字符。
- 计费：Search / Fetch 各 $0.75 / 千次**成功**请求（仅 200 且有结果计费；失败、空结果、超时不计）；`count` 不影响单价。
- 限流：Search QPS 100、Fetch QPS 25（按账号）；超限 429。
- 错误：400 参数无效、401 Key 无效、402 余额不足、403 抓取被安全策略拒绝、404 抓取不到内容、408 读请求体超时、413 请求体过大、429 QPS 超限、500 服务端、504 下游超时。408/429/5xx 可稍后重试，其他 4xx 不改请求不要重试。
- **未核实**：是否需单独开通 Search 权限；`need_content=true` 单条正文长度上限；Fetch 单页长度上限；Fetch 每域名 QPS；是否支持流式。

### 1.2 AnySearch（匿名 / token）

- Base：`https://api.anysearch.com`；搜索：`POST /v1/search`。
- 请求：`{"query", "max_results": 1~10（默认 10）, "tag"?, "params"?, "zone"?, "language"?, "format": "json"|"markdown"}`。
- 响应：`{"code":0,"message","request_id","data":{"results":[{"title","url","snippet","content"?(可缺省)}],"metadata":{"total_results","search_time_ms"}}}`。
- 匿名档：不带头即可调用，按客户端 IP 限速并计入每日免费额度；具体额度/QPS **未核实**（定价页 1000 次/天、10 QPS 是注册免费档，不能当匿名数字）。
- token 档：`Authorization: Bearer as_sk_...`；Free 1000 次/天、10 QPS（每日 00:00 UTC 重置，同账号多 key 共享）；Pro 为一次性调用包（$3/1k 次限时价），额度叠加不过期，QPS 最高 20。
- 鉴权失败 401/403 **不会**回落到匿名。
- 额度耗尽 402：body 的 `message` 可能含自动生成的凭据，**禁止写日志、禁止进模型上下文**。
- 错误 envelope：失败 `code:-1` + `message` + `request_id` + 可选 `error_code`；状态码 400/401/402/403/415/429/502。
- 其他端点：`/v1/extract`（url → content，最多 50000 字符、30s、不支持 PDF/图片）、`/v1/sub-domains`（查 tag，不耗额度）。
- 隐私：查询与结果只在临时环境处理，最迟 24h 删除；不用于训练/画像（隐私政策 v1.3）。
- **未核实**：匿名档具体额度/QPS；匿名档是否支持 `tag`/`params`；429 响应头（`Retry-After` / `X-RateLimit-*`）；匿名 IP 记录保留期。

## 2. 架构：通用映射 + 适配器层

```
模型 → web_search(query, limit)            ← 前端通用 Tool（schema 字节稳定）
          │
          ├─ WebSearchTool（信封 + 体积截断 + 隐私日志）
          │
          └─ SearchProvider 接口（统一结果与错误）
                ├─ DeepSeekSearchProvider（Bearer 用聊天 Key）
                └─ AnySearchProvider（apiKey 可空：空 = 匿名档）
```

### 2.1 统一结果模型

```kotlin
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val siteName: String? = null,
    val publishedAt: String? = null
)

sealed interface SearchOutcome {
    data class Ok(val hits: List<SearchHit>) : SearchOutcome
    data class Failure(val code: SearchErrorCode, val retryable: Boolean) : SearchOutcome
}

enum class SearchErrorCode { INVALID_REQUEST, UNAUTHORIZED, QUOTA_EXCEEDED, RATE_LIMITED, UNAVAILABLE }
```

- 映射规则：两家响应字段的**数据映射**写成适配器内纯函数，不做「按服务商 if」；`rules.md` 允许按 provider 实现子类。
- 空结果按 `Ok(emptyList())` 处理（不是错误）：DeepSeek 空数组、AnySearch `total_results:0`。

### 2.2 前端 Tool 与信封

- 工具名：`web_search`；参数 `query`（必填）、`limit`（1~10，默认 5）；schema 固定顺序、无动态内容。
- 成功信封（沿用 `ToolEnvelope` 风格）：
  `{"ok":true,"results":[{"title","url","snippet","site_name","date"}],"returned":N}`
- 失败信封：`{"ok":false,"error":"<code>","message":"给模型看的说明"}`。
- 体积上限（与记忆检索一致）：单条 `snippet` 截断 200 字，总长 ≈ 1500 字符上限，`limit` 再大也不突破。
- 隐私：日志只记 `query.length`、provider、返回条数、耗时；**不记 query 内容、不记结果内容**。

### 2.3 错误归一化与重试

| 归一 code | 来源 | 重试？ | 模型可见提示 |
|---|---|---|---|
| `invalid_request` | 400 | 否 | 换个检索词 |
| `unauthorized` | 401/403 | 否 | 检查 Key 配置 |
| `quota_exceeded` | AnySearch 402 | 否 | 额度用尽 |
| `rate_limited` | 429 | 退避 1 次 | 稍后再试 |
| `unavailable` | 408/5xx/超时/解析失败 | 退避 1 次 | 搜索服务暂不可用 |

- 重试只做**一次**短退避，避免工具轮次被拖长；失败后仍返回错误信封让模型自行收尾（不使整个 turn 失败）。

### 2.4 provider 选择（通用逻辑，不做服务商硬编码表）

- 设置项 `web_search_provider`：`auto`（默认）/ `deepseek` / `anysearch`。
- `auto` 规则：聊天 `baseUrl` 主机为 `api.deepseek.com` → DeepSeek；否则 AnySearch（有 Key 用 token 档，无 Key 用匿名档）。
- 不做运行时学习、不设内置表；只按一个可解释规则选择，失败不跨 provider 静默补救（配置类错误直接报错信封）。

## 3. 配置与设置页

- `web_search_enabled`（默认 **false**）：关闭时不注册 `web_search`，tools 数组与现在逐字节一致（缓存不受影响）。
- `web_search_provider`：auto / deepseek / anysearch。
- `web_search_anysearch_key`（DataStore，与现有 API Key 同存储方式；不写日志、不进设置导出以外的路径）。
- DeepSeek 侧直接用 `SettingsManager.getApiKey()`，不新增 Key 字段。
- UI：`ProviderSettings` 新增分组「联网搜索」：开关、provider 单选、AnySearch Key 输入（`auto` 且 baseUrl 非 DeepSeek 时显示）、说明文案（匿名档限速/免费额度、Pro 计费、查询不用于训练）。
- 修改开关或 provider 会改变 tools 数组 → 接受一次缓存失效（与新增工具同类事件）。

## 4. 与 S4 的关系（必须先拆）

现在 `ChatService.wantsTools = enableMemoryTools && memoryMode.usesTools && !toolsUnsupported` 同时决定：走不走工具循环、是否走文本协议、是否回放工具历史、system 拼什么。加入 web 工具后会出现「web 开 + 记忆 TEXT」的组合，必须拆成：

- `memoryUsesTools = enableMemoryTools && memoryMode.usesTools && !toolsUnsupported`（决定记忆走工具还是文本协议；决定人设/协议注入）
- `useToolLoop = registry 非空 && !toolsUnsupported`（决定是否走循环；决定 `includeToolHistory`）
- `textProtocol = !memoryUsesTools && memoryMode != MemoryMode.OFF`
- `includeToolHistory = useToolLoop`
- `TurnMetrics.toolMode = useToolLoop`，`mode` 仍为 `memoryMode`
- 端点拒绝 tools 的降级：同 turn 重建 stable/tail/echo，并强制 `includeToolHistory = false`

## 5. 缓存与测试

- tools 数组变化会使 SHA-256 快照失效一次：把 `toolsArrayIsByteStableSnapshot` 拆成「记忆工具子集 hash（不变）」+「完整 registry hash（随开关/新增工具更新）」。
- 新增测试：
  - `DeepSeekSearchProviderTest` / `AnySearchProviderTest`：FakeHttpClientEngine 脚本化成功、空结果、400/401/402/403/429/5xx、字段缺省容错；
  - `WebSearchToolTest`：schema 稳定、信封体积上限、`limit` 夹取、隐私日志不含 query；
  - `ChatService`：仅 web 开 + 记忆 TEXT 的组合（S4 测试清单）；
  - 设置页状态：开关/provider/Key 落盘与回显。

## 6. 分步实施

| 步骤 | 内容 |
|---|---|
| S4 | 拆 `wantsTools`（见 §4），先做，为多工具准备 |
| P1 | `SearchProvider` 接口 + 统一模型 + `DeepSeekSearchProvider` + `WebSearchTool` 注册（默认关） |
| P2 | `AnySearchProvider`（匿名/token）+ provider 选择 + 设置页「联网搜索」 |
| P3 | （可选）`web_fetch`：DeepSeek `/v1/tools/fetch`、AnySearch `/v1/extract` |
| P4 | 真机验收：开关生效、tools 数组与缓存、超时/限流提示、结果体积 |

## 7. 风险

- **延迟**：AnySearch 示例 `search_time_ms` 可达 4s，工具循环里每轮都会等；需单次超时（如 15s）与体积上限，避免拖慢整轮。
- **计费**：DeepSeek $0.75/1k 次；AnySearch Free 1000/天、Pro 按包。默认关闭可避免用户不知情消耗。
- **凭据泄露**：AnySearch 402 body 可能含自动生成凭据 → 该路径只记 HTTP 状态与 request_id。
- **缓存**：开关/新增工具都会导致前缀失效一次，按版本事件对待。
- **协议共存**：必须等 S4 拆完再加第二个工具，否则文本协议与工具协议会同时存在（模型摇摆）。

## 8. 未核实项（实施前确认）

- DeepSeek Search 是否需单独开通；`need_content` 正文长度上限；Fetch 页面长度上限与每域 QPS；是否支持流式。
- AnySearch 匿名档具体额度/QPS、是否支持 `tag`/`params`、429 的 `Retry-After`、匿名 IP 记录保留期。
- 两家搜索的缓存/重复调用折扣（如有）。

## 9. 参考

- DeepSeek：[Search 指南](https://api-docs.deepseek.com/guides/search/)、[Search API](https://api-docs.deepseek.com/api/search/)、[内置联网工具](https://api-docs.deepseek.com/guides/web_tools/)、[Function calling](https://api-docs.deepseek.com/guides/tool_calls/)、[价格](https://api-docs.deepseek.com/quick_start/pricing/)、[限流](https://api-docs.deepseek.com/quick_start/rate_limit/)
- AnySearch：[鉴权](https://www.anysearch.com/docs/auth)、[快速开始](https://www.anysearch.com/docs/quick-start)、[搜索端点](https://www.anysearch.com/docs/api-endpoints/v1-search.md)、[定价](https://www.anysearch.com/pricing.md)、[隐私政策](https://www.anysearch.com/legal?type=privacy)
