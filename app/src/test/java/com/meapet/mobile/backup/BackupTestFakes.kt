package com.meapet.mobile.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryStats
import com.meapet.mobile.memory.MemoryStore
import com.meapet.mobile.memory.MemoryType
import com.meapet.mobile.storage.ConversationRepository
import com.meapet.mobile.storage.InMemoryConversationRepository
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * 设置读写替身：内存里保存 DataStore 的 [Preferences] 快照。
 *
 * [apply] 与生产实现同路径——先把快照转成可变副本，再经 [SettingsWhitelist.apply]
 * 写入白名单键，因此能直接验证「导入只写白名单内的键」。
 *
 * `preferencesOf` / `mutablePreferencesOf` 属于 `datastore-preferences-core` 的 JVM
 * 实现，纯 JVM 单测可用，无需 Robolectric。
 */
class FakeSettingsSink(initial: Preferences = emptyPreferences()) : SettingsSink {

    private val lock = Any()
    private var prefs: Preferences = initial

    /** 每次 [apply] 收到的键值（按调用顺序），供断言「未参与导入的类别没被写过」。 */
    val applied: MutableList<Map<String, JsonElement>> = mutableListOf()

    override fun snapshot(): Preferences = synchronized(lock) { prefs }

    override suspend fun apply(values: Map<String, JsonElement>) {
        synchronized(lock) {
            val editable = prefs.toMutablePreferences()
            SettingsWhitelist.apply(values, editable)
            prefs = editable.toPreferences()
            applied += LinkedHashMap(values)
        }
    }

    fun getString(key: String): String? = synchronized(lock) { prefs[stringPreferencesKey(key)] }

    fun getBoolean(key: String): Boolean? = synchronized(lock) { prefs[booleanPreferencesKey(key)] }

    fun getDouble(key: String): Double? = synchronized(lock) { prefs[doublePreferencesKey(key)] }

    fun getInt(key: String): Int? = synchronized(lock) { prefs[intPreferencesKey(key)] }

    /** 预置当前设置（模拟本机已有数据）。 */
    fun seed(block: (MutablePreferences) -> Unit) {
        synchronized(lock) {
            prefs = prefs.toMutablePreferences().also(block).toPreferences()
        }
    }
}

/**
 * 会话仓库替身：默认委托给 [InMemoryConversationRepository]。
 *
 * [failReplaceAll] 置 true 后 `replaceAll` 抛异常（异常消息故意带敏感字样，
 * 便于断言导入失败原因不会把底层异常文本透传给 UI）。
 */
class FakeConversationRepository(
    private val delegate: InMemoryConversationRepository = InMemoryConversationRepository(),
) : ConversationRepository {

    /** 置 true 后 [replaceAll] 抛异常，模拟对话类别写入失败。 */
    var failReplaceAll: Boolean = false

    override suspend fun append(message: ChatMessage) {
        delegate.append(message)
    }

    override suspend fun appendAll(messages: List<ChatMessage>) {
        delegate.appendAll(messages)
    }

    override suspend fun remove(id: String): Boolean = delegate.remove(id)

    override suspend fun clear() {
        delegate.clear()
    }

    override suspend fun replaceAll(messages: List<ChatMessage>) {
        if (failReplaceAll) throw IllegalStateException("secret-dialog-content-must-not-leak")
        delegate.replaceAll(messages)
    }

    override suspend fun loadRecent(limit: Int): List<ChatMessage> = delegate.loadRecent(limit)

    override suspend fun pageBefore(beforeId: String?, limit: Int): List<ChatMessage> =
        delegate.pageBefore(beforeId, limit)

    override suspend fun count(): Int = delegate.count()

    override suspend fun contains(id: String): Boolean = delegate.contains(id)

    /** 预置本机已有消息。 */
    suspend fun seed(vararg messages: ChatMessage) {
        delegate.replaceAll(messages.toList())
    }

    /** 当前全部消息（按写入顺序）。 */
    suspend fun messages(): List<ChatMessage> = delegate.loadRecent(Int.MAX_VALUE)
}

/**
 * 记忆存储替身：简单 [MutableList]，并记录 [replaceAll] 调用（验证单事务接口被使用）。
 */
class FakeMemoryStore : MemoryStore {

    private val lock = Any()
    private val items = mutableListOf<MemoryItem>()
    private var exchangeCount = 0

    /** 每次 [replaceAll] 的参数（条目列表 + 轮数），按调用顺序。 */
    val replaceAllCalls: MutableList<Pair<List<MemoryItem>, Int>> = mutableListOf()

    override suspend fun save(item: MemoryItem): MemoryItem {
        synchronized(lock) { put(item) }
        return item
    }

    override suspend fun saveAll(items: List<MemoryItem>) {
        synchronized(lock) { items.forEach { put(it) } }
    }

    override suspend fun applyChanges(upserts: List<MemoryItem>, deleteIds: Collection<String>) {
        synchronized(lock) {
            upserts.forEach { put(it) }
            items.removeAll { it.id in deleteIds }
        }
    }

    override suspend fun findById(id: String): MemoryItem? =
        synchronized(lock) { items.firstOrNull { it.id == id } }

    override suspend fun getAll(): List<MemoryItem> = synchronized(lock) { items.toList() }

    override suspend fun getByType(type: MemoryType): List<MemoryItem> =
        synchronized(lock) { items.filter { it.type == type } }

    override suspend fun getPersonaFacts(maxCount: Int): List<MemoryItem> = synchronized(lock) {
        items.filter { it.type.isPermanent }.sortedBy { it.createdAt }.take(maxCount)
    }

    override suspend fun delete(id: String) {
        synchronized(lock) { items.removeAll { it.id == id } }
    }

    override suspend fun deleteAll(ids: Collection<String>) {
        synchronized(lock) { items.removeAll { it.id in ids } }
    }

    override suspend fun clear() {
        synchronized(lock) { items.clear() }
    }

    override suspend fun search(query: String): List<MemoryItem> = synchronized(lock) {
        items.filter { it.content.contains(query, ignoreCase = true) }
    }

    override suspend fun getRelevant(userInput: String, maxCount: Int): List<MemoryItem> =
        synchronized(lock) { items.take(maxCount) }

    override suspend fun markAccessed(ids: Collection<String>) {
        synchronized(lock) {
            ids.forEach { id ->
                val index = items.indexOfFirst { it.id == id }
                if (index >= 0) items[index] = items[index].accessed()
            }
        }
    }

    override suspend fun getStats(): MemoryStats = synchronized(lock) {
        MemoryStats(
            totalCount = items.size,
            shortTermCount = items.count { it.type == MemoryType.SHORT_TERM },
            longTermCount = items.count { it.type == MemoryType.LONG_TERM },
            coreTraitsCount = items.count { it.type == MemoryType.CORE_TRAIT },
            factualsCount = items.count { it.type == MemoryType.FACTUAL },
            averageImportance = if (items.isEmpty()) {
                0f
            } else {
                items.map { it.importance.toDouble() }.average().toFloat()
            },
        )
    }

    override suspend fun trimCache() = Unit

    override suspend fun loadFromDisk() = Unit

    override suspend fun getExchangeCount(): Int = synchronized(lock) { exchangeCount }

    override suspend fun setExchangeCount(count: Int) {
        synchronized(lock) { exchangeCount = count }
    }

    override suspend fun replaceAll(items: List<MemoryItem>, exchangeCount: Int) {
        synchronized(lock) {
            replaceAllCalls += items.toList() to exchangeCount
            this.items.clear()
            this.items.addAll(items)
            this.exchangeCount = exchangeCount
        }
    }

    private fun put(item: MemoryItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) items[index] = item else items.add(item)
    }
}

/**
 * 备份包读取替身：内容元信息 + 各条目的文本/字节分离给出。
 *
 * 包内路径由调用方通过 [BackupContents] 完全控制（可构造带 `..`/非法字符的壁纸名，
 * 验证导入侧的安全化不依赖 Inspector 的路径校验）。
 */
class FakeBackupContentReader(
    override val contents: BackupContents,
    private val texts: Map<BackupCategory, String> = emptyMap(),
    private val files: Map<BackupCategory, ByteArray> = emptyMap(),
) : BackupContentReader {

    /** 被请求解出的类别（按请求顺序），用于断言未勾选类别没被读取。 */
    val extracted: MutableList<BackupCategory> = mutableListOf()

    /** 自定义 [extractToFile] 行为（模拟写出中途失败等）；默认写 [files] 里的字节。 */
    var onExtractToFile: ((category: BackupCategory, target: File) -> Unit)? = null

    var closed: Boolean = false
        private set

    override fun extractText(category: BackupCategory, maxBytes: Long): String {
        extracted += category
        return texts[category] ?: throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
    }

    override fun extractToFile(category: BackupCategory, target: File) {
        extracted += category
        val custom = onExtractToFile
        if (custom != null) {
            custom(category, target)
            return
        }
        val bytes = files[category] ?: throw ManifestRejectException(BackupFormat.STRUCTURE_INCOMPLETE)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    override fun close() {
        closed = true
    }
}

// ── 构造辅助 ──────────────────────────────────────────

/** 构造「包内只含给定类别」的 [BackupContents]；壁纸条目路径可按用例覆盖。 */
internal fun backupContentsOf(
    vararg categories: BackupCategory,
    wallpaperPath: String = "files/wallpaper/wallpaper.jpg",
): BackupContents {
    val entries = LinkedHashMap<BackupCategory, BackupContentEntry>()
    categories.forEach { category ->
        entries[category] = BackupContentEntry(
            path = if (category == BackupCategory.WALLPAPER) {
                wallpaperPath
            } else {
                BackupFormat.pathFor(category)
            },
            bytes = 1L,
        )
    }
    val manifest = BackupManifest(
        format = BackupFormat.SUPPORTED_FORMAT,
        createdAt = "2026-11-22T15:30:00Z",
        appVersion = "1.9.0-test",
        entries = entries.map { (category, entry) ->
            ManifestEntry(
                name = category.entryName,
                schema = BackupFormat.schemaFor(category),
                bytes = entry.bytes,
                path = entry.path,
            )
        },
    )
    return BackupContents(manifest, entries)
}

/** 测试用聊天消息（固定 id / 内容，便于断言顺序）。 */
internal fun testMessage(
    id: String,
    content: String = "内容-$id",
    role: ChatRole = ChatRole.user,
): ChatMessage = ChatMessage(
    id = id,
    role = role,
    content = content,
    timestamp = 1_700_000_000_000L,
)

/** 测试用记忆条目。 */
internal fun testMemory(
    id: String,
    content: String = "记忆-$id",
    type: MemoryType = MemoryType.SHORT_TERM,
): MemoryItem = MemoryItem(
    id = id,
    content = content,
    type = type,
    createdAt = 1_700_000_000_000L,
)
