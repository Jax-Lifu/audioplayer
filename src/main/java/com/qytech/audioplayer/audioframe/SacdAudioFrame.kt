package com.qytech.audioplayer.audioframe

import com.qytech.audioplayer.model.ScarletBook
import com.qytech.audioplayer.sacd.DSTDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean


/**
 * 数据结构在内存中的位布局描述（根据字节序不同而有所不同）：
 *
 * 在大端模式下 (big endian):
 * - packet_info_count 使用最高3位:  N_Packets
 * - frame_info_count  使用第3到第5位: N_Frame_Starts
 * - reserved          使用第2位
 * - dst_encoded       使用最低1位
 *
 * 在小端模式下 (little endian):
 * - dst_encoded       使用最低1位
 * - reserved          使用第2位
 * - frame_info_count  使用第3到第5位: N_Frame_Starts
 * - packet_info_count 使用最高3位:  N_Packets
 */
data class AudioFrameHeader(
    val dstEncoded: Int,       // 表示 DST 编码标志
    //    val reserved: Int,   // 保留位，用于未来扩展
    val frameInfoCount: Int,   // 表示帧信息的数量
    val packetInfoCount: Int,   // 表示数据包信息的数量
) {

    companion object {
        /**
         * 从给定的字节创建一个 AudioFrameHeader 实例。
         *
         * @param byte 用于创建 AudioFrameHeader 的字节数据。
         * @param bigEndian 指示是否使用大端模式，如果为 false 则使用小端模式。
         * @return 生成的 AudioFrameHeader 实例。
         */
        fun fromByte(byte: Byte, bigEndian: Boolean = false): AudioFrameHeader {

            // 将有符号字节转换为无符号整数
            val unsignedByte = byte.toInt() and 0xFF

            // 提取各个位的数据
            val leastSignificantBit = unsignedByte and 0x1              // 提取最低位
            val secondLeastBit = (unsignedByte shr 1) and 0x1           // 提取第二低位
            val middleThreeBits = (unsignedByte shr 2) and 0x7        // 提取第3至5位
            val mostSignificantBits = (unsignedByte shr 5) and 0x7    // 提取最高3位
            // Logger.d("FrameHeader ${unsignedByte.toString(16)} dstEncoded = $leastSignificantBit frameInfoCount = $middleThreeBits, packetInfoCount = $mostSignificantBits")
            // 根据字节序的不同，构造 AudioFrameHeader 实例
            return if (bigEndian) {
                // 大端模式: 最高位开始依次映射到 dstEncoded, reserved, frameInfoCount, packetInfoCount
                AudioFrameHeader(
                    dstEncoded = mostSignificantBits,
//                    reserved = middleThreeBits,
                    frameInfoCount = secondLeastBit, packetInfoCount = leastSignificantBit
                )
            } else {
                // 小端模式: 最低位开始依次映射到 dstEncoded, reserved, frameInfoCount, packetInfoCount
                AudioFrameHeader(
                    dstEncoded = leastSignificantBit,
//                    reserved = secondLeastBit,
                    frameInfoCount = middleThreeBits, packetInfoCount = mostSignificantBits
                )
            }
        }
    }
}


data class AudioPacketInfo(
    val frameStart: Int,    // 1
    // val reserved: Int,      // 1
    val dataType: Int,      // 3
    val packetLength: Int,   // 11
) {
    companion object {
        // bigEndian
        fun fromLong(data1: Int, data2: Int): AudioPacketInfo {
            val frameStart = (data1 shr 7) and 1
            val dataType = (data1 shr 3) and 7
            val packetLength = ((data1 and 7) shl 8 or data2)
            // Logger.d("AudioPacketInfo ${data1.toString(16)} ${data2.toString(16)} frameStart = $frameStart dataType = $dataType packetLength = $packetLength")
            return AudioPacketInfo(frameStart, dataType, packetLength)
        }
    }
}

data class AudioFrameInfo(
    val time: ScarletBook.TrackTime,
    val reserved: Int,
) {

    fun getChannelCount(): Int {
        val channelBit3 = reserved and 0x01
        val channelBit2 = (reserved shr 1) and 0x01
        return if (channelBit2 == 1 && channelBit3 == 0) {
            6
        } else if (channelBit2 == 0 && channelBit3 == 1) {
            5
        } else {
            2
        }
    }

    fun getSectorCount(): Int {
        //    uint8_t channel_bit_3 : 1;
        //    uint8_t channel_bit_2 : 1;
        //    uint8_t sector_count  : 5;
        //    uint8_t channel_bit_1 : 1;
        return (reserved shr 2) and 0x1F
    }

    companion object {
        fun read(data: ByteArray): AudioFrameInfo {
            var reserved = 0
            if (data.size == 4) {
                reserved = data[3].toInt() and 0xFF
            }
            return AudioFrameInfo(
                ScarletBook.TrackTime(data[0], data[1], data[2]), reserved
            )
        }
    }
}

object SacdAudioFrame {
    private var header: AudioFrameHeader? = null
    private var packetInfoList: List<AudioPacketInfo>? = null
    private var frameInfoList: List<AudioFrameInfo>? = null
    private var currentDataSize = 0
    private var index = 0
    private val dstDecoder = DSTDecoder().apply {
        init(2, 64)
    }
    private var frameSize = 0
    private var frameDstBuffer: MutableList<Byte>? = null
    private var frameDsdBuffer = ByteArray(9408)
    private var dffBuffer = ByteArray(9408)


    fun read(srcData: ByteArray, destData: ByteArray, length: Int, isDopEnable: Boolean): Int {
        val tempData = ByteArray(length)
        index = 0
        currentDataSize = 0
        while (index < length) {
            header = AudioFrameHeader.fromByte(srcData[index])
            index++

            header?.let { frameHeader ->
                if (frameHeader.packetInfoCount in 1..6) {
                    packetInfoList = MutableList(frameHeader.packetInfoCount) {
                        // Extract 2 bytes for each AudioPacketInfo
                        val byte1 = srcData[index++].toInt() and 0xFF
                        val byte2 = srcData[index++].toInt() and 0xFF
                        AudioPacketInfo.fromLong(byte1, byte2)
                    }
                }

                frameInfoList = MutableList(frameHeader.frameInfoCount) {
                    val arraySize = if (frameHeader.dstEncoded == 1) 4 else 3
                    val audioFrameArray = ByteArray(arraySize)
                    System.arraycopy(srcData, index, audioFrameArray, 0, arraySize)
                    index += arraySize
                    AudioFrameInfo.read(audioFrameArray)
                }

                // Process packet info
                packetInfoList?.forEach { packetInfo ->
                    when (ScarletBook.AudioPacketDataType.fromValue(packetInfo.dataType)) {
                        ScarletBook.AudioPacketDataType.AUDIO -> {
                            val packet = ByteArray(packetInfo.packetLength)
                            System.arraycopy(srcData, index, packet, 0, packetInfo.packetLength)
                            index += packetInfo.packetLength
                            System.arraycopy(packet, 0, tempData, currentDataSize, packet.size)
                            currentDataSize += packetInfo.packetLength
                        }

                        ScarletBook.AudioPacketDataType.SUPPLEMENTARY,
                        ScarletBook.AudioPacketDataType.PADDING,
                            -> {
                            index += packetInfo.packetLength
                        }
                    }
                }
            }
        }
        // Logger.d("currentDataSize: $currentDataSize")

        //interleaveDffBlockStereo(destData, tempData, currentDataSize)
        return DffAudioFrame.read(tempData, destData, currentDataSize, isDopEnable)
    }

//    fun readDstFrame(
//        srcData: ByteArray,
//        length: Int,
//        shouldCancelDstDecode: AtomicBoolean,
//        onFrameDecoded: ((ByteArray, Int) -> Unit)? = null,
//    ) {
//        index = 0
//        while (index < length) {
//            if (shouldCancelDstDecode.get()) {
//                break
//            }
//            val header = AudioFrameHeader.fromByte(srcData[index])
//            index++
//
//
//            packetInfoList = MutableList(header.packetInfoCount) {
//                // Extract 2 bytes for each AudioPacketInfo
//                val byte1 = srcData[index++].toInt() and 0xFF
//                val byte2 = srcData[index++].toInt() and 0xFF
//                AudioPacketInfo.fromLong(byte1, byte2)
//            }
//
//            MutableList(header.frameInfoCount) {
//                val arraySize = if (header.dstEncoded == 1) 4 else 3
//                if (index + arraySize > length) {
//                    return
//                }
//                val audioFrameArray = ByteArray(arraySize)
//                System.arraycopy(srcData, index, audioFrameArray, 0, arraySize)
//                index += arraySize
//                AudioFrameInfo.read(audioFrameArray)
//            }
//
//            packetInfoList?.forEach { packetInfo ->
//                when (ScarletBook.AudioPacketDataType.fromValue(packetInfo.dataType)) {
//                    ScarletBook.AudioPacketDataType.AUDIO -> {
//                        if (header.dstEncoded == 1) {
//                            handleDstAudioPacket(srcData, packetInfo, index, onFrameDecoded)
//                        }
//                        index += packetInfo.packetLength
//                    }
//
//                    ScarletBook.AudioPacketDataType.SUPPLEMENTARY,
//                    ScarletBook.AudioPacketDataType.PADDING,
//                        -> {
//                        index += packetInfo.packetLength
//                    }
//                }
//
//            }
//        }
//    }
//    @OptIn(ExperimentalStdlibApi::class)
//    private fun handleDstAudioPacket(
//        srcData: ByteArray,
//        packetInfo: AudioPacketInfo,
//        index: Int,
//        onFrameDecoded: ((ByteArray, Int) -> Unit)? = null,
//    ) {
//        runCatching {
//            if (packetInfo.frameStart == 1) {
//                if (frameSize != 0) {
//                    frameDstBuffer?.toByteArray()?.let { dstData ->
//                        // DST 解码为 DSD 流
////                        dstDecoder.frameDSTDecode(dstData, frameDsdBuffer, frameSize)
////                        DffAudioFrame.read(frameDsdBuffer, dffBuffer, frameDsdBuffer.size, false)
////                        onFrameDecoded?.invoke(dffBuffer, dffBuffer.size)
//                        val result = FFmpegDstDecoder.decodeDstFrame(dstData)
//                        result?.let { dsdData ->
//                            onFrameDecoded?.invoke(dsdData, dsdData.size)
//                        }
//                    }
//                }
//
//                frameSize = packetInfo.packetLength
//                frameDstBuffer = mutableListOf<Byte>().apply {
//                    addAll(srcData.copyOfRange(index, index + packetInfo.packetLength).toList())
//                }
//            } else {
//                frameSize += packetInfo.packetLength
//                frameDstBuffer?.addAll(
//                    srcData.copyOfRange(index, index + packetInfo.packetLength).toList()
//                )
//            }
//        }.onFailure {
//            Timber.e("handleDstAudioPacket error $it")
//        }
//    }


    // 新增一个协程解码器实例
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dstFrameDecoder: DSTFrameDecoder? = null

    fun readDstFrame(
        srcData: ByteArray,
        length: Int,
        shouldCancelDstDecode: AtomicBoolean,
        onFrameDecoded: ((ByteArray, Int) -> Unit)? = null,
    ) {
        // 如果没有初始化异步解码器，则创建一个
        if (dstFrameDecoder == null && onFrameDecoded != null) {
            dstFrameDecoder = DSTFrameDecoder(coroutineScope) { dsdData, size ->
                onFrameDecoded(dsdData, size)
            }
        }

        var index = 0
        while (index < length) {
            if (shouldCancelDstDecode.get()) {
                Timber.w("DST decode canceled, stop parsing.")
                dstFrameDecoder?.cancel()
                break
            }

            // 解析帧头
            val header = AudioFrameHeader.fromByte(srcData[index])
            index++

            // 解析 AudioPacketInfo
            packetInfoList = MutableList(header.packetInfoCount) {
                val byte1 = srcData[index++].toInt() and 0xFF
                val byte2 = srcData[index++].toInt() and 0xFF
                AudioPacketInfo.fromLong(byte1, byte2)
            }

            // 解析 AudioFrameInfo（可能不重要，仅推进 index）
            repeat(header.frameInfoCount) {
                val arraySize = if (header.dstEncoded == 1) 4 else 3
                if (index + arraySize > length) return
                val audioFrameArray = ByteArray(arraySize)
                System.arraycopy(srcData, index, audioFrameArray, 0, arraySize)
                index += arraySize
                AudioFrameInfo.read(audioFrameArray)
            }

            // 遍历所有数据包
            packetInfoList?.forEach { packetInfo ->
                when (ScarletBook.AudioPacketDataType.fromValue(packetInfo.dataType)) {
                    ScarletBook.AudioPacketDataType.AUDIO -> {
                        if (header.dstEncoded == 1) {
                            handleDstAudioPacketAsync(srcData, packetInfo, index)
                        }
                        index += packetInfo.packetLength
                    }

                    ScarletBook.AudioPacketDataType.SUPPLEMENTARY,
                    ScarletBook.AudioPacketDataType.PADDING,
                        -> {
                        index += packetInfo.packetLength
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleDstAudioPacketAsync(
        srcData: ByteArray,
        packetInfo: AudioPacketInfo,
        index: Int,
    ) {
        runCatching {
            if (packetInfo.frameStart == 1) {
                // 遇到新帧头，说明上一帧已完整收集
                if (frameSize != 0 && frameDstBuffer?.isNotEmpty() == true) {
                    val dstData = frameDstBuffer?.toByteArray() ?: return@runCatching
                    // 异步提交给协程解码器（不会阻塞主线程）
                    coroutineScope.launch {
                        dstFrameDecoder?.submitFrame(dstData)
                    }
                }

                // 启动新帧
                frameSize = packetInfo.packetLength
                frameDstBuffer = mutableListOf<Byte>().apply {
                    addAll(srcData.copyOfRange(index, index + packetInfo.packetLength).toList())
                }
            } else {
                // 当前包属于上一帧，拼接数据
                frameSize += packetInfo.packetLength
                frameDstBuffer?.addAll(
                    srcData.copyOfRange(index, index + packetInfo.packetLength).toList()
                )
            }
        }.onFailure {
            Timber.e(it, "handleDstAudioPacketAsync error")
        }
    }

}
