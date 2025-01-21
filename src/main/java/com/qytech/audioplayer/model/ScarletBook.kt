package com.qytech.audioplayer.model

import com.qytech.audioplayer.extension.getFixedSizeStringOrEmpty
import com.qytech.audioplayer.extension.skip
import java.nio.ByteBuffer

object ScarletBook {
    //  SACD 常量定义

    const val SACD_ID_LENGTH = 8
    const val SACD_TOC = "SACDMTOC"
    const val SACD_TWOCHTOC = "TWOCHTOC"
    const val SACD_MULCHTOC = "MULCHTOC"
    const val SACD_ALBUM_INFO = "SACDText"
    const val SACD_TRACK_TEXT = "SACDTTxt"
    const val SACD_TRACK_OFFSET = "SACDTRL1"
    const val SACD_TRACK_TIME = "SACDTRL2"
    const val SACD_IGL = "SACD_IGL"
    const val SACD_MAN = "SACD_Man"

    const val SACD_LSN_SIZE = 2048
    const val SACD_SAMPLING_FREQUENCY = 2822400
    const val SACD_FRAME_RATE = 75

    const val START_OF_FILE_SYSTEM_AREA = 0
    const val START_OF_MASTER_TOC = 510
    const val MASTER_TOC_LEN = 10
    const val MAX_AREA_TOC_SIZE_LSN = 96
    const val MAX_LANGUAGE_COUNT = 8
    const val MAX_CHANNEL_COUNT = 6
    const val MAX_DST_SIZE = (1024 * 64)
    const val SAMPLES_PER_FRAME = 588
    const val FRAME_SIZE_64 = (SAMPLES_PER_FRAME * 64 / 8)
    const val SUPPORTED_VERSION_MAJOR = 1
    const val SUPPORTED_VERSION_MINOR = 20

    const val MAX_GENRE_COUNT = 29
    const val MAX_CATEGORY_COUNT = 3

    const val MAX_PROCESSING_BLOCK_SIZE = 512

    const val SACD_BLOCK_SIZE_PER_CHANNEL = 4096
    const val SACD_BITS_PER_SAMPLE = 1
    const val AUDIO_SECTOR_HEADER_SIZE = 1
    const val AUDIO_PACKET_INFO_SIZE = 2
    const val AUDIO_FRAME_INFO_SIZE = 4
    const val MAX_PACKET_SIZE = 2045


    //  SACD 枚举定义
    enum class FrameFormat(val value: Int) {
        DST(0),
        DSD_3_IN_14(2),
        DSD_3_IN_16(3);

        fun getFormatName(): String {
            return when (this) {
                DST -> "DST"
                else -> "DSD"
            }
        }

        companion object {
            fun fromValue(value: Int): FrameFormat {
                return entries.find { it.value == value } ?: DST
            }
        }
    }

    enum class CharacterSet(val value: Int) {
        UNKNOWN(0),
        ISO646(1),
        ISO8859_1(2),
        RIS506(3),
        KSC5601(4),
        GB2312(5),
        BIG5(6),
        ISO8859_1_ESC(7);

        companion object {
            fun fromValue(characterSet: Int): CharacterSet {
                return entries.find { it.value == characterSet } ?: UNKNOWN
            }
        }
    }

    enum class Genre(val value: Int) {
        OTHER(0),
        NOT_DEFINED(1),
        ADULT_CONTEMPORARY(2),
        ALTERNATIVE_ROCK(3),
        CHILDRENS(4),
        CLASSICAL(5),
        CONTEMPORARY_CHRISTIAN(6),
        COUNTRY(7),
        DANCE(8),
        EASY_LISTENING(9),
        EROTIC(10),
        FOLK(11),
        GOSPEL(12),
        HIP_HOP(13),
        JAZZ(14),
        LATIN(15),
        MUSICAL(16),
        NEW_AGE(17),
        OPERA(18),
        OPERETTA(19),
        POP(20),
        RAP(21),
        REGGAE(22),
        ROCK(23),
        RHYTHM_AND_BLUES(24),
        SOUND_EFFECTS(25),
        SOUND_TRACK(26),
        SPOKEN_WORD(27),
        WORLD(28),
        BLUES(29);

        companion object {
            fun fromValue(value: Int): Genre {
                return entries.find { it.value == value } ?: OTHER
            }
        }

        fun toLocalizedString(): String {
            return name.lowercase().replace("_", " ")
        }
    }

    enum class Category(val value: Int) {
        NOT_USED(0),
        GENERAL(1),
        JAPANESE(2);

        companion object {
            fun fromValue(value: Int): Category {
                return entries.find { it.value == value } ?: NOT_USED
            }
        }
    }

    enum class TrackTextType(val value: Int) {
        TITLE(0x01),
        PERFORMER(0x02),
        SONGWRITER(0x03),
        COMPOSER(0x04),
        ARRANGER(0x05),
        MESSAGE(0x06),
        EXTRA_MESSAGE(0x07),
        TITLE_PHONETIC(0x81),
        PERFORMER_PHONETIC(0x82),
        SONGWRITER_PHONETIC(0x83),
        COMPOSER_PHONETIC(0x84),
        ARRANGER_PHONETIC(0x85),
        MESSAGE_PHONETIC(0x86),
        EXTRA_MESSAGE_PHONETIC(0x87);

        companion object {
            fun fromValue(value: Int): TrackTextType {
                return entries.find { it.value == value } ?: TITLE
            }
        }
    }

    enum class AudioPacketDataType(val value: Int) {
        AUDIO(2),
        SUPPLEMENTARY(3),
        PADDING(7);

        companion object {
            fun fromValue(value: Int): AudioPacketDataType =
                entries.find { it.value == value } ?: SUPPLEMENTARY
        }
    }

    data class Version(
        val major: Byte,
        val minor: Byte
    )

    data class GenreTable(
        val category: Category,
        val genre: Genre
    ) {
        companion object {
            fun read(buffer: ByteBuffer): GenreTable {
                val category = buffer.get().toInt()
                buffer.skip(2)
                val genre = buffer.get().toInt()
                return GenreTable(
                    Category.fromValue(category),
                    Genre.fromValue(genre)
                )
            }
        }
    }

    data class LocaleTable(
        val languageCode: String,  // ISO639-2 Language code
        val characterSet: CharacterSet,
    ) {
        companion object {
            fun read(buffer: ByteBuffer): LocaleTable {
                val languageCode = buffer.getFixedSizeStringOrEmpty(2)
                val characterSet = buffer.get().toInt() and 0x07
                buffer.skip(1)
                return LocaleTable(languageCode, CharacterSet.fromValue(characterSet))
            }
        }
    }


    data class AlbumInfo(
        val albumTitle: String? = null,
        val albumTitlePhonetic: String? = null,
        val albumArtist: String? = null,
        val albumArtistPhonetic: String? = null,
        val albumPublisher: String? = null,
        val albumPublisherPhonetic: String? = null,
        val albumCopyright: String? = null,
        val albumCopyrightPhonetic: String? = null,
        val discTitle: String? = null,
        val discTitlePhonetic: String? = null,
        val discArtist: String? = null,
        val discArtistPhonetic: String? = null,
        val discPublisher: String? = null,
        val discPublisherPhonetic: String? = null,
        val discCopyright: String? = null,
        val discCopyrightPhonetic: String? = null
    )


    data class TrackTime(
        val minutes: Byte,
        val seconds: Byte,
        val frames: Byte,
    ) {
        operator fun plus(other: TrackTime): TrackTime {
            val totalFrames = this.getFrameCount() + other.getFrameCount()
            val minutes = (totalFrames / (60 * SACD_FRAME_RATE)).toByte()
            val seconds = ((totalFrames % (60 * SACD_FRAME_RATE)) / SACD_FRAME_RATE).toByte()
            val frames = (totalFrames % SACD_FRAME_RATE).toByte()
            return TrackTime(minutes, seconds, frames)
        }

        operator fun minus(other: TrackTime): TrackTime {
            val totalFrames = this.getFrameCount() - other.getFrameCount()
            if (totalFrames < 0) throw IllegalArgumentException("Resulting TrackTime cannot be negative")

            val minutes = (totalFrames / (60 * SACD_FRAME_RATE)).toByte()
            val seconds = ((totalFrames % (60 * SACD_FRAME_RATE)) / SACD_FRAME_RATE).toByte()
            val frames = (totalFrames % SACD_FRAME_RATE).toByte()
            return TrackTime(minutes, seconds, frames)
        }

        fun getFrameCount(): Long {
            return ((minutes * 60 + seconds) * SACD_FRAME_RATE + frames).toLong()
        }

        fun getDuration(): Long {
            return (minutes * 60 + seconds + frames / SACD_FRAME_RATE).toLong()
        }

        override fun toString(): String {
            return "TrackTime($minutes:$seconds.$frames)"
        }
    }
}




