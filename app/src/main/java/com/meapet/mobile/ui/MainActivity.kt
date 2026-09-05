package com.meapet.mobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.core.PrivacyConsentManager
import com.meapet.mobile.core.isDarkTheme
import com.meapet.mobile.app.AppContainer
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.live2d.Live2dDelegate
import com.meapet.mobile.live2d.Live2dRenderState
import com.meapet.mobile.live2d.Live2dRenderer
import com.meapet.mobile.live2d.overlay.FloatingLive2dService
import com.meapet.mobile.ui.component.AutoUpdateOptInDialog
import com.meapet.mobile.ui.component.PRIVACY_POLICY_VERSION
import com.meapet.mobile.ui.screen.ChatScreenContent
import com.meapet.mobile.ui.theme.MeaPetTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主入口 Activity。
 *
 * ## 设计原则
 * - `launchMode="singleTask"` 防止重复实例
 * - `isTaskRoot` 防护 Android 15+ 兼容性
 * - GLSurfaceView + ComposeView 混合渲染
 * - 所有系统回调均 try-catch 保护
 */
class MainActivity : ComponentActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var container: AppContainer
    private var insetsController: WindowInsetsControllerCompat? = null

    /** 等待悬浮窗 Service 完全停止后再恢复本 Activity GL 渲染的订阅任务。 */
    private var resumeJob: Job? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002

        /** 等悬浮窗 Service 停止的上限：超时则照常恢复 GL，不允许无上限等待。 */
        private const val GL_RESUME_WAIT_TIMEOUT_MS = 2_000L
    }

    // ── 生命周期 ──────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility", "SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        // isTaskRoot 防护（Android 15+ 兼容）
        if (!isTaskRoot) {
            finish()
            return
        }
        super.onCreate(savedInstanceState)

        container = MeaPetApplication.from(applicationContext as android.app.Application)

        // 启动即按持久化主题设定 Live2D 背景与窗口背景，让首帧直接是主题色，
        // 避免"先浅后深"的闪烁（Compose 内 themeModeFlow 异步收集稍后才会接管，
        // 在此之前若只靠默认值，深色主题下会先白后黑）。
        // getThemeMode 在快照未就绪时退化为一次 runBlocking 读盘兜底（值小、量级可接受）。
        try {
            val themeMode = container.settingsManager.getThemeMode()
            val isDark = isDarkTheme(this, themeMode)
            val bg = if (isDark) intArrayOf(0x14, 0x14, 0x14) else intArrayOf(0xF7, 0xF7, 0xF7)
            Live2dDelegate.getInstance().let { d ->
                d.bgR = bg[0] / 255f; d.bgG = bg[1] / 255f; d.bgB = bg[2] / 255f; d.bgA = 1.0f
            }
            window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt() or (bg[0] shl 16) or (bg[1] shl 8) or bg[2]))
        } catch (e: Exception) {
            window.setBackgroundDrawable(ColorDrawable(0xFFF7F7F7.toInt()))
        }

        // 首帧即预置背景壁纸路径与模糊强度（从 DataStore 读），避免旋转/重建后先闪纯色再上壁纸；
        // 后续由 Compose 内 wallpaperPathFlow / wallpaperBlurFlow 响应式接管（见 setContent）
        try {
            val d = Live2dDelegate.getInstance()
            d.wallpaperPath = container.settingsManager.getWallpaperPath().ifBlank { null }
            d.wallpaperBlur = container.settingsManager.getWallpaperBlur().toFloat()
        } catch (_: Exception) {}

        insetsController = WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        hideSystemBars()

        // GLSurfaceView
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(Live2dRenderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setPreserveEGLContextOnPause(true)
        }

        // ComposeView
        val composeView = ComposeView(this).apply {
            setContent {
                val themeMode by container.settingsManager.themeModeFlow
                    .collectAsState(initial = "system")
                val enableDynamicColor by container.settingsManager.enableDynamicColorFlow
                    .collectAsState(initial = true)
                val colorPreset by container.settingsManager.colorPresetFlow
                    .collectAsState(initial = "default")

                // Live2D 背景色跟随主题（onCreate 已同步预置首帧主题色，此处响应后续切换）
                val bgColor = remember(themeMode) {
                    val isDark = isDarkTheme(this@MainActivity, themeMode)
                    if (isDark) floatArrayOf(0.08f, 0.08f, 0.08f)
                    else floatArrayOf(0.97f, 0.97f, 0.97f)
                }
                LaunchedEffect(bgColor) {
                    Live2dDelegate.getInstance().let { d ->
                        d.bgR = bgColor[0]; d.bgG = bgColor[1]; d.bgB = bgColor[2]; d.bgA = 1.0f
                    }
                }

                // 背景壁纸：设置变化实时同步到 GL（GL 线程每帧读，下帧即生效）
                val wallpaperPath by container.settingsManager.wallpaperPathFlow
                    .collectAsState(initial = "")
                val wallpaperBlur by container.settingsManager.wallpaperBlurFlow
                    .collectAsState(initial = 0.0)
                LaunchedEffect(wallpaperPath) {
                    Live2dDelegate.getInstance().wallpaperPath = wallpaperPath.ifBlank { null }
                }
                LaunchedEffect(wallpaperBlur) {
                    Live2dDelegate.getInstance().wallpaperBlur = wallpaperBlur.toFloat()
                }

                // ── 隐私/更新弹窗 ──
                val context = androidx.compose.ui.platform.LocalContext.current
                val scope = rememberCoroutineScope()
                val settingsManager = container.settingsManager
                val currentPrivacyVersion = PRIVACY_POLICY_VERSION

                var showPrivacyDialog by remember { mutableStateOf(false) }
                var showUpdateOptInDialog by remember { mutableStateOf(false) }
                var privacyIsUpdate by remember { mutableStateOf(false) }

                // 启动判定（首帧后异步执行，不阻塞渲染）：
                // - 首次启动（first_launch）：先落 first_launch=false，再弹隐私政策 + 检查更新。
                // - 非首次但隐私版本号变化：弹隐私政策，副标题提示「隐私政策更新」。
                // - 隐私弹窗仅用于友盟统计采集授权：无统计 SDK 的构建（umeng.enabled=false）
                //   无采集可言，弹窗失效——且**不记录已看版本号**，未来换回含 SDK 的构建时
                //   版本不匹配会重新弹窗（若在此记录，同版本下换回 SDK 构建不再弹、授权又默认
                //   未同意 → 统计永远不会初始化）。检查更新弹窗与统计无关，照常。
                LaunchedEffect(Unit) {
                    val firstLaunch = settingsManager.isFirstLaunch()
                    if (firstLaunch) {
                        settingsManager.markFirstLaunchDone()
                        showUpdateOptInDialog = true
                        if (BuildConfig.UMENG_ENABLED) {
                            settingsManager.setPrivacyVersionShown(currentPrivacyVersion)
                            showPrivacyDialog = true
                        }
                    } else if (BuildConfig.UMENG_ENABLED && settingsManager.getPrivacyVersionShown() != currentPrivacyVersion) {
                        privacyIsUpdate = true
                        showPrivacyDialog = true
                    }
                }

                /** 隐私弹窗关闭后的统一收尾：记录已看过版本号。 */
                fun closePrivacyDialog() {
                    showPrivacyDialog = false
                    scope.launch { settingsManager.setPrivacyVersionShown(currentPrivacyVersion) }
                }

                MeaPetTheme(themeMode = themeMode, dynamicColor = enableDynamicColor, colorPreset = colorPreset) {
                    if (showPrivacyDialog) {
                        com.meapet.mobile.ui.component.PrivacyDialog(
                            onAgree = {
                                PrivacyConsentManager.setAgreed(context, true)
                                (applicationContext as? MeaPetApplication)?.initUmengSdk()
                                closePrivacyDialog()
                            },
                            onDisagree = {
                                PrivacyConsentManager.setAgreed(context, false)
                                closePrivacyDialog()
                            },
                            subtitle = if (privacyIsUpdate) "隐私政策更新" else ""
                        )
                    }
                    // 等隐私弹窗关闭后再显示，避免首次启动两弹窗叠加
                    if (showUpdateOptInDialog && !showPrivacyDialog) {
                        AutoUpdateOptInDialog(
                            onEnable = {
                                scope.launch { settingsManager.setEnableAutoUpdateCheck(true) }
                                showUpdateOptInDialog = false
                            },
                            onDisable = {
                                scope.launch { settingsManager.setEnableAutoUpdateCheck(false) }
                                showUpdateOptInDialog = false
                            }
                        )
                    }
                    ChatScreenContent(onToggleOverlay = { toggleOverlay() })
                }
            }
        }

        // 根布局 + 触摸透传
        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                try {
                    if (event != null && ::glSurfaceView.isInitialized) {
                        // MotionEvent 会被系统回收复用，先在主线程拷贝出基本类型，
                        // 再交给 GL 线程延迟读取
                        val action = event.actionMasked
                        val x = event.x
                        val y = event.y
                        glSurfaceView.queueEvent {
                            try {
                                when (action) {
                                    MotionEvent.ACTION_DOWN -> Live2dDelegate.getInstance().onTouchBegan(x, y)
                                    MotionEvent.ACTION_UP -> Live2dDelegate.getInstance().onTouchEnd(x, y)
                                    MotionEvent.ACTION_MOVE -> Live2dDelegate.getInstance().onTouchMoved(x, y)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            addView(glSurfaceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        try {
            Live2dDelegate.getInstance().onStart(this)
            // 从桌面返回时自动关悬浮窗
            if (Live2dRenderState.overlayActive.value) {
                Live2dRenderState.setOverlayActive(false)
                FloatingLive2dService.stop(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStart error: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // 从悬浮窗返回时，Service 的 onDestroy（含其 GL 线程暂停）是异步的。
        // 若此刻立即恢复本 Activity 的 GL 线程，两条 GL 线程会并发使用共享的
        // CubismShaderAndroid 单例（Service 侧曾在其上下文里重建过 shader），
        // 导致本 Activity 用到无效 program → 黑屏/GL 报错。
        // 因此等 Service 完全停止（isRunning=false）后再恢复渲染。
        resumeJob?.cancel()
        resumeJob = null
        if (Live2dRenderState.isRunning.value) {
            resumeJob = lifecycleScope.launch {
                // 订阅 StateFlow 而非轮询；必须带超时上限：Service 若异常退出、
                // 没能把 isRunning 置回 false，无上限等待会让 GL 永不恢复（主界面永久黑屏）。
                // 超时后照常恢复——宁可冒一次 shader 竞争，也不能把界面卡死。
                val stopped = withTimeoutOrNull(GL_RESUME_WAIT_TIMEOUT_MS) {
                    Live2dRenderState.isRunning.first { !it }
                    true
                } == true
                if (!stopped) {
                    Log.w(TAG, "Overlay service still running after ${GL_RESUME_WAIT_TIMEOUT_MS}ms, resuming GL anyway")
                }
                resumeJob = null
                try {
                    glSurfaceView.onResume()
                } catch (e: Exception) {
                    Log.e(TAG, "onResume(gated) error: ${e.message}")
                }
            }
        } else {
            try {
                glSurfaceView.onResume()
            } catch (e: Exception) {
                Log.e(TAG, "onResume error: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 取消等待 Service 停止的订阅，避免 Activity 已 pause 后还去 onResume GL
        resumeJob?.cancel()
        resumeJob = null
        try {
            glSurfaceView.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "onPause error: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            Live2dDelegate.getInstance().onStop()
        } catch (e: Exception) {
            Log.e(TAG, "onStop error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (Live2dRenderState.isRunning.value) {
                // 悬浮窗 Service 的 GL 线程还在使用共享的静态单例
                // （CubismShaderAndroid / Live2dManager / CubismFramework），
                // 此处不能 dispose，置位标记交给 Service onDestroy 收尾。
                // 但必须清除单例对本 Activity 的强引用，否则悬浮窗存活期间
                // 已销毁的 Activity 及其视图树会被长期持有（内存泄漏）。
                Live2dRenderState.setPendingSharedDispose(true)
                Live2dDelegate.getInstance().onActivityDestroyed(this)
            } else {
                Live2dDelegate.getInstance().onDestroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error: ${e.message}")
        }
    }

    @Deprecated("Use registerForActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            // minSdk 26 > API 23，SDK_INT < M 恒 false，直接判断悬浮窗权限
            if (Settings.canDrawOverlays(this)) {
                requestNotificationPermissionThenStartOverlay()
            } else {
                Log.w(TAG, "Overlay permission not granted")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            // 通知权限被拒不阻塞悬浮窗：前台服务仍可运行，只是不显示常驻通知
            startOverlayAndGoBack()
        }
    }

    // ── 悬浮窗 ──────────────────────────────────────

    private fun toggleOverlay() {
        if (Live2dRenderState.overlayActive.value) {
            Live2dRenderState.setOverlayActive(false)
            FloatingLive2dService.stop(this)
        } else {
            // minSdk 26 > API 23，SDK_INT >= M 恒 true，直接判断悬浮窗权限
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    OVERLAY_PERMISSION_REQUEST
                )
                return
            }
            requestNotificationPermissionThenStartOverlay()
        }
    }

    /** API 33+ 需要运行时通知权限，前台服务的常驻通知才可见；请求后无论结果都继续启动。 */
    private fun requestNotificationPermissionThenStartOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }
        startOverlayAndGoBack()
    }

    private fun startOverlayAndGoBack() {
        // 先让本 Activity 的 GL 线程静止：GLSurfaceView.onPause 会阻塞到
        // GL 线程完成当前帧并暂停。之后 Service 的 GL 线程在 onSurfaceCreated
        // 里 deleteInstance 重建 CubismShaderAndroid 时，不会有另一条 GL 线程
        // 在并发遍历同一份 shader 列表。Activity 侧要到 onResume 才会恢复渲染，
        // 而 moveTaskToBack 之后不会发生 onResume。
        if (::glSurfaceView.isInitialized) {
            try { glSurfaceView.onPause() } catch (_: Exception) {}
        }
        Live2dRenderState.setOverlayActive(true)
        FloatingLive2dService.start(this)
        moveTaskToBack(true)
    }

    private fun hideSystemBars() {
        insetsController?.hide(
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.statusBars()
        )
    }
}
