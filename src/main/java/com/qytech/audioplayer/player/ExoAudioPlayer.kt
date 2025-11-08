package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
    override val audioInfo: AudioInfo,
    val headers: Map<String, String> = emptyMap(),
) : BaseAudioPlayer(audioInfo) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var progressJob: Job? = null
    private var player: ExoPlayer? = null

    private val cacheDataSourceFactory by lazy {
        CacheDataSource.Factory().apply {
            simpleCache?.let { setCache(it) }
            setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient
                        .Builder()
                        .build()
                ).setDefaultRequestProperties(
                    headers
                )
            )
        }
    }

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_IDLE -> {
                    updateStateChange(PlaybackState.IDLE)
                }

                Player.STATE_BUFFERING -> {
                    updateStateChange(PlaybackState.BUFFERING)
                }

                Player.STATE_READY -> {
                    updateStateChange(PlaybackState.PLAYING)
                }

                Player.STATE_ENDED -> {
                    updateStateChange(PlaybackState.COMPLETED)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            updateStateChange(PlaybackState.ERROR)
            progressListener?.onProgress(PlaybackProgress.DEFAULT)
        }
    }


    init {
        initExoPlayer()
        player?.addListener(exoPlayerListener)

    }

    override fun prepare() {
        val media = MediaItem.fromUri(audioInfo.sourceId)
        val mediaSource = when (audioInfo) {
            is AudioInfo.Local, is AudioInfo.Tidal -> {
                DefaultMediaSourceFactory(cacheDataSourceFactory)
                    .createMediaSource(media)
            }

            is AudioInfo.Remote -> {
                when {
                    isSonySelectMediaSource(audioInfo) -> {
                        val securityKey =
                            audioInfo.encryptedSecurityKey?.let { encryptedSecurityKey ->
                                SecurityKeyDecryptor.decryptSecurityKey(encryptedSecurityKey)
                            } ?: return
                        val initVector = audioInfo.encryptedInitVector ?: return

                        val factory = FlacAesDataSourceFactory(
                            upstreamFactory = OkHttpDataSource.Factory(
                                OkHttpClient.Builder().build()
                            ),
                            securityKey = securityKey,
                            initVector = initVector
                        )
                        DefaultMediaSourceFactory(factory)
                            .createMediaSource(media)
                    }

                    isHlsMediaSource(audioInfo) -> {
                        HlsMediaSource.Factory(cacheDataSourceFactory)
                            .createMediaSource(media)
                    }

                    else -> {
                        ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                            .createMediaSource(media)
                    }
                }
            }
        }
        coroutineScope.launch(Dispatchers.Main) {
            player?.setMediaSource(mediaSource)
            player?.prepare()
            if (needsCueSeek()) {
                seekTo(0)
            }
        }
    }

    private fun isSonySelectMediaSource(audioInfo: AudioInfo.Remote): Boolean =
        audioInfo.sourceId.contains("sonyselect", ignoreCase = true)

    private fun isHlsMediaSource(audioInfo: AudioInfo.Remote): Boolean =
        (audioInfo.sourceId.contains("u3m8", ignoreCase = true) ||
                audioInfo.sourceId.contains("m3u8", ignoreCase = true))

    override fun play() {
        coroutineScope.launch(Dispatchers.Main) {
            player?.play()
        }
        updateStateChange(PlaybackState.PLAYING)
        startProgressJob()
    }

    override fun pause() {
        coroutineScope.launch(Dispatchers.Main) {
            player?.pause()
        }
        stopProgressJob()
        updateStateChange(PlaybackState.PAUSED)
    }

    override fun stop() {
        coroutineScope.launch(Dispatchers.Main) {
            player?.stop()
        }
        stopProgressJob()
        updateStateChange(PlaybackState.STOPPED)
    }

    override fun release() {
        coroutineScope.launch(Dispatchers.Main) {
            player?.stop()
            player?.release()
        }
        stopProgressJob()
    }

    override fun seekTo(positionMs: Long) {
        coroutineScope.launch(Dispatchers.Main) {
            if (player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true) {
                val position = if (needsCueSeek()) {
                    positionMs + getCueStartTime()
                } else {
                    positionMs
                }
                player?.seekTo(position)
            }
        }
    }

    override fun fastForward(ms: Long) {
        coroutineScope.launch(Dispatchers.Main) {
            if (player?.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) == true) {
                player?.seekForward()
            }
        }
    }

    override fun fastRewind(ms: Long) {
        coroutineScope.launch(Dispatchers.Main) {
            if (player?.isCommandAvailable(Player.COMMAND_SEEK_BACK) == true) {
                player?.seekBack()
            }
        }
    }


    override fun getDuration(): Long {
        return player?.duration ?: 0L
    }

    override fun getCurrentPosition(): Long {
        var position = player?.currentPosition ?: 0L
        if (needsCueSeek()) {
            position -= getCueStartTime()
        }
        return position
    }

    override fun isPlaying(): Boolean = player?.isPlaying ?: super.isPlaying()

    override fun getPlaybackSpeed(): Float =
        player?.playbackParameters?.speed ?: super.getPlaybackSpeed()

    override fun setPlaybackSpeed(speed: Float) {
        super.setPlaybackSpeed(speed)
        player?.let {
            it.playbackParameters = it.playbackParameters.withSpeed(speed)
        }
    }

    private fun initExoPlayer() = runCatching {
        player = ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context).apply {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .build()
    }.onFailure {
        Timber.e(it, "initExoPlayer error")
    }

    private fun startProgressJob() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch {
            while (isActive) {
                withContext(Dispatchers.Main) {
                    // 需要考虑到CUE文件，播放到CUE当前轨道结束的时候应该Stop
                    if (needsCueSeek() && getCurrentPosition() >= getDuration()) {
                        stop()
                    }
                    updateProgress(getCurrentPosition())
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressJob() {
        progressJob?.cancel()
        progressJob = null
    }
}
