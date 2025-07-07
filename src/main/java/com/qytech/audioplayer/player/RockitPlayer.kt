package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import com.qytech.audioplayer.model.AudioInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class RockitPlayer(
    context: Context,
    override val audioInfo: AudioInfo,
) : BaseAudioPlayer(audioInfo) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var progressJob: Job? = null
    private var mediaPlayer: MediaPlayer = MediaPlayer()

    override fun prepare() {
        runCatching {
            mediaPlayer.setDataSource(audioInfo.sourceId)
            mediaPlayer.setOnErrorListener { _, _, _ ->
                updateStateChange(PlaybackState.ERROR)
                updateProgress(0)
                stopProgressUpdate()
                true
            }
            mediaPlayer.setOnCompletionListener {
                stopProgressUpdate()
                updateStateChange(PlaybackState.COMPLETED)
            }
            mediaPlayer.prepare()
            if (needsCueSeek()) {
                seekTo(0)
            }
        }.onFailure {
            Timber.e(it, "prepare error")
            updateStateChange(PlaybackState.ERROR)
        }
    }

    override fun play() {
        mediaPlayer.start()
        updateStateChange(PlaybackState.PLAYING)
        startProgressUpdate()
    }

    override fun pause() {
        mediaPlayer.pause()
        updateStateChange(PlaybackState.PAUSED)
        stopProgressUpdate()
    }

    override fun stop() {
        mediaPlayer.stop()
        updateStateChange(PlaybackState.STOPPED)
        stopProgressUpdate()
    }

    override fun release() {
        mediaPlayer.release()
        updateStateChange(PlaybackState.IDLE)
        stopProgressUpdate()
    }

    override fun seekTo(positionMs: Long) {
        // 需要考虑到CUE文件， SEEK时需要加上起始的偏移量
        val position = if (needsCueSeek()) {
            positionMs + getCueStartTime()
        } else {
            positionMs
        }
        mediaPlayer.seekTo(position.toInt())
    }

    override fun fastForward(ms: Long) {
        var position = getCurrentPosition() + ms
        if (position >= getDuration()) {
            position = getDuration()
        }
        mediaPlayer.seekTo(position.toInt())
    }

    override fun fastRewind(ms: Long) {
        var position = getCurrentPosition() - ms
        if (position < 0) {
            position = 0
        }
        mediaPlayer.seekTo(position.toInt())
    }

    private fun startProgressUpdate() = runCatching {
        progressJob?.cancel()
        progressJob = coroutineScope.launch(Dispatchers.IO) { // 使用IO线程避免阻塞主线程
            while (isActive) {
                // 需要考虑到CUE文件，播放到CUE当前轨道结束的时候应该Stop
                if (needsCueSeek() && getCurrentPosition() >= getDuration()) {
                    stop()
                }
                updateProgress(getCurrentPosition())
                // 延迟更新，每500毫秒更新一次
                delay(500L)
            }
        }
    }


    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }


    override fun getCurrentPosition(): Long = runCatching {
        // 如果是CUE文件，需要加上偏移量
        var position = mediaPlayer.currentPosition.toLong()
        if (needsCueSeek()) {
            position -= getCueStartTime()
        }
        return position
    }.getOrDefault(0)

    override fun setPlaybackSpeed(speed: Float) {
        super.setPlaybackSpeed(speed)
        mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed)
    }

    override fun getPlaybackSpeed(): Float {
        return mediaPlayer.playbackParams.speed
    }

    override fun isPlaying(): Boolean {
        return mediaPlayer.isPlaying
    }
}