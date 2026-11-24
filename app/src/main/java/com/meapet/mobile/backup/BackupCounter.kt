package com.meapet.mobile.backup

/**
 * 一个类别的条数对比：[inPackage] 是备份包里的条数，[local] 是本机现有条数。
 *
 * 用于导入清单里每个类别的副标题（「本机 → 包内」），让用户在覆盖前看清规模差异。
 */
data class ImportCount(val inPackage: Int, val local: Int)

/**
 * 导入前的条数对比。
 *
 * 只对「按条数计」的类别有意义：
 * - `chat`：包内消息条数 vs 本机会话消息条数；
 * - `memory`：包内记忆条数 vs 本机记忆条数。
 *
 * 其余类别（设置 / 压缩状态 / 壁纸）不是条数概念，不出现在结果里。
 */
object BackupCounter {

    /** 参与条数对比的类别（顺序即展示顺序）。 */
    val countedCategories: List<BackupCategory> = listOf(
        BackupCategory.CHAT,
        BackupCategory.MEMORY,
    )

    /**
     * 计算 [selected] 中**包里实际存在**、且属于 [countedCategories] 的类别条数对比。
     *
     * 读取包内容与读取本机数据都在调用方线程执行（ViewModel 放在 IO 线程），
     * 这里不做调度。单个类别解析失败时忽略该类别（不阻断导入流程——
     * 真正的结构校验与拒绝在 [BackupImporter] 里做）。
     *
     * @param reader 已通过 [BackupInspector.open] 校验的读取器
     * @param target 本机落地目标（只读，用来取本机条数）
     * @param selected 用户勾选的类别（默认用包内全部类别）
     */
    suspend fun compare(
        reader: BackupContentReader,
        target: BackupTarget,
        selected: Set<BackupCategory> = reader.contents.categories,
    ): Map<BackupCategory, ImportCount> {
        val present = reader.contents.categories
        val result = LinkedHashMap<BackupCategory, ImportCount>()
        countedCategories.forEach { category ->
            if (category !in present || category !in selected) return@forEach
            val count = runCatching { countOf(category, reader, target) }.getOrNull()
                ?: return@forEach
            result[category] = count
        }
        return result
    }

    private suspend fun countOf(
        category: BackupCategory,
        reader: BackupContentReader,
        target: BackupTarget,
    ): ImportCount = when (category) {
        BackupCategory.CHAT -> ImportCount(
            inPackage = BackupCodec.decodeChat(reader.extractText(category)).size,
            local = target.conversationRepository.count(),
        )

        BackupCategory.MEMORY -> ImportCount(
            inPackage = BackupCodec.decodeMemory(reader.extractText(category)).items.size,
            local = target.memoryStore.getAll().size,
        )

        // 其余类别不按条数计（设置项 / 摘要 / 壁纸各一张），由调用方过滤。
        else -> throw IllegalArgumentException("类别 ${category.entryName} 不按条数对比")
    }
}
