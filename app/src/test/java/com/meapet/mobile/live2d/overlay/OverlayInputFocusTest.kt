package com.meapet.mobile.live2d.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayInputFocus] 的 flag 组合单测。
 *
 * 这组 flag 决定了悬浮输入条会不会一直占着焦点、能不能弹出键盘，
 * 而这两件事都只能在真机上观察，出错的代价很高——用测试把不变量钉住。
 */
class OverlayInputFocusTest {

    @Test
    fun releasedFlagsCannotBecomeImeTarget() {
        assertFalse(OverlayInputFocus.isImeCapable(OverlayInputFocus.flagsFor(engaged = false)))
    }

    @Test
    fun engagedFlagsCanBecomeImeTarget() {
        assertTrue(OverlayInputFocus.isImeCapable(OverlayInputFocus.flagsFor(engaged = true)))
    }

    @Test
    fun bothBlockingBitsAreSetWhenReleased() {
        val flags = OverlayInputFocus.flagsFor(engaged = false)
        assertTrue(flags and OverlayInputFocus.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and OverlayInputFocus.FLAG_ALT_FOCUSABLE_IM != 0)
    }

    @Test
    fun bothBlockingBitsAreClearedWhenEngaged() {
        val flags = OverlayInputFocus.flagsFor(engaged = true)
        assertEquals(0, flags and OverlayInputFocus.FLAG_NOT_FOCUSABLE)
        assertEquals(0, flags and OverlayInputFocus.FLAG_ALT_FOCUSABLE_IM)
    }

    /** 两种状态下都必须保留窗外触摸通报，否则「用户点了别处」这个信号会断掉。 */
    @Test
    fun outsideTouchWatchSurvivesBothStates() {
        assertTrue(OverlayInputFocus.canWatchOutsideTouch(OverlayInputFocus.flagsFor(engaged = false)))
        assertTrue(OverlayInputFocus.canWatchOutsideTouch(OverlayInputFocus.flagsFor(engaged = true)))
    }

    /** 同一次状态导出必须稳定，否则 updateViewLayout 的去重会失效、每次都重排。 */
    @Test
    fun flagsForIsStableAcrossCalls() {
        assertEquals(OverlayInputFocus.flagsFor(engaged = true), OverlayInputFocus.flagsFor(engaged = true))
        assertEquals(OverlayInputFocus.flagsFor(engaged = false), OverlayInputFocus.flagsFor(engaged = false))
    }

    @Test
    fun engagedAndReleasedFlagsDiffer() {
        assertTrue(OverlayInputFocus.flagsFor(engaged = true) != OverlayInputFocus.flagsFor(engaged = false))
    }

    /**
     * 位常量按字面量复制自 `WindowManager.LayoutParams`，本类刻意不引用框架符号。
     * 这条断言守着两者不分叉——一旦分叉，单测会全绿而真机全错。
     */
    @Test
    fun bitConstantsMatchFrameworkValues() {
        assertEquals(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, OverlayInputFocus.FLAG_NOT_FOCUSABLE)
        assertEquals(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, OverlayInputFocus.FLAG_NOT_TOUCH_MODAL)
        assertEquals(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, OverlayInputFocus.FLAG_ALT_FOCUSABLE_IM)
        assertEquals(
            android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            OverlayInputFocus.FLAG_WATCH_OUTSIDE_TOUCH,
        )
    }

    @Test
    fun baseFlagsCarryTouchModalityNotImeBits() {
        assertEquals(
            OverlayInputFocus.FLAG_NOT_TOUCH_MODAL or OverlayInputFocus.FLAG_WATCH_OUTSIDE_TOUCH,
            OverlayInputFocus.BASE,
        )
    }
}
