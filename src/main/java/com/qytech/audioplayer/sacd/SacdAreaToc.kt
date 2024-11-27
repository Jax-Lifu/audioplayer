package com.qytech.audioplayer.sacd

import com.qytech.audioplayer.model.ScarletBook
import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.extension.skip
import timber.log.Timber
import java.nio.ByteBuffer

data class SacdAreaToc(
    val id: String, // TWOCHTOC 或 MULCHTOC: 用于标识这是 2 声道或多声道区域的 TOC
    val version: ScarletBook.Version, // TOC 的版本号，例如 1.20 或 0x0114
    val size: Int, // TOC 的总大小，单位为字节
    val maxByteRate: Int, // 最大字节率，表示该区域的最大数据流速
    val sampleFrequency: Int, // 采样频率，如 0x04 表示 (64 * 44.1 kHz)
    val frameFormat: Byte, // 帧格式，描述音频帧的结构
    val channelCount: Int, // 声道数量，如立体声为 2，多声道可能更多
    val loudspeakerConfig: Byte, // 扬声器配置，描述音频输出的扬声器布局
    val maxAvailableChannels: Byte, // 最大可用声道数
    val areaMuteFlags: Byte, // 区域静音标志，用于指示该区域的静音状态
    val trackAttribute: Byte, // 轨道属性，描述音轨的特定特性
    val totalPlaytime: ScarletBook.TrackTime, // 区域的总播放时间
    val trackOffset: Byte, // 轨道偏移量，用于定位音轨的开始位置
    val trackCount: Int, // 音轨数量，表示该区域包含的音轨总数
    val trackStart: Int, // 第一条音轨的起始位置
    val trackEnd: Int, // 最后一条音轨的结束位置
    val textAreaCount: Byte, // 文本区域数量，表示该区域包含多少个文本信息区域
    val languageList: List<ScarletBook.LocaleTable>, // 支持的语言列表，用于文本区域的显示
    val trackTextOffset: Int, // 音轨文本的偏移位置
    val indexListOffset: Int, // 索引列表的偏移位置
    val accessListOffset: Int, // 访问列表的偏移位置
    val areaDescriptionOffset: Int, // 区域描述的偏移位置，用于描述该区域的详细信息
    val copyrightOffset: Int, // 版权信息的偏移位置
    val areaDescriptionPhoneticOffset: Int, // 区域描述的拼音偏移位置
    val copyrightPhoneticOffset: Int // 版权信息的拼音偏移位置
) {
    companion object {
        /**
         * Offset      0  1  2  3  4  5  6  7   8  9  A  B  C  D  E  F
         *
         * 00110000   54 57 4F 43 48 54 4F 43  01 14 00 08 00 00 00 00   TWOCHTOC
         * 00110010   00 0A F0 00 04 02 00 00  00 00 00 00 00 00 00 00     ?
         * 00110020   02 00 02 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 00110030   00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 00110040   29 0A 3C 00 00 0A 00 00  00 00 02 28 00 0D 34 34   ) <        (  44
         * 00110050   01 00 00 00 00 00 00 00  65 6E 07 00 00 00 00 00           en
         * 00110060   00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 00110070   00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 00110080   00 05 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 00110090   00 D0 00 E1 00 00 00 00  00 00 00 00 00 00 00 00    ??
         * 001100A0   00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 001100B0   00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 001100C0   00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 001100D0   4D 69 6E 67 20 59 75 6E  20 50 61 69 20 44 75 69   Ming Yun Pai Dui
         * 001100E0   00 55 6E 69 76 65 72 73  61 6C 20 4D 75 73 69 63    Universal Music
         * 001100F0   20 4C 74 64                                         Ltd
         * */
        fun read(buffer: ByteBuffer): SacdAreaToc? {
            val id = buffer.getString(ScarletBook.SACD_ID_LENGTH)
            if (id != ScarletBook.SACD_TWOCHTOC &&
                id != ScarletBook.SACD_MULCHTOC
            ) {
                Timber.d("id is not TWOCHTOC or MULCHTOC")
                return null
            }
            val version = ScarletBook.Version(buffer.get(), buffer.get())
            val size = buffer.short.toInt()
            buffer.skip(4)
            val maxByteRate = buffer.int
            val sampleFrequency = 16 * 44100 * (buffer.get().toInt() and 0xFF)
            val frameFormat = buffer.get()
            buffer.skip(10)
            val channelCount = buffer.get().toInt()
            val loudspeakerConfig = buffer.get()
            val maxAvailableChannels = buffer.get()
            val areaMuteFlags = buffer.get()
            buffer.skip(12)
            val trackAttribute = buffer.get()
            buffer.skip(15)
            val sacdTime = ScarletBook.TrackTime(buffer.get(), buffer.get(), buffer.get())
            buffer.skip(1)
            val trackOffset = buffer.get()
            val trackCount = buffer.get().toInt()
            buffer.skip(2)
            val trackStart = buffer.int
            val trackEnd = buffer.int
            val textAreaCount = buffer.get()
            buffer.skip(7)
            val locales =
                List(ScarletBook.MAX_LANGUAGE_COUNT) { ScarletBook.LocaleTable.read(buffer) }
            buffer.skip(8)
            val trackTextOffset = buffer.short.toInt()
            val indexListOffset = buffer.short.toInt()
            val accessListOffset = buffer.short.toInt()
            buffer.skip(10)
            val areaDescriptionOffset = buffer.short.toInt()
            val copyrightOffset = buffer.short.toInt()
            val areaDescriptionPhoneticOffset = buffer.short.toInt()
            val copyrightPhoneticOffset = buffer.short.toInt()

            return SacdAreaToc(
                id,
                version,
                size,
                maxByteRate,
                sampleFrequency,
                frameFormat,
                channelCount,
                loudspeakerConfig,
                maxAvailableChannels,
                areaMuteFlags,
                trackAttribute,
                sacdTime,
                trackOffset,
                trackCount,
                trackStart,
                trackEnd,
                textAreaCount,
                locales,
                trackTextOffset,
                indexListOffset,
                accessListOffset,
                areaDescriptionOffset,
                copyrightOffset,
                areaDescriptionPhoneticOffset,
                copyrightPhoneticOffset
            )
        }
    }
}