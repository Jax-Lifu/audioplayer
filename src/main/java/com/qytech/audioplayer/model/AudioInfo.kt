package com.qytech.audioplayer.model

sealed class AudioInfo {
    abstract val sourceId: String
    abstract val codecName: String
    abstract val formatName: String
    abstract val duration: Long
    abstract val channels: Int
    abstract val sampleRate: Int
    abstract val bitRate: Int
    abstract val bitPreSample: Int
    abstract val title: String
    abstract val album: String
    abstract val artist: String
    abstract val genre: String
    abstract val date: String?
    abstract val albumImageUrl: String?
    abstract val artistImageUrl: String?
    abstract val startOffset: Long?
    abstract val endOffset: Long?
    abstract val dataLength: Long?
    abstract val trackId: Int

    data class Local(
        val filepath: String,
        val folder: String,
        val fileSize: Long,
        val fingerprint: String? = null,
        val startTime: Long? = 0,
        override val trackId: Int = 0,
        override val codecName: String,
        override val formatName: String,
        override val duration: Long,
        override val channels: Int,
        override val sampleRate: Int,
        override val bitRate: Int,
        override val bitPreSample: Int,
        override val title: String,
        override val album: String,
        override val artist: String,
        override val genre: String,
        override val date: String?,
        override val albumImageUrl: String? = null,
        override val artistImageUrl: String? = null,
        override val sourceId: String = filepath,
        override val startOffset: Long? = 0,
        override val endOffset: Long? = 0,
        override val dataLength: Long? = 0,
    ) : AudioInfo()

    data class Remote(
        val url: String,
        val encryptedSecurityKey: String? = null,
        val encryptedInitVector: String? = null,
        val headers: Map<String, String>? = null,
        override val trackId: Int = 0,
        override val codecName: String,
        override val formatName: String,
        override val duration: Long,
        override val channels: Int,
        override val sampleRate: Int,
        override val bitRate: Int,
        override val bitPreSample: Int,
        override val title: String,
        override val album: String,
        override val artist: String,
        override val genre: String,
        override val date: String?,
        override val albumImageUrl: String? = null,
        override val artistImageUrl: String? = null,
        override val sourceId: String = url,
        override val startOffset: Long? = 0,
        override val endOffset: Long? = 0,
        override val dataLength: Long? = 0,
    ) : AudioInfo()

    data class Tidal(
        val productId: String,
        val clientId: String,
        val clientSecret: String,
        val credentialsKey: String = "",
        override val trackId: Int = 0,
        override val codecName: String = "",
        override val formatName: String = "",
        override val duration: Long = 0,
        override val channels: Int = 0,
        override val sampleRate: Int = 0,
        override val bitRate: Int = 0,
        override val bitPreSample: Int = 0,
        override val title: String = "",
        override val album: String = "",
        override val artist: String = "",
        override val genre: String = "",
        override val date: String? = null,
        override val albumImageUrl: String? = null,
        override val artistImageUrl: String? = null,
        override val sourceId: String = productId,
        override val startOffset: Long? = 0,
        override val endOffset: Long? = 0,
        override val dataLength: Long? = 0,
    ) : AudioInfo()
}
