package com.meapet.mobile.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.meapet.mobile.R
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.tts.TtsManager

/**
 * 顶部右上角菜单按钮组件。
 *
 * 主界面语音开关开启时，菜单按钮左侧显示喇叭快捷钮：
 * - TTS 播放中图标变为蓝色；
 * - 点击切换「本次会话」的语音（内存标志，重启恢复），静音时立即停止当前朗读。
 *
 * 「清除对话」的二次确认由上层的 Snackbar 负责（见 [com.meapet.mobile.ui.component.ConfirmSnackbarEffect]），
 * 本组件只把点击意图透传出去，不自己弹窗。
 */
@Composable
fun OverlayMenu(
    onSettings: () -> Unit = {},
    onClearConversation: () -> Unit = {},
    onShowMemories: () -> Unit = {},
    onToggleOverlay: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableStateOf(0) }
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(menuExpanded) {
        if (menuExpanded) {
            showPopup = true
            animProgress.animateTo(1f, animationSpec = tween(200))
        } else if (showPopup) {
            animProgress.animateTo(0f, animationSpec = tween(200))
            showPopup = false
        }
    }

    // TTS 状态（经 Application 容器；主界面语音开关 + 播放中 + 会话静音）
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember(context) { (context.applicationContext as? MeaPetApplication)?.container }
    val mainVoiceOn = if (container != null) {
        container.settingsManager.ttsMainEnabledFlow.collectAsState(initial = false).value
    } else false

    Row(
        modifier = modifier
            .padding(top = 48.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 喇叭快捷钮：仅主界面语音开关开启时显示
        if (mainVoiceOn && container != null) {
            SpeakerButton(container.ttsManager)
        }

        Box(
            modifier = Modifier
                .onGloballyPositioned { anchorHeight = it.size.height }
                .background(
                    color = Color.Black.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu_more),
                    contentDescription = "更多选项",
                    tint = Color.White
                )
            }

            if (showPopup) {
                Popup(
                    onDismissRequest = { menuExpanded = false },
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(x = 0, y = anchorHeight + 8),
                    properties = PopupProperties(focusable = true)
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = 160.dp)
                            .graphicsLayer {
                                alpha = animProgress.value
                                scaleX = 0.85f + 0.15f * animProgress.value
                                scaleY = 0.85f + 0.15f * animProgress.value
                                transformOrigin = TransformOrigin(1f, 0f) // 右上角锚点缩放
                            },
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 2.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column {
                            MenuEntry("设置", R.drawable.ic_menu_settings) { menuExpanded = false; onSettings() }
                            HorizontalDivider()
                            MenuEntry("清除对话", R.drawable.ic_menu_clear) { menuExpanded = false; onClearConversation() }
                            HorizontalDivider()
                            MenuEntry("查看记忆", R.drawable.ic_menu_memory) { menuExpanded = false; onShowMemories() }
                            HorizontalDivider()
                            MenuEntry("悬浮窗", R.drawable.ic_menu_overlay) { menuExpanded = false; onToggleOverlay() }
                        }
                    }
                }
            }
        }
    }
}

/** 菜单项：文本 + 矢量图标（MDI，见 tools/icons.txt）。 */
@Composable
private fun MenuEntry(label: String, iconRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.padding(0.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/** 喇叭快捷钮：播放中变蓝；点击切换本次会话语音（静音立即停止）。 */
@Composable
private fun SpeakerButton(ttsManager: TtsManager) {
    val isPlaying by ttsManager.isPlaying.collectAsState()
    val muted by ttsManager.sessionMuted.collectAsState()

    // 颜色优先级：播放中=主题蓝 > 静音=灰 > 待播=白
    val tint = when {
        isPlaying -> MaterialTheme.colorScheme.primary
        muted -> Color.White.copy(alpha = 0.4f)
        else -> Color.White
    }
    val icon = if (muted && !isPlaying) R.drawable.ic_tts_volume_off
               else R.drawable.ic_tts_volume_on

    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .background(
                color = Color.Black.copy(alpha = 0.25f),
                shape = RoundedCornerShape(20.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = { ttsManager.toggleSessionMute() }) {
            Icon(
                painter = painterResource(icon),
                contentDescription = if (muted) "开启本次语音" else "关闭本次语音",
                tint = tint
            )
        }
    }
}
