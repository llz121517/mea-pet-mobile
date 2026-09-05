package com.meapet.mobile.client

import android.util.Log
import com.meapet.mobile.client.exception.ApiException
import com.meapet.mobile.client.model.ApiResponse

/**
 * OpenAI 兼容 HTTP 客户端。
 *
 * 特点：
 * - 仅负责 HTTP 通信与请求体序列化，不处理业务逻辑；
 * - 所有 API 返回原始 JSON 字符串或二进制字节数组，由调用方自行解析；
 * - HTTP 引擎通过 [HttpClientEngine] 抽象注入，默认使用 Ktor CIO；
 * - 所有公开方法均为 `suspend`，原生协程支持；
 * - 用户在设置里填的是 **完整 API 根**（含版本路径，如 `/v1`、`/v4`），客户端只补齐后面的请求路径
 *   （`/chat/completions`、`/models` 等），**不会**自动附加任何版本号。
 *
 * 例如填 `https://api.openai.com/v1` → 实际请求 `https://api.openai.com/v1/chat/completions`；
 * 填 `https://open.bigmodel.cn/api/paas/v4` → 实际请求 `https://open.bigmodel.cn/api/paas/v4/chat/completions`。
 *
 * @param apiKey API 密钥
 * @param baseUrl 完整 API 根地址，例如 `https://api.openai.com/v1`
 * @param engine HTTP 引擎，单元测试可注入 Fake 实现
 */
class OpenAiCompatibleClient(
    private val apiKey: String,
    baseUrl: String,
    private val engine: HttpClientEngine = KtorHttpClientEngine()
) {

    /**
     * 规范化后的 API 根（无尾部 `/`）：
     * - 去空白、去尾部 `/`
     * - 版本路径由用户填写，原样保留
     */
    private val baseUrl: String = normalizeBaseUrl(baseUrl)

    /** `GET .../models`（完整路径为 `{base}/models`） */
    suspend fun listModels(): String {
        val request = HttpRequest(
            method = HttpMethod.GET,
            url = apiUrl("models"),
            headers = authHeaders()
        )
        return executeExpectText(request)
    }

    /** `POST .../chat/completions` */
    suspend fun chatCompletion(requestBody: String): String {
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = apiUrl("chat/completions"),
            headers = authHeaders(),
            body = RequestBody.Json(requestBody)
        )
        return executeExpectText(request)
    }

    /** `POST .../audio/transcriptions` */
    suspend fun createTranscription(parts: List<MultipartPart>): String {
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = apiUrl("audio/transcriptions"),
            headers = authHeaders(),
            body = RequestBody.Multipart(parts)
        )
        return executeExpectText(request)
    }

    /** `POST .../audio/speech` */
    suspend fun createSpeech(requestBody: String): ByteArray {
        val request = HttpRequest(
            method = HttpMethod.POST,
            url = apiUrl("audio/speech"),
            headers = authHeaders(),
            body = RequestBody.Json(requestBody)
        )
        return executeExpectOk(request).body
    }

    /**
     * 在 API 根之后补齐请求路径。
     *
     * @param path API 根之后的路径，例如 `"chat/completions"` / `"models"`
     */
    private fun apiUrl(path: String): String {
        val cleaned = path.trim().trimStart('/')
        require(cleaned.isNotEmpty()) { "API path must not be empty" }
        return "$baseUrl/$cleaned"
    }

    companion object {
        private const val TAG = "OpenAiCompatibleClient"

        /**
         * 把用户填写的地址规范成 API 根：去空白、去尾部 `/`，其余原样保留。
         *
         * - `https://api.openai.com/v1/` → `https://api.openai.com/v1`
         * - `https://open.bigmodel.cn/api/paas/v4` → `https://open.bigmodel.cn/api/paas/v4`
         * - `https://proxy.example.com/openai/v1/` → `https://proxy.example.com/openai/v1`
         */
        internal fun normalizeBaseUrl(raw: String): String =
            raw.trim().trimEnd('/')
    }

    /** 关闭底层 HTTP 引擎，释放资源。 */
    fun close() {
        engine.close()
    }

    /**
     * API Key 为空时不带 `Authorization` 头——本地模型（Ollama / LM Studio 等）通常
     * 不需要鉴权，未填 Key 也能工作；云端服务缺 Key 会返回 401，由上层给出友好提示。
     */
    private fun authHeaders(): Map<String, String> =
        if (apiKey.isBlank()) {
            emptyMap()
        } else {
            mapOf("Authorization" to "Bearer $apiKey")
        }

    private suspend fun executeExpectText(request: HttpRequest): String {
        val response = executeExpectOk(request)
        return response.bodyAsText()
    }

    private suspend fun executeExpectOk(request: HttpRequest): HttpResponse {
        val response = engine.execute(request)
        if (response.statusCode !in 200..299) {
            val body = response.bodyAsText()
            // 原始响应体只进日志：排查时必须有它（此前既不进 UI 也不进日志，
            // 用户只看到「请求过于频繁」而无从判断真因），但可能含冗长的网关堆栈
            Log.w(TAG, "HTTP ${response.statusCode} on ${request.url}: $body")
            // 面向用户的友好文案 + 网关给出的具体原因
            throw ApiException(
                statusCode = response.statusCode,
                responseBody = body,
                message = ApiException.friendlyMessage(
                    response.statusCode,
                    ApiResponse.errorMessage(body)
                )
            )
        }
        return response
    }
}
