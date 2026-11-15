package com.meapet.mobile.tts.model

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.RandomAccessFile

/**
 * [TtsModelManager] 的纯 JVM 单测：无 Robolectric，`Context` / `ContentResolver` 用 mockito 打桩，
 * `filesDir` 指向 [TemporaryFolder]。注意不要真的生成 74MB 的 model.onnx。
 */
class TtsModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val managers = mutableListOf<TtsModelManager>()

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.close() } }
    }

    private fun managerWithFilesDir(): TtsModelManager {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tmp.root)
        return TtsModelManager(context).also { managers += it }
    }

    private fun modelDir(): File = File(tmp.root, "tts_model")

    /** 用 setLength 造出指定长度的文件，避免真的写入大文件内容。 */
    private fun writeSized(file: File, size: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    private fun writeAllExceptModelOnnxWithExactSizes() {
        val dir = modelDir()
        dir.mkdirs()
        TtsModelManager.REQUIRED_MODEL_FILES
            .filter { it != "model.onnx" }
            .forEach { rel -> writeSized(File(dir, rel), TtsModelManager.EXPECTED_SIZES.getValue(rel)) }
    }

    // ── 回归：openInputStream 返回 null 时状态不能卡在 Importing ──

    @Test
    fun `zip 流为空时返回失败且状态为 Error 而非 Importing`() = runBlocking {
        val resolver = mock<ContentResolver>()
        val uri = mock<Uri>()
        whenever(resolver.openInputStream(uri)).thenReturn(null)
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tmp.root)
        whenever(context.contentResolver).thenReturn(resolver)
        val manager = TtsModelManager(context).also { managers += it }

        val result = manager.importFromZip(uri)

        assertTrue("应当返回 failure", result.isFailure)
        assertTrue(
            "状态应为 Error，实际为 ${manager.state.value}",
            manager.state.value is TtsModelState.Error,
        )
    }

    // ── isModelReady() 严格校验 ──

    @Test
    fun `isModelReady - 缺文件返回 false`() {
        modelDir().mkdirs()
        val manager = managerWithFilesDir()

        assertFalse(manager.isModelReady())
    }

    @Test
    fun `isModelReady - 单个文件大小不符返回 false`() {
        writeAllExceptModelOnnxWithExactSizes()
        writeSized(File(modelDir(), "model.onnx"), 1024L)   // 截断，不创建 74MB 真实文件
        val manager = managerWithFilesDir()

        assertFalse(manager.isModelReady())
    }

    @Test
    fun `isModelReady - model_onnx 是目录时返回 false`() {
        writeAllExceptModelOnnxWithExactSizes()
        assertTrue(File(modelDir(), "model.onnx").mkdirs())
        val manager = managerWithFilesDir()

        assertFalse(manager.isModelReady())
    }
}
