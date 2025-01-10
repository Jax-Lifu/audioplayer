package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.qytech.audioplayer.model.AudioFileInfo
import kotlinx.coroutines.*
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class ExoAudioPlayer(val context: Context) : AudioPlayer {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }
        )
        .build()
    private var onPlaybackStateChanged: OnPlaybackStateChangeListener? = null
    private var onProgressListener: OnProgressListener? = null
    private var progressJob: Job? = null
    private var currentMediaItem: AudioFileInfo? = null

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
                    val progress = currentPosition.toFloat() / duration
                    Timber.d("progress: $progress currentPosition: $currentPosition duration: $duration")
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
        runCatching {
            val media = MediaItem.fromUri(mediaItem.filePath)
            currentMediaItem = mediaItem
            player.setMediaItem(media)
        }.onFailure {
            updatePlaybackState(PlaybackState.ERROR)
        }
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
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            player.seekTo(position)
        }
    }

    override fun fastForward(milliseconds: Long) {
        if (player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
            player.seekForward()
        }
    }

    override fun fastRewind(milliseconds: Long) {
        if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
            player.seekBack()
        }
    }

    override fun getCurrentPosition(): Long {
        if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            return player.currentPosition
        }
        return 0
    }

    override fun getDuration(): Long {
        if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) && player.duration != C.TIME_UNSET) {
            return player.duration
        }
        return currentMediaItem?.trackInfo?.duration ?: 0
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters.speed = speed
    }

    override fun getPlaybackSpeed(): Float {
        return player.playbackParameters.speed
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
