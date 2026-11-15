package com.meapet.mobile.live2d

/**
 * 递归收集 assets 目录下的所有文件相对路径（目录本身不会出现在结果里）。
 *
 * 「列子项」做成参数注入而不是直接吃 [android.content.res.AssetManager]：不同 ROM 的
 * `list()` 行为不一致（目录名带不带结尾 `/`），注入后可以在 JVM 单测里把这些差异都覆盖掉，
 * 见 `Live2dAssetListTest`。
 *
 * 目录判定**不能**看名字是否以 `/` 结尾（MIUI 等 ROM 不返回尾斜杠，会把目录当文件去
 * `open()` → FileNotFoundException，子目录里的贴图/动作全部预热不到），统一用「能否列出
 * 子项」判断；拼路径前先去掉尾斜杠，避免出现 `a//b`。
 *
 * @param path 相对 assets 根的目录路径（顶层传 `""`）
 * @param listChildren 列出某路径下的直接子项；文件/不存在返回空列表
 */
internal fun collectAssetFiles(
    path: String,
    listChildren: (String) -> List<String>
): List<String> {
    val result = ArrayList<String>()
    for (name in listChildren(path)) {
        val leaf = name.trimEnd('/')
        if (leaf.isEmpty()) continue
        val child = if (path.isEmpty()) leaf else "$path/$leaf"
        if (listChildren(child).isNotEmpty()) {
            result.addAll(collectAssetFiles(child, listChildren))
        } else {
            result.add(child)
        }
    }
    return result
}
