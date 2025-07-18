package com.qytech.audioplayer.parser

import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.ffprobe.FFprobe
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.utils.AudioUtils
import com.qytech.core.extensions.toAudioCodec
import java.nio.ByteOrder

class DsfAudioFileParser(filePath: String) : StandardAudioFileParser(filePath) {

    companion object {
        const val ENCODING_TYPE_DSF = "DSF"
    }

    override suspend fun parse(): List<AudioInfo.Local>? {
        // 初始化缓冲区并设置为小端字节序
        val buffer =
            reader.readBuffer()?.apply { order(ByteOrder.LITTLE_ENDIAN) } ?: return super.parse()

        // 验证文件标识符和格式标识符
        if (buffer.getString() != HEADER_ID_DSF) return super.parse() // 验证是否为 DSF 文件
        buffer.long // 跳过 chunkSize
        buffer.long // 跳过 fileSize
        buffer.long // 跳过 元数据偏移
        if (buffer.getString() != BLOCK_ID_FMT) return super.parse() // 验证格式标识符

        // 解析格式块信息
        buffer.long
        buffer.int
        buffer.int
        buffer.int
        val channelCount = buffer.int
        val sampleRate = buffer.int
        val bitsPerSample = buffer.int
        buffer.long
        buffer.int
        buffer.int // 跳过保留字段

        // 验证数据标识符
        if (buffer.getString() != BLOCK_ID_DATA) return super.parse()
        val dataSize = buffer.long

        val startOffset = buffer.position().toLong()
        val endOffset = startOffset + dataSize

        // 计算音频文件的关键属性
        val bitRate = AudioUtils.getBitRate(sampleRate, channelCount, bitsPerSample)
        val codec = sampleRate.toAudioCodec()

        val fingerprint = FFprobe.getFingerprint(sourceId, 30)
        // 返回解析后的 AudioDetails
        return super.parse()?.map { audioDetails ->
            audioDetails.copy(
                codecName = codec,
                formatName = ENCODING_TYPE_DSF,
                sampleRate = sampleRate,
                channels = channelCount,
                bitPreSample = bitsPerSample,
                bitRate = bitRate,
                startOffset = startOffset,
                endOffset = endOffset,
                dataLength = dataSize,
                fingerprint = fingerprint
            )
        }
    }
}
