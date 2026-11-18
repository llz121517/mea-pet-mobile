package com.meapet.mobile.memory.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具定义（JSON Schema）。
 *
 * ## 铁律：schema 必须字节级稳定
 * `tools` 字段是请求前缀的一部分（服务端渲染在 messages 之前）。字段顺序、措辞、
 * 是否存在都影响前缀缓存：**改动 schema 等于让所有用户缓存失效一次**，应视为版本事件。
 * 所以这里：
 * - 用固定顺序的 `buildJsonObject` 构造，不遍历 Map；
 * - 描述文字里**不允许出现任何动态内容**（当前记忆条数、日期等）；
 * - 一次构建后缓存复用（[ALL] 为 val）。
 *
 * ## 工具集
 * - `search_memory`：只读检索（本阶段已实现）
 * - `create_memory` / `update_memory` / `delete_memory`：下一阶段接入，schema 先定义好，
 *   避免后续增删工具导致缓存反复失效（提前稳定下来）。
 */
object MemoryToolSchemas {

    const val SEARCH_MEMORY = "search_memory"
    const val CREATE_MEMORY = "create_memory"
    const val UPDATE_MEMORY = "update_memory"
    const val DELETE_MEMORY = "delete_memory"

    /** 可写入的记忆类型（LONG_TERM 只由摘要产生，模型不可直接创建）。 */
    private val WRITABLE_TYPES = listOf("SHORT_TERM", "CORE_TRAIT", "FACTUAL")

    /** 全部工具定义（固定顺序）。 */
    val ALL: JsonArray = buildJsonArray {
        add(searchMemory())
    }

    private fun searchMemory(): JsonObject = functionTool(
        name = SEARCH_MEMORY,
        description = "检索已存储的用户记忆（短期/长期）。当用户提到过去的事、偏好、人物、计划，" +
            "或你不确定是否记过时先检索。用户姓名、身份等永久事实已在【用户人设】中，无需检索。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "检索词或短句，可空格分隔多个关键词")
                }
                putJsonObject("types") {
                    put("type", "array")
                    put("description", "可选，默认短期与长期记忆")
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") { listOf("SHORT_TERM", "LONG_TERM", "CORE_TRAIT", "FACTUAL").forEach { add(it) } }
                    }
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 10)
                    put("description", "默认 5")
                }
                putJsonObject("sort") {
                    put("type", "string")
                    put("description", "默认 relevance")
                    putJsonArray("enum") { listOf("relevance", "recent", "importance").forEach { add(it) } }
                }
            }
            putJsonArray("required") { add("query") }
        }
    )

    /** 统一的 function 工具外壳（固定字段顺序：type → function{name, description, parameters}）。 */
    private fun functionTool(name: String, description: String, parameters: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            }
        }
}
