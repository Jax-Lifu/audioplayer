package com.qytech.audioplayer.player

import com.qytech.audioplayer.strategy.MediaSource

/**
 * 播放器抽象接口，类似 ExoPlayer
 */
interface AudioPlayer {
    fun setMediaSource(mediaSource: MediaSource)
    fun setDsdMode(mode: DSDMode)
    fun setD2pSampleRate(sampleRate: D2pSampleRate)

    fun prepare()
    fun play()
    fun pause()
    fun stop()
    fun release()

    fun seekTo(positionMs: Long)
    fun getDuration(): Long
    fun getPosition(): Long

    fun getState(): PlaybackState

    /** 回调事件监听，如错误、状态变化、结束等 */
    fun addListener(listener: PlayerListener)
    fun removeListener(listener: PlayerListener)

    fun isPlaying() = getState() == PlaybackState.PLAYING

    @Deprecated("Use PlayerListener instead")
    fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener)

    @Deprecated("Use PlayerListener instead")
    fun setOnProgressListener(listener: OnProgressListener)
}

/**
 * 播放器事件监听接口
 */
interface PlayerListener {
    fun onPrepared()
    fun onProgress(track: Int, current: Long, total: Long, progress: Float)
    fun onError(code: Int, msg: String)
    fun onComplete()

    fun onStateChanged(state: PlaybackState)
}


enum class PlaybackState(
    val value: Int,
) {
    IDLE(0),
    PREPARING(1),
    PREPARED(2),
    PLAYING(3),
    PAUSED(4),
    STOPPED(5),
    COMPLETED(6),
    ERROR(7),
    BUFFERING(8);

    companion object {
        fun fromValue(value: Int): PlaybackState {
            return PlaybackState.entries.firstOrNull { it.value == value } ?: IDLE
        }
    }
}

@Deprecated("Use PlayerListener instead")
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

@Deprecated("Use PlayerListener instead")
interface OnProgressListener {
    /**
     * 当播放进度更新时调用
     * @param progress 包含当前播放进度、总时长和其他相关信息
     */
    fun onProgress(progress: PlaybackProgress)
}

@Deprecated("Use PlayerListener instead")
interface OnPlaybackStateChangeListener {
    /**
     * 当播放状态发生变化时调用
     * @param state 新的播放状态
     */
    @Deprecated("Use PlayerListener instead")
    fun onPlaybackStateChanged(state: PlaybackState, source: String, track: Int)

    /**
     * 当播放发生错误时调用
     * @param errorMessage 错误信息
     */
    @Deprecated("Use PlayerListener instead")
    fun onPlayerError(errorMessage: String)
}