package com.meapet.mobile.client.test

import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpResponse
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.RequestBody
import com.meapet.mobile.client.exception.ApiException
import com.meapet.mobile.client.model.ApiRequest
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleClientTest {

    @Test
    fun listModelsReturnsRawJson() = runTest {
        val fakeEngine = FakeHttpClientEngine { request ->
            assertEquals(HttpMethod.GET, request.method)
            assertEquals("https://api.openai.com/v1/models", request.url)
            assertEquals("Bearer sk-test", request.headers["Authorization"])
            HttpResponse(
                statusCode = 200,
                body = """{"data":[{"id":"gpt-4"}]}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            engine = fakeEngine
        )

        val result = client.listModels()
        assertEquals("""{"data":[{"id":"gpt-4"}]}""", result)
    }

    @Test
    fun chatCompletionReturnsRawJson() = runTest {
        val fakeEngine = FakeHttpClientEngine { request ->
            assertEquals(HttpMethod.POST, request.method)
            assertEquals("https://api.openai.com/v1/chat/completions", request.url)
            assertTrue(request.body is RequestBody.Json)
            HttpResponse(
                statusCode = 200,
                body = """{"id":"chatcmpl-123"}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            engine = fakeEngine
        )

        val body = ApiRequest.chatCompletion(
            model = "gpt-4",
            messages = listOf(ApiRequest.textMessage("user", "Hello"))
        )
        val result = client.chatCompletion(body)
        assertEquals("""{"id":"chatcmpl-123"}""", result)
    }

    @Test
    fun httpErrorThrowsApiException() = runTest {
        val fakeEngine = FakeHttpClientEngine {
            HttpResponse(
                statusCode = 401,
                body = """{"error":"invalid key"}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-wrong",
            baseUrl = "https://api.openai.com",
            engine = fakeEngine
        )

        val exception = assertFailsWith<ApiException> {
            client.listModels()
        }
        assertEquals(401, exception.statusCode)
        assertEquals("""{"error":"invalid key"}""", exception.responseBody)
    }

    @Test
    fun networkErrorPropagates() = runTest {
        val fakeEngine = FakeHttpClientEngine {
            throw IOException("network failure")
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com",
            engine = fakeEngine
        )

        assertFailsWith<IOException> {
            client.listModels()
        }
    }

    @Test
    fun visionRequestBodyContainsTextAndImageUrl() = runTest {
        val fakeEngine = FakeHttpClientEngine { request ->
            val json = (request.body as RequestBody.Json).content
            val parsed = Json.parseToJsonElement(json).jsonObject
            assertEquals("gpt-4-vision-preview", parsed["model"]?.jsonPrimitive?.content)

            val message = parsed["messages"]?.jsonArray?.first()?.jsonObject
            assertEquals("user", message?.get("role")?.jsonPrimitive?.content)

            val content = message?.get("content")?.jsonArray
            assertEquals(2, content?.size)
            assertEquals(
                "text",
                content?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content
            )
            assertEquals(
                "Describe this image",
                content?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
            )
            assertEquals(
                "image_url",
                content?.get(1)?.jsonObject?.get("type")?.jsonPrimitive?.content
            )
            assertEquals(
                "https://example.com/image.png",
                content?.get(1)?.jsonObject?.get("image_url")?.jsonObject?.get("url")?.jsonPrimitive?.content
            )

            HttpResponse(
                statusCode = 200,
                body = """{"choices":[]}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            engine = fakeEngine
        )

        val body = ApiRequest.chatCompletion(
            model = "gpt-4-vision-preview",
            messages = listOf(
                ApiRequest.visionMessage(
                    role = "user",
                    content = listOf(
                        ApiRequest.textContent("Describe this image"),
                        ApiRequest.imageUrlContent("https://example.com/image.png")
                    )
                )
            )
        )
        client.chatCompletion(body)
    }

    @Test
    fun transcriptionReturnsRawJson() = runTest {
        val fakeEngine = FakeHttpClientEngine { request ->
            assertEquals(HttpMethod.POST, request.method)
            assertEquals("https://api.openai.com/v1/audio/transcriptions", request.url)
            assertTrue(request.body is RequestBody.Multipart)
            HttpResponse(
                statusCode = 200,
                body = """{"text":"hello world"}""".encodeToByteArray(),
                contentType = "application/json"
            )
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            engine = fakeEngine
        )

        val parts = ApiRequest.transcriptionParts(
            file = byteArrayOf(0x01, 0x02, 0x03),
            filename = "audio.mp3",
            model = "whisper-1"
        )
        val result = client.createTranscription(parts)
        assertEquals("""{"text":"hello world"}""", result)
    }

    @Test
    fun speechReturnsByteArrayWithContentType() = runTest {
        val audioBytes = byteArrayOf(0x10, 0x20, 0x30)
        val fakeEngine = FakeHttpClientEngine { request ->
            assertEquals(HttpMethod.POST, request.method)
            assertEquals("https://api.openai.com/v1/audio/speech", request.url)
            assertTrue(request.body is RequestBody.Json)
            HttpResponse(
                statusCode = 200,
                body = audioBytes,
                contentType = "audio/mpeg"
            )
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            engine = fakeEngine
        )

        val body = ApiRequest.speech(
            model = "tts-1",
            input = "Hello world",
            voice = "alloy"
        )
        val result = client.createSpeech(body)
        assertContentEquals(audioBytes, result)
    }

    @Test
    fun baseUrlTrailingSlashIsTrimmed() = runTest {
        val fakeEngine = FakeHttpClientEngine { request ->
            assertEquals("https://api.openai.com/v1/models", request.url)
            HttpResponse(200, "{}".encodeToByteArray(), "application/json")
        }

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1/",
            engine = fakeEngine
        )
        client.listModels()
    }

    @Test
    fun hostOnlyBaseUrlAppendsEndpointDirectly() = runTest {
        // 用户只填主机时，不自动补任何版本号，直接在后面拼请求路径
        val fakeEngine = FakeHttpClientEngine { request ->
            assertEquals("https://api.openai.com/chat/completions", request.url)
            HttpResponse(200, "{}".encodeToByteArray(), "application/json")
        }
        OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com",
            engine = fakeEngine
        ).chatCompletion(
            ApiRequest.chatCompletion(
                model = "gpt-4o-mini",
                messages = listOf(ApiRequest.textMessage("user", "hi"))
            )
        )
    }

    @Test
    fun baseUrlIsUsedVerbatimAndEndpointAppended() = runTest {
        // 版本路径是用户地址的一部分，原样保留后再补请求路径
        val cases = listOf(
            "https://api.openai.com/v1" to "https://api.openai.com/v1/models",
            "https://api.openai.com/v1/" to "https://api.openai.com/v1/models",
            "https://example.com/openai/v1" to "https://example.com/openai/v1/models",
            "https://open.bigmodel.cn/api/paas/v4" to "https://open.bigmodel.cn/api/paas/v4/models",
            "https://proxy.example.com" to "https://proxy.example.com/models",
            "https://proxy.example.com/" to "https://proxy.example.com/models"
        )
        for ((input, expected) in cases) {
            val fakeEngine = FakeHttpClientEngine { request ->
                assertEquals(expected, request.url, "baseUrl=$input")
                HttpResponse(200, "{}".encodeToByteArray(), "application/json")
            }
            OpenAiCompatibleClient(
                apiKey = "sk-test",
                baseUrl = input,
                engine = fakeEngine
            ).listModels()
        }
    }

    @Test
    fun normalizeBaseUrlOnlyTrimsAndPreservesVersion() {
        assertEquals(
            "https://api.openai.com/v1",
            OpenAiCompatibleClient.normalizeBaseUrl("https://api.openai.com/v1/")
        )
        // 不自动补 /v1
        assertEquals(
            "https://api.openai.com",
            OpenAiCompatibleClient.normalizeBaseUrl("https://api.openai.com")
        )
        assertEquals(
            "https://gateway.example.com/openai/v1",
            OpenAiCompatibleClient.normalizeBaseUrl("https://gateway.example.com/openai/v1")
        )
        // v4 等其它版本路径原样保留
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            OpenAiCompatibleClient.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4/")
        )
    }

    @Test
    fun chatCompletionRequestBodyContainsMaxTokens() = runTest {
        val body = ApiRequest.chatCompletion(
            model = "gpt-4o-mini",
            messages = listOf(ApiRequest.textMessage("user", "hi")),
            temperature = 0.7,
            maxTokens = 4096
        )
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals(4096, parsed["max_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    /**
     * `tools` 数组是请求前缀的一部分（服务端渲染在 messages 之前）：**字节级稳定**
     * 才能让全量用户的前缀缓存持续命中。字段顺序、措辞、增删工具都会让缓存整体失效
     * 一次，因此这里用 SHA-256 做字节快照——任何改动都必须显式更新本常量，
     * 视为一次版本事件（见 [com.meapet.mobile.memory.tools.MemoryToolSchemas] 的 KDoc）。
     */
    @Test
    fun toolsArrayIsByteStableSnapshot() {
        val body = ApiRequest.chatCompletion(
            model = "m",
            messages = listOf(ApiRequest.textMessage("user", "hi")),
            tools = com.meapet.mobile.memory.tools.MemoryToolSchemas.ALL,
            toolChoice = "auto"
        )
        // tools 字段固定在请求体末尾：取它之后的全部内容做快照，避免受前面 messages 影响
        val toolsJson = body.substring(body.indexOf("\"tools\":"))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(toolsJson.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(TOOLS_SHA256_SNAPSHOT, digest)
    }

    private companion object {
        /** 见 [toolsArrayIsByteStableSnapshot]。 */
        const val TOOLS_SHA256_SNAPSHOT = "614470f1c1fdbc018ca4e0bd8036ea2d497277229fdfd5ef6be96c18526066d1"
    }
}
