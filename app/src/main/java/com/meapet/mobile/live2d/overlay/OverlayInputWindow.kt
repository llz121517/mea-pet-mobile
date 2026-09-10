package com.meapet.mobile.live2d.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.meapet.mobile.R
import kotlin.math.roundToInt

/**
 * 悬浮窗模式下的独立输入框悬浮窗。
 *
 * 菜单点击「唤起输入框」后出现在人物悬浮窗正下方（默认），可拖动到任意
 * 位置（拖动抓手为左侧的 ≡）。紧凑小条，不是主界面那种全宽药丸输入栏。
 * 发送后保持打开，便于连续发送多条消息。
 *
 * ## 焦点与键盘
 *
 * 这是全项目**唯一**需要软键盘的悬浮窗，而「能弹键盘」（窗口可聚焦）
 * 与「不挡着别人」（窗口不占焦点）在窗口层面互斥：只要窗口可聚焦，它
 * 就是 display 的焦点窗口，下层应用既拉不起自己的键盘、也收不到返回键。
 *
 * 因此可聚焦不作为窗口的固定属性，而是按用户意图在两种状态间切换
 * （flag 组合见 [OverlayInputFocus]）：
 *
 * - **默认不可聚焦**：键盘、焦点、返回键全归下层应用。
 * - **点输入框 → 取得 IME 资格**：清掉两个阻塞位并 `updateViewLayout`，
 *   等窗口真正拿到焦点后再弹键盘。
 * - **注意力移开 → 立刻归还**：窗外触摸（`ACTION_OUTSIDE`，直接信号）、
 *   窗口失焦、返回键，任一命中即归还；发送后同样归还，等回复期间不再
 *   占着焦点。
 *
 * @param context 上下文（Service 即可）
 * @param onSend 发送回调（文本已 trim 且非空）
 * @param onClose 关闭回调（隐藏输入框）
 */
@SuppressLint("ViewConstructor")
class OverlayInputWindow(
    context: Context,
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit,
) {
    companion object {
        private const val TAG = "OverlayInputWindow"

        /**
         * 取得焦点后的静默期：期间忽略失焦回调。
         *
         * 切 flag 会让 WM 重算焦点，可能先送来一次「还没聚焦就已失焦」的
         * 抖动回调；不设静默期，它会把刚取得的输入态清掉，键盘一闪即收。
         */
        private const val FOCUS_SETTLE_MS = 300L

        /** 等待窗口焦点回调的兜底上限：超时仍未收到就直接试弹一次键盘。 */
        private const val FOCUS_FALLBACK_MS = 400L

        /** 输入占位符，与主界面一致。 */
        private const val PLACEHOLDER = "给 Mea 发个消息..."

        /** 输入条固定宽度，dp（紧凑但够输入）。 */
        private const val BAR_WIDTH_DP = 260f

        /** 圆角半径，dp。 */
        private const val CORNER_RADIUS_DP = 20f

        /** 发送按钮直径，dp（主页 40dp 减 4px ≈ 36dp，适配紧凑输入条）。 */
        private const val SEND_BUTTON_DP = 36

        /** 发送图标边长，dp（与主页一致）。 */
        private const val SEND_ICON_DP = 20

        /** 与人物正下方的间距，dp。 */
        private const val GAP_DP = 8f
    }

    private val ctx: Context = context
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val palette = OverlayPalette.resolve(ctx)
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val editText: EditText
    private val sendButton: ImageButton
    private val loadingView: ProgressBar
    private val rootView: View

    private val params = WindowManager.LayoutParams(
        dp(BAR_WIDTH_DP),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 出生即不可聚焦：只有用户真的要输入时才临时取得 IME 资格（见 [acquireFocus]）
        OverlayInputFocus.flagsFor(engaged = false),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 只留 ADJUST_RESIZE。SOFT_INPUT_STATE_ALWAYS_VISIBLE 会让系统在本窗口每次
        // 获得焦点时自行弹键盘，与这里的显式控制打架，也是键盘被别的应用抢走后又
        // 弹回来的来源之一；键盘一律由 [showIme] 驱动。
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    /** 用户是否正处于输入态（窗口可聚焦、能成为输入法目标）。 */
    private var engaged = false

    /**
     * 最近一次取得焦点的时刻，用于过滤切换 flag 引发的假失焦
     * （见 [FOCUS_SETTLE_MS]）。
     */
    private var engagedAt = 0L

    /** 是否正在等待回复（发送中）。 */
    var isSending: Boolean = false
        private set

    // ----- 拖动状态（左侧抓手拖动） -----
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0

    init {
        // 透明无下划线的输入框，紧凑单行
        editText = EditText(ctx).apply {
            setHint(PLACEHOLDER)
            setHintTextColor(palette.onSurfaceVariant.withAlpha(0x99))  // ≈ 60% 透明
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendIfPossible()
                    true
                } else {
                    false
                }
            }
            // 输入变化时刷新发送钮可用态（与主页的空输入置灰一致）
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refreshSendButton()
            })
        }

        loadingView = ProgressBar(ctx).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(palette.primary)
        }

        // 圆形主色发送按钮（MDI send 图标）；尺寸/禁用态与主页 ChatInputBar 一致
        // 初始禁用态在 init 末尾统一刷新（editText/sendButton 均就绪后）
        sendButton = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_send)
            contentDescription = "发送"
            setOnClickListener { sendIfPossible() }
        }

        // 发送区：发送按钮 / 加载指示互换；发送图标约束为 20dp 居中（与主页一致）
        val sendContainer = FrameLayout(ctx).apply {
            addView(
                sendButton,
                FrameLayout.LayoutParams(dp(SEND_BUTTON_DP), dp(SEND_BUTTON_DP))
            )
            addView(
                loadingView,
                FrameLayout.LayoutParams(dp(SEND_BUTTON_DP), dp(SEND_BUTTON_DP), Gravity.CENTER)
            )
        }
        sendButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        sendButton.setPadding(
            dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2), dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2),
            dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2), dp((SEND_BUTTON_DP - SEND_ICON_DP) / 2)
        )

        // 关闭按钮（MDI close 图标）
        val closeButton = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(palette.onSurfaceVariant)
            contentDescription = "关闭"
            isClickable = true
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClose() }
        }

        // 左侧拖动抓手（MDI drag-vertical 图标）
        val grip = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_drag)
            setColorFilter(palette.onSurfaceVariant.withAlpha(0xA6))
            contentDescription = "拖动"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnTouchListener { _, event -> handleDrag(event) }
        }

        // 紧凑输入条：抓手 + 输入框 + 发送 + 关闭
        //
        // 根视图负责「用户意图」的采集，这是整套焦点归还机制的入口：
        // 窗口失焦与返回键都不一定可靠（见各自的注释），而窗外触摸通报是直接信号。
        rootView = object : LinearLayout(ctx) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    // 用户点了窗口外的任何地方 → 注意力已移开，立刻归还 IME 资格，
                    // 让那一下点击落到的应用能正常拉起自己的键盘。
                    MotionEvent.ACTION_OUTSIDE -> releaseFocus("touch outside")
                    // 点了输入框 → 取得 IME 资格。发送中 EditText 已禁用，不取得。
                    MotionEvent.ACTION_DOWN ->
                        if (!engaged && !isSending && hitEditText(event.rawX, event.rawY)) {
                            acquireFocus("tap on input")
                        }
                    else -> Unit
                }
                // 必须继续下发：取得/归还焦点是副作用，事件本身还要照常走视图树
                return super.dispatchTouchEvent(event)
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) {
                    // 焦点真正到手之后才弹键盘。早于此刻调 showSoftInput，请求者还不是
                    // 当前焦点窗口，会被 IMMS 拒绝，并可能让键盘先被收起再被别的应用拉起
                    // ——那正是「键盘闪一下、却仍指向别的应用」的来源。
                    if (engaged) showIme()
                } else if (engaged && SystemClock.uptimeMillis() - engagedAt > FOCUS_SETTLE_MS) {
                    // 失焦即归还。静默期用来滤掉切 flag 时 WM 重算焦点产生的抖动回调。
                    releaseFocus("window focus lost")
                }
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // 键盘收起后，返回键会先落到本窗口。若照单吞下，下层应用就再也收不到
                // 返回；这里把「按了返回」读作「不想打字了」，归还焦点后照常消费掉这一下。
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP && engaged
                ) {
                    releaseFocus("back key")
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant.withAlpha(0xF2))
                cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                setStroke(dp(1), palette.onSurface.withAlpha(0x1A))
            }
            elevation = 8f * density
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(grip, LinearLayout.LayoutParams(dp(32), dp(40)))
            addView(editText, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(sendContainer, LinearLayout.LayoutParams(dp(SEND_BUTTON_DP), dp(SEND_BUTTON_DP)))
            addView(closeButton, LinearLayout.LayoutParams(dp(32), dp(36)))
        }

        // 初始禁用态刷新（此刻 editText 与 sendButton 均已就绪）
        refreshSendButton()
    }

    /** 是否已显示在屏幕上。 */
    val isVisible: Boolean get() = rootView.isAttachedToWindow

    /** 显示输入框：默认出现在人物正下方，并弹软键盘。 */
    fun show(anchor: Rect) {
        if (isVisible) return
        val dm = ctx.resources.displayMetrics
        // 默认位置：人物正下方（贴近人物，便于连续交流），钳制在屏内
        params.x = anchor.left.coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
        params.y = (anchor.bottom + dp(GAP_DP)).coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
        // 菜单里点「唤起输入框」= 用户明确要打字：带着可聚焦 flag 一次 addView 到位，
        // 首次显示不经过 updateViewLayout，也就没有 flag 抖动。
        engaged = true
        engagedAt = SystemClock.uptimeMillis()
        params.flags = OverlayInputFocus.flagsFor(engaged = true)
        try { windowManager.addView(rootView, params) } catch (e: Exception) {
            Log.w(TAG, "Failed to show input window: ${e.message}")
            engaged = false
            return
        }
        // 键盘**不**在这里弹：等窗口真正拿到焦点（[onWindowFocusChanged]）再调
        // showSoftInput，早调会被 IMMS 拒绝。这里只补一个超时兜底，防止焦点回调没来。
        mainHandler.postDelayed({
            if (engaged && isVisible) {
                Log.d(TAG, "focus callback timed out, try ime directly")
                showIme()
            }
        }, FOCUS_FALLBACK_MS)
        Log.d(TAG, "show: attached, awaiting window focus")
    }

    /** 隐藏输入框（收起键盘、归还焦点并移除窗口）。 */
    fun hide() {
        mainHandler.removeCallbacksAndMessages(null)
        engaged = false
        hideIme()
        if (isVisible) {
            try { windowManager.removeView(rootView) } catch (_: Exception) {}
        }
        Log.d(TAG, "hide")
    }

    /** 清空已输入的文字。 */
    fun clearText() {
        editText.setText("")
        refreshSendButton()
    }

    /**
     * 切换发送中状态：禁用编辑并显示加载指示；发送后输入框保持打开，以便
     * 连续发送（产品决定）。
     *
     * 发送即归还焦点：等回复期间不再占着 IME 资格，否则用户这时切到别的应用，
     * 那边会拉不起自己的键盘。
     */
    fun setSending(sending: Boolean) {
        isSending = sending
        editText.isEnabled = !sending
        sendButton.isEnabled = !sending
        sendButton.visibility = if (sending) View.GONE else View.VISIBLE
        loadingView.visibility = if (sending) View.VISIBLE else View.GONE
        if (sending) releaseFocus("send started")
        // 回复到达时不自动弹键盘：等待期间用户可能已经切走，替它弹出来会盖在别的应用
        // 上。要继续输入，点一下输入框即可（见 [rootView] 的 dispatchTouchEvent）。
    }

    // ================ 内部 ================

    /** 左侧抓手拖动：在屏幕内移动输入条。 */
    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dm = ctx.resources.displayMetrics
                params.x = (dragStartX + (event.rawX - dragStartRawX).toInt())
                    .coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
                params.y = (dragStartY + (event.rawY - dragStartRawY).toInt())
                    .coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
                try { windowManager.updateViewLayout(rootView, params) } catch (_: Exception) {}
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            else -> return false
        }
    }

    /**
     * 取得 IME 资格：清掉两个阻塞位并 `updateViewLayout`。
     * 键盘不在这里弹——窗口焦点到手后由 [onWindowFocusChanged] 触发 [showIme]。
     */
    private fun acquireFocus(reason: String) {
        if (engaged) return
        engaged = true
        engagedAt = SystemClock.uptimeMillis()
        applyFlags()
        Log.d(TAG, "acquire focus ($reason)")
    }

    /** 归还 IME 资格：立刻不可聚焦，把键盘和焦点一并让给下层应用。 */
    private fun releaseFocus(reason: String) {
        if (!engaged) return
        engaged = false
        hideIme()
        editText.clearFocus()
        applyFlags()
        Log.d(TAG, "release focus ($reason)")
    }

    /**
     * 把 [engaged] 落到窗口 flag 上。
     * flag 未变时直接返回：多余的 `updateViewLayout` 会白发一次重排，导致闪烁。
     */
    private fun applyFlags() {
        val flags = OverlayInputFocus.flagsFor(engaged)
        if (params.flags == flags) return
        params.flags = flags
        if (!isVisible) return
        try {
            windowManager.updateViewLayout(rootView, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed: ${e.message}")
        }
    }

    /**
     * 弹软键盘。
     *
     * 两道前置条件缺一不可：窗口是当前焦点窗口（否则 IMMS 会拒绝），且
     * `requestFocus` 真的把焦点交给了输入框（窗口不可聚焦时它会失败）。
     */
    private fun showIme() {
        if (!isVisible || !engaged) return
        if (!rootView.hasWindowFocus()) {
            Log.d(TAG, "showIme skipped: window holds no focus")
            return
        }
        if (!editText.requestFocus()) {
            Log.d(TAG, "showIme skipped: editText refused focus")
            return
        }
        try {
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Log.w(TAG, "showSoftInput failed: ${e.message}")
        }
    }

    private fun hideIme() {
        try {
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
        } catch (_: Exception) {}
    }

    /**
     * 触摸点（屏幕坐标）是否落在输入框内。
     * 窗口内坐标不可用：事件可能来自窗口之外。
     */
    private fun hitEditText(rawX: Float, rawY: Float): Boolean {
        if (editText.width == 0 || editText.height == 0) return false
        val loc = IntArray(2)
        editText.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX < loc[0] + editText.width &&
            rawY >= loc[1] && rawY < loc[1] + editText.height
    }

    private fun sendIfPossible() {
        if (isSending) return
        val text = editText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        onSend(text)
    }

    /**
     * 依据输入框是否为空刷新发送钮的可用态与配色，与主页 ChatInputBar 一致：
     * 可用 = 实色 primary 圆 + onPrimary 图标；禁用 = 40% 透明。
     */
    private fun refreshSendButton() {
        val hasText = !editText.text.isNullOrBlank()
        sendButton.isEnabled = hasText
        val alpha = if (hasText) 1f else 0.4f
        sendButton.background = oval(withAlpha(palette.primary, alpha))
        // 用 imageTintList（SRC_IN 准确上色）而非 setColorFilter（SRC_ATOP 相乘发灰）
        sendButton.imageTintList = ColorStateList.valueOf(withAlpha(palette.onPrimary, alpha))
    }

    /** 颜色整体缩放 alpha（0~1），用于禁用态配色。 */
    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (android.graphics.Color.alpha(color) * alpha).roundToInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setShape(GradientDrawable.OVAL)
            cornerRadius = dp(SEND_BUTTON_DP).toFloat() / 2f
        }

    private fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    private fun Int.withAlpha(a: Int): Int = this and 0x00FFFFFF or (a shl 24)
}
