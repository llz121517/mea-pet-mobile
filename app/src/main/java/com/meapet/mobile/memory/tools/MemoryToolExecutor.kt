package com.meapet.mobile.memory.tools

import android.util.Log
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.memory.search.MemoryQuery
import com.meapet.mobile.memory.search.MemorySearch
import com.meapet.mobile.memory.search.MemorySort
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 一个 turn 内工具产生的待提交变更。
 *
 * **暂存后一次性提交**：写类工具先记在这里，等整个 turn（含多轮工具调用）成功拿到最终回复后，
 * 由 ChatService 调用 [MemoryStore.applyChanges] 一次性落库。失败/取消/被打断则直接丢弃，
 * 因此「重试、断网、插队」都不会产生重复或幽灵记忆。
 *
 * @property deletedItems 删除条目的**墓碑快照**（删除前已在库里的完整条目），供撤销还原；
 *   同 turn 内刚创建又删除的记忆没有墓碑（它从未真正落库，撤销无意义）
 */
data class PendingMemoryChanges(
    val upserts: List<MemoryItem> = emptyList(),
    val deletes: List<String> = emptyList(),
    val deletedItems: List<MemoryItem> = emptyList()
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()
}

/**
 * 记忆工具执行器。
 *
 * ## 返回约定
 * 一律返回**字符串化 JSON** 作为 tool 消息内容，**不抛异常**：
 * - 成功：`{"ok":true,...}`
 * - 失败：`{"ok":false,"error":"code","message":"给模型看的说明"}`
 *
 * 让模型自己看到错误并修正参数，比让整轮请求失败更稳。
 *
 * ## 写类工具的护栏
 * - 单 turn 写操作上限 [MAX_WRITES_PER_TURN]（防刷屏）；
 * - 单 turn 删除永久类（FACTUAL/CORE_TRAIT）上限 [MAX_PERMANENT_DELETES]，且必须显式
 *   `confirm_permanent=true` + 非空 `reason`；
 * - `update_memory` 目标不存在返回 `not_found`，**不**静默退化为新建；
 * - `create_memory` 不接受 `LONG_TERM`（由摘要产生）；
 * - content 上限 [MAX_CONTENT_CHARS]，keywords 去重去空最多 8 个。
 *
 * ## 检索结果体积
 * 单次检索总长约 [MAX_RESULT_CHARS] 上限：条数上限 10、单条 content 截断 200 字，
 * 用 `age_days` 代替绝对时间戳（省 token、避免时区问题）。
 */
class MemoryToolExecutor(
    private val search: MemorySearch,
    /** 读写记忆的存储；仅做只读检索时可为 null（此时写类工具返回 unsupported）。 */
    private val store: MemoryStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * turn 成功后提交暂存变更的回调（生产环境由容器传入 `memoryManager::applyToolChanges`）。
     * 为 null 时 [onTurnSuccess] 只清空暂存，不提交。
     */
    private val commit: (suspend (PendingMemoryChanges) -> Unit)? = null,
    /** 动态总开关：执行与提交时读取，防止在途轮次绕过用户关闭记忆的操作。 */
    private val isMemoryEnabled: () -> Boolean = { true }
) : com.meapet.mobile.chat.tools.TurnScoped {

    // ── turn 生命周期（TurnScoped）──────────────────────

    override fun onTurnStart() = discardPending()

    override suspend fun onTurnSuccess() {
        val pending = drainPending()
        if (isMemoryEnabled() && !pending.isEmpty) commit?.invoke(pending)
    }

    override fun onTurnAbort() = discardPending()

    /**
     * 把本执行器的 4 个记忆工具暴露为 [com.meapet.mobile.chat.tools.Tool]，供 [com.meapet.mobile.chat.tools.ToolRegistry] 注册。
     *
     * 顺序固定为 search → create → update → delete，与 [MemoryToolSchemas.ALL] 逐字节一致
     * （tools 数组属于请求前缀，顺序变化会使缓存失效）。四个工具共享同一份 turn 暂存状态，
     * 因此仍由本类统一执行，只是分别包装成 Tool。
     */
    fun asTools(): List<com.meapet.mobile.chat.tools.Tool> =
        MemoryToolSchemas.ALL.map { element ->
            val schema = element.jsonObject
            val toolName = schema["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            object : com.meapet.mobile.chat.tools.Tool {
                override val name: String = toolName
                override val schema: JsonObject = schema
                // hint 只挂在第一个工具上，避免同一段说明被注册表重复拼接
                override val hint: String? =
                    if (toolName == MemoryToolSchemas.SEARCH_MEMORY) MemoryToolSchemas.HINT else null
                override suspend fun execute(argumentsJson: String): String =
                    this@MemoryToolExecutor.execute(toolName, argumentsJson)
            }
        }

    companion object {
        private const val TAG = "MemoryToolExecutor"
        private const val MAX_RESULT_CHARS = 1500
        private const val MAX_ITEM_CHARS = 200
        private const val MAX_CONTENT_CHARS = 500
        private const val MAX_KEYWORDS = 8
        private const val MAX_WRITES_PER_TURN = 6
        private const val MAX_PERMANENT_DELETES = 2

        private const val UNKNOWN_TOOL = "unknown_tool"
        private const val INVALID_ARGUMENTS = "invalid_arguments"
        private const val NOT_FOUND = "not_found"
        private const val UNSUPPORTED = "unsupported"
        private const val TOO_MANY_WRITES = "too_many_writes"
        private const val PERMANENT_DELETE_CONFIRM = "permanent_delete_requires_confirmation"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── 本 turn 暂存 ──────────────────────────────────
    private val pendingUpserts = LinkedHashMap<String, MemoryItem>()
    private val pendingDeletes = LinkedHashSet<String>()

    /** 删除条目的墓碑快照：id → 删除前在库里的完整条目（同 turn 内新建的条目没有墓碑）。 */
    private val deletionTombstones = LinkedHashMap<String, MemoryItem>()
    private var writeCount = 0
    private var permanentDeleteCount = 0

    /** 取出并清空本 turn 的待提交变更（turn 成功后调用）。 */
    fun drainPending(): PendingMemoryChanges {
        val changes = PendingMemoryChanges(
            upserts = pendingUpserts.values.toList(),
            deletes = pendingDeletes.toList(),
            deletedItems = deletionTombstones.values.toList()
        )
        discardPending()
        return changes
    }

    /** 丢弃本 turn 的暂存（turn 失败/取消/被打断时调用）。 */
    fun discardPending() {
        pendingUpserts.clear()
        pendingDeletes.clear()
        deletionTombstones.clear()
        writeCount = 0
        permanentDeleteCount = 0
    }

    /**
     * 执行一次工具调用。
     *
     * @param name 工具名（来自 `tool_calls[].function.name`）
     * @param argumentsJson `tool_calls[].function.arguments`（JSON 字符串）
     * @return tool 消息的 content（字符串化 JSON 信封）
     */
    suspend fun execute(name: String, argumentsJson: String): String = try {
        if (!isMemoryEnabled()) {
            discardPending()
            errorEnvelope("memory_disabled", "记忆已关闭，不能检索或修改记忆")
        } else when (name) {
            MemoryToolSchemas.SEARCH_MEMORY -> searchMemory(argumentsJson)
            MemoryToolSchemas.CREATE_MEMORY -> createMemory(argumentsJson)
            MemoryToolSchemas.UPDATE_MEMORY -> updateMemory(argumentsJson)
            MemoryToolSchemas.DELETE_MEMORY -> deleteMemory(argumentsJson)
            else -> errorEnvelope(UNKNOWN_TOOL, "未知工具：$name")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Tool execution failed: $name", e)
        errorEnvelope("tool_failed", "工具执行失败，请换个参数或稍后再试")
    }

    // ── search ────────────────────────────────────────

    private suspend fun searchMemory(argumentsJson: String): String {
        val args = parseObject(argumentsJson)
            ?: return errorEnvelope(INVALID_ARGUMENTS, "参数不是合法 JSON 对象")
        val query = args.str("query")
        if (query.isEmpty()) return errorEnvelope(INVALID_ARGUMENTS, "query 不能为空")
        val limit = (args["limit"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 10) ?: 5
        val types = parseTypes(args["types"])
        val sort = when (args.str("sort")) {
            "recent" -> MemorySort.RECENT
            "importance" -> MemorySort.IMPORTANCE
            else -> MemorySort.RELEVANCE
        }

        // 候选池 = 已落库记忆去掉本 turn 待删除项，再用本 turn 暂存 upsert 按 id 覆盖/新增。
        // 这样同一 turn 内 create/update 之后紧跟的 search 也能看到这些尚未落库的条目。
        val mergedView = store?.let { st ->
            val merged = LinkedHashMap<String, MemoryItem>()
            st.getAll().filter { it.id !in pendingDeletes }.forEach { merged[it.id] = it }
            pendingUpserts.forEach { (id, item) -> merged[id] = item }
            merged.values.toList()
        }

        val items = search.search(
            MemoryQuery(
                text = query,
                types = types,
                limit = limit,
                sort = sort,
                candidates = mergedView
            )
        )
        // 隐私安全日志：只记录长度/条数，绝不打印 query 内容或记忆内容。
        // candidates=-1 表示只读模式（store == null），候选池由检索实现自行决定。
        Log.d(
            TAG,
            "search_memory: query.length=${query.length}, candidates=${mergedView?.size ?: -1}, returned=${items.size}"
        )
        return successEnvelope(items)
    }

    // ── create ────────────────────────────────────────

    private suspend fun createMemory(argumentsJson: String): String {
        val args = parseObject(argumentsJson)
            ?: return errorEnvelope(INVALID_ARGUMENTS, "参数不是合法 JSON 对象")
        val st = store ?: return errorEnvelope(UNSUPPORTED, "当前不支持写入记忆")
        if (writeCount >= MAX_WRITES_PER_TURN) {
            return errorEnvelope(TOO_MANY_WRITES, "本轮写入过多，请减少到 $MAX_WRITES_PER_TURN 条以内")
        }
        val content = args.str("content")
        if (content.isEmpty()) return errorEnvelope(INVALID_ARGUMENTS, "content 不能为空")
        if (content.length > MAX_CONTENT_CHARS) {
            return errorEnvelope(INVALID_ARGUMENTS, "content 太长（上限 $MAX_CONTENT_CHARS 字）")
        }
        val typeName = args.str("type").ifEmpty { MemoryType.SHORT_TERM.name }
        val type = runCatching { MemoryType.valueOf(typeName) }.getOrNull()
            ?: return errorEnvelope(INVALID_ARGUMENTS, "非法 type：$typeName")
        if (type == MemoryType.LONG_TERM) {
            return errorEnvelope("invalid_type", "长期记忆由系统摘要生成，不能直接创建")
        }
        val importance = (args["importance"] as? JsonPrimitive)?.doubleOrNull
            ?.coerceIn(0.0, 1.0)?.toFloat() ?: 0.5f
        val keywords = parseKeywords(args["keywords"])

        // 去重：同 type 同内容已存在时直接返回已有 id，避免模型重复创建
        val existing = (pendingUpserts.values + st.getAll()).firstOrNull {
            it.type == type && it.content == content
        }
        if (existing != null) {
            return buildJsonObject {
                put("ok", true)
                put("id", existing.id)
                put("status", "duplicate")
            }.toString()
        }

        val now = clock()
        val id = MemoryItem.newId()
        pendingUpserts[id] = MemoryItem(
            id = id,
            content = content,
            type = type,
            importance = importance,
            createdAt = now,
            lastAccessedAt = now,
            keywords = keywords
        )
        writeCount++
        return buildJsonObject {
            put("ok", true)
            put("id", id)
            put("status", "queued")
        }.toString()
    }

    // ── update ────────────────────────────────────────

    private suspend fun updateMemory(argumentsJson: String): String {
        val args = parseObject(argumentsJson)
            ?: return errorEnvelope(INVALID_ARGUMENTS, "参数不是合法 JSON 对象")
        store ?: return errorEnvelope(UNSUPPORTED, "当前不支持写入记忆")
        if (writeCount >= MAX_WRITES_PER_TURN) {
            return errorEnvelope(TOO_MANY_WRITES, "本轮写入过多，请减少到 $MAX_WRITES_PER_TURN 条以内")
        }
        val id = args.str("id")
        if (id.isEmpty()) return errorEnvelope(INVALID_ARGUMENTS, "id 不能为空")
        val existing = findItem(id)
            ?: return errorEnvelope(NOT_FOUND, "id $id 不存在，请先 search_memory 确认")
        val content = args.str("content")
        if (content.isEmpty()) return errorEnvelope(INVALID_ARGUMENTS, "content 不能为空")
        if (content.length > MAX_CONTENT_CHARS) {
            return errorEnvelope(INVALID_ARGUMENTS, "content 太长（上限 $MAX_CONTENT_CHARS 字）")
        }
        val type = args.str("type").ifEmpty { existing.type.name }.let { name ->
            runCatching { MemoryType.valueOf(name) }.getOrNull()
                ?: return errorEnvelope(INVALID_ARGUMENTS, "非法 type：$name")
        }
        // 与 create 一致：长期记忆只由摘要流程生成，工具不得创建或改写出来
        if (type == MemoryType.LONG_TERM) {
            return errorEnvelope("invalid_type", "长期记忆由系统摘要生成，不能直接创建或修改")
        }
        // 永久类（事实/特质）不允许改类型：否则模型可先降级为短期、再免确认删除，
        // 绕过 delete_memory 的 confirm_permanent 护栏
        if (existing.type.isPermanent && type != existing.type) {
            return errorEnvelope(
                INVALID_ARGUMENTS,
                "事实/特质类记忆的类型不能修改；如需删除请按永久类流程请求用户确认"
            )
        }
        val importance = (args["importance"] as? JsonPrimitive)?.doubleOrNull
            ?.coerceIn(0.0, 1.0)?.toFloat() ?: existing.importance
        val keywords = parseKeywords(args["keywords"]).ifEmpty { existing.keywords }

        pendingUpserts[id] = existing.copy(
            content = content,
            type = type,
            importance = importance,
            keywords = keywords
        )
        writeCount++
        return buildJsonObject {
            put("ok", true)
            put("id", id)
            put("status", "queued")
        }.toString()
    }

    // ── delete ────────────────────────────────────────

    private suspend fun deleteMemory(argumentsJson: String): String {
        val args = parseObject(argumentsJson)
            ?: return errorEnvelope(INVALID_ARGUMENTS, "参数不是合法 JSON 对象")
        val st = store ?: return errorEnvelope(UNSUPPORTED, "当前不支持写入记忆")
        val id = args.str("id")
        if (id.isEmpty()) return errorEnvelope(INVALID_ARGUMENTS, "id 不能为空")
        val existing = findItem(id)
            ?: return errorEnvelope(NOT_FOUND, "id $id 不存在，请先 search_memory 确认")

        val permanent = existing.type.isPermanent
        if (permanent) {
            val confirmed = (args["confirm_permanent"] as? JsonPrimitive)?.booleanOrNull == true
            val reason = args.str("reason")
            if (!confirmed || reason.isEmpty()) {
                return errorEnvelope(
                    PERMANENT_DELETE_CONFIRM,
                    "删除事实/特质类记忆需要在用户明确要求时进行，请带上 confirm_permanent=true 与 reason"
                )
            }
            if (permanentDeleteCount >= MAX_PERMANENT_DELETES) {
                return errorEnvelope(TOO_MANY_WRITES, "本轮删除永久类记忆过多")
            }
            permanentDeleteCount++
        }
        if (writeCount >= MAX_WRITES_PER_TURN) {
            return errorEnvelope(TOO_MANY_WRITES, "本轮写入过多，请减少到 $MAX_WRITES_PER_TURN 条以内")
        }

        pendingUpserts.remove(id)
        pendingDeletes.add(id)
        // 墓碑取**删除前已在库里的原始条目**：本轮先 update 后 delete 时，撤销应回到本轮修改前，
        // 而不是恢复成中间版本。同 turn 内新建又删除的条目库里查不到，自然没有墓碑。
        st.findById(id)?.let { original -> deletionTombstones.putIfAbsent(id, original) }
        writeCount++
        return buildJsonObject {
            put("ok", true)
            put("id", id)
            put("status", "queued")
        }.toString()
    }

    // ── 内部 ──────────────────────────────────────────

    private suspend fun findItem(id: String): MemoryItem? = pendingUpserts[id] ?: store?.findById(id)

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

    private fun parseTypes(el: JsonElement?): Set<MemoryType>? {
        val array = el as? JsonArray ?: return null
        val types = array.mapNotNull { item ->
            val name = (item as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            runCatching { MemoryType.valueOf(name) }.getOrNull()
        }.toSet()
        return types.takeIf { it.isNotEmpty() }
    }

    /** keywords：trim、去空、去重、最多 8 个。 */
    private fun parseKeywords(el: JsonElement?): List<String> {
        val array = el as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}
