package com.qytech.audioplayer.model

/**
 * 音频文件的基本信息，包含多个音轨信息和音频文件的头部信息。
 */
data class AudioFileInfo(
    override val filepath: String,
    override val folder: String,
    override val codecName: String,
    override val formatName: String,
    override val channels: Int,
    override val sampleRate: Int,
    override val bitRate: Int,
    override val bitPreSample: Int,
    override val duration: Long,
    override val title: String,
    override val album: String,
    override val artist: String,
    override val genre: String,
    override val date: String? = null,
    override val fileSize:Long,
    override val startOffset: Long? = null,
    override val endOffset: Long? = null,
    override val dataLength: Long? = null,
    override val albumImageUrl: String? = null,
    override val artistImageUrl: String? = null,
) : AudioInfo