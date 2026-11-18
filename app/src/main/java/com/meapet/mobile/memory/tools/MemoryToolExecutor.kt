package com.meapet.mobile.memory.tools

import android.util.Log
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.memory.search.MemoryQuery
import com.meapet.mobile.memory.search.MemorySearch
import com.meapet.mobile.memory.search.MemorySort
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 记忆工具执行器。
 *
 * 本阶段只实现只读的 [MemoryToolSchemas.SEARCH_MEMORY]；
 * 写类工具（create/update/delete）留到下一阶段，届时加「暂存 → turn 成功后统一提交」。
 *
 * ## 返回约定
 * 一律返回**字符串化 JSON** 作为 tool 消息内容，**不抛异常**：
 * - 成功：`{"ok":true,"results":[...],"total_matched":N,"returned":M}`
 * - 失败：`{"ok":false,"error":"code","message":"给模型看的说明"}`
 *
 * 让模型自己看到错误并修正参数，比让整轮请求失败更稳。
 *
 * ## 体积控制
 * 单次检索结果总长约 1500 字符上限：条数上限 10、单条 content 截断 200 字，
 * 用 `age_days` 代替绝对时间戳（省 token、避免时区问题）。
 */
class MemoryToolExecutor(
    private val search: MemorySearch,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val TAG = "MemoryToolExecutor"
        private const val MAX_RESULT_CHARS = 1500
        private const val MAX_ITEM_CHARS = 200
        private const val UNKNOWN_TOOL = "unknown_tool"
        private const val INVALID_ARGUMENTS = "invalid_arguments"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行一次工具调用。
     *
     * @param name 工具名（来自 `tool_calls[].function.name`）
     * @param argumentsJson `tool_calls[].function.arguments`（JSON 字符串）
     * @return tool 消息的 content（字符串化 JSON 信封）
     */
    suspend fun execute(name: String, argumentsJson: String): String = try {
        when (name) {
            MemoryToolSchemas.SEARCH_MEMORY -> searchMemory(argumentsJson)
            else -> errorEnvelope(UNKNOWN_TOOL, "未知工具：$name")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Tool execution failed: $name", e)
        errorEnvelope("tool_failed", "工具执行失败，请换个参数或稍后再试")
    }

    private suspend fun searchMemory(argumentsJson: String): String {
        val args = parseObject(argumentsJson)
            ?: return errorEnvelope(INVALID_ARGUMENTS, "参数不是合法 JSON 对象")
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return errorEnvelope(INVALID_ARGUMENTS, "query 不能为空")
        }
        val limit = (args["limit"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 10) ?: 5
        val types = parseTypes(args["types"])
        val sort = when ((args["sort"] as? JsonPrimitive)?.contentOrNull) {
            "recent" -> MemorySort.RECENT
            "importance" -> MemorySort.IMPORTANCE
            else -> MemorySort.RELEVANCE
        }

        val items = search.search(MemoryQuery(text = query, types = types, limit = limit, sort = sort))
        return successEnvelope(items)
    }

    private fun successEnvelope(items: List<MemoryItem>): String {
        val now = clock()
        val nodes = ArrayList<JsonObject>()
        var used = 0
        for (item in items) {
            val content = item.content.take(MAX_ITEM_CHARS)
            val ageDays = ((now - item.createdAt).coerceAtLeast(0L) / 86_400_000L).toInt()
            val node = buildJsonObject {
                put("id", item.id)
                put("type", item.type.name)
                put("content", content)
                put("importance", item.importance.toDouble())
                put("age_days", ageDays)
            }
            // 简单体积控制：已经装下至少一条、且再加就超上限时停止
            if (nodes.isNotEmpty() && used + content.length + 60 > MAX_RESULT_CHARS) break
            nodes.add(node)
            used += content.length + 60
        }
        val results = buildJsonArray { nodes.forEach { add(it) } }
        return buildJsonObject {
            put("ok", true)
            put("results", results)
            put("total_matched", items.size)
            put("returned", nodes.size)
        }.toString()
    }

    private fun errorEnvelope(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()

    private fun parseObject(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        null
    }

    private fun parseTypes(el: kotlinx.serialization.json.JsonElement?): Set<MemoryType>? {
        val array = el as? JsonArray ?: return null
        val types = array.mapNotNull { item ->
            val name = (item as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            runCatching { MemoryType.valueOf(name) }.getOrNull()
        }.toSet()
        return types.takeIf { it.isNotEmpty() }
    }
}
