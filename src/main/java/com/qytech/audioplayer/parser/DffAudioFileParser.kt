package com.qytech.audioplayer.parser

import com.qytech.audioplayer.extension.getAudioCodec
import com.qytech.audioplayer.extension.getBigEndianUInt64
import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.extension.skip
import com.qytech.audioplayer.model.AudioFileHeader
import com.qytech.audioplayer.model.AudioFileInfo
import com.qytech.audioplayer.model.AudioOffsetInfo
import com.qytech.audioplayer.utils.AudioUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DffAudioFileParser(filePath: String) : StandardAudioFileParser(filePath) {
    companion object {
        const val HEADER_SIZE = 12
        const val CHANNEL_SIZE = 2
        const val ENCODING_TYPE_DFF = "DFF"
    }

    override fun parse(): List<AudioFileInfo>? {
        // 读取文件并确保缓冲区有效
        var buffer = reader.readBuffer()?.apply { order(ByteOrder.BIG_ENDIAN) } ?: return super.parse()

        // 验证文件头
        if (buffer.getString() != HEADER_ID_DFF) return super.parse()

        // 获取主块的大小并验证格式
        val chunkSize = buffer.getBigEndianUInt64()
        if (buffer.getString() != BLOCK_ID_FORMAT) return super.parse()

        var dataSize = 0L
        var startOffset = 0L
        var currentOffset = 16L  // 从文件头后开始
        var header: AudioFileHeader? = null

        // 解析文件内容并处理各个子块
        while (currentOffset < reader.fileSize) {
            val subId = buffer.getString()
            val subChunkSize = buffer.getBigEndianUInt64()

            when (subId) {
                BLOCK_ID_FORMAT -> {
                    dataSize = subChunkSize
                    startOffset = buffer.position().toLong()
                    break
                }
                BLOCK_ID_PROP -> header = readDffProperty(buffer, subChunkSize)
                else -> buffer.skip(subChunkSize.toInt())
            }

            // 更新偏移量并确保缓冲区内容充足
            currentOffset += subChunkSize + HEADER_SIZE
            if (!buffer.hasRemaining() && currentOffset < reader.fileSize) {
                buffer = reader.readBuffer(currentOffset) ?: break
            }
        }

        // 如果没有找到头部信息，返回默认解析结果
        if (header == null) return super.parse()

        val offset = AudioOffsetInfo(
            startOffset = startOffset,
            endOffset = startOffset + dataSize,
            dataLength = dataSize
        )

        // 调用父类解析，并对结果进行修改
        return super.parse()?.map { audioFileInfo ->
            audioFileInfo.copy(
                header = header,
                trackInfo = audioFileInfo.trackInfo.copy(offset = offset)
            )
        }
    }


    private fun readDffProperty(buffer: ByteBuffer, limit: Long): AudioFileHeader? {
        val startPosition = buffer.position()
        if (buffer.getString() != BLOCK_ID_SOUND) return null

        var sampleRate = 0
        var channelCount = 0

        while (buffer.position() - startPosition < limit) {
            val propertyId = buffer.getString()
            val propertyChunkSize = buffer.getBigEndianUInt64().toInt()

            when (propertyId) {
                BLOCK_ID_FS -> sampleRate = buffer.int
                BLOCK_ID_CHANNEL -> {
                    channelCount = buffer.short.toInt()
                    buffer.skip(propertyChunkSize - CHANNEL_SIZE)
                }

                else -> buffer.skip(propertyChunkSize)
            }
        }

        return createAudioFileHeader(sampleRate, channelCount)
    }

    private fun createAudioFileHeader(sampleRate: Int, channelCount: Int): AudioFileHeader {
        val blockAlign = AudioUtils.getBlockAlign(channelCount, DSD_BITS_PER_SAMPLE)
        val byteRate = AudioUtils.getByteRate(sampleRate, channelCount, DSD_BITS_PER_SAMPLE)
        val bitsPerSecond =
            AudioUtils.getBitsPerSecond(sampleRate, channelCount, DSD_BITS_PER_SAMPLE)
        val codec = sampleRate.getAudioCodec()

        return AudioFileHeader(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = DSD_BITS_PER_SAMPLE,
            bitsPerSecond = bitsPerSecond,
            byteRate = byteRate,
            blockAlign = blockAlign,
            codec = codec,
            encodingType = ENCODING_TYPE_DFF
        )
    }
}
