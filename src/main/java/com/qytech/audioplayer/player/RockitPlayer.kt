package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import com.qytech.audioplayer.model.AudioFileInfo
import kotlinx.coroutines.*
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class RockitPlayer(context: Context) : AudioPlayer {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var mediaPlayer: MediaPlayer = MediaPlayer()
    private var onPlaybackStateChanged: OnPlaybackStateChangeListener? = null
    private var onProgressListener: OnProgressListener? = null
    private var progressJob: Job? = null

    private fun startProgressUpdate() = runCatching {
        progressJob?.cancel()
        progressJob = coroutineScope.launch(Dispatchers.IO) { // 使用IO线程避免阻塞主线程
            while (isActive) {
                val currentPosition = getCurrentPosition()
                val duration = getDuration()
                val progress = currentPosition.toFloat() / duration
                onProgressListener?.onProgress(
                    PlaybackProgress(
                        currentPosition,
                        progress,
                        duration
                    )
                )
                // 延迟更新，每500毫秒更新一次
                delay(500L)
            }
        }
    }


    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updatePlaybackState(state: PlaybackState) {
        onPlaybackStateChanged?.onPlaybackStateChanged(state)
    }

    override fun setMediaItem(mediaItem: AudioFileInfo) {
        runCatching {
            mediaPlayer.setDataSource(mediaItem.filePath)
        }.onFailure {
            Timber.e(it, "setDataSource error")
        }
    }

    override fun prepare() {
        runCatching {
            mediaPlayer.setOnErrorListener { _, _, _ ->
                updatePlaybackState(PlaybackState.ERROR) // 更新播放状态
                onProgressListener?.onProgress(PlaybackProgress.DEFAULT)
                stopProgressUpdate()
                true
            }
            mediaPlayer.setOnCompletionListener {
                stopProgressUpdate()
                updatePlaybackState(PlaybackState.COMPLETED) // 更新播放状态
            }
            mediaPlayer.prepare()
        }.onFailure {
            Timber.e(it, "prepare error")
            updatePlaybackState(PlaybackState.ERROR) // 如果准备失败，设为错误状态
        }
    }

    override fun play() {
        mediaPlayer.start()
        updatePlaybackState(PlaybackState.PLAYING) // 更新播放状态
        startProgressUpdate()
    }

    override fun pause() {
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.PAUSED) // 更新播放状态
        mediaPlayer.pause()

    }

    override fun stop() {
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.STOPPED) // 更新播放状态
        mediaPlayer.stop()
    }

    override fun release() {
        mediaPlayer.release()
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.IDLE) // 更新播放状态
        onPlaybackStateChanged = null
        onProgressListener = null
    }

    override fun seekTo(position: Long) {
        mediaPlayer.seekTo(position.toInt())
    }

    override fun fastForward(milliseconds: Long) {
        var position = getCurrentPosition() + milliseconds
        if (position >= getDuration()) {
            position = getDuration()
        }
        mediaPlayer.seekTo(position.toInt())
    }

    override fun fastRewind(milliseconds: Long) {
        var position = getCurrentPosition() - milliseconds
        if (position < 0) {
            position = 0
        }
        mediaPlayer.seekTo(position.toInt())
    }

    override fun getCurrentPosition(): Long = runCatching {
        return mediaPlayer.currentPosition.toLong()
    }.getOrDefault(0)

    override fun getDuration(): Long = runCatching {
        return mediaPlayer.duration.toLong()
    }.getOrDefault(0)

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed)
    }

    override fun getPlaybackSpeed(): Float {
        return mediaPlayer.playbackParams.speed
    }

    override fun isPlaying(): Boolean {
        return mediaPlayer.isPlaying
    }

    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener?) {
        onPlaybackStateChanged = listener
    }

    override fun setOnProgressListener(listener: OnProgressListener?) {
        onProgressListener = listener
    }
}
