package com.meapet.mobile.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 隐私政策授权弹窗。
 *
 * - 点击「同意并继续」→ 回调 [onAgree]，App 正常使用且初始化友盟统计 SDK。
 * - 点击「不同意」→ 回调 [onDisagree]，App 仍然正常使用，但不初始化友盟 SDK。
 * - 点击「查看隐私政策」→ 在弹窗内切换到完整政策文本视图。
 *
 * 摘要正文覆盖两类数据流向：一类是用户自己配置的第三方服务（AI 对话 API、联网搜索），
 * 另一类是本构建可选的友盟统计 SDK；并明确「同意／不同意」只决定统计 SDK 是否初始化，
 * 不与对话、联网搜索功能绑定。完整说明见 [PrivacyPolicyContent]。
 *
 * 使用 Dialog + Card 风格，与 AboutDialog 保持一致。
 * 不设置 dismissOnClickOutside，确保用户做出明确选择。
 */
@Composable
fun PrivacyDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
    /** 非首次启动（隐私政策更新）时，主标题下的副标题提示；首次启动为空串不显示。 */
    subtitle: String = "",
    onViewPrivacyPolicy: () -> Unit = {}
) {
    var showFullPolicy by remember { mutableStateOf(false) }
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(1f, animationSpec = tween(200))
    }

    Dialog(
        onDismissRequest = { /* 不允许点击外部取消，必须明确选择 */ },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 48.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = animProgress.value
                    scaleX = 0.85f + 0.15f * animProgress.value
                    scaleY = 0.85f + 0.15f * animProgress.value
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // ── 标题 ──
                Text(
                    text = if (showFullPolicy) "隐私政策" else "隐私政策与数据使用",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotBlank() && !showFullPolicy) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))

                // ── 正文内容 ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                        if (showFullPolicy) {
                            PrivacyPolicyContent()
                        } else {
                            Text(
                                text = "欢迎使用 MeaPet！",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "MeaPet 是本地优先的 AI 聊天应用：会话、记忆与 API Key 只保存在本机，开发者不运营服务器。" +
                                    "AI 对话的内容、记忆检索与写入结果、系统提示词、摘要请求和上下文容量探测请求，会发送到你在「服务商」中配置的 API 地址；" +
                                    "开启联网搜索后，查询词与网页地址会发送给 DeepSeek 或 AnySearch（自动模式下失败时可能切换到下一家）。" +
                                    "这些内容直接发给你选择的第三方服务，受其隐私政策约束。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "本构建还集成了友盟+ 统计 SDK，用于收集去标识化的使用数据（启动次数、使用时长等），以帮助改进产品质量。SDK 会采集设备标识符和网络信息。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = buildAnnotatedString {
                                    append("你可以查看完整的")
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    ) {
                                        append("《隐私政策》")
                                    }
                                    append("了解详情。")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .clickable { showFullPolicy = true }
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "• 「同意」与「不同意」只针对友盟统计 SDK：同意则开始采集去标识化的统计数据，不同意则完全不采集，App 其余功能不受影响\n• 继续使用 App 即表示你同意隐私政策的其余章节——AI 对话与联网搜索本身需要向网络发起请求，这部分内容会发送给你配置的第三方服务，与是否同意统计 SDK 无关\n• 你可以随时在设置中取消统计授权（取消后 App 将自动退出）\n• AI 对话与联网搜索可随时在设置中关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (showFullPolicy) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                Spacer(Modifier.height(10.dp))

                if (showFullPolicy) {
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                } else {
                    Spacer(Modifier.height(10.dp))
                }

                // ── 底部按钮 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (showFullPolicy) {
                        Box(Modifier.weight(1f)) // 占位，让返回按钮居右
                        TextButton(onClick = { showFullPolicy = false }) {
                            Text("返回")
                        }
                    } else {
                        Row {
                            TextButton(onClick = { showFullPolicy = true }) {
                                Text("查看隐私政策")
                            }
                            Spacer(Modifier.width(5.dp))
                            TextButton(onClick = onDisagree) {
                                Text("不同意")
                            }
                        }
                        TextButton(onClick = onAgree) {
                            Text("同意并继续")
                        }
                    }
                }
            }
        }
    }
}
