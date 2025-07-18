package com.qytech.audioplayer.parser

import com.qytech.audioplayer.cue.CueParser
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.core.extensions.getAbsoluteFolder
import java.io.File

/**
 * @author Administrator
 * @date 2025/7/7 16:05
 */
class CueAudioFileParser(filePath: String) : StandardAudioFileParser(filePath) {
    override suspend fun parse(): List<AudioInfo.Local>? {
        val cueAudioInfo = super.parse()?.firstOrNull() ?: return emptyList()
        // 获取当前当前音频对应的CUE文件
        val audioFile = File(sourceId)
        val cueFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.cue")
        if (!cueFile.exists()) {
            return emptyList()
        }
        val sheet = CueParser.parse(cueFile.absolutePath)
        val tracks = sheet.files.flatMap { it.tracks }
        val durations = CueParser.calculateTrackDurations(tracks, cueAudioInfo.duration)
        val cueInfos = durations.map { (track, duration) ->
            val title = track.title ?: "Track ${track.number}"
            val artist = track.performer ?: sheet.performer ?: "Unknown artist"
            val album = sheet.title ?: "Unknown album"
            val folder = sourceId.getAbsoluteFolder()
            AudioInfo.Local(
                title = title,
                artist = artist,
                album = album,
                filepath = sourceId,
                folder = folder,
                codecName = cueAudioInfo.codecName,
                fileSize = cueAudioInfo.fileSize,
                formatName = cueAudioInfo.formatName,
                duration = duration,
                channels = cueAudioInfo.channels,
                sampleRate = cueAudioInfo.sampleRate,
                bitRate = cueAudioInfo.bitRate,
                bitPreSample = cueAudioInfo.bitPreSample,
                genre = cueAudioInfo.genre,
                date = cueAudioInfo.date,
                albumImageUrl = cueAudioInfo.albumImageUrl,
                startTime = track.indices.firstOrNull()?.timestamp?.toMilliseconds(),
                trackId = track.number,
            )
        }
        return cueInfos
    }
}