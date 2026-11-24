package com.meapet.mobile.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * 基于 Ktor Client (CIO) 的默认 [HttpClientEngine] 实现。
 *
 * 该实现与 [OpenAiCompatibleClient] 完全解耦，可替换为 OkHttp、Apache 或测试 Fake。
 */
class KtorHttpClientEngine(private val followRedirects: Boolean = true) : HttpClientEngine {

    private val client = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = this@KtorHttpClientEngine.followRedirects
        install(HttpTimeout) {
            // LLM 非流式长回复场景：整体与 socket 超时放宽到 5 分钟，连接仍 30 秒快速失败
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val ktorResponse = client.request(request.url) {
            method = when (request.method) {
                HttpMethod.GET -> io.ktor.http.HttpMethod.Get
                HttpMethod.POST -> io.ktor.http.HttpMethod.Post
            }

            request.headers.forEach { (key, value) ->
                header(key, value)
            }

            when (val body = request.body) {
                is RequestBody.Json -> {
                    contentType(ContentType.Application.Json)
                    setBody(body.content)
                }
                is RequestBody.Multipart -> {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                body.parts.forEach { part ->
                                    append(
                                        part.name,
                                        part.body,
                                        Headers.build {
                                            part.contentType?.let {
                                                append(HttpHeaders.ContentType, it)
                                            }
                                            part.filename?.let {
                                                append(
                                                    HttpHeaders.ContentDisposition,
                                                    "filename=\"$it\""
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        )
                    )
                }
                null -> {}
            }
        }

        return HttpResponse(
            statusCode = ktorResponse.status.value,
            body = ktorResponse.body(),
            contentType = ktorResponse.contentType()?.toString()
        )
    }

    override fun close() {
        client.close()
    }
}
