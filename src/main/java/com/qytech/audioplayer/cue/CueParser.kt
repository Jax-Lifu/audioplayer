package com.qytech.audioplayer.cue

import android.annotation.SuppressLint
import com.qytech.core.extensions.detectedCharset
import com.qytech.core.extensions.find
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

data class CueTrack(
    val trackNumber: Int = 0,
    val title: String? = null,
    val performer: String? = null,
    val indexTime: List<String> = emptyList(),
)

data class CueSheet(
    val performer: String? = null,
    val title: String? = null,
    val date: String? = null,
    val genre: String? = null,
    val file: String? = null,
    val disc: String? = null,
    val encoding: String? = null,
    val format: String? = null,
    val tracks: List<CueTrack> = emptyList()
)

class CueParser {
    @SuppressLint("NewApi")
    fun parse(cueFile: String): CueSheet {
        val encoding = File(cueFile).detectedCharset()
        var performer: String? = null
        var title: String? = null
        var file: String? = null
        var format: String? = null
        var date: String? = null
        var disc: String? = null
        var genre: String? = null
        var tracks = mutableListOf<CueTrack>()
        var currentTrack: CueTrack? = null
        BufferedReader(FileReader(cueFile, charset(encoding))).use { reader ->
            reader.lineSequence().forEach { line ->
                when {
                    line.startsWith("PERFORMER") ->
                        performer = extractQuotedString(line)

                    line.startsWith("TITLE") ->
                        title = extractQuotedString(line)

                    line.startsWith("FILE") -> {
                        val (fileName, fileFormat) = extractFileFormat(line)
                        file = fileName
                        format = fileFormat
                    }

                    line.startsWith("REM DATE") ->
                        date = extractRemStrings(line)

                    line.startsWith("REM GENRE") ->
                        genre = extractRemStrings(line)

                    line.startsWith("REM DISCID") ->
                        disc = extractRemStrings(line)

                    line.trim().startsWith("TRACK") -> {
                        currentTrack?.let { tracks.add(it) }
                        currentTrack = CueTrack(trackNumber = extractTrackNumber(line))
                    }

                    line.trim().startsWith("TITLE") ->
                        currentTrack = currentTrack?.copy(title = extractQuotedString(line))

                    line.trim().startsWith("PERFORMER") ->
                        currentTrack = currentTrack?.copy(performer = extractQuotedString(line))

                    line.trim().startsWith("INDEX") ->
                        extractIndexTime(line)?.let { indexTime ->
                            currentTrack?.let { cueTrack ->
                                currentTrack = currentTrack?.copy(
                                    indexTime = cueTrack.indexTime + indexTime
                                )
                            }
                        }
                }
            }
        }
        currentTrack?.let { tracks.add(it) }

        return CueSheet(
            performer = performer,
            title = title,
            file = file,
            encoding = encoding,
            format = format,
            date = date,
            disc = disc,
            genre = genre,
            tracks = tracks
        )
    }

    private fun extractQuotedString(line: String): String? {
        val regex = "\"([^\"]+)\"".toRegex()
        return line.find(regex)?.firstOrNull()
    }

    private fun extractRemStrings(line: String): String? {
        val regex = "REM\\s+.*?\\s+(.*?)$".toRegex()
        return line.find(regex)?.firstOrNull()
    }

    private fun extractFileFormat(line: String): Pair<String?, String?> {
        // FILE "CDImage.wav" WAVE
        val regex = "FILE\\s+\"(.*?)\"\\s+(.*?)$".toRegex()
        val result = line.find(regex)
        return result?.get(0) to result?.get(1)
    }

    private fun extractIndexTime(line: String): String? {
        val regex = "INDEX\\s\\d+\\s([\\d:]+)".toRegex()
        return line.find(regex)?.firstOrNull()
    }

    private fun extractTrackNumber(line: String): Int {
        val regex = "TRACK\\s(\\d+)".toRegex()
        return line.find(regex)?.firstOrNull()?.toInt() ?: -1
    }
}
