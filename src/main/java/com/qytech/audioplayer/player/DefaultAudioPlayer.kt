package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.qytech.audioplayer.model.AudioFileInfo
import kotlinx.coroutines.*
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class DefaultAudioPlayer(context: Context) : AudioPlayer {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var player: ExoPlayer = ExoPlayer.Builder(context, QYRenderersFactory(context)).build()
    private var onPlaybackStateChanged: OnPlaybackStateChangeListener? = null
    private var onProgressListener: OnProgressListener? = null
    private var progressJob: Job? = null

    init {
        // 监听播放器状态变化
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> updatePlaybackState(PlaybackState.IDLE)
                    Player.STATE_BUFFERING -> updatePlaybackState(PlaybackState.BUFFERING)
                    Player.STATE_READY -> updatePlaybackState(PlaybackState.PLAYING)
                    Player.STATE_ENDED -> updatePlaybackState(PlaybackState.COMPLETED)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                updatePlaybackState(PlaybackState.ERROR)
                onProgressListener?.onProgress(PlaybackProgress.DEFAULT)
                stopProgressUpdate()
            }
        })
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val progress = withContext(Dispatchers.Main) {
                    val currentPosition = getCurrentPosition()
                    val duration = getDuration()
                    val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
                    PlaybackProgress(currentPosition, progress, duration)
                }
                onProgressListener?.onProgress(progress)
                delay(500L) // 每500ms更新一次进度
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
        val media = MediaItem.fromUri(mediaItem.filePath) // 使用 ExoPlayer 的 MediaItem
        player.setMediaItem(media)
    }

    override fun prepare() {
        player.prepare()
    }

    override fun play() {
        player.play()
        updatePlaybackState(PlaybackState.PLAYING)
        startProgressUpdate()
    }

    override fun pause() {
        player.pause()
        updatePlaybackState(PlaybackState.PAUSED)
        stopProgressUpdate()
    }

    override fun stop() {
        player.stop()
        updatePlaybackState(PlaybackState.STOPPED)
        stopProgressUpdate()
    }

    override fun release() {
        player.release()
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.IDLE)
        onPlaybackStateChanged = null
        onProgressListener = null
    }

    override fun seekTo(position: Long) {
        Timber.d("seekTo: $position")
        player.seekTo(position * 1000) // ExoPlayer 的 seekTo 接受毫秒值
    }

    override fun fastForward(seconds: Long) {
        var position = getCurrentPosition() + seconds
        if (position >= getDuration()) {
            position = getDuration()
        }
        player.seekTo(position * 1000)
    }

    override fun fastRewind(seconds: Long) {
        var position = getCurrentPosition() - seconds
        if (position < 0) {
            position = 0
        }
        player.seekTo(position * 1000)
    }

    override fun getCurrentPosition(): Long {
        return player.currentPosition / 1000 // 转换为秒
    }

    override fun getDuration(): Long {
        return player.duration / 1000 // 转换为秒
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters.speed = speed
    }

    override fun getPlaybackSpeed(): Float {
        return player.playbackParameters.speed
    }

    override fun setVolume(volume: Float) {
        // ExoPlayer 暂时没有直接设置音量的 API，通常需要操作音量控件
    }

    override fun getVolume(): Float {
        return 1f
    }

    override fun setMute(isMuted: Boolean) {
        // ExoPlayer 暂时没有直接设置静音的 API，通常需要操作音量控件
    }

    override fun isMuted(): Boolean {
        return false
    }

    override fun getBufferedPosition(): Long {
        return player.bufferedPosition / 1000 // 转换为秒
    }

    override fun isPlaying(): Boolean {
        return player.isPlaying
    }

    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener?) {
        onPlaybackStateChanged = listener
    }

    override fun setOnProgressListener(listener: OnProgressListener?) {
        onProgressListener = listener
    }
}
