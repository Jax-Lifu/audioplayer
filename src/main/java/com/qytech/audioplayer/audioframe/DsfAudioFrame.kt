package com.qytech.audioplayer.audioframe

import com.qytech.audioplayer.extension.byteReversalTable
import com.qytech.audioplayer.extension.createDataChannel
import com.qytech.audioplayer.extension.intToByteArray

object DsfAudioFrame {

    /**
     * 交错DSF块的立体声数据
     *
     * @param destData 输出字节数组
     * @param srcData 输入字节数组
     * @param length 数据大小
     * @param lsbFirst 是否LSB优先
     */
    private fun convertToDsfStream(
        srcData: ByteArray,
        destData: ByteArray,
        length: Int,
        lsbFirst: Int = 1,
    ) {
        if (lsbFirst == 1) {
            // 计算迭代次数，每次处理8个目标字节
            val iterations = length / 8
            // 缓存数组引用到局部变量，提升访问速度
            val src = srcData
            val dest = destData
            val table = byteReversalTable
            var j = 0
            // 合并处理两个循环：前4个字节和后4个字节（后4字节来自 srcData 偏移4096 的位置）
            for (k in 0 until iterations) {
                // 计算目标数组的起始下标
                val destIndex = k * 8
                // 处理前4字节：通过 byteReversalTable 做字节反转查找
                dest[destIndex] = table[src[j].toInt() and 0xFF]
                dest[destIndex + 1] = table[src[j + 1].toInt() and 0xFF]
                dest[destIndex + 2] = table[src[j + 2].toInt() and 0xFF]
                dest[destIndex + 3] = table[src[j + 3].toInt() and 0xFF]
                // 处理后4字节：来自 srcData 偏移4096 的位置
                dest[destIndex + 4] = table[src[j + 4096].toInt() and 0xFF]
                dest[destIndex + 5] = table[src[j + 4096 + 1].toInt() and 0xFF]
                dest[destIndex + 6] = table[src[j + 4096 + 2].toInt() and 0xFF]
                dest[destIndex + 7] = table[src[j + 4096 + 3].toInt() and 0xFF]
                j += 4  // 每次循环处理4个字节
            }
        } else {
            val iterations = length / 8
            val src = srcData
            val dest = destData
            var j = 0
            for (k in 0 until iterations) {
                val destIndex = k * 8
                // 直接复制前4字节
                dest[destIndex] = src[j]
                dest[destIndex + 1] = src[j + 1]
                dest[destIndex + 2] = src[j + 2]
                dest[destIndex + 3] = src[j + 3]
                // 直接复制后4字节（来自 srcData 偏移4096 的位置）
                dest[destIndex + 4] = src[j + 4096]
                dest[destIndex + 5] = src[j + 4096 + 1]
                dest[destIndex + 6] = src[j + 4096 + 2]
                dest[destIndex + 7] = src[j + 4096 + 3]
                j += 4
            }
        }
    }

    /**
     * 将DSD格式的DSF文件转换为DOP格式的PCM数据。
     *
     * @param destData 转换后的PCM数据输出字节数组
     * @param srcData 输入的DSF数据字节数组
     * @param length 输入数据的长度
     */
    private fun convertToDopStream(
        srcData: ByteArray,
        destData: ByteArray,
        length: Int,
    ) {
        var destDataIndex: Int
        var dataChannel1: Int
        var dataChannel2: Int

        var marker = 0x05
        var index = 0
        val singleChannelLength = length / 2

        while (singleChannelLength + index + 3 < length) {
            destDataIndex = index * 4
            dataChannel1 = createDataChannel(
                marker,
                byteReversalTable[srcData[index].toInt() and 0xFF],
                byteReversalTable[srcData[index + 1].toInt() and 0xFF]
            )
            dataChannel2 = createDataChannel(
                marker,
                byteReversalTable[srcData[singleChannelLength + index].toInt() and 0xFF],
                byteReversalTable[srcData[singleChannelLength + index + 1].toInt() and 0xFF]
            )
            marker = marker xor 0xFF
            dataChannel1.intToByteArray(destData, destDataIndex)
            dataChannel2.intToByteArray(destData, destDataIndex + 4)
            dataChannel1 = createDataChannel(
                marker,
                byteReversalTable[srcData[index + 2].toInt() and 0xFF],
                byteReversalTable[srcData[index + 3].toInt() and 0xFF]
            )
            dataChannel2 = createDataChannel(
                marker,
                byteReversalTable[srcData[singleChannelLength + index + 2].toInt() and 0xFF],
                byteReversalTable[srcData[singleChannelLength + index + 3].toInt() and 0xFF]
            )
            marker = marker xor 0xFF
            dataChannel1.intToByteArray(destData, destDataIndex + 2 * 4)
            dataChannel2.intToByteArray(destData, destDataIndex + 3 * 4)
            index += 4
        }
    }

    fun read(srcData: ByteArray, destData: ByteArray, length: Int, isDopEnable: Boolean): Int {
        if (isDopEnable) {
            convertToDopStream(srcData, destData, length)
        } else {
            convertToDsfStream(srcData, destData, length)
        }
        return if (isDopEnable) 2 * length else length
    }
}