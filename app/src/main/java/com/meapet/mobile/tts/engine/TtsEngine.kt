package com.meapet.mobile.tts.engine

import com.k2fsa.sherpa.onnx.GeneratedAudio

/**
 * TTS 合成引擎抽象：一段文本 → 一段波形。
 *
 * 单独抽接口是为了让 [com.meapet.mobile.tts.TtsManager] 能在 JVM 单测里替换掉原生实现。
 * 实现必须保证：合成失败抛异常（由上层兜底），返回的 [GeneratedAudio.samples] 允许为空。
 */
interface TtsEngine {
    /**
     * @param text 已完成归一化的文本（拉丁已小写、日语片段已改写假名）
     * @param speed 语速 = 1 / lengthScale，>1 更快
     */
    fun synthesize(text: String, speed: Float): GeneratedAudio

    /** 释放原生资源（删除模型前调用；之后可再次懒加载）。 */
    fun release()
}
