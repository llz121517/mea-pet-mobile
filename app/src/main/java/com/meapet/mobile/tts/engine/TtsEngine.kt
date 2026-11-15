package com.meapet.mobile.tts.engine

import com.k2fsa.sherpa.onnx.GeneratedAudio

/**
 * TTS 合成引擎抽象：一段文本 → 逐句流式波形。
 *
 * 单独抽接口是为了让 [com.meapet.mobile.tts.TtsManager] 能在 JVM 单测里替换掉原生实现。
 * 实现必须保证：合成失败抛异常（由上层兜底）；允许产出 0 个分块（空音频，上层静默跳过）。
 */
interface TtsEngine {
    /**
     * 流式合成：每完成一句就回调该句波形（sherpa 内部按句切分、逐句串行合成）。
     *
     * 回调到达即应开始/继续播放（由上层写入 AudioTrack），因此首块延迟 = 首句合成时间，
     * 不必等整段合成完。回调返回 `false` 表示立即中止后续合成（被打断 / 已被新 speak 取代）；
     * 中止后实现应尽快从原生推理返回。
     *
     * @param text 已完成归一化的文本（拉丁已小写、日语片段已改写假名）
     * @param speed 语速 = 1 / lengthScale，>1 更快
     * @param onChunk 每句回调；返回 false = 中止
     * @return true = 全部分块已产出；false = 被回调中止
     */
    fun synthesizeStreaming(
        text: String,
        speed: Float,
        onChunk: (GeneratedAudio) -> Boolean,
    ): Boolean

    /** 释放原生资源（删除模型前调用；之后可再次懒加载）。 */
    fun release()
}
