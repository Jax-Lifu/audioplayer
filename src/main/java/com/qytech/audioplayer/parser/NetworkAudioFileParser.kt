package com.qytech.audioplayer.parser

import com.qytech.audioplayer.ffprobe.FFprobe
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.parser.netstream.SacdDetector
import com.qytech.audioplayer.utils.AudioUtils

/**
 * @author Administrator
 * @date 2025/4/21 10:05
 */
class NetworkAudioFileParser(
    private val url: String,
    val headers: Map<String, String> = emptyMap(),
) : AudioFileParserStrategy {

    override suspend fun parse(): List<AudioInfo>? {
        if (url.isBlank()) return emptyList()
        val isSacd = SacdDetector.isSacdFile(url, headers)
        if (isSacd) {
            return SacdAudioFileParser(url, headers).parse()
        }

        val ffMediaInfo = FFprobe.probeFile(url, headers) ?: return emptyList()
        val albumCover = ffMediaInfo.image?.let { AudioUtils.saveCoverImage(it) }
        return listOf(
            ffMediaInfo.toRemoteAudioFileInfo(url, albumCover, headers)
        )
    }

}