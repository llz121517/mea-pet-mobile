package com.meapet.mobile.client

import android.util.Log
import com.meapet.mobile.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 探测缓存有效期：每 5 分钟视为过期，下次触发时后台刷新。 */
internal const val CAPACITY_CACHE_TTL_MS: Long = 5L * 60 * 1000

/** 探测失败的负缓存时长，避免重复请求风暴。 */
internal const val CAPACITY_NEGATIVE_TTL_MS: Long = 5L * 60 * 1000

/**
 * 上下文容量提供者。
 *
 * 优先级：DataStore 手动值 > 内存缓存（探测值）> 兜底。缓存仅内存态，不落盘。
 * 获取契机共用同一入口 [refresh]：保存/切换设置、首次启动、首次发送且无缓存、缓存过期。
 * 云端与本地（含回环）共用同一套逻辑，仅兜底值不同。
 */
class ContextCapacityProvider(
    private val settingsManager: SettingsManager,
    private val clientFactory: () -> OpenAiCompatibleClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val key: String, val tokens: Int?, val at: Long)

    @Volatile
    private var cache: Entry? = null
    private val mutex = Mutex()
    private var job: Job? = null

    private fun currentKey(): String =
        settingsManager.getApiUrl().trim().trimEnd('/') + "|" + settingsManager.getModel().trim()

    /** 当前生效的原始容量与来源（同步读取，不阻塞）。 */
    fun current(): ContextCapacity {
        settingsManager.getContextWindow()?.let { return ContextCapacity(it, CapacitySource.MANUAL) }
        val e = cache
        if (e != null && e.key == currentKey() && e.tokens != null) {
            return ContextCapacity(e.tokens, CapacitySource.PROBED)
        }
        return ContextCapacity(fallbackCapacity(settingsManager.getApiUrl()), CapacitySource.FALLBACK)
    }

    /** 当前有效预算（容量 × 0.85 − max_tokens，下限容量 25%）。 */
    fun budget(): Int = effectiveBudget(current().tokens, settingsManager.getMaxTokens())

    /** 缓存缺失、过期或对应设置已变时返回 true。 */
    fun needsRefresh(): Boolean {
        val e = cache ?: return true
        return e.key != currentKey() || clock() - e.at > CAPACITY_CACHE_TTL_MS
    }

    /** 需要时后台刷新（不阻塞调用方）。统一入口。 */
    fun refreshIfNeeded() {
        if (settingsManager.getContextWindow() != null) return
        if (!needsRefresh()) return
        refresh()
    }

    /** 强制后台重新探测（保存/切换设置、重新检测按钮）。 */
    fun refresh() {
        job?.cancel()
        job = scope.launch { probeOnce() }
    }

    /** 挂起式探测入口（refresh 的同步等价物，供测试等待完成）。 */
    suspend fun probeNow() {
        probeOnce()
    }

    /** 供设置页拉取模型列表时顺带写入：避免多发一次请求。 */
    fun onModelsBody(body: String) {
        val key = currentKey()
        val tokens = ContextCapacityParser.parse(body, settingsManager.getModel())
        cache = Entry(key, tokens, clock())
    }

    private suspend fun probeOnce() {
        mutex.withLock {
            val key = currentKey()
            val model = settingsManager.getModel()
            val tokens = withContext(Dispatchers.IO) {
                val client = clientFactory()
                try {
                    ContextCapacityParser.parse(client.listModels(), model)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ContextCapacity", "probe failed: ${e.message}")
                    null
                }
            }
            // 失败同样写入（tokens = null），借 TTL 充当负缓存
            cache = Entry(key, tokens, clock())
        }
    }
}
