package com.qytech.audioplayer.ffprobe

import com.qytech.audioplayer.model.AudioInfo
import com.qytech.core.extensions.getFileName
import com.qytech.core.extensions.getFolderName

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
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var genre: String? = null
    var date: String? = null
    var comment: String? = null

    fun toAudioFileInfo(
        path: String,
        folder: String,
        fileSize: Long,
        albumCover: String? = null,
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
            sourceId = path
        )
    }

    fun toRemoteAudioFileInfo(
        url: String,
        albumCover: String?,
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
        sourceId = url
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