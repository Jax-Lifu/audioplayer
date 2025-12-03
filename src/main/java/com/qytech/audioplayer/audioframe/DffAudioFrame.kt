package com.qytech.audioplayer.audioframe

import com.qytech.audioplayer.extension.createDataChannel
import com.qytech.audioplayer.extension.intToByteArray
import timber.log.Timber


object DffAudioFrame {
    /**
     * 将输入的立体声数据块重新排列后输出。
     *
     * @param destData 输出的字节数组
     * @param srcData 输入的字节数组
     * @param length 数据块的大小
     */
    private fun convertToDffStream(
        srcData: ByteArray,
        destData: ByteArray,
        length: Int,
    ) {
        /*for (i in 0 until length step 8) {
            destData[(i + 0x00)] = srcData[(i + 0x00)]
            destData[(i + 0x01)] = srcData[(i + 0x02)]
            destData[(i + 0x02)] = srcData[(i + 0x04)]
            destData[(i + 0x03)] = srcData[(i + 0x06)]

            destData[(i + 0x04)] = srcData[(i + 0x01)]
            destData[(i + 0x05)] = srcData[(i + 0x03)]
            destData[(i + 0x06)] = srcData[(i + 0x05)]
            destData[(i + 0x07)] = srcData[(i + 0x07)]
        }*/
        /*if (length % 8 != 0) {
            Timber.e("Size must be multiple of 8")
            return
        }*/
        if (srcData.size < length || destData.size < length) {
            Timber.e("Invalid data size")
            return
        }
        var srcPos = 0
        var destPos = 0
        val end = length

        while (srcPos < end) {
            // 批量加载源数据到局部变量
            val s0 = srcData[srcPos++]
            val s1 = srcData[srcPos++]
            val s2 = srcData[srcPos++]
            val s3 = srcData[srcPos++]
            val s4 = srcData[srcPos++]
            val s5 = srcData[srcPos++]
            val s6 = srcData[srcPos++]
            val s7 = srcData[srcPos++]
            // 批量写入目标数组
            destData[destPos++] = s0
            destData[destPos++] = s2
            destData[destPos++] = s4
            destData[destPos++] = s6
            destData[destPos++] = s1
            destData[destPos++] = s3
            destData[destPos++] = s5
            destData[destPos++] = s7
        }
    }


    /**
     * 将DFF的原始数据流转化为PCM (DOP)。
     * 输出的PCM流数据量大小是原始数据的2倍。
     *
     * @param destData 转换后的PCM数据输出字节数组
     * @param srcData 输入的DFF数据字节数组
     * @param length 输入数据的长度
     */
    private fun convertToDopStream(
        srcData: ByteArray,
        destData: ByteArray,
        length: Int,
    ) {
        var dataChannel1: Int
        var dataChannel2: Int
        var destDataIndex: Int

        var marker = 0x05
        var destIndex = 0
        var srcIndex = 0

        while (srcIndex + 3 < length) {
            destDataIndex = destIndex * 4
            dataChannel1 = createDataChannel(marker, srcData[srcIndex], srcData[srcIndex + 2])
            dataChannel2 = createDataChannel(marker, srcData[srcIndex + 1], srcData[srcIndex + 3])
            dataChannel1.intToByteArray(destData, destDataIndex)
            dataChannel2.intToByteArray(destData, destDataIndex + 4)
            marker = marker xor 0xFF
            destIndex += 2
            srcIndex += 4
        }
    }


    fun read(srcData: ByteArray, destData: ByteArray, length: Int, isDopEnable: Boolean): Int {
        if (isDopEnable) {
            convertToDopStream(srcData, destData, length)
        } else {
            convertToDffStream(srcData, destData, length)
        }
        return if (isDopEnable) 2 * length else length
    }
}