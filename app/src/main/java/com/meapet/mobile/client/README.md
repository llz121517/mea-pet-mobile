# OpenAI 兼容客户端模块

仅负责 HTTP 通信、请求体 JSON 序列化与通用响应解析，不包含业务逻辑或依赖注入框架绑定。

> **位置**：`app/src/main/java/com/meapet/mobile/client/`，是 `app` 模块内的一个 Kotlin 包，
> **不是独立的 Gradle 模块**（构建配置来自根 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`）。
> 需要独立复用时的做法见下文「作为独立模块复用」。

## 目录结构

| 文件 / 目录 | 职责 |
|---|---|
| `OpenAiCompatibleClient.kt` | HTTP 客户端：`listModels` / `chatCompletion` / `createTranscription` / `createSpeech` |
| `HttpClientEngine.kt` | 引擎抽象（`HttpRequest` / `HttpResponse` / `RequestBody` / `MultipartPart`） |
| `KtorHttpClientEngine.kt` | 默认引擎，Ktor Client (CIO)，请求/连接超时已配置 |
| `model/ApiRequest.kt` | 请求体构造（文本 / 图片 / 工具调用 / multipart / speech） |
| `model/ApiResponse.kt` | 响应解析助手（content、完整 assistant 消息与 tool_calls、usage、模型列表、错误信息） |
| `exception/ApiException.kt` | 非 2xx 时抛出；带状态码、原始响应体与友好文案 |
| `ContextCapacity.kt` / `ContextCapacityProvider.kt` | 上下文容量探测与有效预算（见下文「上下文容量探测」） |

## 设计原则

- **完全解耦**：`OpenAiCompatibleClient` 构造函数只需 `apiKey` 与 `baseUrl`，HTTP 引擎通过 [HttpClientEngine](HttpClientEngine.kt) 抽象注入。
- **零业务耦合**：客户端方法一律返回原始 `String`（JSON）或 `ByteArray`；`model/` 下的 `ApiRequest` / `ApiResponse` 只做 JSON 结构构造与解析，不定义业务领域数据类。
- **可测试性**：生产环境使用 [KtorHttpClientEngine](KtorHttpClientEngine.kt)；单元测试注入 `FakeHttpClientEngine`，无需真实网络。
- **协程原生**：所有网络方法均为 `suspend` 函数。
- **无鉴权也能用**：`apiKey` 为空时不发 `Authorization` 头，本地模型（Ollama / LM Studio 等）可直接使用；云端缺 Key 会得到 401，由上层给友好提示。

## 作为独立模块复用

本包当前随 `app` 一起编译。若要把 `client/` 抽成独立模块（例如给其它项目用），需要额外：

1. 把目录复制到父工程，并在 `settings.gradle.kts` 声明 `include("client")`；
2. 新建 `client/build.gradle.kts` 声明 Kotlin Android 库插件与下列依赖：
   `kotlinx-coroutines-core`、`kotlinx-serialization-json`、`ktor-client-core`、`ktor-client-cio`
   （版本对齐根 `gradle/libs.versions.toml`）；
3. 调用方加 `implementation(project(":client"))`。

> 注意：`ContextCapacityProvider` 依赖 `SettingsManager` 读取用户设置（手动值 / 模型名）。
> 抽成独立模块时需把这段依赖换成参数或接口，或只取 `ContextCapacityParser` 与 `effectiveBudget()`。

## 依赖声明

依赖清单在根 [`gradle/libs.versions.toml`](../../../../../../../../gradle/libs.versions.toml)，
由 `app/build.gradle.kts` 引用。关键依赖：

- `kotlinx-coroutines-core`
- `kotlinx-serialization-json`
- `ktor-client-core` / `ktor-client-cio`

## 使用示例

### 0. `baseUrl` 是完整 API 根

用户填写的是**含版本路径的完整根地址**，客户端只在其后补请求路径
（`/chat/completions`、`/models`、`/audio/...`），**不会**自动附加版本号：

- `https://api.openai.com/v1` → `https://api.openai.com/v1/chat/completions`
- `https://open.bigmodel.cn/api/paas/v4` → `.../v4/chat/completions`
- `https://ollama.local`（本地无版本路径）→ `https://ollama.local/chat/completions`

### 1. 初始化客户端

```kotlin
import com.meapet.mobile.client.OpenAiCompatibleClient

val client = OpenAiCompatibleClient(
    apiKey = "sk-xxxxxxxx",          // 本地模型可传空串
    baseUrl = "https://api.openai.com/v1"
)

// 使用完毕后关闭以释放连接池
client.close()
```

### 2. 模型列表查询

```kotlin
val json = client.listModels()
// 原始 JSON 字符串，例如 {"data":[{"id":"gpt-4o","context_window":128000}]}
// 解析助手：ApiResponse.modelIds(json)、ContextCapacityParser.parse(json, modelId)
```

### 3. 普通 Chat 对话

```kotlin
import com.meapet.mobile.client.model.ApiRequest

val requestBody = ApiRequest.chatCompletion(
    model = "gpt-4o",
    messages = listOf(
        ApiRequest.textMessage("system", "You are a helpful assistant."),
        ApiRequest.textMessage("user", "Hello!")
    ),
    temperature = 0.7
)

val responseJson = client.chatCompletion(requestBody)
// 解析助手：ApiResponse.chatCompletionContent(responseJson)
```

### 4. Vision 多模态对话

```kotlin
val requestBody = ApiRequest.chatCompletion(
    model = "gpt-4o",
    messages = listOf(
        ApiRequest.visionMessage(
            role = "user",
            content = listOf(
                ApiRequest.textContent("请描述这张图片"),
                ApiRequest.imageUrlContent(
                    url = "https://example.com/image.png",
                    detail = "high"
                )
            )
        )
    )
)

val responseJson = client.chatCompletion(requestBody)
```

### 5. 工具调用（function calling）

客户端只负责**构造请求 / 原样回传 / 解析结构**，多轮循环由业务层实现（见 `chat/tools/ChatToolLoop`）：

```kotlin
// 请求：tools 固定数组 + tool_choice（auto / none / 指定函数；null = 不输出该字段）
val requestBody = ApiRequest.chatCompletion(
    model = "gpt-4o",
    messages = messages,          // 历史里可能已有 assistant(tool_calls) 与 role=tool
    tools = toolsJsonArray,       // kotlinx.serialization JsonArray
    toolChoice = "auto"
)

val responseJson = client.chatCompletion(requestBody)
val parsed = ApiResponse.chatCompletionMessage(responseJson)
// parsed.content      本轮文本（有工具调用时可能为空）
// parsed.toolCalls    List<ParsedToolCall>(id, name, arguments)；arguments 是 JSON 字符串，需二次解析
// parsed.raw          原始 message：下一轮必须原样回传（含 reasoning_content 等）

// 回传 assistant 原始消息 + 每个工具结果一条 role=tool
messages += ApiRequest.assistantRawMessage(parsed.raw)
messages += ApiRequest.toolMessage(toolCallId = parsed.toolCalls.first().id, content = "工具结果 JSON")
```

### 6. STT 语音转文字

```kotlin
val audioBytes = File("audio.mp3").readBytes()

val parts = ApiRequest.transcriptionParts(
    file = audioBytes,
    filename = "audio.mp3",
    model = "whisper-1",
    language = "zh",
    responseFormat = "json"
)

val responseJson = client.createTranscription(parts)
```

### 7. TTS 文字转语音

```kotlin
val requestBody = ApiRequest.speech(
    model = "tts-1",
    input = "你好，世界",
    voice = "alloy",
    responseFormat = "mp3"
)

val audioBytes = client.createSpeech(requestBody)
// audioBytes 为原始音频二进制数据
```

## 响应解析助手（`model/ApiResponse.kt`）

| 方法 | 用途 |
|---|---|
| `chatCompletionContent(body)` | 首个 choice 的文本（老调用方） |
| `chatCompletionMessage(body)` | 完整 assistant 消息：`content` + `toolCalls` + `raw`（工具循环用） |
| `usagePromptTokens(body)` | `usage.prompt_tokens`（本地 token 估算校准） |
| `usagePromptCacheHitTokens(body)` | `usage.prompt_cache_hit_tokens`（前缀缓存命中观察；缺字段返回 null） |
| `modelIds(body)` | `/models` 里的模型 id 列表（兼容 `{data:[...]}` 与顶层数组） |
| `errorMessage(body)` | 从错误响应里提取可展示的原因 |

解析失败一律返回 null / 空列表，不抛异常（取消异常除外）。

## 上下文容量探测（`ContextCapacity*.kt`）

`GET {baseUrl}/models` 顺带解析上下文窗口，**不设白名单、不设内置表**：

- `ContextCapacityParser.parse(body, modelId)`：找到当前模型条目，递归查找名字含 `context`、值为 1024..10,000,000 的正整数字段，多个命中取较小者；任何失败返回 null。
- `effectiveBudget(capacity, maxTokens)`：有效预算 = 容量 × 0.85 − max_tokens，下限为容量的 25%。
- `ContextCapacityProvider`：优先级 **手动值 > 探测缓存（5 分钟 TTL，失败也负缓存 5 分钟）> 兜底**；探测失败直接降级，不重试风暴。兜底原始容量 32K（回环地址同值）。
- 设置页拉取模型列表时调 `onModelsBody(body)` 复用同一次响应，不多发请求。

## 自定义 HTTP 引擎

实现 [HttpClientEngine](HttpClientEngine.kt) 接口即可替换默认 Ktor 引擎：

```kotlin
class OkHttpEngine : HttpClientEngine {
    override suspend fun execute(request: HttpRequest): HttpResponse { /* ... */ }
    override fun close() { /* ... */ }
}

val client = OpenAiCompatibleClient(
    apiKey = "sk-xxx",
    baseUrl = "https://api.openai.com/v1",
    engine = OkHttpEngine()
)
```

## 异常处理

- HTTP 状态码不在 `2xx` 范围内时，抛出 [ApiException](exception/ApiException.kt)：
  `statusCode`、`responseBody`（原始响应体）与 `message`（已按状态码 + 网关原因生成的友好文案）。
- 网络异常（如 `IOException`）原样向上传播，由调用方决定重试策略。

```kotlin
try {
    val result = client.listModels()
} catch (e: ApiException) {
    println("HTTP ${e.statusCode}: ${e.responseBody}")
}
```

## 运行单元测试

本包没有独立 Gradle 模块，测试在 `app` 模块的 JVM 单测源码集里。在项目根目录执行：

```bash
./gradlew :app:testDebugUnitTest --tests "com.meapet.mobile.OpenAiCompatibleClientTest"
./gradlew :app:testDebugUnitTest --tests "com.meapet.mobile.client.*"
```

测试文件位置：

- `app/src/test/java/com/meapet/mobile/OpenAiCompatibleClientTest.kt`
- `app/src/test/java/com/meapet/mobile/FakeHttpClientEngine.kt`（包名 `com.meapet.mobile.client.test`，假 HTTP 引擎）
- `app/src/test/java/com/meapet/mobile/client/model/ApiResponseTest.kt`、`ApiResponseToolCallTest.kt`
- `app/src/test/java/com/meapet/mobile/client/ContextCapacityTest.kt`、`ContextCapacityProviderTest.kt`

测试覆盖：正常 JSON 响应（models / chat / transcription）、HTTP 错误码 → `ApiException`、网络异常向上传播、Vision 请求体构造、TTS 二进制响应、工具调用解析与原始消息透传、usage 解析、容量解析与兜底/缓存优先级。
