package com.qytech.audioplayer.strategy

/**
 * 媒体数据源类型统一接口 (Sealed)
 */
sealed interface MediaSource {
    val uri: String
}

data class DefaultMediaSource(
    override val uri: String,
) : MediaSource

/**
 * SACD 音频数据源
 */
data class SacdMediaSource(
    override val uri: String,
    val trackIndex: Int = -1,
    val headers: Map<String, String> = emptyMap(),
) : MediaSource

/**
 * CUE 音频数据源包装类
 *
 * @param uri 真实的物理音频文件路径 (WAV/FLAC/DFF等)
 * @param trackIndex 轨道号
 * @param startPosition 分轨开始时间 (ms)
 * @param endPosition 分轨结束时间 (ms)，-1 表示直到文件末尾
 */
data class CueMediaSource(
    override val uri: String,
    val trackIndex: Int = -1,
    val startPosition: Long, // 毫秒
    val endPosition: Long,   // 毫秒
    val headers: Map<String, String>? = null,
) : MediaSource

/**
 * 普通网络流媒体数据源 (DASH, HTTP File)
 */
data class StreamingMediaSource(
    override val uri: String,
    val headers: Map<String, String> = emptyMap(),
) : MediaSource


/**
 * Sony Select 专用加密流数据源
 */
data class SonySelectMediaSource(
    override val uri: String,
    val securityKey: String,
    val initVector: String,

    val headers: Map<String, String> = emptyMap(),
) : MediaSource


data class WebDavMediaSource(
    override val uri: String,
    val username: String,
    val password: String,
    val headers: Map<String, String> = emptyMap(),
) : MediaSource