package com.meapet.mobile.memory

import android.util.Log
import com.meapet.mobile.client.OpenAiCompatibleClient
import com.meapet.mobile.client.model.ApiRequest
import com.meapet.mobile.client.model.ApiResponse
import com.meapet.mobile.config.AppConfig
import com.meapet.mobile.memory.MemoryOpsProtocol.MemoryOp
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记忆业务服务。
 *
 * ## 职责
 * - 应用大模型在回复中声明的记忆操作（[applyOps]，来自 [MemoryOpsProtocol]）；
 * - 定期把短期记忆摘要压缩为一条长期记忆（[summarizeShortTermMemories]）。
 *
 * 记忆该不该创建、什么类型、多重要，全部由大模型自己判断——本类不再做
 * 基于长度/关键词的启发式提取，只负责校验、落库与摘要编排。
 *
 * ## 依赖关系
 * - [MemoryRepository] — 数据存储（必需）；
 * - [OpenAiCompatibleClient] — 用于摘要短期记忆（可选，无此 client 时静默跳过摘要）；
 * - [SettingsManager] — 读取记忆开关配置。
 *
 * ## 低耦合
 * - 不依赖 Chat 模块中的任何类型，仅操作 [MemoryItem]；
 * - 调用方（[MemoryManager]）决定何时触发。
 *
 * @param repository 记忆存储器
 * @param summarizationClient 提供 AI 总结用 HTTP 客户端的 provider（每次调用重新获取，
 *   以便配置变更重建客户端后立即生效；返回 null = 跳过摘要）
 * @param settingsManager 设置管理器
 * @param config 应用配置
 */
class MemoryService(
    private val repository: MemoryRepository,
    private val summarizationClient: () -> OpenAiCompatibleClient?,
    private val settingsManager: SettingsManager,
    private val config: AppConfig = AppConfig.DEFAULT
) {
    companion object {
        private const val TAG = "MemoryService"
    }

    @Serializable
    private data class SummaryResult(
        val content: String = "",
        val importance: Float = 0.5f,
        val keywords: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 应用模型本轮声明的记忆操作（创建/更新/删除）。
     *
     * 单条操作解析失败（如校验不通过）只记日志跳过，不影响其余操作；
     * 所有变更收集完后交给 [MemoryRepository.applyChanges] **整批只落盘一次**，
     * 避免逐条 save/delete 把整库全量重写 N 次（写放大）。
     */
    suspend fun applyOps(ops: List<MemoryOp>) {
        if (ops.isEmpty()) return
        val upserts = mutableListOf<MemoryItem>()
        val deletes = mutableListOf<String>()
        var resolved = 0
        for (op in ops) {
            try {
                when (op) {
                    is MemoryOp.Create -> upserts.add(
                        MemoryItem(
                            id = MemoryItem.newId(),
                            content = op.content,
                            type = op.type,
                            importance = op.importance.coerceIn(0f, 1f),
                            keywords = op.keywords.take(8)
                        )
                    ).also { Log.i(TAG, "Create ${op.type} memory queued") }
                    is MemoryOp.Update -> {
                        val existing = repository.findById(op.targetId)
                        if (existing == null) {
                            // targetId 过期或模型幻觉——退化为新建，避免更新意图被静默丢弃
                            Log.w(TAG, "Update target ${op.targetId} not found, falling back to create")
                            upserts.add(
                                MemoryItem(
                                    id = MemoryItem.newId(),
                                    content = op.content,
                                    type = op.type,
                                    importance = op.importance.coerceIn(0f, 1f),
                                    keywords = op.keywords.take(8)
                                )
                            )
                        } else {
                            upserts.add(
                                existing.copy(
                                    content = op.content,
                                    type = op.type,
                                    importance = op.importance.coerceIn(0f, 1f),
                                    keywords = op.keywords.take(8)
                                )
                            )
                        }
                    }
                    is MemoryOp.Delete -> deletes.add(op.targetId)
                }
                resolved++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只记操作类型，不输出 $op（其 content 为记忆内容，属用户隐私，禁止进日志）
                Log.w(TAG, "Failed to resolve memory op: ${op::class.simpleName}", e)
            }
        }
        repository.applyChanges(upserts, deletes)
        Log.i(TAG, "Applied $resolved/${ops.size} memory ops (${upserts.size} upsert, ${deletes.size} delete)")
    }

    /**
     * 把当前所有短期记忆摘要压缩为一条长期记忆。
     *
     * 成功后删除参与本次摘要的短期条目（只删调用开始时取到的快照，
     * 不影响调用期间新产生的短期记忆）；失败时短期记忆原样保留，下次触发再试。
     *
     * @return 生成的长期记忆条目；短期记忆不足 [AppConfig.minSummaryItems] 条或调用失败均返回 null
     */
    suspend fun summarizeShortTermMemories(): MemoryItem? {
        if (!settingsManager.isMemoryEnabled()) return null
        if (!settingsManager.isAutoSummaryEnabled()) return null

        val pending = repository.getByType(MemoryType.SHORT_TERM)
        // 攒够几条再合并：只有一两条时"摘要"等于原样搬运，白花一次请求
        if (pending.size < config.minSummaryItems) {
            Log.d(TAG, "Summary skipped: only ${pending.size} short-term memories (need ${config.minSummaryItems})")
            return null
        }

        val client = summarizationClient() ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val itemsText = pending.joinToString("\n") { "- ${it.content}" }
                val prompt = buildString {
                    appendLine("以下是若干条短期记忆，请提炼合并为一条长期记忆。")
                    appendLine("要求：content 用一句话概括、去除重复信息；importance 给 0~1 的重要性评分；")
                    appendLine("keywords 给 3~8 个**查找词**：用户以后聊到相关话题时会说出口、用来捞回这条记忆的词。")
                    appendLine("要覆盖具体事物 + 相关话题/类别（如「用户喜欢炸鸡」→ 炸鸡、喜欢、喜好、食物），")
                    appendLine("别只从 content 机械摘词，也别塞「用户」「记忆」「对话」这类空词。")
                    appendLine("只输出一个 JSON 对象，不要输出任何其他文字，格式：")
                    appendLine("""{"content":"...","importance":0.x,"keywords":["...","..."]}""")
                    appendLine()
                    appendLine("短期记忆：")
                    append(itemsText)
                }

                val requestBody = ApiRequest.chatCompletion(
                    model = config.memorySummaryModel ?: settingsManager.getModel(),
                    messages = listOf(
                        ApiRequest.textMessage(
                            "system",
                            "你是一个记忆摘要助手，只输出要求的 JSON 对象，不加任何说明文字。"
                        ),
                        ApiRequest.textMessage("user", prompt)
                    ),
                    temperature = 0.3,
                    maxTokens = 300
                )

                val response = client.chatCompletion(requestBody)
                val raw = ApiResponse.chatCompletionContent(response) ?: return@withContext null
                val result = parseSummaryResult(raw) ?: return@withContext null
                if (result.content.isBlank()) return@withContext null

                // keywords 为空的长期记忆永远匹配不上（MemoryRepository.getRelevant 直接跳过），
                // 落库就等于删掉 N 条短期记忆换来一条检索不到的死记录——宁可放弃本次摘要
                val keywords = result.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
                if (keywords.isEmpty()) {
                    Log.w(TAG, "Summary discarded: model returned no keywords, short-term memories kept")
                    return@withContext null
                }

                val item = MemoryItem(
                    id = MemoryItem.newId(),
                    content = result.content,
                    type = MemoryType.LONG_TERM,
                    importance = result.importance.coerceIn(0f, 1f),
                    keywords = keywords
                )
                repository.save(item)
                repository.deleteAll(pending.map { it.id })
                Log.i(TAG, "Summarized ${pending.size} short-term memories into one long-term memory")
                item
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to summarize short-term memories", e)
                null
            }
        }
    }

    /** 从模型输出中提取第一个 JSON 对象（容忍模型在 JSON 前后多写的说明文字）。 */
    private fun parseSummaryResult(raw: String): SummaryResult? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.decodeFromString<SummaryResult>(raw.substring(start, end + 1))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse summary result", e)
            null
        }
    }
}
