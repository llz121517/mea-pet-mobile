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
 * - `search_memory`：只读检索
 * - `create_memory` / `update_memory` / `delete_memory`：写类工具（暂存，turn 成功后提交）
 *
 * 增删工具会改变 tools 数组，使前缀缓存整体失效一次，并需要更新字节快照测试。
 */
object MemoryToolSchemas {

    const val SEARCH_MEMORY = "search_memory"
    const val CREATE_MEMORY = "create_memory"
    const val UPDATE_MEMORY = "update_memory"
    const val DELETE_MEMORY = "delete_memory"

    /**
     * 记忆工具的固定使用说明（拼进首条 system）。
     *
     * 必须**字节级稳定**：它属于请求前缀，含任何动态内容都会破坏缓存。
     * 只讲「何时用」，具体参数由 tools schema 承担。
     */
    const val HINT: String =
        "【记忆工具】你不确定是否记过用户过去说过的事时，先调用 search_memory 检索；" +
            "用户明确要求记住、忘记或纠正信息时，用 create_memory / update_memory / delete_memory。" +
            "不要记录你自己的提议或猜测。" +
            "调用工具前只说一句简短的话（不要长篇铺垫），最终回复不要重复前面说过的内容。"

    /** 可写入的记忆类型（LONG_TERM 只由摘要产生，模型不可直接创建）。 */
    private val WRITABLE_TYPES = listOf("SHORT_TERM", "CORE_TRAIT", "FACTUAL")

    /** 全部工具定义（固定顺序：search → create → update → delete）。 */
    val ALL: JsonArray = buildJsonArray {
        add(searchMemory())
        add(createMemory())
        add(updateMemory())
        add(deleteMemory())
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

    private fun createMemory(): JsonObject = functionTool(
        name = CREATE_MEMORY,
        description = "记录一条以后还能用到的、用户自述或已确认的信息。禁止记录你自己的提议、猜测或推测。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "记忆类型；长期记忆由系统摘要生成，不可直接创建")
                    putJsonArray("enum") { WRITABLE_TYPES.forEach { add(it) } }
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "写清主语，如「主人说明天要面试」")
                }
                putJsonObject("importance") {
                    put("type", "number")
                    put("minimum", 0)
                    put("maximum", 1)
                }
                putJsonObject("keywords") {
                    put("type", "array")
                    put("description", "3~8 个查找词：用户以后聊到相关话题时会说出口的词")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("content"); add("keywords") }
        }
    )

    private fun updateMemory(): JsonObject = functionTool(
        name = UPDATE_MEMORY,
        description = "更正或补充一条已有记忆（用 search_memory 拿到的 id）。目标不存在时返回错误，不要猜 id。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "要更新的记忆 id，如 mem_a1b2c3d4")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "更新后的完整内容")
                }
                putJsonObject("type") {
                    put("type", "string")
                    put("description", "可选，修改记忆类型")
                    putJsonArray("enum") { WRITABLE_TYPES.forEach { add(it) } }
                }
                putJsonObject("importance") {
                    put("type", "number")
                    put("minimum", 0)
                    put("maximum", 1)
                }
                putJsonObject("keywords") {
                    put("type", "array")
                    put("description", "可选，替换原关键词（3~8 个）")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("id"); add("content") }
        }
    )

    private fun deleteMemory(): JsonObject = functionTool(
        name = DELETE_MEMORY,
        description = "删除一条记忆。删除永久类记忆（事实/特质）必须显式确认并给出原因，仅在用户明确要求忘记或纠正时使用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "要删除的记忆 id")
                }
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "为什么删除（用户明确要求/信息错误）")
                }
                putJsonObject("confirm_permanent") {
                    put("type", "boolean")
                    put("description", "删除事实/特质类记忆时必须为 true")
                }
            }
            putJsonArray("required") { add("id") }
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
