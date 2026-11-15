package com.meapet.mobile.update

import android.util.Log
import com.meapet.mobile.client.HttpClientEngine
import com.meapet.mobile.core.runCatchingLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.meapet.mobile.client.HttpMethod
import com.meapet.mobile.client.HttpRequest
import com.meapet.mobile.client.KtorHttpClientEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从 GitHub Releases 检测应用更新。
 *
 * 接口：`GET <仓库地址>/releases/latest`
 * 仅返回最新的非 draft、非 prerelease 正式版。
 */
class UpdateChecker(
    private val currentVersionProvider: () -> String,
    private val engine: HttpClientEngine = KtorHttpClientEngine(),
    private val releasesUrl: String = DEFAULT_RELEASES_URL
) {

    /**
     * 查询最新正式版并与本地 [currentVersionProvider] 比较。
     * 网络/解析失败时返回 [UpdateCheckResult.Failed]，不抛异常。
     */
    suspend fun check(): UpdateCheckResult {
        val current = currentVersionProvider().ifBlank { "0.0.0" }
        return try {
            val response = engine.execute(
                HttpRequest(
                    method = HttpMethod.GET,
                    url = releasesUrl,
                    headers = mapOf(
                        "Accept" to "application/vnd.github+json",
                        "User-Agent" to "MeaPet-Android",
                        "X-GitHub-Api-Version" to "2022-11-28"
                    )
                )
            )
            if (response.statusCode == 404) {
                return UpdateCheckResult.Failed("尚未发布正式版本")
            }
            if (response.statusCode !in 200..299) {
                return UpdateCheckResult.Failed("检测失败（HTTP ${response.statusCode}）")
            }
            // JSON 解析（首次还含 JsonElement 等类初始化）挪到后台：check() 的调用方
            // 常在 Dispatchers.Main 上（启动时静默检测），别让解析占用主线程。
            val release = withContext(Dispatchers.Default) {
                parseRelease(response.bodyAsText())
            } ?: return UpdateCheckResult.Failed("解析版本信息失败")
            if (isNewer(release.versionName, current)) {
                UpdateCheckResult.UpdateAvailable(release = release, currentVersion = current)
            } else {
                UpdateCheckResult.UpToDate(currentVersion = current)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            UpdateCheckResult.Failed(e.message ?: "网络异常，检测失败")
        }
    }

    fun close() {
        engine.close()
    }

    companion object {
        private const val TAG = "UpdateChecker"

        /** 默认发布接口地址：由仓库地址在构建时注入生成。 */
        val DEFAULT_RELEASES_URL: String = run {
            val repo = com.meapet.mobile.BuildConfig.GIT_REPO_URL
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removeSuffix("/")
            if (repo.isBlank()) {
                "https://api.github.com/repos/llz121517/mea-pet-mobile/releases/latest"
            } else {
                "https://api.github.com/repos/$repo/releases/latest"
            }
        }

        private val json = Json { ignoreUnknownKeys = true }

        /** 去掉前缀 `v`/`V` 后的语义化版本比较；remote > local 时为 true。 */
        fun isNewer(remoteVersion: String, localVersion: String): Boolean =
            compareVersions(remoteVersion, localVersion) > 0

        fun compareVersions(a: String, b: String): Int {
            val pa = versionParts(a)
            val pb = versionParts(b)
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val da = pa.getOrElse(i) { 0 }
                val db = pb.getOrElse(i) { 0 }
                if (da != db) return da.compareTo(db)
            }
            return 0
        }

        private fun versionParts(version: String): List<Int> =
            version.trim()
                .removePrefix("v")
                .removePrefix("V")
                .split('.', '-', '_')
                .map { segment ->
                    segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                }
                .ifEmpty { listOf(0) }

        private fun parseRelease(body: String): AppRelease? = runCatchingLog(TAG) {
            val obj = json.parseToJsonElement(body).jsonObject
            val tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (tagName.isEmpty()) return@runCatchingLog null
            val versionName = tagName.removePrefix("v").removePrefix("V")
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (htmlUrl.isEmpty()) return@runCatchingLog null
            AppRelease(
                tagName = tagName,
                versionName = versionName,
                htmlUrl = htmlUrl,
                name = obj["name"]?.jsonPrimitive?.contentOrNull,
                body = obj["body"]?.jsonPrimitive?.contentOrNull
            )
        }
    }
}

/** GitHub 正式版发布信息。 */
data class AppRelease(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val name: String? = null,
    val body: String? = null
)

/** 检测更新结果。 */
sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val release: AppRelease,
        val currentVersion: String
    ) : UpdateCheckResult

    data class UpToDate(val currentVersion: String) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}
