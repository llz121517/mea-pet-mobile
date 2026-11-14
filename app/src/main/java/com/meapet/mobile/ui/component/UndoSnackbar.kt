package com.meapet.mobile.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meapet.mobile.core.UndoableAction

/**
 * 「先执行、可撤销」的 Snackbar。
 *
 * 与「确认后再执行」相反：用户按下即已生效，本条只提供一次反悔机会——
 * 提示语 + 撤销键 + 底部倒计时进度条，进度走完即窗口关闭、快照作废。
 *
 * 生命周期完全由 ViewModel 掌控（见 [UndoableAction.deadlineMs]）：
 * - 窗口到期或撤销：ViewModel 把 pending 置空 → 本 effect 被取消 → Snackbar 自动消失；
 * - 旋转/重建后 pending 仍在：effect 重弹，进度条从**剩余**时间接着走，不会重来一遍。
 *
 * @param pending 刚执行、仍可撤销的操作（null = 没有可撤销项，不弹）
 * @param hostState 宿主页面的 SnackbarHostState
 * @param onUndo 用户点了撤销键——回滚由 ViewModel 用快照完成
 */
@Composable
fun <T : UndoableAction> UndoSnackbarEffect(
    pending: T?,
    hostState: SnackbarHostState,
    onUndo: (T) -> Unit,
) {
    LaunchedEffect(pending) {
        val action = pending ?: return@LaunchedEffect
        // 剩余时长由截止时刻反推（不是固定 5 秒）：重建或切页回来时进度条接着走
        val remainingMs = action.deadlineMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) return@LaunchedEffect

        // 抢占当前提示：撤销有时限，不能排在「发现新版本」这类长提示后面等
        hostState.currentSnackbarData?.dismiss()
        val result = hostState.showSnackbar(
            UndoSnackbarVisuals(
                message = action.message,
                actionLabel = action.undoLabel,
                remainingMs = remainingMs,
            )
        )
        if (result == SnackbarResult.ActionPerformed) onUndo(action)
        // 另一分支是 Dismissed：只在 effect 被取消（窗口到期 / 撤销后置空）时出现，
        // 收尾工作已由 ViewModel 完成，这里无需处理
    }
}

/**
 * 自定义 visuals：多带一个剩余时长，供宿主画倒计时条。
 *
 * `withDismissAction = false`：撤销是唯一主动作，再放一个 ✕ 会让用户以为
 * 「关掉 = 保留」，语义容易混淆；不操作即到期提交，行为一致。
 */
private class UndoSnackbarVisuals(
    override val message: String,
    override val actionLabel: String,
    val remainingMs: Long,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false

    // 关掉 Snackbar 自带计时：时长的唯一裁判是 ViewModel 的撤销窗口
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
}

/**
 * 统一外观的 Snackbar 宿主。
 *
 * - 普通提示：用 M3 默认 Snackbar，但动作色统一跟随主题 primary
 *   （M3 默认 inversePrimary 在多数配色预设下偏蓝）；
 * - 可撤销操作：换成带倒计时进度条的自定义条（见 [UndoSnackbar]）。
 *
 * 宿主自己负责左右留边、底部余量与软键盘避让，调用方不必各自调：
 * - 本应用是沉浸式（隐藏系统栏），Scaffold 的 snackbar 槽位会紧贴物理底边，
 *   手势区会盖住撤销键；
 * - 同样的槽位也不会随内容被 IME 顶起（只有内容区会缩），
 *   在「键盘弹着时触发撤销条」的场景（如设置页改完 Prompt 直接点恢复默认）
 *   会被键盘整个盖住，所以必须 `imePadding()`——窗口本身若已被系统 resize，
 *   IME inset 会被系统消费为 0，这里自然退化为无位移。
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val actionColor = MaterialTheme.colorScheme.primary
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) { data ->
        val visuals = data.visuals
        if (visuals is UndoSnackbarVisuals) {
            UndoSnackbar(data = data, visuals = visuals, actionColor = actionColor)
        } else {
            Snackbar(
                snackbarData = data,
                actionColor = actionColor,
                actionContentColor = actionColor,
            )
        }
    }
}

/**
 * 可撤销提示条：提示语 + 撤销键 + 贴底边的剩余时间条。
 *
 * 时间条与 ViewModel 的截止时刻同源，走到底即窗口关闭；它同时是可视化的
 * 「还能反悔多久」提示，比单纯的文案更直观。
 */
@Composable
private fun UndoSnackbar(
    data: SnackbarData,
    visuals: UndoSnackbarVisuals,
    actionColor: Color,
) {
    // 每个 visuals 实例一份动画：重建时按新的剩余时长重新起算
    val remaining = remember(visuals) { Animatable(1f) }
    LaunchedEffect(visuals) {
        remaining.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = visuals.remainingMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
                easing = LinearEasing,
            ),
        )
    }

    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Surface(
        // 读屏在提示出现时播报一次，与 M3 Snackbar 的行为对齐
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = SnackbarDefaults.shape,
        color = SnackbarDefaults.color,
        contentColor = SnackbarDefaults.contentColor,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = visuals.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                ) {
                    Text(visuals.actionLabel)
                }
            }
            // 剩余时间条：宽度即剩余比例，短到消失就是不能再撤销的时刻
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(remaining.value.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(actionColor)
                )
            }
        }
    }
}
