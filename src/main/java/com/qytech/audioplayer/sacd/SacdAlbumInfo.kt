package com.qytech.audioplayer.sacd

import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.extension.skip
import com.qytech.audioplayer.model.ScarletBook
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.charset.Charset

data class SacdAlbumInfo(
    val id: String, // SACDText: 标识符，用于标识这是 SACD 专辑信息记录
    val albumTitlePosition: Short, // 专辑标题的文本偏移位置，用于定位专辑标题字符串
    val albumArtistPosition: Short, // 专辑艺术家的文本偏移位置
    val albumPublisherPosition: Short, // 专辑发行商的文本偏移位置
    val albumCopyrightPosition: Short, // 专辑版权信息的文本偏移位置
    val albumTitlePhoneticPosition: Short, // 专辑标题的拼音（或读音）偏移位置
    val albumArtistPhoneticPosition: Short, // 专辑艺术家名称的拼音偏移位置
    val albumPublisherPhoneticPosition: Short, // 专辑发行商名称的拼音偏移位置
    val albumCopyrightPhoneticPosition: Short, // 专辑版权信息的拼音偏移位置
    val discTitlePosition: Short, // 碟片标题的文本偏移位置，用于定位碟片标题字符串
    val discArtistPosition: Short, // 碟片艺术家的文本偏移位置
    val discPublisherPosition: Short, // 碟片发行商的文本偏移位置
    val discCopyrightPosition: Short, // 碟片版权信息的文本偏移位置
    val discTitlePhoneticPosition: Short, // 碟片标题的拼音（或读音）偏移位置
    val discArtistPhoneticPosition: Short, // 碟片艺术家名称的拼音偏移位置
    val discPublisherPhoneticPosition: Short, // 碟片发行商名称的拼音偏移位置
    val discCopyrightPhoneticPosition: Short, // 碟片版权信息的拼音偏移位置
    val albumInfo: ScarletBook.AlbumInfo // 包含专辑详细信息的对象，通常包含更多关于专辑的文本信息
) {
    companion object {
        private const val DEBUG = false
        private const val DATA_LENGTH = 1024

        /**
         * Offset      0  1  2  3  4  5  6  7   8  9 10 11 12 13 14 15
         *
         * 01046528   53 41 43 44 54 65 78 74  00 00 00 00 00 00 00 00   SACDText
         * 01046544   00 40 00 51 00 58 00 70  00 00 00 00 00 00 00 00    @ Q X p
         * 01046560   00 84 00 95 00 9C 00 B4  00 00 00 00 00 00 00 00    ????
         * 01046576   00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00
         * 01046592   4D 69 6E 67 20 59 75 6E  20 50 61 69 20 44 75 69   Ming Yun Pai Dui
         * 01046608   00 42 45 59 4F 4E 44 00  43 69 6E 65 70 6F 6C 79    BEYOND Cinepoly
         * 01046624   20 52 65 63 6F 72 64 73  20 43 6F 20 4C 74 64 00    Records Co Ltd
         * 01046640   55 6E 69 76 65 72 73 61  6C 20 4D 75 73 69 63 20   Universal Music
         * 01046656   4C 74 64 00 4D 69 6E 67  20 59 75 6E 20 50 61 69   Ltd Ming Yun Pai
         * 01046672   20 44 75 69 00 42 45 59  4F 4E 44 00 43 69 6E 65    Dui BEYOND Cine
         * 01046688   70 6F 6C 79 20 52 65 63  6F 72 64 73 20 43 6F 20   poly Records Co
         * 01046704   4C 74 64 00 55 6E 69 76  65 72 73 61 6C 20 4D 75   Ltd Universal Mu
         * 01046720   73 69 63 20 4C 74 64 00                            sic Ltd
         * */
        fun read(buffer: ByteBuffer, encoding: String = "UTF-8"): SacdAlbumInfo? {
            val id = buffer.getString(ScarletBook.SACD_ID_LENGTH)
            // Timber.d("read id: $id")
            if (id != ScarletBook.SACD_ALBUM_INFO) {
                Timber.d("ID is not SACDText")
                return null
            }
            buffer.skip(8)
            val albumTitlePosition = buffer.short
            val albumArtistPosition = buffer.short
            val albumPublisherPosition = buffer.short
            val albumCopyrightPosition = buffer.short

            val albumTitlePhoneticPosition = buffer.short
            val albumArtistPhoneticPosition = buffer.short
            val albumPublisherPhoneticPosition = buffer.short
            val albumCopyrightPhoneticPosition = buffer.short

            val discTitlePosition = buffer.short
            val discArtistPosition = buffer.short
            val discPublisherPosition = buffer.short
            val discCopyrightPosition = buffer.short

            val discTitlePhoneticPosition = buffer.short
            val discArtistPhoneticPosition = buffer.short
            val discPublisherPhoneticPosition = buffer.short
            val discCopyrightPhoneticPosition = buffer.short
            //Timber.d("read albumTitlePosition: $albumTitlePosition albumArtistPosition: $albumArtistPosition albumPublisherPosition: $albumPublisherPosition albumCopyrightPosition: $albumCopyrightPosition")

            // 校验所有的 Position 是否都为 0
            if (listOf(
                    albumTitlePosition,
                    albumArtistPosition,
                    albumPublisherPosition,
                    albumCopyrightPosition,
                    albumTitlePhoneticPosition,
                    albumArtistPhoneticPosition,
                    albumPublisherPhoneticPosition,
                    albumCopyrightPhoneticPosition,
                    discTitlePosition,
                    discArtistPosition,
                    discPublisherPosition,
                    discCopyrightPosition,
                    discTitlePhoneticPosition,
                    discArtistPhoneticPosition,
                    discPublisherPhoneticPosition,
                    discCopyrightPhoneticPosition
                ).all { it == 0.toShort() }
            ) {
                // Timber.d("All positions are zero, skipping processing.")
                return null
            }

            val data = ByteArray(DATA_LENGTH)
            val position = buffer.position()
            buffer.get(data)
            val albumInfo = ScarletBook.AlbumInfo(
                albumTitle = getText(data, albumTitlePosition, position, encoding),
                albumArtist = getText(data, albumArtistPosition, position, encoding),
                albumPublisher = getText(data, albumPublisherPosition, position, encoding),
                albumCopyright = getText(data, albumCopyrightPosition, position, encoding),
                albumTitlePhonetic = getText(data, albumTitlePhoneticPosition, position, encoding),
                albumArtistPhonetic = getText(
                    data,
                    albumArtistPhoneticPosition,
                    position,
                    encoding
                ),
                albumPublisherPhonetic = getText(
                    data,
                    albumPublisherPhoneticPosition,
                    position,
                    encoding
                ),
                albumCopyrightPhonetic = getText(
                    data,
                    albumCopyrightPhoneticPosition,
                    position,
                    encoding
                ),
                discTitle = getText(data, discTitlePosition, position, encoding),
                discArtist = getText(data, discArtistPosition, position, encoding),
                discPublisher = getText(data, discPublisherPosition, position, encoding),
                discCopyright = getText(data, discCopyrightPosition, position, encoding),
                discTitlePhonetic = getText(data, discTitlePhoneticPosition, position, encoding),
                discArtistPhonetic = getText(data, discArtistPhoneticPosition, position, encoding),
                discPublisherPhonetic = getText(
                    data,
                    discPublisherPhoneticPosition,
                    position,
                    encoding
                ),
                discCopyrightPhonetic = getText(
                    data,
                    discCopyrightPhoneticPosition,
                    position,
                    encoding
                )
            )

            return SacdAlbumInfo(
                id,
                albumTitlePosition,
                albumArtistPosition,
                albumPublisherPosition,
                albumCopyrightPosition,
                albumTitlePhoneticPosition,
                albumArtistPhoneticPosition,
                albumPublisherPhoneticPosition,
                albumCopyrightPhoneticPosition,
                discTitlePosition,
                discArtistPosition,
                discPublisherPosition,
                discCopyrightPosition,
                discTitlePhoneticPosition,
                discArtistPhoneticPosition,
                discPublisherPhoneticPosition,
                discCopyrightPhoneticPosition,
                albumInfo
            )
        }

        private fun getText(
            data: ByteArray,
            offset: Short,
            position: Int,
            encoding: String
        ): String? {
            if (offset == 0.toShort()) {
                return null
            }
            val startOffset = offset - position
            var length = 0
            while (true) {
                if (data[startOffset + length] == 0x00.toByte()) {
                    return String(data, startOffset, length, Charset.forName(encoding))
                }
                length++
            }
        }
    }
}
