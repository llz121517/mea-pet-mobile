package com.meapet.mobile.chat.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一个可被模型调用的工具。
 *
 * 约定：
 * - [schema] 必须是静态的、字节级稳定的（tools 数组属于请求前缀，改动会使缓存失效一次）；
 * - [execute] 返回信封 JSON 字符串（见 [ToolEnvelope]），**不抛异常**（取消除外）。
 */
interface Tool {
    val name: String
    val schema: JsonObject
    suspend fun execute(argumentsJson: String): String
}

/**
 * 工具注册表：按注册顺序一次性生成 tools 数组，之后只读。
 *
 * 顺序即 [schemas] 的顺序，必须保持稳定；未知工具名返回 `unknown_tool` 信封。
 */
class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = LinkedHashMap<String, Tool>().also { map ->
        tools.forEach { tool ->
            require(map.put(tool.name, tool) == null) { "重复的工具名：${tool.name}" }
        }
    }

    /** 下发给模型的 tools 数组（构造时生成一次）。 */
    val schemas: JsonArray = buildJsonArray { tools.forEach { add(it.schema) } }

    suspend fun execute(name: String, argumentsJson: String): String =
        byName[name]?.execute(argumentsJson) ?: ToolEnvelope.error("unknown_tool", "未知工具：$name")
}

/** 工具结果信封：ok / error / message，各工具与循环共用。 */
object ToolEnvelope {
    fun error(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()
}
