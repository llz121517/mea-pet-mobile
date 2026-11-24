package com.meapet.mobile.live2d

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid
import com.meapet.mobile.live2d.audio.VoicePlayer
import java.security.SecureRandom

/**
 * Singleton application delegate — manages Cubism SDK lifecycle,
 * OpenGL state, and owns the View + TextureManager.
 */
@SuppressLint("StaticFieldLeak")
class Live2dDelegate private constructor() {

    companion object {
        private const val TAG = "Live2dDelegate"

        // ── 语音分区目录常量 ──
        private const val VOICE_DIR_UPPER    = "voice/upper"
        private const val VOICE_DIR_LOWER_L  = "voice/lower_left"
        private const val VOICE_DIR_LOWER_R  = "voice/lower_right"

        /** 从文件名提取显示文本："jp_哼。.ogg" → "哼。" */
        fun textFromFilename(name: String): String =
            name.substringAfter("_").substringBeforeLast(".")

        @Volatile
        private var instance: Live2dDelegate? = null

        /**
         * TTS 语音停止回调（互斥：触摸语音响起时停掉 TTS）。
         * 由 app 容器注入，避免 live2d 包反向依赖 TTS。
         */
        @Volatile
        var ttsStopper: (() -> Unit)? = null

        /** TTS 是否正在播放（触摸语音触发时用于决定是否先停 TTS）。 */
        @Volatile
        var ttsPlayingChecker: (() -> Boolean)? = null

        fun getInstance(): Live2dDelegate {
            return instance ?: synchronized(this) {
                instance ?: Live2dDelegate().also { instance = it }
            }
        }
    }

    @Volatile
    private var _activity: Activity? = null
    /** 安全获取 Activity，可能为 null（GL 线程中可能尚未赋值或被销毁）。 */
    val activity: Activity? get() = _activity
    // 各调用方已用 try-catch 保护，使用时注意空安全

    /** Application context——资源加载用，不随 Activity 生命周期失效，避免泄漏 Activity。 */
    @Volatile
    private var _appContext: Context? = null
    val appContext: Context? get() = _appContext

    val textureManager = Live2dTextureManager()
    var view = Live2dView()
        private set

    /** 主界面背景壁纸渲染器（GL 线程内绘制，无壁纸时 no-op）。 */
    val wallpaper = WallpaperRenderer()

    /** 主界面背景壁纸文件绝对路径；null = 默认纯色。主线程写、GL 线程每帧读。 */
    var wallpaperPath: String?
        get() = wallpaper.desiredPath
        set(value) { wallpaper.desiredPath = value }

    /** 主界面背景壁纸模糊强度（0~1）。主线程写、GL 线程每帧读。 */
    var wallpaperBlur: Float
        get() = wallpaper.blur
        set(value) { wallpaper.blur = value }

    var windowWidth = 0
        private set
    var windowHeight = 0
        private set

    private var isCaptured = false

    /** 触摸分区是否启用（设置页内禁用，防止穿透触发语音）。 */
    @Volatile
    var zoneTouchEnabled = true

    /** 背景色 RGBA（0~1），跟随主题变化。默认浅色。
     *  Kotlin 的 @Volatile 只作用于单个属性，四个通道需各自标注。 */
    @Volatile
    var bgR = 0.98f
    @Volatile
    var bgG = 0.98f
    @Volatile
    var bgB = 0.98f
    @Volatile
    var bgA = 1.0f

    private val cubismOption = CubismFramework.Option()

    /**
     * Cubism SDK 是否已成功启动。
     *
     * 32 位 ARM 设备上没有 Live2D 原生核心（见 [Live2dSupport]），此时整个 delegate
     * 降级为「只做背景色 + 壁纸」：不启动 SDK、不加载模型、不提供触摸语音。
     * 所有公开方法都先看这个标记，避免任何一处漏判又把 native 调用走到崩溃路径。
     */
    private val cubismReady: Boolean

    init {
        // 探测放在最前：不可用时连 Option 初始化后的 startUp 都不能调（会加载 .so）
        val available = Live2dSupport.isAvailable
        if (available) {
            cubismOption.logFunction = Live2dPal.PrintLogFunction()
            cubismOption.loggingLevel = Live2dDefine.CUBISM_LOGGING_LEVEL
            cubismOption.loadFileFunction = Live2dPal.LoadFileFunction()

            CubismFramework.cleanUp()
            CubismFramework.startUp(cubismOption)
            Log.d(TAG, "CubismFramework started")
        } else {
            Log.w(TAG, "Live2D unavailable on this ABI, running in wallpaper-only mode")
        }
        cubismReady = available
    }

    fun onStart(activity: Activity) {
        this._activity = activity
        this._appContext = activity.applicationContext
    }

    /** 本设备是否真的能渲染 Live2D 模型（false = 降级为壁纸/纯色背景）。 */
    val isModelAvailable: Boolean get() = cubismReady

    fun onStop() { /* no-op */ }

    /** 悬浮窗 Service 单独入口：只提供 application context，供无 Activity 时加载资源。 */
    fun attachContext(context: Context) {
        if (_appContext == null) _appContext = context.applicationContext
    }

    /**
     * Activity 销毁时清除对它的强引用，避免共享单例长期持有已销毁的 Activity。
     * appContext 保留（application 级，不会泄漏）。仅当传入的正是当前持有者时清除，
     * 防止竞态下误清后来者。
     */
    fun onActivityDestroyed(activity: Activity) {
        if (_activity === activity) {
            _activity = null
        }
    }

    fun onDestroy() {
        if (cubismReady) {
            view.close()
            CubismShaderAndroid.getInstance().releaseInvalidShaderProgram()
            CubismShaderAndroid.deleteInstance()
            Live2dManager.releaseInstance()
            CubismFramework.dispose()
            Log.d(TAG, "CubismFramework disposed")
        }
        instance = null
    }

    fun onSurfaceCreated() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // 壁纸层与 Cubism 无关：降级模式下也要照常重建，否则背景会变成裸清屏色
        wallpaper.reset()

        if (!cubismReady) {
            Log.i(TAG, "onSurfaceCreated: Live2D degraded, only wallpaper/clear color active")
            return
        }

        // 旧 GL 上下文已失效，彻底清理并重建（CSDN 文章指出的关键）
        textureManager.releaseInvalidTextures()
        view.close()
        view = Live2dView()           // ← 创建新 View，官方示例就是这样做的

        Live2dPal.updateTime()

        if (!CubismFramework.isInitialized()) {
            CubismFramework.initialize()
            Log.d(TAG, "CubismFramework initialized")
        }

        CubismShaderAndroid.getInstance().releaseInvalidShaderProgram()
        CubismShaderAndroid.deleteInstance()

        // 强制重新加载模型（重置 modelLoaded，在新 GL 上下文中重绑纹理）
        Live2dManager.getInstance().resetModel()

        Log.d(TAG, "onSurfaceCreated complete")
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        windowWidth = width
        windowHeight = height

        if (!cubismReady) return

        view.initialize()
        view.initializeSprite()

        Live2dManager.getInstance().setRenderTargetSize(width, height)
        Log.d(TAG, "onSurfaceChanged: ${width}x$height")
    }

    fun run() {
        if (cubismReady && Live2dRenderState.consumeShaderResetRequest()) {
            // 悬浮窗的 GL 上下文已销毁，它的 shader 在当前上下文无效。
            // 跳过 releaseInvalidShaderProgram（跨上下文 GL 操作会崩溃），直接重建。
            CubismShaderAndroid.deleteInstance()
            Log.d(TAG, "Shader state reset after overlay closed")
        }

        if (Live2dRenderState.overlayActive.value) return

        if (cubismReady) Live2dPal.updateTime()

        GLES20.glClearColor(bgR, bgG, bgB, bgA)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glClearDepthf(1.0f)

        // ── 背景壁纸层：清屏（纯色兜底）之后、Live2D 模型绘制之前 ──
        // 无壁纸时 onFrame 是 no-op，清屏纯色照旧。悬浮窗走独立渲染器，不受影响。
        // 降级模式下这是唯一还在画的东西。
        try {
            wallpaper.onFrame(windowWidth, windowHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Wallpaper render error: ${e.message}")
        }

        if (!cubismReady) return

        try {
            view.render()
        } catch (e: Exception) {
            Log.e(TAG, "Render error: ${e.message}")
        }
    }

    // ── 触摸跟随 ──
    // 像素坐标 → 归一化 [-1, 1]（Y 轴翻转：Android 顶部=0，OpenGL 底部=-1）

    fun onTouchBegan(x: Float, y: Float) {
        mouseX = x; mouseY = y
        touchDownX = x; touchDownY = y; touchDownTime = System.currentTimeMillis()
        isCaptured = true
        if (!cubismReady) return
        if (windowWidth > 0 && windowHeight > 0) {
            Live2dManager.getInstance().onDrag(
                ((x / windowWidth) * 2f - 1f).coerceIn(-1f, 1f),
                (-((y / windowHeight) * 2f - 1f)).coerceIn(-1f, 1f)
            )
        }
    }

    fun onTouchMoved(x: Float, y: Float) {
        mouseX = x; mouseY = y
        if (!cubismReady) return
        if (isCaptured && windowWidth > 0 && windowHeight > 0) {
            Live2dManager.getInstance().onDrag(
                ((x / windowWidth) * 2f - 1f).coerceIn(-1f, 1f),
                (-((y / windowHeight) * 2f - 1f)).coerceIn(-1f, 1f)
            )
        }
    }

    private val secureRandom = SecureRandom()
    private var mouseX = 0f
    private var mouseY = 0f

    // ── 触摸分区系统 ──

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    /** 已创建的 VoicePlayer 缓存，按子目录 key 存储，复用同一个播放器。
     *  用 ConcurrentHashMap：ensureVoicePlayer 在 GL 线程（触摸事件），
     *  stopTouchVoices 经 TtsManager 在协程线程，两线程并发访问需线程安全容器。 */
    private val voicePlayers = java.util.concurrent.ConcurrentHashMap<String, VoicePlayer>()

    /** 获取或创建 VoicePlayer（需要 Context，在 onStart 后可用）。 */
    private fun ensureVoicePlayer(dir: String): VoicePlayer? {
        voicePlayers[dir]?.let { return it }
        val ctx = _appContext ?: _activity?.applicationContext ?: return null
        return VoicePlayer(ctx, dir).also { voicePlayers[dir] = it }
    }

    /** 停止当前所有正在播放的语音，确保新触发的语音立即生效。 */
    private fun stopAllVoices() {
        voicePlayers.values.forEach { it.stop() }
    }

    /** 供外部（TTS 互斥）停止所有触摸语音。 */
    fun stopTouchVoices() = stopAllVoices()

    /** 判断是否为轻触（非拖动）。阈值：移动≤30px、时长≤400ms。 */
    private fun isTap(x: Float, y: Float): Boolean {
        val dx = x - touchDownX
        val dy = y - touchDownY
        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val duration = System.currentTimeMillis() - touchDownTime
        return dist < 30f && duration < 400L
    }

    // ── 触摸分区定义 ──

    /**
     * 检测触摸分区。
     * @return (显示文本, 子目录名, 文件名)，不在模型区域内则 null
     */
    private fun detectZone(x: Float, y: Float): Triple<String, String, String>? {
        if (windowWidth <= 0 || windowHeight <= 0) return null
        val nx = (x / windowWidth) * 2f - 1f
        val ny = -((y / windowHeight) * 2f - 1f)

        // 模型实际占屏区域
        if (nx < -0.45f || nx > 0.45f || ny < -0.44f || ny > 0.66f) return null

        // 从上往下 6/10 分割线
        val splitY = 0.00f

        val dir = if (ny > splitY) {
            VOICE_DIR_UPPER
        } else if (nx < 0f) {
            VOICE_DIR_LOWER_L
        } else {
            VOICE_DIR_LOWER_R
        }

        val vp = ensureVoicePlayer(dir) ?: return null
        val files = vp.listVoices()
        if (files.isEmpty()) return null

        val file = files[secureRandom.nextInt(files.size)]
        return Triple(textFromFilename(file), dir, file)
    }

    fun onTouchEnd(x: Float, y: Float) {
        mouseX = x; mouseY = y
        isCaptured = false
        if (!cubismReady) return
        Live2dManager.getInstance().onDrag(0.0f, 0.0f)

        // 检测轻触 → 分区反馈（仅在聊天页启用，防止设置页穿透）
        if (zoneTouchEnabled && isTap(x, y)) {
            val zone = detectZone(x, y)
            if (zone != null) {
                val (text, dir, file) = zone
                // 互斥：TTS 正在朗读时先停掉，再播触摸语音
                if (ttsPlayingChecker?.invoke() == true) ttsStopper?.invoke()
                // 先停止所有正在播放的语音（包括其他分区的），再播放新语音
                stopAllVoices()
                ensureVoicePlayer(dir)?.play(file)
                // 广播消息到 ChatViewModel
                Live2dManager.emitTapMessage(text)
            }
        }
    }
}
