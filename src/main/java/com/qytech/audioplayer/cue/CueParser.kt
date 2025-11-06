package com.qytech.audioplayer.cue

import com.ibm.icu.text.CharsetDetector
import timber.log.Timber
import java.io.File
import java.io.FileInputStream

object CueParser {

    fun parse(filePath: String, charset: String? = null): CueSheet {
        val file = File(filePath)
        val actualCharset = charset ?: file.detectedCharset()
        Timber.d("actualCharset $actualCharset")
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

    private val PREFERRED_CHINESE_ENCODINGS = setOf("GB18030", "GBK", "Big5")
    private val JAPANESE_ENCODINGS = setOf("EUC-JP", "Shift_JIS", "ISO-2022-JP")
    fun File.detectedCharset(): String {
        return runCatching {
            val detector = CharsetDetector()
            val buffer = ByteArray(4096)
            val byteRead: Int

            FileInputStream(this).use { fis ->
                byteRead = fis.read(buffer)
            }
            if (byteRead <= 0) return "UTF-8"

            detector.setText(buffer.copyOfRange(0, byteRead))
            val allMatches = detector.detectAll()

            if (allMatches.isNullOrEmpty()) {
                return "UTF-8"
            }

            val topMatch = allMatches[0]
            val topName = topMatch.name
            val topConfidence = topMatch.confidence

            // 1. 如果最高猜测是 UTF 编码且置信度高，直接返回（最安全的）
            if (topName.startsWith("UTF-") && topConfidence > 80) {
                return topName
            }

            // 2. 如果最高猜测是日文编码 (EUC-JP, Shift_JIS)
            if (topName in JAPANESE_ENCODINGS) {

                // 3. 查找中文 GB 系列的编码候选
                val gbMatch = allMatches.find { it.name == "GB18030" || it.name == "GBK" }

                // 4. CJK 强制覆盖逻辑：
                // 只要我们找到了 GB 编码的候选（即使只有 1% 的置信度），
                // 鉴于这个文件包含中文内容，我们倾向于相信它是中文环境下的 GBK 文件。
                if (gbMatch != null) {
                    // 强制返回 GB18030 (GBK的超集，更安全)
                    return "GB18030"
                }
            }

            // 5. 如果 Top 1 既不是 UTF，也不是日文，我们采用原始的置信度逻辑
            //    (比如 Big5 75%，GB18030 75%，我们直接取 Top 1 的 Big5 或 GB18030)
            //    由于GB18030和Big5在列表里紧随其后且置信度相同(75)，取哪个都行。
            if (topConfidence > 50) {
                // 即使 Top 1 是 EUC-JP (100)，如果前面的 if 没有拦截（即没有找到 GB 编码），
                // 理论上它会返回 EUC-JP。但基于你的日志，GB18030/Big5 总是会出现。
                return topName
            }

            // 6. 最终回退
            return "UTF-8"

        }.getOrDefault("UTF-8")
    }
}