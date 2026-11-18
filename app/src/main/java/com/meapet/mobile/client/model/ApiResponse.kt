package com.meapet.mobile.client.model

import android.util.Log
import com.meapet.mobile.core.runCatchingLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OpenAI 兼容 API 响应解析辅助函数。
 *
 * 与 [ApiRequest] 对应：仅做 JSON 结构解析，不定义任何业务领域数据类。
 * 调用方将 [com.meapet.mobile.client.OpenAiCompatibleClient] 返回的原始 JSON 传入即可。
 */
object ApiResponse {

    private const val TAG = "ApiResponse"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 一次工具调用（`tool_calls[]` 的元素）。
     *
     * @property arguments 模型给出的参数是 **JSON 字符串**，需要调用方二次解析
     */
    data class ParsedToolCall(val id: String, val name: String, val arguments: String)

    /**
     * 解析后的一条 assistant 消息。
     *
     * @property raw 原始 message JsonObject（含 `tool_calls`、`reasoning_content` 等全部字段），
     *   下一轮请求必须**原样回传**它，不要重新拼装
     * @property content 文本内容（有工具调用时可能为 null 或空串）
     * @property toolCalls 本轮的并行工具调用（按数组顺序）
     * @property finishReason `stop` / `tool_calls` / `length` 等
     */
    data class ParsedMessage(
        val raw: JsonObject,
        val content: String?,
        val toolCalls: List<ParsedToolCall>,
        val finishReason: String?
    )

    /**
     * 从 `/chat/completions` 响应中解析首个 choice 的完整 assistant 消息。
     *
     * 与 [chatCompletionContent] 并存：老调用方继续用后者，工具循环用本方法。
     *
     * @return `choices` 为空或结构不符时返回 null（此时视为接口异常）
     */
    fun chatCompletionMessage(body: String): ParsedMessage? = try {
        val choice = json.parseToJsonElement(body).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject ?: return null
        val message = choice["message"]?.jsonObject ?: return null
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull
        val toolCalls = (message["tool_calls"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            // arguments 是 JSON 字符串；个别端点直接给对象，这里统一成字符串
            val args = when (val a = fn["arguments"]) {
                is JsonPrimitive -> a.contentOrNull ?: "{}"
                is JsonObject -> a.toString()
                else -> "{}"
            }
            ParsedToolCall(id = id, name = name, arguments = args)
        } ?: emptyList()
        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        ParsedMessage(raw = message, content = content, toolCalls = toolCalls, finishReason = finishReason)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse assistant message: ${e.message}")
        null
    }

    /**
     * 从 `/chat/completions` 响应中提取首个 choice 的 `message.content`。
     *
     * @return 消息文本；`choices` 为空、content 缺失或结构不符时返回 null
     */
    fun chatCompletionContent(body: String): String? = runCatchingLog(TAG) {
        json.parseToJsonElement(body).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
    }

    /**
     * 从 `/chat/completions` 响应中提取 `usage.prompt_tokens`（本轮输入的真实 token 数）。
     *
     * 用于校准本地 token 估算（见 `TokenEstimator`）：不同服务商的分词器差异很大，
     * 只有服务端给的数字是权威值。
     *
     * @return 提示 token 数；响应缺 `usage`、字段缺失或非正整数时返回 null
     */
    fun usagePromptTokens(body: String): Int? = try {
        val root = json.parseToJsonElement(body) as? JsonObject
        val value = root?.get("usage")?.let { it as? JsonObject }
            ?.get("prompt_tokens")?.jsonPrimitive?.longOrNull
        value?.takeIf { it > 0 }?.toInt()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * 从 `/models` 响应中提取模型 id 列表。
     *
     * 兼容常见 OpenAI 兼容网关：优先读 `data[].id`；若顶层是数组则直接读每项 `id`。
     * 结果去重、去空、按字母序排序。
     */
    fun modelIds(body: String): List<String> = try {
        val root = json.parseToJsonElement(body)
        val array: JsonArray = when (root) {
            is JsonObject -> root["data"] as? JsonArray ?: return emptyList()
            is JsonArray -> root
            else -> return emptyList()
        }
        array.mapNotNull { element ->
            (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.distinct().sorted()
    } catch (e: CancellationException) {
        // 取消一律重抛（见 ErrorHandling.kt 约定），不能吞
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse model ids: ${e.message}")
        emptyList()
    }

    /**
     * 从错误响应体里提取网关给出的具体错误说明。
     *
     * 兼容三种常见形态：
     * - `{"error":{"message":"Rate limit exceeded for model X"}}`（OpenAI 及多数兼容网关）
     * - `{"error":"invalid key"}`（部分自建代理直接给字符串）
     * - `{"message":"…"}`（少数网关不套 error）
     *
     * @return 去空白后的错误说明；结构不符、字段缺失或为空时返回 null
     */
    fun errorMessage(body: String): String? = try {
        val root = json.parseToJsonElement(body) as? JsonObject
        val detail = when (val err = root?.get("error")) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> null
        } ?: root?.get("message")?.jsonPrimitive?.contentOrNull
        detail?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        // 取消一律重抛（见 ErrorHandling.kt 约定），不能吞
        throw e
    } catch (e: Exception) {
        // 错误体本身解析失败不值得再报错，调用方退回纯状态码文案即可
        null
    }
}
