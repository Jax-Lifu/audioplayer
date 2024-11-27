package com.qytech.audioplayer.parser

import com.qytech.audioplayer.extension.getAudioCodec
import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.model.AudioFileHeader
import com.qytech.audioplayer.model.AudioFileInfo
import com.qytech.audioplayer.model.AudioOffsetInfo
import com.qytech.audioplayer.utils.AudioUtils
import java.nio.ByteOrder

class DsfAudioFileParser(filePath: String) : StandardAudioFileParser(filePath) {

    companion object {
        const val ENCODING_TYPE_DSF = "DSF"
    }

    override fun parse(): AudioFileInfo? {
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
        val fmtChunkSize = buffer.long
        val version = buffer.int
        val formatId = buffer.int
        val channelType = buffer.int
        val channelCount = buffer.int
        val sampleRate = buffer.int
        val bitsPerSample = buffer.int
        val sampleCountPerChannel = buffer.long
        val blockSizePerChannel = buffer.int
        buffer.int // 跳过保留字段

        // 验证数据标识符
        if (buffer.getString() != BLOCK_ID_DATA) return super.parse()
        val dataSize = buffer.long

        val startOffset = buffer.position().toLong()
        val endOffset = startOffset + dataSize

        // 计算音频文件的关键属性
        val blockAlign = AudioUtils.getBlockAlign(channelCount, bitsPerSample)
        val byteRate = AudioUtils.getByteRate(sampleRate, channelCount, bitsPerSample)
        val bitsPerSecond = AudioUtils.getBitsPerSecond(sampleRate, channelCount, bitsPerSample)
        val codec = sampleRate.getAudioCodec()

        // 构造文件头信息
        val header = AudioFileHeader(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            bitsPerSecond = bitsPerSecond,
            byteRate = byteRate,
            blockAlign = blockAlign,
            encodingType = ENCODING_TYPE_DSF,
            codec = codec
        )
        val offset = AudioOffsetInfo(
            startOffset = startOffset,
            endOffset = endOffset,
            dataLength = dataSize
        )

        // 返回解析后的 AudioFileInfo
        return super.parse()?.let { audioFileInfo ->
            audioFileInfo.copy(
                header = header,
                trackInfo = audioFileInfo.trackInfo.map { track -> track?.copy(offset = offset) }
            )
        }
    }
}
