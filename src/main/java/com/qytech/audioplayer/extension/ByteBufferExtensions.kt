package com.qytech.audioplayer.extension


import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest

// 预定义的字节反转表，用于比特位翻转
val byteReversalTable: ByteArray = arrayOf(
    0x00, 0x80, 0x40, 0xc0, 0x20, 0xa0, 0x60, 0xe0, 0x10, 0x90, 0x50, 0xd0, 0x30, 0xb0, 0x70, 0xf0,
    0x08, 0x88, 0x48, 0xc8, 0x28, 0xa8, 0x68, 0xe8, 0x18, 0x98, 0x58, 0xd8, 0x38, 0xb8, 0x78, 0xf8,
    0x04, 0x84, 0x44, 0xc4, 0x24, 0xa4, 0x64, 0xe4, 0x14, 0x94, 0x54, 0xd4, 0x34, 0xb4, 0x74, 0xf4,
    0x0c, 0x8c, 0x4c, 0xcc, 0x2c, 0xac, 0x6c, 0xec, 0x1c, 0x9c, 0x5c, 0xdc, 0x3c, 0xbc, 0x7c, 0xfc,
    0x02, 0x82, 0x42, 0xc2, 0x22, 0xa2, 0x62, 0xe2, 0x12, 0x92, 0x52, 0xd2, 0x32, 0xb2, 0x72, 0xf2,
    0x0a, 0x8a, 0x4a, 0xca, 0x2a, 0xaa, 0x6a, 0xea, 0x1a, 0x9a, 0x5a, 0xda, 0x3a, 0xba, 0x7a, 0xfa,
    0x06, 0x86, 0x46, 0xc6, 0x26, 0xa6, 0x66, 0xe6, 0x16, 0x96, 0x56, 0xd6, 0x36, 0xb6, 0x76, 0xf6,
    0x0e, 0x8e, 0x4e, 0xce, 0x2e, 0xae, 0x6e, 0xee, 0x1e, 0x9e, 0x5e, 0xde, 0x3e, 0xbe, 0x7e, 0xfe,
    0x01, 0x81, 0x41, 0xc1, 0x21, 0xa1, 0x61, 0xe1, 0x11, 0x91, 0x51, 0xd1, 0x31, 0xb1, 0x71, 0xf1,
    0x09, 0x89, 0x49, 0xc9, 0x29, 0xa9, 0x69, 0xe9, 0x19, 0x99, 0x59, 0xd9, 0x39, 0xb9, 0x79, 0xf9,
    0x05, 0x85, 0x45, 0xc5, 0x25, 0xa5, 0x65, 0xe5, 0x15, 0x95, 0x55, 0xd5, 0x35, 0xb5, 0x75, 0xf5,
    0x0d, 0x8d, 0x4d, 0xcd, 0x2d, 0xad, 0x6d, 0xed, 0x1d, 0x9d, 0x5d, 0xdd, 0x3d, 0xbd, 0x7d, 0xfd,
    0x03, 0x83, 0x43, 0xc3, 0x23, 0xa3, 0x63, 0xe3, 0x13, 0x93, 0x53, 0xd3, 0x33, 0xb3, 0x73, 0xf3,
    0x0b, 0x8b, 0x4b, 0xcb, 0x2b, 0xab, 0x6b, 0xeb, 0x1b, 0x9b, 0x5b, 0xdb, 0x3b, 0xbb, 0x7b, 0xfb,
    0x07, 0x87, 0x47, 0xc7, 0x27, 0xa7, 0x67, 0xe7, 0x17, 0x97, 0x57, 0xd7, 0x37, 0xb7, 0x77, 0xf7,
    0x0f, 0x8f, 0x4f, 0xcf, 0x2f, 0xaf, 0x6f, 0xef, 0x1f, 0x9f, 0x5f, 0xdf, 0x3f, 0xbf, 0x7f, 0xff,
).map { it.toByte() }.toByteArray()

/**
 * 获取指定范围的ByteBuffer切片
 *
 * @param start 开始位置
 * @param end 结束位置
 * @return 切片的ByteBuffer
 */
fun ByteBuffer.sliceOfRange(start: Int, end: Int): ByteBuffer {
    if (start < 0 || end > this.capacity() || start > end) {
        throw IllegalArgumentException("Invalid start or end position")
    }

    val originalPosition = this.position()
    val originalLimit = this.limit()

    return try {
        this.position(start)
        this.limit(end)
        val slicedBuffer = this.slice()
        slicedBuffer.order(this.order()) // 保持字节顺序一致
        slicedBuffer
    } finally {
        // 恢复原始缓冲区的状态
        this.position(originalPosition)
        this.limit(originalLimit)
    }
}

/**
 * 从当前ByteBuffer的位置获取指定长度的ByteBuffer
 *
 * @param length 获取的长度
 * @return 新的ByteBuffer切片
 */
fun ByteBuffer.getByteBuffer(length: Int): ByteBuffer {
    // Check if length is within the remaining bounds
    if (length > this.remaining()) {
        throw IllegalArgumentException("Length exceeds the remaining buffer size")
    }

    // Create a new ByteBuffer slice from the current position with the specified length
    val originalPosition = this.position()
    val slice = this.slice().limit(length) as ByteBuffer

    // Advance the position of the original ByteBuffer
    this.position(originalPosition + length)

    return slice
}

/**
 * 从当前ByteBuffer的位置获取指定长度的字符串
 *
 * @param length 获取的字符串长度，默认为4
 * @return 获取的字符串
 */
fun ByteBuffer.getString(length: Int = 4, charset: Charset = Charsets.UTF_8): String {
    if (this.remaining() < length) {
        return ""
    }
    val bytes = ByteArray(length)
    this.get(bytes)
    return String(bytes, charset)
}

/**
 * 从 ByteBuffer 中获取字符串，遇到0x00结束，同时丢弃后面的 00 直到遇到下一个不为 00的元素
 * */
fun ByteBuffer.getStringUntilNextNonNull(charset: Charset = Charsets.UTF_8): String {
    val bytes = mutableListOf<Byte>()
    var foundNull = false
    while (hasRemaining()) {
        val byte = get()

        if (byte == 0x00.toByte()) {
            foundNull = true
            continue
        }

        if (foundNull && byte != 0x00.toByte()) {
            position(position() - 1)
            break
        }
        if (!foundNull) {
            bytes.add(byte)
        }
    }

    return bytes.toByteArray().toString(charset)
}

fun ByteBuffer.getStringUntilNull(charset: Charset = Charsets.UTF_8): String {
    val byteList = mutableListOf<Byte>()
    while (hasRemaining()) {
        val byte = get()
        if (byte == 0.toByte()) break
        byteList.add(byte)
    }
    return byteList.toByteArray().toString(charset)
}


/**
 * 将两个32位整数（高位和低位）转换为64位长整数
 *
 * @return 转换后的64位长整数
 */
fun ByteBuffer.getBigEndianUInt64(): Long {
    val high = this.int
    val low = this.int
    val highReversed = high.toLong() and 0xFFFFFFFFL
    val lowReversed = low.toLong() and 0xFFFFFFFFL
    return (highReversed shl 32) or (lowReversed and 0xFFFFFFFFL)
}


/**
 * 将 int 转化为4个字节的ByteArray，并存入指定位置。
 *
 * @param data 目标字节数组
 * @param index 存入字节数组的位置
 */
fun Int.intToByteArray(data: ByteArray, index: Int) {
    data[index] = this.toByte()
    data[index + 1] = (this ushr 8).toByte()
    data[index + 2] = (this ushr 16).toByte()
    data[index + 3] = (this ushr 24).toByte()
}


fun ByteArray.indexOfNullByte(start: Int, end: Int = this.size): Int {
    for (i in start until end) {
        if (this[i] == 0x00.toByte()) {
            return i
        }
    }
    return -1
}

fun ByteBuffer.skip(bytes: Int): ByteBuffer {
    this.position(this.position() + minOf(bytes, this.remaining()))
    return this
}

fun ByteBuffer.getFixedSizeStringOrEmpty(size: Int): String {
    val bytes = ByteArray(size)
    this.get(bytes)
    return if (bytes[0] == 0x00.toByte()) "" else String(bytes, Charsets.UTF_8)
}


// 扩展函数将 ByteArray 转换为 ByteBuffer
fun ByteArray.toByteBuffer(order: ByteOrder = ByteOrder.BIG_ENDIAN): ByteBuffer {
    return ByteBuffer.wrap(this).order(order)
}


/**
 * 计算一个数据通道的值，将标记和两个数据字节组合成一个整数。
 *
 * @param marker 用于设置高位的标记值
 * @param byte1 第一个数据字节
 * @param byte2 第二个数据字节
 * @return 计算得到的整数值
 */
fun createDataChannel(marker: Int, byte1: Byte, byte2: Byte): Int =
    (marker shl 24) or ((byte1.toInt() and 0xFF) shl 16) or ((byte2.toInt() and 0xFF) shl 8) or 0x00

// 计算MD5值
fun ByteArray.calculateMd5(): String {
    val md5 = MessageDigest.getInstance("MD5").digest(this)
    return md5.joinToString("") { "%02x".format(it) }
}