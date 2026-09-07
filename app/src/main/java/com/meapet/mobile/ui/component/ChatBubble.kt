package com.meapet.mobile.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole

/** 气泡宽度上限（dp）。表格类内容需要横向空间，固定 280dp 在大屏上是浪费。 */
private const val MAX_BUBBLE_WIDTH_DP = 460

/** 气泡最多占屏幕宽度的比例。 */
private const val MAX_WIDTH_FRACTION = 0.82f

/** 气泡左右内边距，正文可用宽度需扣掉两侧。 */
private val BUBBLE_HORIZONTAL_PADDING = 16.dp

/**
 * 气泡最大宽度 = min(屏幕宽度 × [MAX_WIDTH_FRACTION], [MAX_BUBBLE_WIDTH_DP])。
 *
 * 原先两处都写死 280dp，与屏幕无关——平板和大屏手机同样只有 280dp。而 Markwon 的
 * 表格按可用宽度均分列宽、没有横向滚动，列一多每列就只剩几十 dp、单元格逐字换行。
 * 取值思路与悬浮窗气泡（OverlayBubbleWindow 的 MAX_BUBBLE_WIDTH_DP / MAX_WIDTH_FRACTION）
 * 保持一致。
 */
@Composable
private fun bubbleMaxWidth(): Dp =
    minOf(MAX_BUBBLE_WIDTH_DP.dp, LocalConfiguration.current.screenWidthDp.dp * MAX_WIDTH_FRACTION)

/**
 * 聊天气泡组件。
 *
 * 根据角色自动选择样式：
 * - 用户消息：靠右 → 蓝色气泡（深色模式变体）
 * - 助手消息：靠左 → 灰色气泡
 * - 系统消息：居中 → 小型提示条
 *
 * @param message 消息
 * @param modifier Modifier
 * @param alpha 气泡整体透明度（0.2~1.0，1.0 不透明），主页聊天列表可调
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    alpha: Float = 1f
) {
    when (message.role) {
        ChatRole.user -> UserBubble(message, modifier, alpha)
        ChatRole.assistant -> AssistantBubble(message, modifier, alpha)
        ChatRole.system -> SystemBanner(message, modifier, alpha)
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage,
    modifier: Modifier,
    alpha: Float
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth())
                .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // 长按弹出系统文字选择菜单（复制 / 全选）。SelectionContainer 只接管
            // 长按选择，普通触摸仍在根布局透传给背后 Live2D，不影响立绘点击。
            SelectionContainer {
                Text(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = alpha),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    modifier: Modifier,
    alpha: Float
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // 助手头像（小圆点装饰）
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "M",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth())
                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    // 左右内边距交给 MarkdownText 施加：横向滚动的表格要能一路滑到气泡
                    // 边缘，若内边距加在这里，表格会在内边距那一段就被裁掉、看起来像被
                    // 气泡边缘遮住一截
                    .padding(vertical = 10.dp)
            ) {
                // 助手消息走 Markdown 渲染（代码块/公式/表格/链接），流式时自动补全未闭合围栏。
                // TextView 已开启原生文字选择：长按弹出复制/全选菜单，与链接点击并存。
                MarkdownText(
                    markdown = message.content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    alpha = alpha,
                    isStreaming = message.isStreaming,
                    // 扣掉左右内边距后的正文宽度，供宽表格判断是否需要横向滚动
                    availableWidth = bubbleMaxWidth() - BUBBLE_HORIZONTAL_PADDING * 2,
                    horizontalPadding = BUBBLE_HORIZONTAL_PADDING
                )
            }

            // 流式输出指示
            if (message.isStreaming) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "正在输入...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * alpha),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SystemBanner(
    message: ChatMessage,
    modifier: Modifier,
    alpha: Float
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f * alpha))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = alpha)
            )
        }
    }
}
