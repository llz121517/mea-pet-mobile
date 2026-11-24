package com.meapet.mobile.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 上下文容量（原始容量，输入输出合计，单位 token）的来源。
 */
enum class CapacitySource { MANUAL, PROBED, FALLBACK }

/** 容量及其来源。 */
data class ContextCapacity(val tokens: Int, val source: CapacitySource)

/**
 * 云端与私网的兜底原始容量。
 *
 * 取 32K 而非更高的值：**估大的代价是请求被服务端以 400 拒绝**（还可能白等一次往返），
 * 而估小的代价只是提前一点压缩/截断。两害相权取其小，所以默认按较小的窗口假设。
 * 探测或用户手动填写可覆盖此值。
 */
internal const val FALLBACK_CAPACITY_TOKENS: Int = 32768

/**
 * 回环地址（localhost、127.0.0.0/8、::1）的兜底原始容量。
 *
 * 与云端同值（本机小模型常见窗口也不大），保留独立常量是为了将来两者需要分叉时改一处即可。
 */
internal const val LOOPBACK_FALLBACK_CAPACITY_TOKENS: Int = 32768

/**
 * 有效预算系数，对探测值、手动值、兜底值统一生效。
 * 为估算误差、推理 token 与系统注入留 15% 余量。
 */
internal const val CAPACITY_FACTOR: Double = 0.85

/**
 * `max_tokens`（本轮输出上限）最多占容量的比例。
 *
 * 上下文窗口是输入输出合计：输出上限过大时输入预算会被吃穿，请求可能被服务端直接拒绝。
 * 约 25% 是计划里的起始值，随探测/手动容量一起生效。
 */
internal const val MAX_TOKENS_CAPACITY_RATIO: Double = 0.25

/** 合理容量字段的取值范围（过滤掉 max_tokens 之类的小值与哨兵大值）。 */
private const val MIN_CAPACITY = 1024L
private const val MAX_CAPACITY = 10_000_000L

/**
 * 有效预算 = 容量 × [CAPACITY_FACTOR] − maxTokens，下限为容量的 25%。
 * 上下文窗口是输入输出合计，所以要减去本轮输出上限。
 */
fun effectiveBudget(capacity: Int, maxTokens: Int): Int {
    val floor = (capacity * 0.25).toInt()
    return (capacity * CAPACITY_FACTOR - maxTokens).toInt().coerceAtLeast(floor)
}

/** 判断 baseUrl 是否回环地址（localhost、127.0.0.0/8、::1）。其余一律同公网处理。 */
fun isLoopbackUrl(baseUrl: String): Boolean {
    val host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.lowercase()
        ?.removePrefix("[")?.removeSuffix("]") ?: return false
    return host == "localhost" || host == "::1" || Regex("^127(\\.\\d{1,3}){3}$").matches(host)
}

/** 兜底原始容量（回环与其他地址不同）。 */
fun fallbackCapacity(baseUrl: String): Int =
    if (isLoopbackUrl(baseUrl)) LOOPBACK_FALLBACK_CAPACITY_TOKENS else FALLBACK_CAPACITY_TOKENS

/**
 * 容量解析适配器：只解析 `/models` 响应，不设白名单、不设内置表。
 *
 * 找到 id 等于 [modelId] 的条目（忽略大小写；响应可为 `{data:[...]}` 或顶层数组），
 * 在条目内递归查找名字含 `context` 且值为 1024 到 10,000,000 正整数的字段，多个命中取较小者。
 * 任何失败返回 null，由调用方降级到兜底。
 */
object ContextCapacityParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, modelId: String): Int? = try {
        val root = json.parseToJsonElement(body)
        val array: JsonArray? = when (root) {
            is JsonObject -> root["data"] as? JsonArray
            is JsonArray -> root
            else -> null
        }
        val wanted = modelId.trim()
        val entry = array?.firstOrNull {
            val id = (it as? JsonObject)?.get("id")?.let { v -> (v as? JsonPrimitive)?.content }
            id != null && id.equals(wanted, ignoreCase = true)
        }
        entry?.let { findContext(it).minOrNull()?.toInt() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun findContext(el: JsonElement): List<Long> = when (el) {
        is JsonObject -> el.entries.flatMap { (k, v) ->
            if (k.contains("context", ignoreCase = true) && v is JsonPrimitive) {
                listOfNotNull(v.longOrNull?.takeIf { it in MIN_CAPACITY..MAX_CAPACITY })
            } else {
                findContext(v)
            }
        }
        is JsonArray -> el.flatMap { findContext(it) }
        else -> emptyList()
    }
}
