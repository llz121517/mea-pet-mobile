package com.meapet.mobile.client.model

import com.meapet.mobile.client.MultipartPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val json = Json { encodeDefaults = false }

/**
 * OpenAI 兼容 API 请求体构建辅助函数。
 *
 * 仅负责生成 JSON 字符串或多媒体表单部件，不定义任何业务领域数据类。
 * 调用方可将返回值直接传入 [com.meapet.mobile.client.OpenAiCompatibleClient]。
 */
object ApiRequest {

    /**
     * 构建 `/chat/completions` 请求体。
     *
     * @param messages 消息列表，由 [textMessage] 或 [visionMessage] 生成
     * @param tools 工具定义数组（null = 不输出该字段，保持旧请求体逐字节不变）
     * @param toolChoice `auto` / `none` / 指定函数；null = 不输出
     */
    fun chatCompletion(
        model: String,
        messages: List<JsonObject>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        stream: Boolean = false,
        tools: JsonArray? = null,
        toolChoice: String? = null
    ): String = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { messages.forEach { add(it) } })
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        put("stream", stream)
        // 工具字段放在最后且固定顺序：tools schema 是缓存前缀的一部分，必须字节级稳定
        tools?.let { put("tools", it) }
        toolChoice?.let { put("tool_choice", it) }
    }.let { json.encodeToString(it) }

    /**
     * `role=tool` 的工具结果消息。
     *
     * @param toolCallId 对应 assistant 消息里 `tool_calls[].id`，必须一一对应
     */
    fun toolMessage(toolCallId: String, content: String): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        put("content", content)
    }

    /**
     * 把模型返回的 assistant 原始 message 原样放回下一轮请求。
     *
     * **不要重新拼装**：思考模式要求 `reasoning_content` 等字段随消息回传，
     * 重新构造会丢字段导致接口报错。这里只做原样透传。
     */
    fun assistantRawMessage(rawMessage: JsonObject): JsonObject = rawMessage

    /** 纯文本消息。 */
    fun textMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** 多模态（Vision）消息，content 为 text / image_url 数组。 */
    fun visionMessage(role: String, content: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", role)
        put("content", buildJsonArray { content.forEach { add(it) } })
    }

    /** 文本内容片段。 */
    fun textContent(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** 图片 URL 内容片段。 */
    fun imageUrlContent(url: String, detail: String? = null): JsonObject = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", url)
            detail?.let { put("detail", it) }
        })
    }

    /**
     * 构建 `/audio/transcriptions` 所需的 multipart/form-data 部件列表。
     *
     * @param contentType 音频文件 MIME 类型，例如 `audio/mpeg`、`audio/wav`、`audio/mp4`
     */
    fun transcriptionParts(
        file: ByteArray,
        filename: String,
        model: String,
        language: String? = null,
        prompt: String? = null,
        responseFormat: String? = null,
        temperature: Double? = null,
        contentType: String = "audio/mpeg"
    ): List<MultipartPart> = buildList {
        add(
            MultipartPart(
                name = "file",
                filename = filename,
                contentType = contentType,
                body = file
            )
        )
        add(textPart("model", model))
        language?.let { add(textPart("language", it)) }
        prompt?.let { add(textPart("prompt", it)) }
        responseFormat?.let { add(textPart("response_format", it)) }
        temperature?.let { add(textPart("temperature", it.toString())) }
    }

    /**
     * 构建 `/audio/speech` 请求体。
     */
    fun speech(
        model: String,
        input: String,
        voice: String,
        responseFormat: String? = null,
        speed: Double? = null
    ): String = buildJsonObject {
        put("model", model)
        put("input", input)
        put("voice", voice)
        responseFormat?.let { put("response_format", it) }
        speed?.let { put("speed", it) }
    }.let { json.encodeToString(it) }

    private fun textPart(name: String, value: String): MultipartPart = MultipartPart(
        name = name,
        filename = null,
        contentType = null,
        body = value.encodeToByteArray()
    )
}
