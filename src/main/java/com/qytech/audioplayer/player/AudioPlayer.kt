package com.qytech.audioplayer.player

import com.qytech.audioplayer.model.AudioInfo

enum class PlaybackState {
    IDLE,           // 空闲状态
    PREPARING,      // 准备中
    PLAYING,        // 播放中
    PAUSED,         // 暂停
    COMPLETED,      // 播放完成
    STOPPED,        // 停止
    BUFFERING,      // 缓冲中 (用于网络流媒体)
    ERROR           // 发生错误
}

data class PlaybackProgress(
    val currentPosition: Long,     // 当前播放进度（秒）
    val progress: Float,           // 播放进度百分比 (0.0 - 1.0)
    val duration: Long,             // 媒体总时长（秒）
) {
    companion object {
        val DEFAULT = PlaybackProgress(0, 0f, 0)
    }

    fun isAvailable(): Boolean = currentPosition >= 0 && duration > 0 && progress in 0f..1f
}


enum class D2pSampleRate(val hz: Int) {
    // 自动匹配，根据输入 DSD 速率选择合适的 PCM 速率
    AUTO(0),

    // 基于 44.1kHz 倍频
    PCM_44100(44100),
    PCM_88200(88200),
    PCM_176400(176400),
    PCM_352800(352800),
    PCM_705600(705600),

    // 基于 48kHz 倍频
    PCM_48000(48000),
    PCM_96000(96000),
    PCM_192000(192000),
    PCM_384000(384000),
    PCM_768000(768000),
}

interface DsdLoopbackDataCallback {
    fun onDataReceived(data: ByteArray)
}

interface OnProgressListener {
    /**
     * 当播放进度更新时调用
     * @param progress 包含当前播放进度、总时长和其他相关信息
     */
    fun onProgress(progress: PlaybackProgress)
}

interface OnPlaybackStateChangeListener {
    /**
     * 当播放状态发生变化时调用
     * @param state 新的播放状态
     */
    fun onPlaybackStateChanged(state: PlaybackState, source: String, track: Int)

    /**
     * 当播放发生错误时调用
     * @param errorMessage 错误信息
     */
    fun onPlayerError(errorMessage: String)
}

/**
 * @author Administrator
 * @date 2025/7/5 16:40
 */
interface AudioPlayer {
    val audioInfo: AudioInfo

    @Deprecated("this method is deprecated, and will be removed in the future")
    fun setMediaItem(mediaItem: AudioInfo)

    fun prepare()
    fun play()
    fun pause()
    fun stop()
    fun release()

    fun seekTo(positionMs: Long)
    fun fastForward(ms: Long)
    fun fastRewind(ms: Long)

    fun getDuration(): Long
    fun getCurrentPosition(): Long
    fun setPlaybackSpeed(speed: Float)
    fun getPlaybackSpeed(): Float
    fun isPlaying(): Boolean

    fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener)
    fun setOnProgressListener(listener: OnProgressListener)

    fun setDsdMode(dsdMode: DSDMode)
    fun setDsdLoopbackCallback(callback: DsdLoopbackDataCallback?)

    fun setD2pSampleRate(sampleRate: D2pSampleRate)
}