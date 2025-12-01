package com.qytech.audioplayer.parser

import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.parser.model.AudioTrackItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale


class DefaultParserStrategy(
    val source: String,
    val headers: Map<String, String> = emptyMap(),
) : AudioFileParserStrategy {
    private val mutex = Mutex()

    /**
     * 对应接口 AudioFileParserStrategy 的 parse 方法
     */
    override suspend fun parse(): List<AudioInfo>? {
        mutex.withLock {
            val metadata = AudioProbe.probeFile(source, headers) ?: return null
            return metadata.tracks.map { track ->
                mapTrackToAudioInfo(source, track, metadata.coverPath, metadata.date)
            }
        }
    }

    private fun mapTrackToAudioInfo(
        originalSource: String,
        track: AudioTrackItem,
        coverUrl: String?,
        date: String?,
    ): AudioInfo {
        val path = track.path.ifEmpty { originalSource }
        val isRemotePath = isRemote(path)

        // 基础信息填充 (防止空指针)
        val safeCodec = track.format ?: "unknown"
        val safeTitle = track.title.ifEmpty { File(path).nameWithoutExtension }
        val safeAlbum = track.album.ifEmpty { "Unknown Album" }
        val safeArtist = track.artist.ifEmpty { "Unknown Artist" }

        // 采样率/位深默认值
        val safeSampleRate = if (track.sampleRate > 0) track.sampleRate else 44100
        val safeChannels = if (track.channels > 0) track.channels else 2
        val safeBitDepth = if (track.bitDepth > 0) track.bitDepth else 16

        return if (isRemotePath) {
            // === 映射为 Remote ===
            AudioInfo.Remote(
                url = path,
                headers = headers,
                // 旧代码可能需要的加密字段，普通解析默认为 null
                encryptedSecurityKey = null,
                encryptedInitVector = null,

                trackId = track.trackId,
                codecName = safeCodec,
                formatName = safeCodec, // 通常 formatName 和 codecName 近似
                duration = track.durationMs,
                channels = safeChannels,
                sampleRate = safeSampleRate,
                bitRate = track.bitRate.toInt(),
                bitPreSample = safeBitDepth,
                title = safeTitle,
                album = safeAlbum,
                artist = safeArtist,
                genre = track.genre ?: "",
                date = date,
                albumImageUrl = coverUrl,
                artistImageUrl = null, // Probe 通常拿不到歌手图

                // === 关键：CUE 分轨支持 ===
                startOffset = track.startMs,
                endOffset = track.endMs,
                dataLength = 0 // 网络流长度未知或不重要
            )
        } else {
            // === 映射为 Local ===
            val file = File(path)
            val parentFolder = file.parent ?: ""
            val fileSize = if (file.exists()) file.length() else 0L

            AudioInfo.Local(
                filepath = path,
                folder = parentFolder,
                fileSize = fileSize,
                fingerprint = null,
                startTime = 0,

                trackId = track.trackId,
                codecName = safeCodec,
                formatName = safeCodec,
                duration = track.durationMs,
                channels = safeChannels,
                sampleRate = safeSampleRate,
                bitRate = track.bitRate.toInt(),
                bitPreSample = safeBitDepth,
                title = safeTitle,
                album = safeAlbum,
                artist = safeArtist,
                genre = track.genre ?: "",
                date = date,
                albumImageUrl = coverUrl,
                artistImageUrl = null,

                // === 关键：CUE 分轨支持 ===
                startOffset = track.startMs,
                endOffset = track.endMs,
                dataLength = fileSize
            )
        }
    }

    private fun isRemote(path: String): Boolean {
        val lower = path.lowercase(Locale.getDefault())
        return lower.startsWith("http") || lower.startsWith("rtmp") || lower.startsWith("rtsp")
    }
}

@Deprecated("Use createParser instead")
object AudioFileParserFactory {

    fun createParser(
        source: String,
        headers: Map<String, String> = emptyMap(),
    ): AudioFileParserStrategy {
        return DefaultParserStrategy(source, headers)
    }
}