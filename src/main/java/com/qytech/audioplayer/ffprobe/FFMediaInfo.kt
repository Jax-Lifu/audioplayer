package com.qytech.audioplayer.ffprobe

import com.qytech.audioplayer.model.AudioInfo
import com.qytech.core.extensions.getFileName
import com.qytech.core.extensions.getFolderName
import timber.log.Timber

class FFMediaInfo {

    var filename: String? = null
    var formatName: String? = null
    var duration: Long = 0
    var start: Long = 0
    var bitRate: Long = 0
    var sampleRate: Int = 0
    var bitPerSample: Int = 0
    var channels: Int = 0
    var channelLayout: String? = null
    var codecName: String? = null
    var codecLongName: String? = null
    var codecType: String? = null
    var image: ByteArray? = null
    var titleBytes: ByteArray? = null
    var artistBytes: ByteArray? = null
    var albumBytes: ByteArray? = null
    var genreBytes: ByteArray? = null
    var date: String? = null
    var comment: String? = null

    val title: String?
        get() = titleBytes?.toAudioTagString()
    val artist: String?
        get() = artistBytes?.toAudioTagString()
    val album: String?
        get() = albumBytes?.toAudioTagString()
    val genre: String?
        get() = genreBytes?.toAudioTagString()


    fun toLocalAudioFileInfo(
        path: String,
        folder: String,
        fileSize: Long,
        albumCover: String? = null,
        fingerprint: String? = null,
    ): AudioInfo {
        return AudioInfo.Local(
            filepath = filename ?: path,
            folder = folder,
            codecName = codecName ?: "",
            formatName = formatName ?: "",
            channels = channels,
            sampleRate = sampleRate,
            bitRate = bitRate.toInt(),
            bitPreSample = bitPerSample,
            duration = duration / 1000,
            title = title ?: path.getFileName(),
            album = album ?: folder.getFolderName(),
            artist = artist ?: "Unknown Artist",
            genre = genre?.lowercase() ?: "other",
            date = date,
            albumImageUrl = albumCover,
            fileSize = fileSize,
            sourceId = path,
            fingerprint = fingerprint,
        )
    }

    fun toRemoteAudioFileInfo(
        url: String,
        albumCover: String?,
        headers: Map<String, String>? = null,
    ): AudioInfo.Remote = AudioInfo.Remote(
        url = url,
        codecName = codecName ?: "",
        formatName = formatName ?: "",
        duration = duration / 1000,
        channels = channels,
        sampleRate = sampleRate,
        bitRate = bitRate.toInt(),
        bitPreSample = bitPerSample,
        title = title ?: url.getFileName(),
        album = album ?: url.getFolderName(),
        artist = artist ?: "Unknown Artist",
        genre = genre?.lowercase() ?: "other",
        date = date,
        albumImageUrl = albumCover,
        sourceId = url,
        headers = headers,
    )

    override fun toString(): String {
        return buildString {
            append("FFMediaInfo {")
            append("\n  filename: $filename")
            append("\n  title: $title")
            append("\n  artist: $artist")
            append("\n  album: $album")
            append("\n  genre: $genre")
            append("\n  date: $date")
            append("\n  duration: $duration")
            append("\n  start: $start")
            append("\n  bitRate: $bitRate")
            append("\n  sampleRate: $sampleRate")
            append("\n  bitPerSample: $bitPerSample")
            append("\n  channels: $channels")
            append("\n  channelLayout: $channelLayout")
            append("\n  codecName: $codecName")
            append("\n  codecLongName: $codecLongName")
            append("\n  codecType: $codecType")
            append("\n  image: ${image?.size ?: 0} bytes") // 显示图片字节数组的长度
            append("\n  comment: $comment")
            append("\n  format name: $formatName")
            append("\n}")
        }
    }

}


fun ByteArray.toAudioTagString(): String {
    // 按优先级排列常用编码
    val charsets = listOf("GBK", "UTF-8", "ISO-8859-1", "Windows-1252")

    for (cs in charsets) {
        try {
            val str = String(this, charset(cs))

            // 如果没有非法字符，直接返回
            if (!str.contains('\uFFFD') && str.isNotBlank()) {
                return str
            }
        } catch (e: Exception) {
            Timber.e(e, "Charset $cs decode failed")
        }
    }

    // 如果所有编码都失败，尝试剔除不可打印字符再返回
    val cleaned = this.map { it.toInt().toChar() }
        .filter {
            it.isLetterOrDigit() ||
                    it.isWhitespace() ||
                    it in listOf('-', '_', '.', ',', '!', '?')
        }
        .joinToString("")
    if (cleaned.isNotEmpty()) return cleaned

    // fallback 输出 hex
    return this.joinToString("") { "%02X".format(it) }
}

