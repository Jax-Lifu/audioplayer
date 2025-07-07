package com.qytech.audioplayer.cue

import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.io.FileInputStream

object CueParser {

    fun parse(filePath: String, charset: String? = null): CueSheet {
        val file = File(filePath)
        val actualCharset = charset ?: file.detectedCharset()
        val lines = file.readLines(charset(actualCharset))
        val rem = mutableMapOf<String, String>()
        var performer: String? = null
        var title: String? = null
        val files = mutableListOf<CueFile>()

        var currentTracks: MutableList<Track>? = null

        for (line in lines) {
            val parts = line.trim().split(Regex(" "), 2)
            val command = parts[0].uppercase()

            when (command) {
                "REM" -> {
                    val remParts = parts[1].split(Regex(" "), 2)
                    rem[remParts[0]] = remParts.getOrNull(1) ?: ""
                }

                "PERFORMER" -> {
                    if (currentTracks?.isNotEmpty() == true) {
                        val lastTrack = currentTracks.last()
                        currentTracks[currentTracks.size - 1] =
                            lastTrack.copy(performer = parts[1].unquote())
                    } else {
                        performer = parts[1].unquote()
                    }
                }

                "TITLE" -> {
                    if (currentTracks?.isNotEmpty() == true) {
                        val lastTrack = currentTracks.last()
                        currentTracks[currentTracks.size - 1] =
                            lastTrack.copy(title = parts[1].unquote())
                    } else {
                        title = parts[1].unquote()
                    }
                }

                "FILE" -> {
                    val fileParts = parts[1].split(" ")
                    val fileName =
                        fileParts.subList(0, fileParts.size - 1).joinToString(" ").unquote()
                    val fileType = fileParts.last()
                    currentTracks = mutableListOf()
                    files.add(CueFile(fileName, fileType, currentTracks))
                }

                "TRACK" -> {
                    val trackParts = parts[1].split(" ")
                    val trackNumber = trackParts[0].toInt()
                    val trackType = trackParts[1]
                    val trackTitle = files.lastOrNull()?.name?.let {
                        File(it).nameWithoutExtension.replace(Regex("^\\d+\\s*\\.?\\s*"), "")
                    }
                    currentTracks?.add(
                        Track(
                            trackNumber,
                            trackType,
                            trackTitle,
                            null,
                            mutableListOf()
                        )
                    )
                }

                "INDEX" -> {
                    val indexParts = parts[1].split(" ")
                    val indexNumber = indexParts[0].toInt()
                    val timestamp = indexParts[1].toTimestamp()
                    currentTracks?.last()?.indices?.add(Index(indexNumber, timestamp))
                }
            }
        }
        return CueSheet(rem, performer, title, files)
    }

    fun calculateTrackDurations(
        tracks: List<Track>,
        totalDurationSec: Long,
    ): List<Pair<Track, Long>> {
        val result = mutableListOf<Pair<Track, Long>>()
        for (i in tracks.indices) {
            val start =
                tracks[i].indices.find { it.number == 1 }?.timestamp?.toMilliseconds() ?: continue
            val end = if (i + 1 < tracks.size) {
                tracks[i + 1].indices.find { it.number == 1 }?.timestamp?.toMilliseconds()
            } else {
                totalDurationSec
            }
            val duration = (end ?: totalDurationSec) - start
            result.add(tracks[i] to duration)
        }
        return result
    }

    private fun String.unquote(): String {
        return if (startsWith("\"") && endsWith("\"")) {
            substring(1, length - 1)
        } else {
            this
        }
    }

    private fun String.toTimestamp(): Timestamp {
        val parts = split(":").map { it.toInt() }
        return Timestamp(parts[0], parts[1], parts[2])
    }

    fun File.detectedCharset(): String {
        val detector = UniversalDetector(null)
        val buffer = ByteArray(4096)
        FileInputStream(this).use { fis ->
            var byteRead: Int
            while (fis.read(buffer).also { byteRead = it } > 0 && !detector.isDone) {
                detector.handleData(buffer, 0, byteRead)
            }
        }
        detector.dataEnd()
        return detector.detectedCharset ?: "GBK"
    }
}