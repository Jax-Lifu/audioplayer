package com.qytech.audioplayer.parser

import java.io.RandomAccessFile
import java.nio.ByteBuffer

// 文件读取器类，负责管理文件读取
class AudioFileReader(val filePath: String) : AutoCloseable {
    companion object {
        const val DEFAULT_BUFFER_SIZE = 2048
    }

    private val file = RandomAccessFile(filePath, "r").apply {
        require(fd != null && channel != null)
    }


    val fileSize = file.length()
    private var currentOffset = 0L
    private var buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)


    /**
     * 从绝对偏移量或相对偏移量开始读取数据。
     * @param absoluteOffset 绝对偏移量（文件的固定位置），如果为 null，则使用相对偏移量。
     * @param relativeOffset 相对偏移量，如果为 null，则使用绝对偏移量。
     * @return 读取到的数据缓冲区，或 null 如果超出文件大小。
     */
    fun readBuffer(absoluteOffset: Long? = null, relativeOffset: Long? = null): ByteBuffer? {
        // 计算最终的读取位置
        val startPosition = when {
            absoluteOffset != null -> absoluteOffset        // 如果指定了绝对偏移量，则从该位置开始读取
            relativeOffset != null -> currentOffset + relativeOffset  // 使用相对偏移量
            else -> currentOffset          // 否则使用当前的偏移量
        }

        return readData(startPosition)
    }

    /**
     * 从指定位置读取数据。
     * @param position 文件中的绝对位置。
     * @return 读取到的数据缓冲区，或 null 如果超出文件大小。
     */
    private fun readData(position: Long): ByteBuffer? {
        currentOffset = position
        // Timber.d("readData currentOffset: ${currentOffset / 2048}")
        if (currentOffset >= fileSize) {
            return null
        }

        buffer.clear()
        val bytesRead = file.channel.read(buffer, currentOffset)

        // 如果没有读到任何数据或读取失败，则返回 null
        if (bytesRead < 0) {
            return null
        }

        buffer.flip()
        currentOffset += bytesRead
        return buffer
    }

    /**
     * 重置缓冲区大小。
     * @param newSize 新的缓冲区大小（字节）。
     */
    fun resetBufferSize(newSize: Int) {
        require(newSize > 0) { "Buffer size must be greater than 0." }
        buffer = ByteBuffer.allocate(newSize)
    }

    // 显式关闭文件
    override fun close() {
        file.close()
    }
}
