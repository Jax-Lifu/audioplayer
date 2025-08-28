package com.qytech.audioplayer.player

import com.qytech.audioplayer.model.AudioInfo
import timber.log.Timber

/**
 * @author Administrator
 * @date 2025/7/5 17:02
 */
abstract class BaseAudioPlayer(
    override val audioInfo: AudioInfo,
) : AudioPlayer {
    protected var state: PlaybackState = PlaybackState.IDLE
    protected var playSpeed: Float = 1f

    protected var dsdPlayMode: DSDMode = DSDMode.NATIVE

    protected var dsdToPcmSampleRate: D2pSampleRate = D2pSampleRate.AUTO

    protected var dsdLoopbackDataCallback: DsdLoopbackDataCallback? = null

    protected var stateListener: OnPlaybackStateChangeListener? = null
    protected var progressListener: OnProgressListener? = null


    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener) {
        Timber.d("setOnPlaybackStateChangeListener: $listener")
        stateListener = listener
    }

    override fun setOnProgressListener(listener: OnProgressListener) {
        Timber.d("setOnProgressListener: $listener")
        progressListener = listener
    }

    override fun setPlaybackSpeed(speed: Float) {
        playSpeed = speed
    }

    override fun getDuration(): Long = audioInfo.duration

    override fun isPlaying(): Boolean = state == PlaybackState.PLAYING

    override fun getPlaybackSpeed(): Float = playSpeed


    protected fun updateStateChange(newState: PlaybackState) {
        Timber.d("updateStateChange: $newState $stateListener")
        state = newState
        stateListener?.onPlaybackStateChanged(newState)
    }

    @Deprecated("this method is deprecated, and will be removed in the future")
    override fun setMediaItem(mediaItem: AudioInfo) {
        // do nothing
    }

    protected fun updateProgress(position: Long) {
        val duration = getDuration()
        val progress = if (duration > 0) position.toFloat() / duration else 0f
        progressListener?.onProgress(
            PlaybackProgress(
                currentPosition = position,
                progress = progress,
                duration = duration
            )
        )
    }

    protected fun onPlayerError(exception: Throwable) {
        Timber.d("onPlayerError: ${exception.message} ${stateListener}")
        updateStateChange(PlaybackState.ERROR)
        progressListener?.onProgress(PlaybackProgress.DEFAULT)
        stateListener?.onPlayerError(exception.message ?: "未知错误")
        release()
    }

    /**
     * 是否需要调整 CUE 音频的进度
     */
    protected fun needsCueSeek(): Boolean {
        val local = audioInfo as? AudioInfo.Local ?: return false
        return local.trackId > 1 && (local.startTime ?: 0) > 0
    }

    protected fun getCueStartTime(): Long {
        return (audioInfo as? AudioInfo.Local)?.startTime ?: 0
    }

    /**
     * 设置DSD模式，如果是DSD512，采样率为 22579200，只能使用NATIVE模式或者D2P
     * */
    override fun setDsdMode(dsdMode: DSDMode) {
        this.dsdPlayMode = dsdMode
        if (dsdMode == DSDMode.DOP && audioInfo.sampleRate == 22579200) {
            // 如果是DSD512，采样率为 22579200，只能使用NATIVE模式或者D2P
            this.dsdPlayMode = DSDMode.NATIVE
        } else if (audioInfo.sampleRate > 22579200) {
            // 如果采样率大于22579200，只能使用D2P
            this.dsdPlayMode = DSDMode.D2P
        }
    }

    override fun setDsdLoopbackCallback(callback: DsdLoopbackDataCallback?) {
        this.dsdLoopbackDataCallback = callback
    }

    override fun setD2pSampleRate(sampleRate: D2pSampleRate) {
        this.dsdToPcmSampleRate = sampleRate
    }
}