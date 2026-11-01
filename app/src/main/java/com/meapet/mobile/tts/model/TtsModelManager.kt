package com.meapet.mobile.tts.model

import android.content.Context
import android.net.Uri
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * TTS 模型（sherpa-onnx 音素化 VITS，6 个文件共约 84MB）的下载 / 校验 / 导入管理。
 *
 * ## 分发模型（不打进 APK，按需下载 / 本地 zip 导入以减小体积）
 * - 6 个文件（共 ~84MB）→ `filesDir/tts_model/`，其中 3 个 fst 位于 `fsts/` 子目录
 * - 远端平铺上传 basename（GitHub Release / 静态目录不能带子路径），本地路径见 [REQUIRED_MODEL_FILES]
 *
 * ## 原生库加载
 * JNI（libsherpa-onnx-jni.so）与 libonnxruntime.so 随 sherpa AAR 打包进 APK（仅 arm64-v8a /
 * armeabi-v7a，见 app/build.gradle.kts 的 abiFilters），首次推理时由 sherpa 自动加载，
 * 无需下载、也无需显式 `System.loadLibrary`。
 *
 * ## 配置
 * 远程地址经 BuildConfig 从 local.properties 注入到 AppConfig（`ttsModelBaseUrl`）。
 * 联调期可手动把文件推到 `filesDir/tts_model/` 跳过下载。
 */
class TtsModelManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsModelManager"
        private const val MODEL_DIR = "tts_model"

        /** 必需文件（相对 modelDir）。fsts/ 子目录由 App 维护，远端上传名是平铺的 basename。 */
        val REQUIRED_MODEL_FILES = listOf(
            "model.onnx",
            "tokens.txt",
            "lexicon.txt",
            "fsts/phone.fst",
            "fsts/date.fst",
            "fsts/number.fst",
        )

        /** 各文件精确字节数（来源：.tools/tts-spike/dist/manifest.json，见 docs/tts/model-package.md）。 */
        val EXPECTED_SIZES = mapOf(
            "model.onnx" to 74_864_089L,
            "tokens.txt" to 439L,
            "lexicon.txt" to 8_915_316L,
            "fsts/phone.fst" to 88_630L,
            "fsts/date.fst" to 59_154L,
            "fsts/number.fst" to 64_482L,
        )
    }

    /** 一个待下载文件：远程地址 + 落盘相对路径 + 预期大小（0=不校验）。 */
    data class ModelFile(val url: String, val relativePath: String, val expectedSize: Long = 0L)

    private val modelDir: File by lazy { File(context.filesDir, MODEL_DIR) }

    private val _state = MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded)
    val state: StateFlow<TtsModelState> = _state.asStateFlow()

    /** 下载重入锁：防止并发下载写同一批 .part 临时文件。 */
    private val downloadLock = kotlinx.coroutines.sync.Mutex()

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = null   // 大文件不卡总时长（null = 无上限）
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    init {
        TtsLegacyMigration.migrate(modelDir)
        refreshState()
        TtsLegacyMigration.cleanLegacyNativeLibDir(modelDir)
    }

    /** 模型 6 件是否齐全且大小精确匹配（下载/导入中断的半文件不会被误判就绪）。 */
    fun isModelReady(): Boolean = REQUIRED_MODEL_FILES.all { rel ->
        val file = File(modelDir, rel)
        // 缺少预期大小即视为不可就绪（fail closed），避免清单漏项时误判
        EXPECTED_SIZES[rel]?.let { expected -> file.isFile && file.length() == expected } ?: false
    }

    /** 模型目录绝对路径（sherpa 配置构造用）。 */
    fun modelDir(): File = modelDir

    /** 模型就绪即可用（原生库随 sherpa AAR 分发，无需单独下载）。 */
    fun isReady(): Boolean = isModelReady()

    /** 模型文件绝对路径（供 ONNX 引擎加载）。 */
    fun modelFile(name: String): File = File(modelDir, name)

    /** 启动/操作后按磁盘实际状态刷新状态流。 */
    fun refreshState() {
        _state.value = if (isReady()) TtsModelState.Ready else TtsModelState.NotDownloaded
    }

    /**
     * 由 baseUrl 生成下载清单。远端是**平铺文件名**（GitHub Release / 静态目录不能带子路径），
     * 本地落盘仍按 [REQUIRED_MODEL_FILES] 的相对路径（fsts/ 子目录）。
     */
    fun buildDownloadFiles(baseUrl: String): List<ModelFile> {
        if (baseUrl.isBlank()) return emptyList()
        val base = baseUrl.trimEnd('/')
        return REQUIRED_MODEL_FILES.map { rel ->
            ModelFile(
                url = "$base/${rel.substringAfterLast('/')}",
                relativePath = rel,
                expectedSize = EXPECTED_SIZES[rel] ?: 0L,
            )
        }
    }

    /**
     * 顺序下载一组文件，逐个流式落盘并更新进度。已存在且校验通过的文件跳过（断点续传）。
     *
     * 重入保护：正在下载时再次调用直接忽略，避免两个下载协程写同一批 .part 临时文件。
     * 用协程 [kotlinx.coroutines.sync.Mutex] 而非 `ReentrantLock`：后者是线程绑定锁，
     * 而本函数内 `downloadOne` 有挂起点（网络 IO），协程恢复可能切到另一个 IO 线程，
     * 届时 `finally { unlock() }` 会在非持锁线程执行 → `IllegalMonitorStateException`。
     */
    suspend fun download(files: List<ModelFile>) = withContext(Dispatchers.IO) {
        if (!downloadLock.tryLock()) {
            Log.w(TAG, "正在下载中，忽略重复调用")
            return@withContext
        }
        try {
            if (files.isEmpty()) {
                _state.value = TtsModelState.Error("未配置模型下载地址")
                return@withContext
            }
            modelDir.mkdirs()
            val totalBytes = files.sumOf { it.expectedSize }.takeIf { it > 0 }
            var downloaded = 0L
            try {
                for (file in files) {
                    // 跳过已下载完成的（大小校验通过），重试时不全量重下
                    if (isFileComplete(file)) {
                        Log.d(TAG, "跳过已存在：${file.relativePath}")
                        downloaded += file.expectedSize
                        continue
                    }
                    _state.value = TtsModelState.Downloading(
                        progress = if (totalBytes != null) downloaded.toFloat() / totalBytes else 0f,
                        currentFile = file.relativePath
                    )
                    downloadOne(file) { readBytes ->
                        if (totalBytes != null) {
                            _state.value = TtsModelState.Downloading(
                                progress = (downloaded + readBytes).toFloat() / totalBytes,
                                currentFile = file.relativePath
                            )
                        }
                    }
                    downloaded += file.expectedSize
                }
                refreshState()
                if (_state.value != TtsModelState.Ready) {
                    _state.value = TtsModelState.Error("下载完成但文件不完整")
                } else {
                    Log.i(TAG, "模型下载完成，共 ${files.size} 个文件")
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型下载失败", e)
                _state.value = TtsModelState.Error(e.message ?: "下载失败")
            }
        } finally {
            // tryLock 成功才走到这里；unlock 需持有锁（协程 Mutex 不绑定线程）
            try { downloadLock.unlock() } catch (_: IllegalStateException) {}
        }
    }

    /** 文件存在且大小严格等于预期大小才算已完成；未配置预期大小视为未完成（fail closed）。 */
    private fun isFileComplete(file: ModelFile): Boolean {
        val target = File(modelDir, file.relativePath)
        return file.expectedSize > 0L && target.isFile && target.length() == file.expectedSize
    }

    /** 流式下载单个文件到临时文件，完成后原子改名 + 大小校验。 */
    private suspend fun downloadOne(file: ModelFile, onProgress: (Long) -> Unit) {
        val target = File(modelDir, file.relativePath)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")

        val response = client.get(file.url)
        if (response.status.value !in 200..299) {
            throw IllegalStateException("HTTP ${response.status.value}：${file.relativePath}")
        }

        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        tmp.outputStream().buffered().use { out ->
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    onProgress(read.toLong())
                }
            }
        }

        if (file.expectedSize > 0 && tmp.length() != file.expectedSize) {
            tmp.delete()
            throw IllegalStateException(
                "文件大小不符：${file.relativePath} 期望 ${file.expectedSize} 实得 ${tmp.length()}"
            )
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("落盘失败：${file.relativePath}")
    }

    /**
     * 从本地 zip 资源包手动导入（绕过 GitHub 下载，供网络受限用户使用）。
     *
     * 兼容两种打包方式：条目平铺（`model.onnx`、`fsts/phone.fst`），或整体包一层目录
     * （`tts-model-v1/model.onnx`、`tts-model-v1/fsts/phone.fst`）。定位规则见 [resolveModelEntries]。
     *
     * 导入前先整体清空模型目录（避免残留半套模型 / 旧 fsts 混入），再逐文件 `.part` 临时写盘后
     * 原子改名（与 [download] 一致，中断不留半文件）；完成后 [refreshState]，
     * 缺件/失败进 [TtsModelState.Error]。
     */
    suspend fun importFromZip(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        _state.value = TtsModelState.Importing
        try {
            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri)
                ?: run {
                    // 早退必须落 Error，否则 UI 会一直停在 Importing 转圈
                    val msg = "无法读取所选文件"
                    _state.value = TtsModelState.Error(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

            // 第一遍：列出全部条目，定位模型包（根级平铺或包一层目录）
            val entries = ZipInputStream(stream).use { zis ->
                buildList {
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory) add(entry.name)
                        zis.closeEntry()
                    }
                }
            }
            if (entries.isEmpty()) {
                val msg = "zip 为空或无法解析"
                _state.value = TtsModelState.Error(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            val mapping = resolveModelEntries(entries)
                ?: run {
                    val msg = "资源包缺少模型文件（需要 ${REQUIRED_MODEL_FILES.joinToString("、")}）"
                    _state.value = TtsModelState.Error(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
            val byEntry = mapping.entries.associate { (rel, entryName) -> entryName to rel }

            // 先整体清空再解压：避免残留半套模型 / 旧 fsts 混入
            modelDir.deleteRecursively()
            modelDir.mkdirs()

            val maxEntrySize = 256L * 1024 * 1024   // 单条目上限（zip 炸弹粗防御）
            resolver.openInputStream(uri).use { input ->
                val zis = ZipInputStream(input)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val rel = byEntry[entry.name]
                    if (rel != null) writeEntry(zis, File(modelDir, rel), maxEntrySize, buf)
                    zis.closeEntry()
                }
            }
            refreshState()
            if (_state.value == TtsModelState.Ready) {
                Log.i(TAG, "zip 导入完成：${byEntry.size} 个文件")
                Result.success("导入成功")
            } else {
                val msg = "导入失败：文件不完整或大小不符"
                _state.value = TtsModelState.Error(msg)
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "zip 导入失败", e)
            _state.value = TtsModelState.Error(e.message ?: "导入失败")
            Result.failure(e)
        }
    }

    /** 把当前 zip entry 逐块写入目标文件（`.part` 临时文件 + 原子改名）。 */
    private fun writeEntry(zis: ZipInputStream, target: File, maxSize: Long, buf: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.outputStream().buffered().use { out ->
            var total = 0L
            while (true) {
                val n = zis.read(buf, 0, buf.size)
                if (n < 0) break
                total += n
                if (total > maxSize) throw IllegalStateException("文件过大：${target.name}")
                out.write(buf, 0, n)
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("落盘失败：${target.name}")
    }

    /** 删除模型文件，释放空间；调用方负责随后关闭语音开关。 */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        modelDir.deleteRecursively()
        refreshState()
        Log.i(TAG, "已删除 TTS 模型")
    }

    fun close() = client.close()
}

/**
 * 在 zip 条目里定位模型包：优先根级平铺，其次逐个尝试嵌套目录
 * （取 model.onnx 所在目录作为基准），避免嵌套的同名 model.onnx 抢先匹配。
 * @return 本地相对路径 → zip 条目名；缺任一必需文件返回 null。
 */
internal fun resolveModelEntries(entries: List<String>): Map<String, String>? {
    val candidates = buildList {
        if ("model.onnx" in entries) add("model.onnx")
        entries.forEach { if (it != "model.onnx" && it.endsWith("/model.onnx")) add(it) }
    }
    for (modelEntry in candidates) {
        val prefix = modelEntry.removeSuffix("model.onnx")
        val mapping = TtsModelManager.REQUIRED_MODEL_FILES.associateWith { rel -> "$prefix$rel" }
        if (mapping.values.all { it in entries }) return mapping
    }
    return null
}
