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

    /** 可选：拼进首条 system 的固定使用说明（必须字节稳定，不含动态内容）。 */
    val hint: String? get() = null

    suspend fun execute(argumentsJson: String): String
}

/**
 * 带 turn 生命周期的工具（有暂存状态的工具实现它；无状态工具如 web 搜索无需实现）。
 *
 * - [onTurnStart]：新 turn 开始前，清掉上一轮残留；
 * - [onTurnSuccess]：整个 turn 成功（历史已落库）后调用，提交暂存；
 * - [onTurnAbort]：超时 / 取消 / 失败时调用，丢弃暂存。
 */
interface TurnScoped {
    fun onTurnStart()
    suspend fun onTurnSuccess()
    fun onTurnAbort()
}

/**
 * 工具注册表：按注册顺序一次性生成 tools 数组，之后只读。
 *
 * 顺序即 [schemas] 的顺序，必须保持稳定；未知工具名返回 `unknown_tool` 信封。
 * 同时把 turn 生命周期钩子扇出给所有 [TurnScoped] 工具与额外注册的 [turnHooks]。
 */
class ToolRegistry(
    tools: List<Tool>,
    turnHooks: List<TurnScoped> = emptyList()
) {
    private val byName: Map<String, Tool> = LinkedHashMap<String, Tool>().also { map ->
        tools.forEach { tool ->
            require(map.put(tool.name, tool) == null) { "重复的工具名：${tool.name}" }
        }
    }

    private val scoped: List<TurnScoped> =
        (tools.filterIsInstance<TurnScoped>() + turnHooks).distinct()

    /** 下发给模型的 tools 数组（构造时生成一次）。 */
    val schemas: JsonArray = buildJsonArray { tools.forEach { add(it.schema) } }

    /** 各工具非空 hint 按注册顺序用换行连接；全无则为 null。 */
    val hint: String? = tools.mapNotNull { it.hint?.takeIf { h -> h.isNotBlank() } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n")

    suspend fun execute(name: String, argumentsJson: String): String =
        byName[name]?.execute(argumentsJson) ?: ToolEnvelope.error("unknown_tool", "未知工具：$name")

    fun turnStart() = scoped.forEach { it.onTurnStart() }

    suspend fun turnSuccess() = scoped.forEach { it.onTurnSuccess() }

    fun turnAbort() = scoped.forEach { it.onTurnAbort() }
}

/** 工具结果信封：ok / error / message，各工具与循环共用。 */
object ToolEnvelope {
    fun error(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()
}
