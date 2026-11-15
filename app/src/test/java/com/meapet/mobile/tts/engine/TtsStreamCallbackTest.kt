package com.meapet.mobile.tts.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归护栏：sherpa-onnx JNI 用 `GetMethodID(callbackClass, "invoke", "([F)Ljava/lang/Integer;")`
 * 取回调方法（`.so` 里的字符串就是这个签名）。Kotlin 2.x 的 indy lambda 脱糖后只有
 * `invoke(Object): Object`，没有这个"参数 float[]、返回装箱 Integer"的专用桥接方法，
 * 于是 JNI 拿到 null methodID 后带 pending exception 调 `NewFloatArray` → CheckJNI SIGABRT。
 * 必须走 Java 适配器 [TtsStreamCallback]；这里断言那个方法确实存在、返回装箱 Integer。
 */
class TtsStreamCallbackTest {

    @Test
    fun `JNI 查找的 invoke 方法存在且返回装箱 Integer`() {
        val method = TtsStreamCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java, method.returnType)
    }

    @Test
    fun `回调返回 true 映射为 1 继续、false 映射为 0 中止`() {
        assertEquals(1, TtsStreamCallback { true }.invoke(floatArrayOf(0.1f)).toInt())
        assertEquals(0, TtsStreamCallback { false }.invoke(floatArrayOf(0.1f)).toInt())
    }

    @Test
    fun `接口擦除入口 invoke Object 同样转发`() {
        val callback: Function1<FloatArray, Int> = TtsStreamCallback { true }
        assertEquals(1, callback.invoke(floatArrayOf(0.1f)))
    }
}
