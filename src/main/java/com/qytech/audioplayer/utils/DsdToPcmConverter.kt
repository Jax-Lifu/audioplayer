package com.qytech.audioplayer.utils

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DSD交错(LRLR...) 转 16bit PCM交错(LPCM)
 * 支持DSD64 / DSD128 / DSD256 / DSD512（通过 oversampleRatio 调整）
 */
object DsdInterleavedToPcm {

    // 默认参数
    private const val DEFAULT_GAIN = 0.8f           // 音量增益，避免爆音
    private const val DEFAULT_OVERSAMPLE_RATIO = 64 // DSD64
    private const val DEFAULT_FILTER_TAPS = 256     // FIR滤波器长度

    /**
     * 入口方法
     */
    fun convert(
        dsdData: ByteArray,
        oversampleRatio: Int = DEFAULT_OVERSAMPLE_RATIO,
        gain: Float = DEFAULT_GAIN,
    ): ByteArray {
        // 1. 拆分左右声道的DSD数据
        val (leftDsd, rightDsd) = splitDsdInterleaved(dsdData)

        // 2. 分别转成PCM ShortArray
        val leftPcm = dsdToPcmFIR(leftDsd, oversampleRatio, gain)
        val rightPcm = dsdToPcmFIR(rightDsd, oversampleRatio, gain)

        // 3. 合并成交错PCM字节
        return interleavePcm(leftPcm, rightPcm)
    }

    /**
     * 拆分交错的DSD数据 (LRLR...) -> (L[], R[])
     */
    private fun splitDsdInterleaved(dsdData: ByteArray): Pair<ByteArray, ByteArray> {
        val left = ByteArray(dsdData.size / 2)
        val right = ByteArray(dsdData.size / 2)

        var li = 0
        var ri = 0
        for (i in dsdData.indices step 2) {
            left[li++] = dsdData[i]
            right[ri++] = dsdData[i + 1]
        }
        return left to right
    }

    fun getFirTaps(oversampleRatio: Int): Int {
        return when (oversampleRatio) {
            64 -> 256
            128 -> 512
            256 -> 1024
            512 -> 2048
            else -> 256
        }
    }

    fun getCutoffFreq(oversampleRatio: Int): Double {
        return 0.45 / oversampleRatio
    }

    /**
     * 将DSD(1bit打包成8bit) 转 PCM(16bit)
     * 使用FIR低通滤波 + 降采样
     */
    private fun dsdToPcmFIR(
        dsd: ByteArray,
        oversampleRatio: Int,
        gain: Float,
    ): ShortArray {
        val firTaps = getFirTaps(oversampleRatio)
        val cutoff = getCutoffFreq(oversampleRatio)
        val fir = designLowPassFIR(firTaps, cutoff)
        val pcmList = ArrayList<Short>()
        val buf = DoubleArray(firTaps)

        var bufIndex = 0
        var bitMask = 0x80
        var currentByteIndex = 0
        var currentByte = dsd[0].toInt() and 0xFF

        var sampleCounter = 0

        val totalBits = dsd.size * 8
        for (bitIndex in 0 until totalBits) {
            // 取当前bit并转成 +1.0 / -1.0
            val bit = if ((currentByte and bitMask) != 0) 1.0 else -1.0
            buf[bufIndex] = bit
            bufIndex = (bufIndex + 1) % firTaps

            // 移动bit mask
            bitMask = bitMask shr 1
            if (bitMask == 0) {
                bitMask = 0x80
                currentByteIndex++
                if (currentByteIndex < dsd.size) {
                    currentByte = dsd[currentByteIndex].toInt() and 0xFF
                }
            }

            // 降采样：每 oversampleRatio 次采样取一个
            sampleCounter++
            if (sampleCounter == oversampleRatio) {
                sampleCounter = 0
                // FIR卷积
                var acc = 0.0
                var tapIndex = bufIndex
                for (j in 0 until firTaps) {
                    acc += fir[j] * buf[tapIndex]
                    tapIndex = (tapIndex + 1) % firTaps
                }
                val s = (acc * gain * Short.MAX_VALUE).toInt()
                pcmList.add(s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }

        return pcmList.toShortArray()
    }

    /**
     * 合并左右声道PCM成交错字节
     */
    private fun interleavePcm(left: ShortArray, right: ShortArray): ByteArray {
        val pcmBytes = ByteArray(left.size * 4)
        var idx = 0
        for (i in left.indices) {
            val l = left[i].toInt()
            val r = right[i].toInt()
            pcmBytes[idx++] = (l and 0xFF).toByte()
            pcmBytes[idx++] = ((l shr 8) and 0xFF).toByte()
            pcmBytes[idx++] = (r and 0xFF).toByte()
            pcmBytes[idx++] = ((r shr 8) and 0xFF).toByte()
        }
        return pcmBytes
    }

    /**
     * 设计FIR低通滤波器
     * Hamming窗
     */
    private fun designLowPassFIR(taps: Int, cutoff: Double): DoubleArray {
        val coeffs = DoubleArray(taps)
        val m = taps - 1
        for (i in 0 until taps) {
            val n = i - m / 2.0
            val sinc = if (n == 0.0) {
                2.0 * cutoff
            } else {
                sin(2.0 * PI * cutoff * n) / (PI * n)
            }
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / m)
            coeffs[i] = sinc * window
        }
        return coeffs
    }
}
