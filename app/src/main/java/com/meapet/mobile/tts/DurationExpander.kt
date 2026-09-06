package com.meapet.mobile.tts

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.random.Random

/**
 * VITS 推理的纯数值胶水逻辑（不依赖 ONNX）。
 *
 * 逐行移植自 `mea_vits_inference.py` 的步骤 3~5：
 * 时长展开（dp 的 logw → 对齐路径）→ 先验展开（attn @ m_p）→ SDP 随机采样（z_p）。
 *
 * 全部为 [batch=1, channel, time] 的小矩阵运算，Kotlin 实现即可，无需 BLAS。
 */
object DurationExpander {

    /**
     * 单个音素展开帧数上限。dp 模型输出异常值时 `exp(logw)` 可能溢出为极大数，
     * 不钳制会导致 `yLengths` 求和后 Int 溢出变负（NegativeArraySizeException）或 OOM。
     * 22050Hz 下 50 帧 ≈ 0.5 秒，足以覆盖任何正常音素时长。
     */
    private const val MAX_FRAMES_PER_PHONE = 50

    /**
     * attn 矩阵（yLengths × tX 个 Float）的内存上限，超出即截断对齐路径。
     *
     * 按内存封顶而非固定帧数：原先的 `MAX_TOTAL_FRAMES = 480_000` 在上游 200 字截断
     * （`TtsManager.MAX_SYNTH_CHARS`）下永远触发不到——最坏是逐音素全被钳到
     * [MAX_FRAMES_PER_PHONE]，50 × 约 3600 音素 ≈ 180000 帧 < 480000，等于没有护栏；
     * 那条注释「约等于 22 秒音频」也算错了：480000 帧 × 256 采样 / 22050Hz ≈ 93 分钟。
     *
     * 而它真正要拦的就是 dp 输出异常、逐音素全被钳满的那条路径（此时音频本就是废的，
     * 早截无损失，旧值下 attn 可达 2GB 量级）。按内存封顶与文本长度无关：
     * 正常语音（200 字、语速 0.5~2.0，tX 约 1800）对应上限约 9300 帧 ≈ 108 秒，不会被截。
     */
    private const val MAX_ATTENTION_BYTES = 64L * 1024 * 1024

    /** attn 元素类型为 Float。 */
    private const val BYTES_PER_FLOAT = 4L

    /**
     * 步骤 3：时长展开 + 注意力路径。
     *
     * Python 原貌：
     * ```
     * w = exp(logw) * x_mask * length_scale
     * w_ceil = ceil(w)
     * y_lengths = max(sum(w_ceil), 1)
     * cum_duration = cumsum(w_ceil)
     * path[i, cum_duration[i-1] : cum_duration[i]] = 1   # 逐音素单调对齐
     * path *= x_mask
     * attn = path.T                                       # [y_lengths, t_x]
     * ```
     *
     * @param logw dp 输出的对数时长，形状 [1,1,t_x] 拉平为 [t_x]
     * @param xMask enc_p 输出的掩码，[t_x]（0/1）
     * @param lengthScale 语速（>1 变慢，<1 变快）
     * @return attn 矩阵 [yLengths][tX] 与 yLengths
     */
    fun buildAttention(
        logw: FloatArray,
        xMask: FloatArray,
        lengthScale: Float
    ): AttentionResult {
        val tX = logw.size
        // w = exp(logw) * x_mask * length_scale，再取 ceil。
        // 逐音素钳制到上限：exp 溢出为 Infinity 时 toInt() 得 Int.MAX_VALUE，
        // 必须先钳到有限值，否则求和后 Int 溢出变负（NegativeArraySizeException）。
        val wCeil = IntArray(tX) { i ->
            val w = exp(logw[i].toDouble()) * xMask[i] * lengthScale
            when {
                w.isNaN() || w <= 0.0 -> 0
                w >= MAX_FRAMES_PER_PHONE -> MAX_FRAMES_PER_PHONE
                else -> ceil(w).toInt()
            }
        }

        // 累加总帧数，按 attn 矩阵内存上限截断（防 OOM，见 MAX_ATTENTION_BYTES）
        val maxFrames = (MAX_ATTENTION_BYTES / (tX.coerceAtLeast(1) * BYTES_PER_FLOAT))
            .coerceAtLeast(1L)
            .toInt()
        var yLengths = 0
        for (v in wCeil) {
            yLengths += v
            if (yLengths >= maxFrames) {
                yLengths = maxFrames
                break
            }
        }
        if (yLengths < 1) yLengths = 1

        // cumsum 得累计时长，逐音素把 [start:end) 置 1 构造单调对齐路径
        // path[tX][yLengths]，转置后 attn[yLengths][tX]
        val attn = Array(yLengths) { FloatArray(tX) }
        var start = 0
        for (i in 0 until tX) {
            val end = minOf(start + wCeil[i], yLengths)
            if (start < end && xMask[i] > 0f) {
                for (j in start until end) {
                    attn[j][i] = 1f
                }
            }
            start += wCeil[i]
            if (start >= yLengths) break   // 已达总帧数上限，剩余音素不再展开
        }
        return AttentionResult(attn, yLengths)
    }

    /**
     * 步骤 4：先验展开。`m_p_exp = attn @ m_p.T`，等价于按对齐路径把每个音素的
     * 192 维向量复制到它占据的若干帧上。
     *
     * @param attn [yLengths][tX]（buildAttention 的输出）
     * @param prior enc_p 输出的 m_p 或 logs_p，[channel=192][tX]
     * @return 展开后的 [channel][yLengths]
     */
    fun expandPrior(attn: Array<FloatArray>, prior: Array<FloatArray>): Array<FloatArray> {
        val yLengths = attn.size
        val tX = prior[0].size
        val channels = prior.size
        val out = Array(channels) { FloatArray(yLengths) }
        // attn[y][i]=1 表示第 y 帧来自第 i 个音素 → out[c][y] = prior[c][i]
        for (y in 0 until yLengths) {
            val row = attn[y]
            for (i in 0 until tX) {
                if (row[i] != 0f) {
                    for (c in 0 until channels) {
                        out[c][y] = prior[c][i]
                    }
                    break  // 每帧只对应一个音素，命中即停
                }
            }
        }
        return out
    }

    /**
     * 步骤 5：SDP 随机采样 `z_p = m_p_exp + randn * noise_scale * exp(logs_p_exp) * y_mask`。
     *
     * @param mPExp 展开后的先验均值 [channel][yLengths]
     * @param logsPExp 展开后的先验对数方差 [channel][yLengths]
     * @param noiseScale 音高随机性（默认 0.667）
     * @param random 可注入种子以复现（测试用）
     * @return z_p，[channel][yLengths]（y_mask 全 1，省略乘法）
     */
    fun sampleZp(
        mPExp: Array<FloatArray>,
        logsPExp: Array<FloatArray>,
        noiseScale: Float,
        random: Random = Random.Default
    ): Array<FloatArray> {
        val channels = mPExp.size
        val yLengths = mPExp[0].size
        val out = Array(channels) { FloatArray(yLengths) }
        for (c in 0 until channels) {
            val m = mPExp[c]
            val lp = logsPExp[c]
            val o = out[c]
            for (y in 0 until yLengths) {
                val noise = random.nextGaussian().toFloat() * noiseScale
                o[y] = m[y] + noise * exp(lp[y].toDouble()).toFloat()
            }
        }
        return out
    }

    /** 高斯采样（Box-Muller），kotlin.random 无内置 nextGaussian。 */
    private fun Random.nextGaussian(): Double {
        var u: Double
        var v: Double
        var s: Double
        do {
            u = nextDouble() * 2 - 1
            v = nextDouble() * 2 - 1
            s = u * u + v * v
        } while (s >= 1 || s == 0.0)
        val mul = kotlin.math.sqrt(-2.0 * kotlin.math.ln(s) / s)
        return u * mul
    }

    data class AttentionResult(val attn: Array<FloatArray>, val yLengths: Int)
}
