package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.qytech.audioplayer.decrypted.FlacAesDataSourceFactory
import com.qytech.audioplayer.decrypted.SecurityKeyDecryptor
import com.qytech.audioplayer.model.AudioInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class ExoAudioPlayer(
    val context: Context,
    val simpleCache: SimpleCache?,
) : AudioPlayer {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val cacheDataSourceFactory by lazy {
        CacheDataSource.Factory().apply {
            simpleCache?.let { setCache(it) }
            setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient.Builder().build()))
        }
    }
    private var player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(cacheDataSourceFactory)
        )
        .build()
    private var onPlaybackStateChanged: OnPlaybackStateChangeListener? = null
    private var onProgressListener: OnProgressListener? = null
    private var progressJob: Job? = null
    private var currentMediaItem: AudioInfo? = null

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
                    // Timber.d("progress: $progress currentPosition: $currentPosition duration: $duration")
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

    override fun setMediaItem(mediaItem: AudioInfo) {
        runCatching {
            val media = MediaItem.fromUri(mediaItem.sourceId)
            currentMediaItem = mediaItem
            Timber.d("setMediaItem ${mediaItem.sourceId}")
            val mediaSource = when {
                mediaItem is AudioInfo.Remote &&
                        mediaItem.sourceId.contains("sonyselect", ignoreCase = true)
                    -> {
                    // 使用自定义解密 DataSource
                    val securityKey = mediaItem.encryptedSecurityKey?.let { encryptedSecurityKey ->
                        SecurityKeyDecryptor.decryptSecurityKey(encryptedSecurityKey)
                    } ?: return@runCatching
                    val initVector = mediaItem.encryptedInitVector ?: return@runCatching

                    val factory = FlacAesDataSourceFactory(
                        upstreamFactory = OkHttpDataSource.Factory(OkHttpClient.Builder().build()),
                        securityKey = securityKey,
                        initVector = initVector
                    )
                    DefaultMediaSourceFactory(factory)
                        .createMediaSource(media)
                }

                mediaItem is AudioInfo.Remote &&
                        (mediaItem.sourceId.contains("u3m8", ignoreCase = true) ||
                                mediaItem.sourceId.contains("m3u8", ignoreCase = true))
                    -> {
                    HlsMediaSource.Factory(cacheDataSourceFactory)
                        .createMediaSource(media)
                }

                else -> {
                    DefaultMediaSourceFactory(cacheDataSourceFactory)
                        .createMediaSource(media)
                }
            }
            player.setMediaSource(mediaSource)
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
        Timber.d("seekTo isCommandAvailable ${player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)} $position ${getDuration()} ")
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
        return currentMediaItem?.duration ?: 0
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
