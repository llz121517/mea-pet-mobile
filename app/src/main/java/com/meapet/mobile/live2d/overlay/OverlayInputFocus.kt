package com.meapet.mobile.live2d.overlay

/**
 * 悬浮输入条的焦点 / IME 窗口 flag 计算。
 *
 * 纯逻辑：零 `android.*` 依赖，位常量按
 * [android.view.WindowManager.LayoutParams] 的取值字面量复制，便于 JVM 单测。
 *
 * 之所以单独抽出来：这个窗口是全项目**唯一**需要 IME 的悬浮窗，它的 flag 组合
 * 决定了三件事，任何一处写错都会以「其他应用拉不起键盘」「键盘闪断」这类
 * 难以复现的形式表现出来，值得被测试钉住：
 *
 * 1. **窗外触摸穿透**（[FLAG_NOT_TOUCH_MODAL]）：窗口可聚焦时，窗外的指针事件
 *    仍然下发给下层应用，否者本窗口会把整屏触摸都吃掉。
 * 2. **窗外触摸通报**（[FLAG_WATCH_OUTSIDE_TOUCH]）：配合上一条，把窗外那一下
 *    触摸以 `ACTION_OUTSIDE` 单发通报给本窗口——这是「用户把注意力移开了」的
 *    **直接**信号，不依赖 WM 的焦点转移回调。
 * 3. **IME 资格**（[FLAG_NOT_FOCUSABLE] 与 [FLAG_ALT_FOCUSABLE_IM]
 *    必须同时为 0）：任一置位，窗口都无法成为输入法目标，见 [isImeCapable]。
 */
internal object OverlayInputFocus {

    /** 窗口不可获得焦点，也无法与输入法交互，且在 Z 序上盖住输入法。 */
    const val FLAG_NOT_FOCUSABLE = 0x00000008

    /** 窗外指针事件下发给下层应用。 */
    const val FLAG_NOT_TOUCH_MODAL = 0x00000020

    /** 反转输入法可聚焦性；窗口可聚焦时置位会让它无法成为输入法目标。 */
    const val FLAG_ALT_FOCUSABLE_IM = 0x00020000

    /**
     * 窗外触摸以 `ACTION_OUTSIDE` 通报给本窗口（需配合
     * [FLAG_NOT_TOUCH_MODAL]）。
     */
    const val FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000

    /** 常驻 flag：两个方向都必须带着，否则要么吃掉触摸，要么漏掉窗外信号。 */
    const val BASE = FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH

    /** 让窗口丧失输入法资格的两位；增删必须成对，缺一不可。 */
    private const val IME_BLOCKING = FLAG_NOT_FOCUSABLE or FLAG_ALT_FOCUSABLE_IM

    /**
     * 按「用户是否正在输入」导出窗口 flag。
     *
     * 不可聚焦是**默认**状态：本窗口只在用户真的要点输入框时才临时取得 IME
     * 资格，一旦用户移开注意力就立刻归还，否则它会一直占着 display 的焦点窗口，
     * 让下层应用既拉不起键盘、也收不到返回键。
     */
    fun flagsFor(engaged: Boolean): Int =
        if (engaged) BASE and IME_BLOCKING.inv() else BASE or IME_BLOCKING

    /** 该 flag 组合下窗口能否成为输入法目标。 */
    fun isImeCapable(flags: Int): Boolean = (flags and IME_BLOCKING) == 0

    /** 该 flag 组合下是否仍能收到窗外触摸通报（任一状态下都必须成立）。 */
    fun canWatchOutsideTouch(flags: Int): Boolean =
        (flags and FLAG_NOT_TOUCH_MODAL) != 0 && (flags and FLAG_WATCH_OUTSIDE_TOUCH) != 0
}
