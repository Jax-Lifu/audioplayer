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

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch(Dispatchers.IO) { // 使用IO线程避免阻塞主线程
            while (isActive) {
                val progress = withContext(Dispatchers.Main) { // 在主线程更新UI
                    val currentPosition = getCurrentPosition()
                    val duration = getDuration()
                    val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
                    PlaybackProgress(currentPosition, progress, duration)
                }
                onProgressListener?.onProgress(progress)
                // 延迟更新，假设每500毫秒更新一次
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
        }
    }

    override fun prepare() {
        mediaPlayer.setOnErrorListener { _, _, _ ->
            updatePlaybackState(PlaybackState.ERROR) // 更新播放状态
            onProgressListener?.onProgress(PlaybackProgress.DEFAULT)
            stopProgressUpdate()
            true
        }
        mediaPlayer.setOnCompletionListener {
            updatePlaybackState(PlaybackState.COMPLETED) // 更新播放状态
            stopProgressUpdate()
        }
        mediaPlayer.prepare()
    }

    override fun play() {
        mediaPlayer.start()
        updatePlaybackState(PlaybackState.PLAYING) // 更新播放状态
        startProgressUpdate()
    }

    override fun pause() {
        mediaPlayer.pause()
        updatePlaybackState(PlaybackState.PAUSED) // 更新播放状态
        stopProgressUpdate()

    }

    override fun stop() {
        mediaPlayer.stop()
        updatePlaybackState(PlaybackState.STOPPED) // 更新播放状态
        stopProgressUpdate()
    }

    override fun release() {
        mediaPlayer.release()
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.IDLE) // 更新播放状态
        onPlaybackStateChanged = null
        onProgressListener = null
    }

    override fun seekTo(position: Long) {
        Timber.d("seekTo: $position")
        mediaPlayer.seekTo((position * 1000).toInt())
    }

    override fun fastForward(milliseconds: Long) {
        var position = getCurrentPosition() + milliseconds
        if (position >= getDuration()) {
            position = getDuration()
        }
        mediaPlayer.seekTo((position * 1000).toInt())
    }

    override fun fastRewind(milliseconds: Long) {
        var position = getCurrentPosition() - milliseconds
        if (position < 0) {
            position = 0
        }
        mediaPlayer.seekTo((position * 1000).toInt())
    }

    override fun getCurrentPosition(): Long {
        return (mediaPlayer.currentPosition / 1000).toLong()
    }

    override fun getDuration(): Long {
        return (mediaPlayer.duration / 1000).toLong()
    }

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed)
    }

    override fun getPlaybackSpeed(): Float {
        return mediaPlayer.playbackParams.speed
    }

    override fun setVolume(volume: Float) {

    }

    override fun getVolume(): Float {
        return 1f
    }

    override fun setMute(isMuted: Boolean) {
    }

    override fun isMuted(): Boolean {
        return false
    }

    override fun getBufferedPosition(): Long {
        return 0
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
