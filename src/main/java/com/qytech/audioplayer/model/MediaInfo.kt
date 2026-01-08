package com.qytech.audioplayer.model

/**
 * @author Administrator
 * @date 2026/1/8 11:34
 */
data class MediaInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Long,
    val bitDepth: Int,
    val format: String,
    val title: String = "Unknown Title",
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
)