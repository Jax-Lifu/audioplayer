package com.qytech.audioplayer.utils

import android.os.Environment
import com.qytech.audioplayer.extension.calculateMd5
import timber.log.Timber
import java.io.File

/**
 * 音频工具类，用于计算音频相关参数。
 */
object AudioUtils {
    // 常量定义，1 字节 = 8 位
    private const val BYTES_PER_BIT = 8
    //- byteRate = sampleRate * channelCount * bitsPerSample / 8
    //- blockAlign = channelCount * bitsPerSample / 8.0f
    //- bitsPerSecond = sampleRate * channelCount * bitsPerSample
    /**
     * 计算字节率 (Byte Rate)。
     * @param sampleRate 采样率，单位为 Hz。
     * @param channelCount 声道数量，例如单声道为 1，立体声为 2。
     * @param bitsPerSample 每个采样点的比特数，例如 16 位为 16。
     * @return 字节率 (Bytes per second)。
     */
    fun getByteRate(sampleRate: Int, channelCount: Int, bitsPerSample: Int): Int =
        sampleRate * channelCount * bitsPerSample / BYTES_PER_BIT

    /**
     * 计算块对齐 (Block Align)。
     * @param channelCount 声道数量。
     * @param bitsPerSample 每个采样点的比特数。
     * @return 块对齐大小 (Block align)，单位为字节。
     */
    fun getBlockAlign(channelCount: Int, bitsPerSample: Int): Float =
        channelCount * (bitsPerSample.toFloat() / BYTES_PER_BIT)

    /**
     * 计算比特率 (Bits Per Second)。
     * @param sampleRate 采样率。
     * @param channelCount 声道数量。
     * @param bitsPerSample 每个采样点的比特数。
     * @return 比特率 (Bits per second)。
     */
    fun getBitsPerSecond(sampleRate: Int, channelCount: Int, bitsPerSample: Int): Int =
        sampleRate * channelCount * bitsPerSample

    fun getBitRate(sampleRate: Int, channelCount: Int, bitsPerSample: Int): Int =
        sampleRate * channelCount * bitsPerSample

    /**
     * 保存封面图片
     */
    fun saveCoverImage(artBytes: ByteArray): String? {
        if (artBytes.isEmpty()) return null

        val md5 = artBytes.calculateMd5()
        val dir = File(Environment.getExternalStorageDirectory().absolutePath, ".qytech/cover/")
        val outputFile = File(dir, "$md5.jpg")

        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                Timber.e("Failed to create directory for cover images")
                return@runCatching null
            }
            if (!outputFile.exists()) {
                outputFile.writeBytes(artBytes)
            }
            outputFile.absolutePath
        }.getOrElse {
            Timber.e(it, "Failed to save cover image")
            null
        }
    }
}
