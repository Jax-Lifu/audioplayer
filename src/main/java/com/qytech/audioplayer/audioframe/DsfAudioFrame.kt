package com.qytech.audioplayer.audioframe

import com.qytech.audioplayer.extension.byteReversalTable
import com.qytech.audioplayer.extension.createDataChannel
import com.qytech.audioplayer.extension.intToByteArray
import timber.log.Timber

object DsfAudioFrame {

    /*if (lsbFirst == 1) {
            var j = 0
            for (i in 0 until size step 8) {
                outData[(i + 0)] = byteReversalTable[(inData[j + 0].toInt() and 0xFF)]
                outData[(i + 1)] = byteReversalTable[(inData[j + 1].toInt() and 0xFF)]
                outData[(i + 2)] = byteReversalTable[(inData[j + 2].toInt() and 0xFF)]
                outData[(i + 3)] = byteReversalTable[(inData[j + 3].toInt() and 0xFF)]
                j += 4
            }
            j = 0
            for (i in 0 until size step 8) {
                outData[(i + 4)] = byteReversalTable[inData[j + 4096 + 0].toInt() and 0xFF]
                outData[(i + 5)] = byteReversalTable[inData[j + 4096 + 1].toInt() and 0xFF]
                outData[(i + 6)] = byteReversalTable[inData[j + 4096 + 2].toInt() and 0xFF]
                outData[(i + 7)] = byteReversalTable[inData[j + 4096 + 3].toInt() and 0xFF]
                j += 4
            }
        } else {
            var j = 0
            for (i in 0 until size step 8) {
                outData[(i + 0)] = inData[j + 0]
                outData[(i + 1)] = inData[j + 1]
                outData[(i + 2)] = inData[j + 2]
                outData[(i + 3)] = inData[j + 3]
                j += 4
            }
            j = 0
            for (i in 0 until size step 8) {
                outData[(i + 4)] = inData[j + 4096 + 0]
                outData[(i + 5)] = inData[j + 4096 + 1]
                outData[(i + 6)] = inData[j + 4096 + 2]
                outData[(i + 7)] = inData[j + 4096 + 3]
                j += 4
            }
        }*/
    /**
     * 交错DSF块的立体声数据
     *
     * @param destData 输出字节数组
     * @param srcData 输入字节数组
     * @param length 数据大小
     * @param lsbFirst 是否LSB优先
     */
    private fun convertToDffStream(
        srcData: ByteArray,
        destData: ByteArray,
        length: Int,
        lsbFirst: Int = 1
    ) {
        /*if (length % 8 != 0) {
            Timber.e("Size must be multiple of 8")
            return
        }*/
        if (srcData.size < length || destData.size < length) {
            Timber.e("Invalid data size")
            return
        }


        val processor: (Byte) -> Byte = if (lsbFirst == 1) {
            { byte -> byteReversalTable[byte.toInt() and 0xFF] }
        } else {
            { byte -> byte }
        }
        // 处理前4字节块
        processSegment(srcData, destData, 0, 0, length, processor)
        // 处理后4字节块（偏移4096）
        processSegment(srcData, destData, 4096, 4, length, processor)
    }

    private inline fun processSegment(
        src: ByteArray,
        dest: ByteArray,
        srcOffset: Int,
        destOffset: Int,
        totalSize: Int,
        processor: (Byte) -> Byte
    ) {
        var srcIndex = srcOffset
        var destIndex = destOffset
        val blockCount = totalSize / 8

        repeat(blockCount) {
            dest[destIndex] = processor(src[srcIndex++])
            dest[destIndex + 1] = processor(src[srcIndex++])
            dest[destIndex + 2] = processor(src[srcIndex++])
            dest[destIndex + 3] = processor(src[srcIndex++])
            destIndex += 8
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
        length: Int
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
            convertToDffStream(srcData, destData, length)
        }
        return if (isDopEnable) 2 * length else length
    }
}