package com.meapet.mobile.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 状态条底色的不透明度底数。
 *
 * 它是覆盖在聊天流上的一条，比输入胶囊更需要一点遮罩感，所以不直接用设置值，
 * 而是在其之上乘 0.9：设置 1.0 ≈ 90% 不透明，调低时同步变淡。
 */
private const val FILL_ALPHA = 0.9f

/** 顶部分割线的不透明度（随设置值衰减，比底色更淡）。 */
private const val DIVIDER_ALPHA = 0.4f

/**
 * 「Mea 还在…」轻提示条。
 *
 * 固定贴在输入栏上方、随输入栏一起移动的**细长条**（不是悬浮按钮，也不遮挡消息列表）：
 * 左侧小转圈 + 文案，右侧可选动作（如「停止」）。
 *
 * 用于：
 * - 工具/插队 turn 进行中：出现第一条回复后显示，右侧「停止」（见 ChatScreen）
 * - 手动压缩上下文进行中
 *
 * 出现/消失由调用方用 `AnimatedVisibility` 包裹做渐入渐出，避免状态切换时跳变。
 * 设计稿（已归档）见 `docs/archive/plans/2026-11-18-interject-ui-mock.html` 的状态条。
 *
 * @param text 文案，如「Mea 还在整理前面的内容…」
 * @param actionLabel 右侧动作文案；null = 不显示动作
 * @param onAction 动作回调
 */
@Composable
fun MeaBusyBar(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** 在「聊天气泡透明度」设置之上再乘一个底数：设置 1.0 时约 90% 不透明。 */
    alpha: Float = 1f
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_ALPHA * alpha)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = FILL_ALPHA * alpha))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
