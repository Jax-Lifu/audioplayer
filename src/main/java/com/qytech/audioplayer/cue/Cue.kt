package com.qytech.audioplayer.cue

import com.qytech.core.extensions.detectedCharset
import java.io.File
import java.nio.charset.Charset

//region Data Structures


sealed class CueElement {
    data class FileDeclare(
        val path: String = "",
        val type: String = "",
        val tracks: List<TrackInfo> = emptyList()
    ) : CueElement()

    data class TrackInfo(
        val number: Int = 0,
        val type: String = "",
        val title: String? = null,
        val performer: String? = null,
        val preGap: TimeCode? = null,
        val indices: List<TrackIndex> = emptyList(),
    ) : CueElement()

    data class TimeCode(
        val minutes: Int,
        val seconds: Int,
        val frames: Int
    ) {
        companion object {
            fun fromString(timeStr: String): TimeCode {
                val parts = timeStr.split(":").map { it.toIntOrNull() ?: 0 }
                return when (parts.size) {
                    3 -> TimeCode(parts[0], parts[1], parts[2])
                    else -> throw IllegalArgumentException("Invalid time format: $timeStr")
                }
            }

        }

        fun toMillis(): Long = ((minutes * 60 + seconds) * 1000L) + (frames * 1000L / 75)
    }

    data class TrackIndex(
        val index: Int = 0,
        val time: TimeCode? = null,
    )
}

data class CueSheet(
    val performer: String = "",
    val title: String = "",
    val files: List<CueElement.FileDeclare> = emptyList(),
)
//endregion

class CueParser() {
    private class ParserContext {
        var performer: String = ""
        var title: String = ""
        var files: MutableList<FileBuilder> = mutableListOf()
        var currentFile: FileBuilder? = null

        class FileBuilder(
            var path: String = "",
            var type: String = "",
            var tracks: MutableList<CueElement.TrackInfo> = mutableListOf()
        ) {
            fun build(): CueElement.FileDeclare {
                return CueElement.FileDeclare(path, type, tracks)
            }
        }
    }

    fun parse(file: File): CueSheet {
        val encoding = file.detectedCharset()
        return parse(file.readText(Charset.forName(encoding)))
    }

    fun parse(content: String): CueSheet {
        val context = ParserContext()
        content.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { processLine(it.trim(), context) }
        return CueSheet(
            performer = context.performer,
            title = context.title,
            files = context.files.map { it.build() }
        )
    }

    private fun processLine(line: String, ctx: ParserContext) {
        when {
            line.startsWith("PERFORMER") -> handlePerformer(line, ctx)
            line.startsWith("TITLE") -> handleTitle(line, ctx)
            line.startsWith("FILE") -> handleFile(line, ctx)
            line.startsWith("TRACK") -> handleTrack(line, ctx)
            line.startsWith("INDEX") -> handleIndex(line, ctx)
            line.startsWith("PREGAP") -> handlePregap(line, ctx)
        }
    }

    private fun handlePerformer(line: String, ctx: ParserContext) {
        val performer = parseQuotedValue(line)
        when {
            ctx.currentFile?.tracks?.lastOrNull() != null -> {
                ctx.currentFile?.tracks?.modifyLast { it.copy(performer = performer) }
            }

            else -> {
                ctx.performer = performer
            }
        }
    }

    private fun handleTitle(line: String, ctx: ParserContext) {
        val title = parseQuotedValue(line)
        when {
            ctx.currentFile?.tracks?.lastOrNull() != null -> {
                ctx.currentFile?.tracks?.modifyLast { it.copy(title = title) }
            }

            else -> {
                ctx.title = title
            }
        }
    }

    private fun handleFile(line: String, ctx: ParserContext) {
        val (path, type) = parseFileLine(line)
        ctx.currentFile = ParserContext.FileBuilder(path, type).also {
            ctx.files.add(it)
        }
    }

    private fun handleTrack(line: String, ctx: ParserContext) {
        val (number, type) = parseTrackLine(line)
        ctx.currentFile?.tracks?.add(
            CueElement.TrackInfo(
                number = number,
                type = type,
            )
        )
    }

    private fun handleIndex(line: String, ctx: ParserContext) {
        val trackIndex = parseIndexLine(line)
        ctx.currentFile?.tracks?.modifyLast {
            it.copy(indices = it.indices + trackIndex)
        }

    }

    private fun handlePregap(line: String, ctx: ParserContext) {
        val time = parsePregapLine(line)
        ctx.currentFile?.tracks?.modifyLast {
            it.copy(preGap = time)
        }
    }

    private fun parseQuotedValue(str: String): String {
        return "\\s+\"(.*?)\"$".toRegex().find(str)?.groupValues[1] ?: ""
    }

    private fun parseFileLine(line: String): Pair<String, String> {
        val result = "^FILE\\s+\"(.*?)\"\\s+(.*?)$".toRegex().find(line) ?: return Pair("", "")
        return result.destructured.let { (path, type) ->
            Pair(path, type)
        }
    }

    private fun parseTrackLine(line: String): CueElement.TrackInfo {
        val result = "^TRACK\\s+(\\d+)\\s+(.*?)$".toRegex().find(line)
            ?: return CueElement.TrackInfo()
        return result.destructured.let { (index, type) ->
            CueElement.TrackInfo(index.toInt(), type)
        }
    }

    private fun parseIndexLine(line: String): CueElement.TrackIndex {
        val result = "^INDEX\\s+(\\d+)\\s+(\\d+:\\d+:\\d+)$".toRegex().find(line)
            ?: return CueElement.TrackIndex()
        return result.destructured.let { (index, time) ->
            CueElement.TrackIndex(index.toInt(), CueElement.TimeCode.fromString(time))
        }
    }

    private fun parsePregapLine(line: String): CueElement.TimeCode {
        val result = "^PREGAP\\s+(\\d+:\\d+:\\d+)$".toRegex().find(line)
            ?: return CueElement.TimeCode(0, 0, 0)
        return CueElement.TimeCode.fromString(result.groupValues[1])
    }
}

private fun <T> MutableList<T>.modifyLast(block: (T) -> T) {
    if (isNotEmpty()) {
        val lastIndex = lastIndex
        set(lastIndex, block(last()))
    }
}
