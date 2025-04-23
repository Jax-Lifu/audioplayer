package com.qytech.audioplayer.parser

import com.qytech.audioplayer.ffprobe.FFprobe
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.utils.AudioUtils

/**
 * @author Administrator
 * @date 2025/4/21 10:05
 */
class NetworkAudioFileParser(private val url: String) : AudioFileParserStrategy {
    override suspend fun parse(): List<AudioInfo.Remote>? {
        val ffMediaInfo = FFprobe.probeFile(url) ?: return emptyList()
        val albumCover = ffMediaInfo.image?.let { AudioUtils.saveCoverImage(it) }
        return listOf(
            ffMediaInfo.toRemoteAudioFileInfo(url, albumCover)
        )
    }
}