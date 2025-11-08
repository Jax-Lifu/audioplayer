package com.qytech.audioplayer.sacd

import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.extension.getStringUntilNextNonNull
import com.qytech.audioplayer.extension.skip
import com.qytech.audioplayer.model.ScarletBook
import com.qytech.audioplayer.utils.Logger
import timber.log.Timber
import java.nio.ByteBuffer

data class SacdTrackOffset(
    val trackStart: Long, val trackLength: Long, val trackEnd: Long,
) {

    companion object {
        fun read(
            buffer: ByteBuffer, trackCount: Int, isMultiChannel: Boolean,
        ): List<SacdTrackOffset>? {
            val id = buffer.getString(8)
            if (id != ScarletBook.SACD_TRACK_OFFSET) {
                Logger.d("ID is not SACDTRL1")
                return null
            }

            val sacdTrackOffsets = ArrayList<SacdTrackOffset>(trackCount)
            val trackStartList = ArrayList<Long>(trackCount)
            val trackLengthList = ArrayList<Long>(trackCount)

            // 读取起始偏移量
            repeat(trackCount) {
                trackStartList.add(buffer.getInt().toLong())
            }

            // 更新 buffer 位置
            buffer.position(255 * 4 + 8 + if (isMultiChannel) 12 else 0)

            // 读取轨道长度
            repeat(trackCount) {
                trackLengthList.add(buffer.getInt().toLong())
            }

            // 计算结束偏移量并创建 SacdTrackOffset 对象
            for (i in 0 until trackCount) {
                val trackEnd = trackStartList[i] + trackLengthList[i] - 1
                sacdTrackOffsets.add(
                    SacdTrackOffset(
                        trackStartList[i], trackLengthList[i], trackEnd
                    )
                )
            }

            return sacdTrackOffsets
        }
    }
}

data class SacdTrackTime(
    val startTime: ScarletBook.TrackTime,
    val durationTime: ScarletBook.TrackTime,
    val endTime: ScarletBook.TrackTime,
) {

    companion object {
        fun read(
            buffer: ByteBuffer, trackCount: Int, isMultiChannel: Boolean,
        ): List<SacdTrackTime>? {
            val id = buffer.getString(8)
            if (id != ScarletBook.SACD_TRACK_TIME) {
                Logger.d("ID is not SACDTRL2")
                return null
            }

            val sacdTrackTimes = ArrayList<SacdTrackTime>(trackCount)
            val startList = ArrayList<ScarletBook.TrackTime>(trackCount)
            val durationList = ArrayList<ScarletBook.TrackTime>(trackCount)

            // 解析起始时间
            repeat(trackCount) {
                startList.add(parseTrackTime(buffer))
            }

            // 更新 buffer 位置
            buffer.position(255 * 4 + 8 + if (isMultiChannel) 12 else 0)

            // 解析持续时间
            repeat(trackCount) {
                durationList.add(parseTrackTime(buffer))
            }

            // 计算结束时间并创建 SacdTrackTime 对象
            for (i in 0 until trackCount) {
                val endTime = startList[i] + durationList[i]
                sacdTrackTimes.add(SacdTrackTime(startList[i], durationList[i], endTime))
            }

            return sacdTrackTimes
        }

        private fun parseTrackTime(buffer: ByteBuffer): ScarletBook.TrackTime {
            val minutes = buffer.get()
            val seconds = buffer.get()
            val frames = buffer.get()
            buffer.skip(1) // Skip the unused byte
            return ScarletBook.TrackTime(minutes, seconds, frames)
        }
    }
}

data class SacdTrackText(
    val title: String? = null,
    val performer: String? = null,
    val songwriter: String? = null,
    val composer: String? = null,
    val arranger: String? = null,
    val message: String? = null,
    val extraMessage: String? = null,
    val titlePhonetic: String? = null,
    val performerPhonetic: String? = null,
    val songwriterPhonetic: String? = null,
    val composerPhonetic: String? = null,
    val arrangerPhonetic: String? = null,
    val messagePhonetic: String? = null,
    val extraMessagePhonetic: String? = null,
) {
    companion object {
        fun read(
            buffer: ByteBuffer, trackCount: Int, isMultiChannel: Boolean,
        ): List<SacdTrackText>? {
            val id = buffer.getString(ScarletBook.SACD_ID_LENGTH)
            if (id != ScarletBook.SACD_TRACK_TEXT) {
                Timber.e("ID is not SACDTTxt")
                return null
            }

            val textOffsetList = List(trackCount) { buffer.short.toInt() }
            val sacdTrackTexts = mutableListOf<SacdTrackText>()

            textOffsetList.forEach { offset ->
                //Logger.d("Track text offset = $offset")
                buffer.position(offset + if (isMultiChannel) 44 else 0)

                val typeCount = buffer.get().toInt()
                //Logger.d("Track type count = $typeCount")
                buffer.skip(3) // Skip 3 bytes

                // 初始化所有字段
                val textFields = mutableMapOf<ScarletBook.TrackTextType, String>()

                repeat(typeCount) {
                    val type = buffer.get().toInt()
                    buffer.skip(1) // Skip padding byte
                    val text = buffer.getStringUntilNextNonNull()

                    // 保存文本字段
                    ScarletBook.TrackTextType.fromValue(type).let { textType ->
                        textFields[textType] = text
                    }
                }

                // 根据解析的字段创建 SacdTrackText 实例
                sacdTrackTexts.add(
                    SacdTrackText(
                        title = textFields[ScarletBook.TrackTextType.TITLE],
                        performer = textFields[ScarletBook.TrackTextType.PERFORMER],
                        songwriter = textFields[ScarletBook.TrackTextType.SONGWRITER],
                        composer = textFields[ScarletBook.TrackTextType.COMPOSER],
                        arranger = textFields[ScarletBook.TrackTextType.ARRANGER],
                        message = textFields[ScarletBook.TrackTextType.MESSAGE],
                        extraMessage = textFields[ScarletBook.TrackTextType.EXTRA_MESSAGE],
                        titlePhonetic = textFields[ScarletBook.TrackTextType.TITLE_PHONETIC],
                        performerPhonetic = textFields[ScarletBook.TrackTextType.PERFORMER_PHONETIC],
                        songwriterPhonetic = textFields[ScarletBook.TrackTextType.SONGWRITER_PHONETIC],
                        composerPhonetic = textFields[ScarletBook.TrackTextType.COMPOSER_PHONETIC],
                        arrangerPhonetic = textFields[ScarletBook.TrackTextType.ARRANGER_PHONETIC],
                        messagePhonetic = textFields[ScarletBook.TrackTextType.MESSAGE_PHONETIC],
                        extraMessagePhonetic = textFields[ScarletBook.TrackTextType.EXTRA_MESSAGE_PHONETIC]
                    )
                )
            }

            return sacdTrackTexts
        }
    }
}
