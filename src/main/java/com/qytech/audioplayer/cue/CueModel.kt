package com.qytech.audioplayer.cue

import java.util.Locale
import kotlin.math.roundToLong

data class CueSheet(
    val rem: Map<String, String>,
    val performer: String?,
    val title: String?,
    val files: List<CueFile>,
)

data class CueFile(
    val name: String,
    val type: String,
    val tracks: List<Track>,
)

data class Track(
    val number: Int,
    val type: String,
    val title: String?,
    val performer: String?,
    val indices: MutableList<Index>,
)

data class Index(
    val number: Int,
    val timestamp: Timestamp,
)

data class Timestamp(val minutes: Int, val seconds: Int, val frames: Int) {
    fun toMilliseconds(): Long {
        return (((minutes * 60 + seconds) + frames / 75.0) * 1000).roundToLong()
    }

    override fun toString(): String {
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", minutes, seconds, frames)
    }
}
