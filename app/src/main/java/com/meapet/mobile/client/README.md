# OpenAI 兼容客户端模块

仅负责 HTTP 通信与请求体 JSON 序列化，不包含任何业务逻辑、配置管理或依赖注入框架绑定。

> **位置**：`app/src/main/java/com/meapet/mobile/client/`，是 `app` 模块内的一个 Kotlin 包，
> **不是独立的 Gradle 模块**（构建配置来自根 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`）。
> 需要独立复用时的做法见下文「作为独立模块复用」。

## 设计原则

- **完全解耦**：`OpenAiCompatibleClient` 构造函数仅要求 `apiKey` 与 `baseUrl`，HTTP 引擎通过 [HttpClientEngine](HttpClientEngine.kt) 抽象注入。
- **零业务耦合**：所有 API 响应以原始 `String`（JSON）或 `ByteArray`（二进制）返回，由调用方自行反序列化。
- **可测试性**：生产环境使用 [KtorHttpClientEngine](KtorHttpClientEngine.kt)；单元测试注入 `FakeHttpClientEngine`，无需真实网络。
- **协程原生**：所有异步方法均为 `suspend` 函数。

## 作为独立模块复用

本包当前随 `app` 一起编译。若要把 `client/` 抽成独立模块（例如给其它项目用），需要额外：

1. 把目录复制到父工程，并在 `settings.gradle.kts` 声明 `include("client")`；
2. 新建 `client/build.gradle.kts` 声明 Kotlin Android 库插件与下列依赖：
   `kotlinx-coroutines-core`、`kotlinx-serialization-json`、`ktor-client-core`、`ktor-client-cio`
   （版本对齐根 `gradle/libs.versions.toml`）；
3. 调用方加 `implementation(project(":client"))`。

## 依赖声明

依赖清单在根 [`gradle/libs.versions.toml`](../../../../../../../../gradle/libs.versions.toml)，
由 `app/build.gradle.kts` 引用。关键依赖：

- `kotlinx-coroutines-core`
- `kotlinx-serialization-json`
- `ktor-client-core` / `ktor-client-cio`

## 使用示例

### 1. 初始化客户端

```kotlin
import com.meapet.mobile.client.OpenAiCompatibleClient

val client = OpenAiCompatibleClient(
    apiKey = "sk-xxxxxxxx",
    baseUrl = "https://api.openai.com"
)

// 使用完毕后关闭以释放连接池
client.close()
```

### 2. 模型列表查询

```kotlin
val json = client.listModels()
// json 为原始 JSON 字符串，例如 {"data":[{"id":"gpt-4"}]}
```

### 3. 普通 Chat 对话

```kotlin
import com.meapet.mobile.client.model.ApiRequest
import kotlinx.serialization.json.Json

val requestBody = ApiRequest.chatCompletion(
    model = "gpt-4",
    messages = listOf(
        ApiRequest.textMessage("system", "You are a helpful assistant."),
        ApiRequest.textMessage("user", "Hello!")
    ),
    temperature = 0.7
)

val responseJson = client.chatCompletion(requestBody)
```

### 4. Vision 多模态对话

```kotlin
val requestBody = ApiRequest.chatCompletion(
    model = "gpt-4-vision-preview",
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

### 5. STT 语音转文字

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

### 6. TTS 文字转语音

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

## 自定义 HTTP 引擎

实现 [HttpClientEngine](HttpClientEngine.kt) 接口即可替换默认 Ktor 引擎：

```kotlin
class OkHttpEngine : HttpClientEngine {
    override suspend fun execute(request: HttpRequest): HttpResponse { /* ... */ }
    override fun close() { /* ... */ }
}

val client = OpenAiCompatibleClient(
    apiKey = "sk-xxx",
    baseUrl = "https://api.openai.com",
    engine = OkHttpEngine()
)
```

## 异常处理

- HTTP 状态码不在 `2xx` 范围内时，抛出 [ApiException](exception/ApiException.kt)。
- 网络异常（如 `IOException`）会原样向上传播，由调用方决定重试策略。

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
./gradlew :app:testDebugUnitTest --tests "com.meapet.mobile.client.model.ApiResponseTest"
```

测试文件位置：`app/src/test/java/com/meapet/mobile/OpenAiCompatibleClientTest.kt`、
`app/src/test/java/com/meapet/mobile/FakeHttpClientEngine.kt`（假 HTTP 引擎）、
`app/src/test/java/com/meapet/mobile/client/model/ApiResponseTest.kt`。

测试覆盖：

- 正常 JSON 响应（models、chat、transcription）
- HTTP 错误码 → `ApiException`
- 网络异常向上传播
- Vision 多模态请求体构造验证
- TTS 二进制响应
