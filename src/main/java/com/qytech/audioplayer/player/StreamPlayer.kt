package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.qytech.audioplayer.decrypted.FlacAesDataSourceFactory
import com.qytech.audioplayer.decrypted.SecurityKeyDecryptor
import com.qytech.audioplayer.strategy.CueMediaSource
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.SonySelectMediaSource
import com.qytech.audioplayer.strategy.StreamingMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import androidx.media3.exoplayer.source.MediaSource as ExoMediaSource

/**
 * 整合了网络缓存、Sony Select 解密、HLS 支持和 CUE 分轨的播放器。
 * 此时它主要作为：
 * 1. Sony Select 的专用播放器
 * 2. HLS (m3u8) 的专用播放器
 * 3. FFPlayer 播放失败时的 备用播放器 (Fallback)
 */
@SuppressLint("UnsafeOptInUsageError")
class StreamPlayer(
    private val context: Context,
    private val simpleCache: SimpleCache? = null,
) : AudioPlayer {

    private var exoPlayer: ExoPlayer? = null
    private val listeners = CopyOnWriteArrayList<PlayerListener>()

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    private var mediaSource: MediaSource? = null

    @Deprecated("Use PlayerListener instead")
    private var progressListener: OnProgressListener? = null

    @Deprecated("Use PlayerListener instead")
    private var playbackStateChangeListener: OnPlaybackStateChangeListener? = null


    // OkHttpClient 懒加载
    private val okHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    /**
     * 构建通用的 DataSourceFactory
     * 1. 优先使用 DefaultDataSource (支持本地 File/Asset/Content)
     * 2. 网络层使用 OkHttp (支持 Headers)
     * 3. 挂载缓存
     */
    private fun buildDataSourceFactory(headers: Map<String, String> = emptyMap()): DataSource.Factory {
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(headers)

        val defaultDataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)

        return if (simpleCache != null) {
            CacheDataSource.Factory().apply {
                setCache(simpleCache)
                setUpstreamDataSourceFactory(defaultDataSourceFactory)
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        } else {
            defaultDataSourceFactory
        }
    }

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    listeners.forEach { it.onPrepared() }
                    mediaSource?.let { source ->
                        playbackStateChangeListener?.onPlaybackStateChanged(
                            PlaybackState.PREPARED,
                            source.uri, 0
                        )
                    }
                }

                Player.STATE_ENDED -> {
                    stopProgressJob()
                    listeners.forEach { it.onComplete() }
                    mediaSource?.let { source ->
                        playbackStateChangeListener?.onPlaybackStateChanged(
                            PlaybackState.COMPLETED,
                            source.uri, 0
                        )
                    }
                }

                Player.STATE_IDLE -> {
                    stopProgressJob()
                }

                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startProgressJob() else stopProgressJob()
        }

        override fun onPlayerError(error: PlaybackException) {
            stopProgressJob()
            Timber.e(error, "ExoPlayer Error")
            listeners.forEach {
                it.onError(error.errorCode, error.message ?: "ExoPlayer Internal Error")
            }
            playbackStateChangeListener?.onPlayerError(error.message ?: "ExoPlayer Internal Error")
        }
    }

    init {
        initPlayer()
    }

    private fun initPlayer() {
        if (exoPlayer == null) {
            val renderersFactory = DefaultRenderersFactory(context).apply {
                // 优先使用扩展 (如 ffmpeg extension)，提高兼容性
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            }
            exoPlayer = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .build()
            exoPlayer?.addListener(exoListener)
        }
    }

    override fun setMediaSource(mediaSource: MediaSource) {
        this.mediaSource = mediaSource
        initPlayer()

        val uri = Uri.parse(mediaSource.uri)
        val mediaItem = MediaItem.fromUri(uri)

        try {
            // --- 1. 构建基础数据源 ---
            val baseExoSource: ExoMediaSource = when (mediaSource) {
                // A. Sony Select 加密流
                is SonySelectMediaSource -> {
                    val securityKey =
                        SecurityKeyDecryptor.decryptSecurityKey(mediaSource.securityKey)
                    val initVector = mediaSource.initVector

                    if (securityKey != null) {
                        val aesFactory = FlacAesDataSourceFactory(
                            upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
                                .setDefaultRequestProperties(mediaSource.headers),
                            securityKey = securityKey,
                            initVector = initVector
                        )
                        DefaultMediaSourceFactory(aesFactory).createMediaSource(mediaItem)
                    } else {
                        throw PlaybackException(
                            null, null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        )
                    }
                }

                // B. 普通网络流 / HLS
                is StreamingMediaSource -> {
                    val dataSourceFactory = buildDataSourceFactory(mediaSource.headers)
                    val urlLowercase = mediaSource.uri.lowercase(Locale.getDefault())

                    // 强制 HLS 检查
                    if (urlLowercase.contains("m3u8") || urlLowercase.contains("u3m8")) {
                        HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(mediaItem)
                    } else {
                        // 普通流 (MP3/FLAC/Progressive)
                        // 因为 StreamPlayer 是 Fallback，所以这里必须能处理 Progressive 流
                        DefaultMediaSourceFactory(dataSourceFactory)
                            .createMediaSource(mediaItem)
                    }
                }

                // C. 本地文件 / CUE / Default
                else -> {
                    val dataSourceFactory = buildDataSourceFactory()
                    DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
                }
            }

            // --- 2. 处理分轨裁剪 (Clipping) ---
            // 无论是 CUE 还是作为 Backup 的普通流，只要有 start/end，就进行裁剪
            val finalSource = if (mediaSource is CueMediaSource) {
                val startUs = mediaSource.startPosition * 1000L
                val endUs =
                    if (mediaSource.endPosition > 0 && mediaSource.endPosition > mediaSource.startPosition) {
                        mediaSource.endPosition * 1000L
                    } else {
                        C.TIME_END_OF_SOURCE
                    }

                ClippingMediaSource.Builder(baseExoSource)
                    .setStartPositionUs(startUs)
                    .setEndPositionUs(endUs)
                    .setEnableInitialDiscontinuity(true)
                    .setAllowDynamicClippingUpdates(false)
                    .setRelativeToDefaultPosition(true)
                    .build()
            } else {
                baseExoSource
            }

            // --- 3. 设置给播放器 ---
            exoPlayer?.setMediaSource(finalSource)

        } catch (e: Exception) {
            Timber.e(e, "StreamPlayer: Failed to create MediaSource")
            listeners.forEach {
                it.onError(
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                    "Create Source Failed: ${e.message}"
                )
            }
            playbackStateChangeListener?.onPlayerError(
                "Create Source Failed: ${e.message}"
            )
        }
    }


    override fun prepare() {
        mainScope.launch { exoPlayer?.prepare() }
    }

    override fun play() {
        if (exoPlayer == null) initPlayer()
        mainScope.launch { exoPlayer?.play() }
        mediaSource?.let { source ->
            playbackStateChangeListener?.onPlaybackStateChanged(
                PlaybackState.PLAYING,
                source.uri,
                0
            )
        }
    }

    override fun pause() {
        mainScope.launch { exoPlayer?.pause() }
        mediaSource?.let { source ->
            playbackStateChangeListener?.onPlaybackStateChanged(
                PlaybackState.PAUSED,
                source.uri,
                0
            )
        }
    }

    override fun stop() {
        mainScope.launch {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
        mediaSource?.let { source ->
            playbackStateChangeListener?.onPlaybackStateChanged(
                PlaybackState.STOPPED,
                source.uri,
                0
            )
        }
    }

    override fun release() {
        mainScope.launch {
            stopProgressJob()
            exoPlayer?.removeListener(exoListener)
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    override fun seekTo(positionMs: Long) {
        mainScope.launch {
            exoPlayer?.seekTo(positionMs)
        }
    }

    override fun getDuration(): Long {
        return exoPlayer?.duration?.takeIf { it > 0 } ?: 0L
    }

    override fun getPosition(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }

    override fun getState(): PlaybackState {
        val player = exoPlayer ?: return PlaybackState.IDLE
        return when (player.playbackState) {
            Player.STATE_IDLE -> PlaybackState.IDLE
            Player.STATE_BUFFERING -> PlaybackState.PREPARING
            Player.STATE_READY -> if (player.isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
            Player.STATE_ENDED -> PlaybackState.COMPLETED
            else -> PlaybackState.IDLE
        }
    }

    override fun addListener(listener: PlayerListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }


    override fun setDsdMode(mode: DSDMode) {
        Timber.d("DSD mode ignored in StreamPlayer: $mode")
    }

    override fun setD2pSampleRate(sampleRate: D2pSampleRate) {
        Timber.d("D2P sample rate ignored in StreamPlayer: $sampleRate")
    }

    private fun startProgressJob() {
        if (progressJob?.isActive == true) return
        progressJob = mainScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        val current = player.currentPosition
                        val total = player.duration
                        val safeTotal = if (total > 0) total else 1L
                        val progress = current.toFloat() / safeTotal
                        listeners.forEach {
                            it.onProgress(
                                player.currentMediaItemIndex,
                                current,
                                safeTotal,
                                progress
                            )
                        }
                        progressListener?.onProgress(
                            PlaybackProgress(
                                current,
                                progress,
                                safeTotal,
                            )
                        )
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressJob() {
        progressJob?.cancel()
        progressJob = null
    }

    @Deprecated("Use PlayerListener instead")
    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener) {
    }

    @Deprecated("Use PlayerListener instead")
    override fun setOnProgressListener(listener: OnProgressListener) {
    }
}