package com.meapet.mobile.live2d

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live2D 原生核心可用性探测。
 *
 * 真机之外没法让 `System.loadLibrary` 可控失败，因此探测逻辑接受一个加载动作参数，
 * 用注入的 Lambda 覆盖「本 ABI 没有 .so」这条分支——它正是 32 位 ARM 上的实际情形
 * （Cubism Core AAR 只含 arm64-v8a / x86 / x86_64）。
 */
class Live2dSupportTest {

    @Test
    fun `加载成功时判定可用`() {
        var loaded = 0
        val available = Live2dSupport.probe { loaded++ }

        assertTrue(available)
        assertTrue(loaded == 1)
    }

    /** 32 位 ARM 的真实失败形态：找不到对应 ABI 的 .so。 */
    @Test
    fun `UnsatisfiedLinkError 判定为不可用且不上抛`() {
        val available = Live2dSupport.probe {
            throw UnsatisfiedLinkError("dlopen failed: library \"libLive2DCubismCoreJNI.so\" not found")
        }

        assertFalse(available)
    }

    /** 其它链接错误（依赖缺失、架构不匹配等）同样只降级，不让启动失败。 */
    @Test
    fun `其它 LinkageError 也判定为不可用`() {
        assertFalse(Live2dSupport.probe { throw NoClassDefFoundError("core") })
    }

    /** 少数 ROM 以安全异常拒绝 dlopen。 */
    @Test
    fun `SecurityException 判定为不可用`() {
        assertFalse(Live2dSupport.probe { throw SecurityException("dlopen blocked") })
    }

    /** 非链接类异常不应被吞掉：那是代码 bug，应当暴露。 */
    @Test(expected = IllegalStateException::class)
    fun `普通异常继续上抛`() {
        Live2dSupport.probe { throw IllegalStateException("unexpected") }
    }
}
