package com.meapet.mobile.ui.screen.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meapet.mobile.ui.theme.THEME_PRESETS
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════
//  外观：背景壁纸、气泡透明度、主题模式与配色预设
// ═══════════════════════════════════════════════════

/**
 * 背景壁纸：当前预览 + 从相册选图 + 恢复默认 + 模糊强度。
 *
 * 「恢复默认」立即切回纯色背景，随后由设置页的撤销条给一次反悔机会
 * （撤销窗口内壁纸文件仍保留，见 SettingsViewModel.clearWallpaper）。
 */
@Composable
internal fun BackgroundWallpaperSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    SectionTitle("背景壁纸")
    Text(
        text = "从相册选一张图片作为主界面聊天背景，实时生效；悬浮窗保持透明",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    // 相册选图（PickVisualMedia，免权限；activity-compose 已内置）
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.importWallpaper(it) }
    }

    // ── 模糊强度（本地拖动状态）──
    // 仅在有壁纸时启用。用本地 remember 拖动顺滑，松手才落盘（与语速/透明度一致）。
    // 先于预览声明，让预览在拖动时实时跟随。
    val blur = state.wallpaperBlur.toFloat().coerceIn(0f, 1f)
    var localBlur by remember { mutableStateOf(blur) }
    LaunchedEffect(state.wallpaperBlur) {
        if (localBlur != state.wallpaperBlur.toFloat()) {
            localBlur = state.wallpaperBlur.toFloat()
        }
    }

    // 源缩略图（未模糊）：路径不变就复用，避免随滑杆拖动反复解码原图
    var sourceThumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state.wallpaperPath) {
        sourceThumb = if (state.wallpaperPath.isBlank()) null
        else withContext(Dispatchers.IO) { decodeThumbnail(state.wallpaperPath, 480) }
    }

    // 实时预览：模糊强度（localBlur）变化即在 IO 线程重算；0 = 直接显示源图。
    // σ 与 GL 渲染同源：blur→σ = 12·√blur（见 Live2dDefine.blurToSigma），再按
    // 「缩略图宽 / 屏幕宽」等比换算，保证预览模糊观感与主界面一致。
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val preview by produceState<Bitmap?>(initialValue = sourceThumb, sourceThumb, localBlur) {
        val src = sourceThumb
        value = if (src == null || localBlur <= 0.001f) src
        else withContext(Dispatchers.IO) {
            val sigma = com.meapet.mobile.live2d.Live2dDefine.blurToSigma(localBlur) *
                src.width / screenWidthPx
            gaussianBlurBitmap(src, sigma)
        }
    }

    // 预览盒纵横比（宽 / 高）：竖图源也按盒子比例做 cover 裁剪，不溢出盒高、不叠到下方按钮。
    // 竖图放大到宽度填满盒宽后高度远超盒高，需按比例约束（aspectRatio 锁形状），
    // 再让 Image 在盒内居中铺满并裁掉超出的上/下部分。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ALPHA_CARD_BG)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = preview
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "当前壁纸",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT_RATIO)
                    .align(Alignment.Center),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "当前为默认纯色背景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALPHA_MUTED_TEXT)
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.weight(1f)
        ) {
            Text("从相册选择")
        }
        OutlinedButton(
            // 立即切纯色，撤销条由设置页统一显示
            onClick = { viewModel.clearWallpaper() },
            enabled = state.wallpaperPath.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text("恢复默认")
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "背景模糊: ${kotlin.math.round(localBlur * 100).toInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (state.wallpaperPath.isNotBlank()) 1f else 0.5f
        )
    )
    Slider(
        value = localBlur,
        onValueChange = { localBlur = it },
        onValueChangeFinished = {
            viewModel.updateWallpaperBlur(localBlur.toDouble())
        },
        valueRange = WALLPAPER_BLUR_RANGE,
        steps = WALLPAPER_BLUR_STEPS,
        enabled = state.wallpaperPath.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )
}

/** 按 maxDim 限制采样解码缩略图。 */
private fun decodeThumbnail(path: String, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}

/**
 * 可分离高斯模糊（CPU，两遍同轴，约 3σ 截断）。
 *
 * 仅在 IO 线程调用（随滑杆拖动时也会触发，保持 480px 缩略图尺度、单遍 O(n) 成本）。
 * 内核 tap 数随 σ 自适应（与 GL 33-tap / σ≤16px 的口径一致）；σ≤0.6 时截断半径不足
 * 1 像素，直接返回原图避免画质损失与多余开销。
 */
private fun gaussianBlurBitmap(src: Bitmap, sigma: Float): Bitmap {
    val w = src.width
    val h = src.height
    if (sigma <= 0.6f) return src

    val sigmaC = sigma.coerceIn(0.6f, 16f)
    val radius = (sigmaC * 3f).toInt().coerceIn(1, 33)
    val half = radius.coerceAtMost(33)

    // 预计算 (2*half+1)-tap 高斯权重（对称），并归一化
    val weights = FloatArray(2 * half + 1)
    var sum = 0f
    val twoSigma2 = 2f * sigmaC * sigmaC
    for (i in -half..half) {
        val wgt = kotlin.math.exp(-(i * i) / twoSigma2)
        weights[i + half] = wgt
        sum += wgt
    }
    for (i in weights.indices) weights[i] /= sum

    val srcPx = IntArray(w * h)
    src.getPixels(srcPx, 0, w, 0, 0, w, h)

    // 第 1 遍：水平模糊
    val tmp = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (i in -half..half) {
                val px = srcPx[row + (x + i).coerceIn(0, w - 1)]
                val wt = weights[i + half]
                a += ((px ushr 24) and 0xFF) * wt
                r += ((px ushr 16) and 0xFF) * wt
                g += ((px ushr 8) and 0xFF) * wt
                b += (px and 0xFF) * wt
            }
            tmp[row + x] = ((a.toInt() and 0xFF) shl 24) or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }
    }

    // 第 2 遍：垂直模糊，直接写入结果
    val outPx = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (i in -half..half) {
                val px = tmp[(y + i).coerceIn(0, h - 1) * w + x]
                val wt = weights[i + half]
                a += ((px ushr 24) and 0xFF) * wt
                r += ((px ushr 16) and 0xFF) * wt
                g += ((px ushr 8) and 0xFF) * wt
                b += (px and 0xFF) * wt
            }
            outPx[y * w + x] = ((a.toInt() and 0xFF) shl 24) or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }
    }

    return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
}

/** 外观：背景壁纸 + 聊天气泡透明度 + 主题（模式 / 动态颜色 / 颜色预设）。 */
@Composable
internal fun AppearanceSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    darkTheme: Boolean
) {
    // ── 背景壁纸 ──
    BackgroundWallpaperSection(state, viewModel, darkTheme)

    Spacer(Modifier.height(12.dp))

    // ── 聊天气泡 ──
    // 透明度滑杆（0.2~1.0）。用本地 remember 拖动顺滑，松手才落盘（与语速一致）。
    val alpha = state.chatBubbleAlpha.toFloat().coerceIn(0.2f, 1.0f)
    var localAlpha by remember { mutableStateOf(alpha) }
    LaunchedEffect(state.chatBubbleAlpha) {
        if (localAlpha != state.chatBubbleAlpha.toFloat()) {
            localAlpha = state.chatBubbleAlpha.toFloat()
        }
    }

    Text(
        text = "聊天气泡透明度: ${(localAlpha * 100).toInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = localAlpha,
        onValueChange = { localAlpha = it },
        onValueChangeFinished = {
            viewModel.updateChatBubbleAlpha(localAlpha.toDouble())
        },
        valueRange = CHAT_BUBBLE_ALPHA_RANGE,
        steps = CHAT_BUBBLE_ALPHA_STEPS,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(inactiveTrackColor = sliderTrackColor(darkTheme))
    )

    Spacer(Modifier.height(12.dp))
    SectionTitle("主题")

    // ── 主题模式 ──
    ThemeModeSelector(
        current = state.themeMode,
        onSelect = { viewModel.updateThemeMode(it) }
    )

    Spacer(Modifier.height(12.dp))

    // ── 动态颜色开关 ──
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsSwitchRow(
        label = "使用系统动态颜色",
        description = if (dynamicColorSupported) "关闭后可选择预设主题色" else "当前系统不支持动态颜色",
        checked = state.enableDynamicColor && dynamicColorSupported,
        darkTheme = darkTheme,
        onCheckedChange = { if (dynamicColorSupported) viewModel.updateEnableDynamicColor(it) },
        enabled = dynamicColorSupported
    )

    // ── 颜色预设选择区（关闭动态颜色时展开） ──
    AnimatedVisibility(
        visible = !(state.enableDynamicColor && dynamicColorSupported),
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        ColorPresetSelector(
            currentPreset = state.colorPreset,
            onSelect = { viewModel.updateColorPreset(it) }
        )
    }
}

@Composable
private fun ThemeModeSelector(
    current: String,
    onSelect: (String) -> Unit
) {
    val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    var expanded by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var boxWidthPx by remember { mutableStateOf(0) }
    var boxHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            animProgress.animateTo(1f, animationSpec = tween(200))
        } else if (showPopup) {
            animProgress.animateTo(0f, animationSpec = tween(200))
            showPopup = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                boxWidthPx = it.size.width
                boxHeightPx = it.size.height
            }
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == current }?.second ?: "跟随系统",
            onValueChange = {},
            readOnly = true,
            label = { Text("主题模式") },
            trailingIcon = {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                                  else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        // 透明点击层——避免与 OutlinedTextField 的内部触摸处理冲突
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = !expanded }
        )

        if (showPopup) {
            val popupWidth = with(density) { boxWidthPx.toDp().coerceAtLeast(160.dp) }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x = 0, y = boxHeightPx + 4),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    modifier = Modifier
                        .width(popupWidth)
                        .graphicsLayer {
                            alpha = animProgress.value
                            scaleX = 0.95f + 0.05f * animProgress.value
                            scaleY = 0.95f + 0.05f * animProgress.value
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Column {
                        options.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onSelect(value); expanded = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 颜色预设选择区——色块网格。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresetSelector(
    currentPreset: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "主题色预设",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            THEME_PRESETS.forEach { preset ->
                val isSelected = preset.id == currentPreset

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(preset.id) }
                        .width(56.dp)
                ) {
                    // 色块圆
                    val borderMod = if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(preset.seed)
                            .then(borderMod)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
