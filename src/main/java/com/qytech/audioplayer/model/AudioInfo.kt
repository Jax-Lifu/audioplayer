package com.qytech.audioplayer.model

interface AudioInfo {
    val filepath: String
    val folder: String
    val codecName: String
    val formatName: String
    val fileSize:Long

    val channels: Int
    val sampleRate: Int
    val bitRate: Int
    val bitPreSample: Int
    val duration: Long

    val title: String
    val album: String
    val artist: String
    val genre: String
    val date: String?

    val startOffset: Long?
    val endOffset: Long?
    val dataLength: Long?

    val albumImageUrl: String?
    val artistImageUrl: String?
}