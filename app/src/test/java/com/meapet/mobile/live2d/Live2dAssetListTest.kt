package com.meapet.mobile.live2d

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * assets 目录枚举的单测。
 *
 * 背景（真机 2026-11-15）：`AssetManager.list()` 返回的子项**是否带结尾 `/` 因 ROM 而异**。
 * 原实现用「name 以 `/` 结尾」判断目录，在 MIUI 上把目录当文件去 `open()` →
 * FileNotFoundException，子目录里的贴图/动作全部预热不到，GL 线程加载模型时只能现场
 * 读 assets（实测多花约 2s）。
 */
class Live2dAssetListTest {

    /**
     * 用 Map 模拟 assets 树的 listChildren：未登记的路径（=文件）返回空。
     * key 结尾斜杠不影响查询（真实 `AssetManager.list()` 带不带 `/` 都能列出子项）。
     */
    private fun assetTree(entries: Map<String, List<String>>): (String) -> List<String> {
        val normalized = entries.mapKeys { it.key.trimEnd('/') }
        return { path -> normalized[path.trimEnd('/')] ?: emptyList() }
    }

    private val expected = listOf(
        "model/mea.model3.json",
        "model/motions/idle.motion3.json",
        "model/textures/tex_00.png"
    )

    @Test
    fun `collects files under directories whose names end with slash`() {
        val list = assetTree(
            mapOf(
                "model" to listOf("mea.model3.json", "motions/", "textures/"),
                "model/textures/" to listOf("tex_00.png"),
                "model/motions/" to listOf("idle.motion3.json")
            )
        )

        assertEquals(expected, collectAssetFiles("model", list).sorted())
    }

    @Test
    fun `collects files under directories whose names have no trailing slash`() {
        // MIUI 等 ROM：list() 返回 "textures" 而不是 "textures/"
        val list = assetTree(
            mapOf(
                "model" to listOf("mea.model3.json", "motions", "textures"),
                "model/textures" to listOf("tex_00.png"),
                "model/motions" to listOf("idle.motion3.json")
            )
        )

        assertEquals(expected, collectAssetFiles("model", list).sorted())
    }

    @Test
    fun `does not recurse into files that merely sit next to directories`() {
        val list = assetTree(
            mapOf(
                "model" to listOf("a.json", "textures"),
                "model/textures" to listOf("t.png")
            )
        )

        assertEquals(
            listOf("model/a.json", "model/textures/t.png"),
            collectAssetFiles("model", list).sorted()
        )
    }
}
