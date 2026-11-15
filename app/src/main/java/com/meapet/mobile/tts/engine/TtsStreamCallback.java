package com.meapet.mobile.tts.engine;

import kotlin.jvm.functions.Function1;

/**
 * sherpa-onnx 逐句合成回调的 Java 适配器（**必须用 Java 写，不要换成 Kotlin lambda**）。
 *
 * <p>原因：sherpa JNI 用 {@code GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")}
 * 取回调方法（可用 {@code strings libsherpa-onnx-jni.so} 复核该签名字符串）。
 * Kotlin 2.x 的 indy lambda 经 D8 脱糖后只有 {@code invoke(Object): Object} 这个擦除方法，
 * 没有"参数 float[]、返回装箱 Integer"的专用签名；JNI 拿到 null methodID 后
 * 带 pending exception 继续调 {@code NewFloatArray} → CheckJNI SIGABRT（真机 2026-11-15 崩溃）。
 *
 * <p>本类按泛型实参实现 {@code Function1<float[], Integer>}：javac 会生成真正的
 * {@code invoke(float[]): Integer} 方法，并自动补 `invoke(Object): Object` 桥接方法，
 * 两个签名都能被满足。
 *
 * <p>签名语义：返回 1 = 继续合成下一句，返回 0 = 中止剩余句子（打断/抢占时用）。
 * 保留规则：`proguard-rules.pro` 的 {@code -keep class com.meapet.mobile.tts.** { *; }}
 * 已覆盖本类，R8 不会删改这些方法。
 */
public final class TtsStreamCallback implements Function1<float[], Integer> {

    /** Kotlin 侧回调：true = 继续，false = 中止。 */
    private final Function1<? super float[], Boolean> onSamples;

    public TtsStreamCallback(Function1<? super float[], Boolean> onSamples) {
        this.onSamples = onSamples;
    }

    /** JNI 按 "([F)Ljava/lang/Integer;" 查的就是它；桥接方法由 javac 自动生成。 */
    @Override
    public Integer invoke(float[] samples) {
        return onSamples.invoke(samples) ? 1 : 0;
    }
}
