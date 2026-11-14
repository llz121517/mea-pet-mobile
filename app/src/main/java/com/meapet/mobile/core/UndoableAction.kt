package com.meapet.mobile.core

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager

/** 撤销窗口默认时长（毫秒）：5 秒。无障碍开启时按系统建议延长，见 [undoWindowMs]。 */
const val UNDO_WINDOW_MS = 5_000L

/**
 * 破坏性操作的可撤销快照——同时充当 UI 呈现契约。
 *
 * ## 与「二次确认」的区别
 * 操作**立即执行**，执行前的状态以快照形式放进 UiState：UI 据此显示
 * 「提示语 + 撤销键 + 倒计时进度条」。窗口内点撤销用快照回滚；窗口到期由
 * ViewModel 丢弃快照（必要时执行真正的清理，如删除已移出配置的壁纸文件）。
 *
 * 放在 core 而非 ui 包：`chat` / `viewmodel` 两层的状态类都要实现它，
 * 若定义在 ui 会让下层反向依赖 UI。
 */
interface UndoableAction {
    /** 唯一标识：同一操作连续触发时用于让 Snackbar effect 重新弹条。 */
    val id: Long

    /** 执行完成后的提示语，如「已清除 12 条对话」。 */
    val message: String

    /** 动作键文案。 */
    val undoLabel: String

    /**
     * 撤销截止时刻（[android.os.SystemClock.elapsedRealtime] 基准）。
     *
     * 用单调时钟而非 `currentTimeMillis`：用户行进中改系统时间不应延长或立即关闭窗口。
     */
    val deadlineMs: Long
}

/**
 * 本次撤销窗口时长（毫秒）。
 *
 * 默认 [UNDO_WINDOW_MS]；开启无障碍后用户操作更慢，按系统建议值
 * [AccessibilityManager.getRecommendedTimeoutMillis] 延长
 * （撤销条含文字 + 图标 + 动作键，三个内容标志都要带上）。
 *
 * 之所以在这里自己换算：撤销条用 `SnackbarDuration.Indefinite` 显示（时长必须由
 * ViewModel 的到期定时器掌控，才能让进度条与真正提交对齐），Snackbar 自带的
 * a11y 时长换算因此不生效，得补上。
 */
fun undoWindowMs(context: Context): Long {
    val base = UNDO_WINDOW_MS
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return base
    return try {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return base
        val flags = AccessibilityManager.FLAG_CONTENT_ICONS or
            AccessibilityManager.FLAG_CONTENT_TEXT or
            AccessibilityManager.FLAG_CONTENT_CONTROLS
        manager.getRecommendedTimeoutMillis(base.toInt(), flags).toLong()
    } catch (_: Exception) {
        // 个别 OEM 实现会抛异常：退化为默认窗口，不值得让撤销功能整体失效
        base
    }
}
