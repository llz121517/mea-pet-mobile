package com.meapet.mobile.live2d.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.live2d.Live2dDelegate
import com.meapet.mobile.live2d.Live2dRenderState
import com.meapet.mobile.live2d.Live2dSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that renders the Live2D model in a transparent floating window.
 * - Drag to move
 * - Pinch to resize
 * - Double-tap to open menu (关闭菜单 / 关闭悬浮窗 / 唤起输入框)
 * - 输入框发消息走主界面同一 chat 包，回复以气泡显示在人物旁
 *
 * 职责被拆分为三类，本类只负责 Service 生命周期与窗口编排：
 * - 触摸/手势 → [OverlayTouchHandler]；
 * - GL 渲染 → [Live2dOverlayRenderer]；
 * - 子窗口（菜单/输入框/气泡）→ [OverlayMenuWindow] / [OverlayInputWindow] / [OverlayBubbleWindow]。
 */
class FloatingLive2dService : Service() {

    companion object {
        private const val TAG = "FloatingLive2d"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "live2d_overlay"

        /** 连击判定时间窗，ms（两次轻触间隔超过则重新计数）。 */
        private const val TAP_INTERVAL_MS = 500L

        /** 双击后延迟开菜单的确认时长，ms——留出时间判断是否会有第三击（三击关悬浮窗）。 */
        private const val TAP_CONFIRM_MS = 250L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingLive2dService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingLive2dService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private var renderer: Live2dOverlayRenderer? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 主线程 Handler（延迟任务：开菜单确认等）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 轻触计数与时间戳（区分双击开菜单 / 三击关悬浮窗）。 */
    private var lastTapTime = 0L
    private var tapCount = 0
    private val pendingShowMenu = Runnable { showMenu() }

    /** 悬浮窗子窗口相关协程作用域（Main），onDestroy 时取消。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ----- 悬浮窗子窗口：菜单 / 输入框 / 气泡回复 -----
    private var menuWindow: OverlayMenuWindow? = null
    private var inputWindow: OverlayInputWindow? = null
    private var bubbleWindow: OverlayBubbleWindow? = null
    private var alphaWindow: OverlayAlphaWindow? = null

    /** Live2D 悬浮窗当前透明度（内存态，不保存）。 */
    private var overlayAlpha = 1f

    /** Screen-density conversion cache */
    private var _density = 0f
    private val density: Float get() {
        if (_density == 0f) _density = resources.displayMetrics.density
        return _density
    }

    /** 触摸/手势处理器（拖动 / 捏合 / 轻触判定）。 */
    private val touchHandler by lazy {
        OverlayTouchHandler(
            density = density,
            resources = resources,
            layoutParams = { layoutParams },
            windowManager = { windowManager },
            glSurfaceView = { if (::glSurfaceView.isInitialized) glSurfaceView else null },
            onWindowChanged = { repositionOverlayAnchors() },
            onTap = { onOverlayTap() }
        )
    }

    override fun onCreate() {
        super.onCreate()
        // shuttingDown 是共享 StateFlow（全局），上次关闭时置位过；新 Service 必须重置，
        // 否则渲染器每帧开头的 shuttingDown 检查恒为 true → 模型永不加载（透明僵尸窗）
        Live2dRenderState.setShuttingDown(false)
        Live2dRenderState.setWasActive(true)
        Live2dRenderState.setOverlayActive(true)
        Live2dRenderState.setRunning(true)
        // 提供 application context，Activity 已销毁时悬浮窗仍能加载模型资源
        Live2dDelegate.getInstance().attachContext(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        // 无悬浮窗权限时（如权限被用户在后台撤销后服务被重启）直接退出，
        // 否则会留下一个加不上视图/吞触摸的空壳服务
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing, stopping service")
            Live2dRenderState.setOverlayActive(false)
            stopSelf()
            return
        }
        // 32 位 ARM 没有 Live2D 原生核心（见 Live2dSupport / ADR 0003）：悬浮窗的唯一内容
        // 就是模型，没有模型就没有可显示的东西，直接拒绝启动，避免留下透明僵尸窗。
        if (!Live2dSupport.isAvailable) {
            Log.w(TAG, "Live2D unavailable on this ABI, overlay not supported; stopping service")
            Live2dRenderState.setOverlayActive(false)
            stopSelf()
            return
        }
        createFloatingWindowWithPalette()
    }

    /**
     * 先等设置快照预热完成再建窗。
     *
     * 悬浮窗配色（[OverlayPalette.resolve]）读的是设置内存快照：进程为悬浮窗而冷启动时
     * 快照可能还没就绪，直接建窗会拿到默认主题的配色，而这些窗口长期存活、不会自愈。
     * 预热是毫秒级的 IO，这里只挂起协程等待，不阻塞主线程。
     */
    private fun createFloatingWindowWithPalette() {
        val settings = (application as? MeaPetApplication)?.container?.settingsManager
        serviceScope.launch {
            settings?.awaitLoaded()
            createFloatingWindow()
            initOverlayWindows()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 返回 [START_NOT_STICKY]：进程被系统杀死后 CubismFramework 未 startUp、
     * Live2dDelegate 也没有 Activity，自动重启只会得到一个模型加载失败、
     * 只吞触摸的隐形窗口，因此不做自动重启，由用户手动重新打开。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: flags=$flags startId=$startId")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Live2dRenderState.setRunning(false)
        Live2dRenderState.setOverlayActive(false)
        // 标记服务正在关闭：GL 线程每帧检查，立刻停止绘制
        Live2dRenderState.setShuttingDown(true)
        // 收尾子窗口：先取消协程与延迟任务、隐藏输入框（收起键盘），再逐个销毁，
        // 避免服务销毁后 Handler 任务仍引用已移除的视图
        serviceScope.cancel()
        mainHandler.removeCallbacks(pendingShowMenu)
        inputWindow?.hide()
        inputWindow = null
        menuWindow?.destroy()
        menuWindow = null
        bubbleWindow?.destroy()
        bubbleWindow = null
        alphaWindow?.destroy()
        alphaWindow = null
        if (::glSurfaceView.isInitialized) {
            // 先在 GL 线程释放模型的 native 资源。事件在 onPause 之前入队：
            // GLSurfaceView 的 GL 线程按序处理事件队列且优先于暂停处理，
            // 因此 releaseModel 执行时 EGL 上下文仍然有效
            glSurfaceView.queueEvent { renderer?.releaseModel() }
            // onPause 会阻塞到 GL 线程完成当前帧并暂停，此后本服务不再发出任何 GL 调用
            glSurfaceView.onPause()
            // 延迟移除视图——SurfaceView 可能有未完成的绘制回调，
            // 立即 removeView 会导致 pending callback 中 getParent() 为 null → NPE
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { windowManager?.removeView(glSurfaceView) } catch (_: Exception) {}
                windowManager = null
            }
        }
        // 必须在 GL 线程静止（上面的 onPause 返回）之后再置位 wasActive：
        // MainActivity 的 GL 线程看到 wasActive 才会 deleteInstance 重建 shader 单例，
        // 这个顺序保证两条 GL 线程不会并发操作 CubismShaderAndroid
        Live2dRenderState.setWasActive(true)
        // MainActivity 已销毁且把共享单例的收尾托付给了本服务
        if (Live2dRenderState.pendingSharedDispose.value) {
            Live2dRenderState.setPendingSharedDispose(false)
            try { Live2dDelegate.getInstance().onDestroy() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Live2D Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows Live2D model floating over other apps" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Live2D Overlay")
            .setContentText("Double-tap for menu · Drag to move · Pinch to resize")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun createFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val winWidth = (150 * density).toInt()
        val winHeight = (218 * density).toInt()

        renderer = Live2dOverlayRenderer(
            isShuttingDown = { Live2dRenderState.shuttingDown.value },
            onLoadFailed = { stopSelf() }
        ).also { it.setOpacity(overlayAlpha) }

        glSurfaceView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                touchHandler.handleTouch(event)
                return true
            }
        }.apply {
            // CRITICAL: explicit EGL config with 8-bit alpha for transparency
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setZOrderOnTop(true)
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            // 模型有常驻待机动画，每帧都要重绘，如实使用连续渲染模式
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setPreserveEGLContextOnPause(false)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        layoutParams = WindowManager.LayoutParams(
            winWidth, winHeight,
            flags,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 160
        }

        windowManager?.addView(glSurfaceView, layoutParams)
        Log.d(TAG, "Floating window created: ${winWidth}x$winHeight")
    }

    // ================ Overlay sub-windows ================

    private fun initOverlayWindows() {
        bubbleWindow = OverlayBubbleWindow(this).also { it.setAnchor(anchorRect()) }
        menuWindow = OverlayMenuWindow(
            context = this,
            onCloseOverlay = { stopSelf() },
            onOpenInput = { toggleInputWindow() },
            isLocked = { touchHandler.locked },
            onToggleLock = { toggleLock() },
            onOpenAlpha = { toggleAlphaWindow() }
        )
        inputWindow = OverlayInputWindow(this, onSend = ::sendFromOverlay, onClose = { inputWindow?.hide() })
    }

    /** 切换锁定态（内存态）：锁定后人物不可拖动/缩放，轻触操作不变。 */
    private fun toggleLock() {
        touchHandler.locked = !touchHandler.locked
        Log.d(TAG, "Overlay lock toggled: ${touchHandler.locked}")
    }

    /** 打开/关闭透明度调节面板（toggle）。 */
    private fun toggleAlphaWindow() {
        if (alphaWindow?.isVisible == true) {
            alphaWindow?.hide()
        } else {
            // 每次新建以读取最新 overlayAlpha 作初始滑杆位置；旧实例先销毁
            alphaWindow?.destroy()
            alphaWindow = OverlayAlphaWindow(this, overlayAlpha) { applyOverlayAlpha(it) }
                .also { it.show(anchorRect()) }
        }
    }

    /** 实时应用 Live2D 悬浮窗透明度（内存态）。 */
    private fun applyOverlayAlpha(alpha: Float) {
        overlayAlpha = alpha
        // 真正生效的是 GL 绘制管线内的整体不透明度：GLSurfaceView 的画面在独立
        // Surface 上，View.setAlpha 只作用于占位层，部分机型（含 Android 10+ 国产
        // ROM）合成时不乘该 alpha 导致调节无效。GL 内乘 alpha 对所有机型统一生效。
        renderer?.setOpacity(alpha)
        // 保留 View alpha 作为视觉辅助（GL 内已覆盖全部机型，双保险）
        if (::glSurfaceView.isInitialized) glSurfaceView.alpha = alpha
    }

    /** 当前人物悬浮窗在屏幕上的矩形（位置由 layoutParams 表达）。 */
    private fun anchorRect(): Rect {
        val p = layoutParams ?: return Rect()
        return Rect(p.x, p.y, p.x + p.width, p.y + p.height)
    }

    /** 人物悬浮窗被拖动/缩放后，同步气泡、菜单与透明度面板的位置。 */
    private fun repositionOverlayAnchors() {
        val r = anchorRect()
        bubbleWindow?.setAnchor(r)
        menuWindow?.takeIf { it.isVisible }?.reposition(r)
        alphaWindow?.takeIf { it.isVisible }?.reposition(r)
    }

    private fun showMenu() {
        menuWindow?.show(anchorRect())
    }

    /**
     * 轻触人物悬浮窗：
     * - 菜单开着 → 任意轻触收起菜单；
     * - 双击（[TAP_INTERVAL_MS] 内两击）→ 延迟确认后开菜单；
     * - 快速三连击 → 直接关闭整个悬浮窗（无需打开菜单）。
     */
    private fun onOverlayTap() {
        val now = System.currentTimeMillis()

        // 菜单开着：任何轻触直接收起，并重置连击计数
        if (menuWindow?.isVisible == true) {
            hideMenu()
            lastTapTime = now
            tapCount = 1
            return
        }

        // 连击计数（间隔超过 TAP_INTERVAL_MS 视为新一轮）
        if (now - lastTapTime > TAP_INTERVAL_MS) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTime = now

        when {
            tapCount == 2 -> {
                // 等片刻确认不是第三击再开菜单，避免与"三击关闭"冲突
                mainHandler.removeCallbacks(pendingShowMenu)
                mainHandler.postDelayed(pendingShowMenu, TAP_CONFIRM_MS)
            }
            tapCount >= 3 -> {
                mainHandler.removeCallbacks(pendingShowMenu)
                Log.d(TAG, "Triple-tap: closing overlay")
                stopSelf()
            }
        }
    }

    private fun hideMenu() {
        menuWindow?.hide()
    }

    /** 输入框可见则隐藏，隐藏则显示（菜单项"唤起输入框"的 toggle 语义）。 */
    private fun toggleInputWindow() {
        if (inputWindow?.isVisible == true) inputWindow?.hide() else inputWindow?.show(anchorRect())
    }

    /** 从悬浮窗输入框发送消息：走主界面同一个 chat 包，回复以气泡展示。 */
    private fun sendFromOverlay(text: String) {
        val container = (applicationContext as? MeaPetApplication)?.container ?: return
        inputWindow?.setSending(true)
        serviceScope.launch {
            try {
                // sendMessage 内部已 withContext(IO)，返回后回到 Main 直接更新 UI
                val result = container.chatService.sendMessage(text)
                result.fold(
                    onSuccess = { (_, assistant) ->
                        inputWindow?.clearText()
                        bubbleWindow?.addBubble(assistant.content)
                        // 悬浮窗语音（模型就绪且开关开启时才真正发声，否则静默跳过）
                        container.ttsManager.speak(assistant, com.meapet.mobile.tts.TtsManager.Source.OVERLAY)
                    },
                    onFailure = { e ->
                        Log.w(TAG, "Overlay send failed: ${e.message}")
                        bubbleWindow?.addBubble("发送失败：${e.message ?: "未知错误"}")
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 主界面插队 / 清除对话会取消共享 ChatService 的在途 turn（G6），悬浮窗
                // 这一条随之取消：不是发送失败，不回「发送失败」气泡，但必须退出发送中状态
                Log.i(TAG, "Overlay send cancelled: ${e.message}")
                throw e
            } finally {
                // 取消是异步的：无论成功、失败还是被取消，都要恢复输入框，否则会一直
                // 卡在「发送中」禁用态
                inputWindow?.setSending(false)
            }
        }
    }
}
