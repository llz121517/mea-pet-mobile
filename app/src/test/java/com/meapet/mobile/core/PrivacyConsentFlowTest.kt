package com.meapet.mobile.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [booleanPreferenceFlow] 的纯逻辑测试（不触碰 Android SharedPreferences）。
 *
 * 覆盖 `agreedFlow` 桥接协议：注册监听 → 先发当前值 → 变化时再发 → 相同值不重复发
 * → 订阅取消时注销监听。SharedPreferences 本身的读写只能在真机/仪器测试验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyConsentFlowTest {

    @Test
    fun `先发当前值再发变化且取消订阅时注销监听`() = runTest {
        var agreed = false
        var notifyChange: (() -> Unit)? = null
        var registered = 0
        var unregistered = 0

        val flow = booleanPreferenceFlow(
            read = { agreed },
            register = { onChange ->
                registered++
                notifyChange = onChange
                AutoCloseable { unregistered++ }
            }
        )

        val received = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received.add(it) }
        }
        runCurrent()

        assertEquals("订阅时应立刻拿到当前值", listOf(false), received)
        assertEquals("只注册一次监听", 1, registered)

        agreed = true
        notifyChange?.invoke()
        runCurrent()
        assertEquals(listOf(false, true), received)

        // 监听到与本值相同的变化（如其他代码重复 commit 同一值）不应造成重复发射
        notifyChange?.invoke()
        runCurrent()
        assertEquals("相同值不应重复发射", listOf(false, true), received)

        job.cancelAndJoin()
        assertEquals("取消订阅必须注销监听", 1, unregistered)
    }

    @Test
    fun `注册后立刻变化不会漏事件`() = runTest {
        var agreed = false
        var notifyChange: (() -> Unit)? = null

        val flow = booleanPreferenceFlow(
            read = { agreed },
            register = { onChange ->
                notifyChange = onChange
                // 模拟「注册监听」与「读取初值」之间发生的写入
                agreed = true
                onChange()
                AutoCloseable { }
            }
        )

        val received = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received.add(it) }
        }
        runCurrent()

        assertEquals("初值应是最新值且不重复", listOf(true), received)
        notifyChange?.invoke()
        runCurrent()
        assertEquals(listOf(true), received)

        job.cancelAndJoin()
    }
}
