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
