package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.qytech.audioplayer.strategy.CueMediaSource
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.SacdMediaSource
import com.qytech.audioplayer.utils.QYPlayerLogger
import java.util.concurrent.CopyOnWriteArrayList

enum class PlayerStrategy(
    val value: Int,
) {
    FFmpeg(0),
    SACD(1)
}

abstract class BaseNativePlayer(
    protected val context: Context,
    playerStrategy: PlayerStrategy,
) : AudioPlayer {

    internal val engine = NativePlayerEngine()
    private val listeners = CopyOnWriteArrayList<PlayerListener>()

    @Deprecated("Use PlayerListener instead")
    private var onProgressListener: OnProgressListener? = null

    @Deprecated("Use PlayerListener instead")
    private var onPlaybackStateChangeListener: OnPlaybackStateChangeListener? = null

    private var dsdMode: DSDMode? = null

    private var mediaSource: MediaSource? = null
    private var nextMediaSource: MediaSource? = null

    private var d2pSampleRate: D2pSampleRate? = null

    // 标记是否需要在 prepare 完成后自动播放
    private var playWhenReady = false

    // 标记是否已经通知过 PLAYING 状态，防止在 onAudioData 中频繁回调
    @Volatile
    private var isPlayingNotified = false
    protected var audioTrack: AudioTrack? = null

    private val engineCallback = EngineCallbackImpl()

    init {
        engine.init(playerStrategy, engineCallback)
    }

    override fun setMediaSource(mediaSource: MediaSource) {
        this.mediaSource = mediaSource
    }

    protected fun isNextMediaSourceAccepted(mediaSource: MediaSource): Boolean {
        val nextNeedsSacd = mediaSource is SacdMediaSource
        val currentIsSacd = engine.currentStrategy == PlayerStrategy.SACD

        if (nextNeedsSacd != currentIsSacd) {
            QYPlayerLogger.d("setNextMediaSource: cross-engine, ignored")
            return false
        }

        this.nextMediaSource = mediaSource
        return true
    }

    override fun setNextMediaSource(mediaSource: MediaSource) {
        isNextMediaSourceAccepted(mediaSource)
    }

    override fun setDsdMode(mode: DSDMode) {
        dsdMode = mode
    }

    override fun setD2pSampleRate(sampleRate: D2pSampleRate) {
        d2pSampleRate = sampleRate
    }

    override fun prepare() {
        QYPlayerLogger.d("BaseNativePlayer: prepare")
        playWhenReady = false
        isPlayingNotified = false

        releaseAudioTrack()

        // 设置 DSD 模式和 D2P 采样率
        dsdMode?.let { engine.setDsdConfig(it.value, d2pSampleRate?.hz ?: -1) }
        engine.prepare()
    }

    override fun play() {
        val currentState = engine.getPlayerState()
        QYPlayerLogger.d("BaseNativePlayer: play call. state=$currentState")

        if (currentState == PlaybackState.PREPARING || currentState == PlaybackState.IDLE) {
            QYPlayerLogger.d("BaseNativePlayer: Not ready yet. Set playWhenReady=true")
            playWhenReady = true
            return
        }

        performPlay()
    }

    private fun performPlay() {
        QYPlayerLogger.d("BaseNativePlayer: performPlay state=${engine.getPlayerState()}")

        isPlayingNotified = false

        if (engine.getPlayerState() == PlaybackState.PAUSED) {
            engine.resume()
        } else {
            engine.play()
        }

        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    val playState = track.playState
                    if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    } else {
                        QYPlayerLogger.w("AudioTrack is already playing, skipping play() call")
                    }
                }
            }
        } catch (e: Exception) {
            QYPlayerLogger.e("AudioTrack play failed", e)
        }
    }

    override fun pause() {
        QYPlayerLogger.d("BaseNativePlayer: pause")
        playWhenReady = false
        isPlayingNotified = false

        engine.pause()

        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
            audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
        ) {
            audioTrack?.pause()
        }
        notifyStateChanged(PlaybackState.PAUSED)
    }

    override fun stop() {
        QYPlayerLogger.d("BaseNativePlayer: stop")
        playWhenReady = false
        isPlayingNotified = false

        engine.stop()

        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            try {
                if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.pause()
                }
                audioTrack?.flush()
                audioTrack?.stop()
            } catch (e: Exception) {
                QYPlayerLogger.e("AudioTrack stop failed", e)
            }
        }
        notifyStateChanged(PlaybackState.STOPPED)
    }

    override fun release() {
        QYPlayerLogger.d("BaseNativePlayer: release (Triggering Soft Release)")
        playWhenReady = false
        isPlayingNotified = false

        engine.release()
        releaseAudioTrack()
        listeners.clear()
    }

    override fun seekTo(positionMs: Long) {
        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            audioTrack?.flush()
        }
        isPlayingNotified = false
        engine.seek(positionMs)
    }

    override fun getDuration(): Long = engine.getDuration()
    override fun getPosition(): Long = engine.getPosition()
    override fun getState(): PlaybackState = engine.getPlayerState()

    override fun addListener(listener: PlayerListener) {
        listeners.add(listener)

        val currentState = engine.getPlayerState()
        if (currentState == PlaybackState.PREPARED ||
            currentState == PlaybackState.PLAYING ||
            currentState == PlaybackState.PAUSED ||
            currentState == PlaybackState.BUFFERING
        ) {
            try {
                listener.onPrepared()
                if (currentState != PlaybackState.PREPARED) {
                    listener.onStateChanged(currentState)
                }
            } catch (e: Exception) {
                QYPlayerLogger.e("Error notifying new listener", e)
            }
        }
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }

    private fun notifyStateChanged(state: PlaybackState) {
        mediaSource?.let { source ->
            onPlaybackStateChangeListener?.onPlaybackStateChanged(
                state, source.uri, getTrackIndex()
            )
            listeners.forEach { it.onStateChanged(state) }
        }
    }

    @SuppressLint("InlinedApi")
    private fun getAudioEncoding(bitPerSample: Int): Int {
        return when (bitPerSample) {
            1 -> AudioFormat.ENCODING_DSD
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private inner class EngineCallbackImpl : EngineCallback {
        override fun onPrepared() {
            DsdPlaybackProperty.setDsdPlaybackMode(if (engine.isDsd()) dsdMode else null)
            val sampleRate = engine.getSampleRate()
            val channel = engine.getChannelCount()
            val bitPerSample = engine.getBitPerSample()
            val mediaInfo = engine.getMediaInfo()
            val targetEncoding = getAudioEncoding(bitPerSample)

            try {
                releaseAudioTrack()
                audioTrack = createAudioTrack(sampleRate, targetEncoding, channel)

                mediaSource?.let { source ->
                    listeners.forEach { listener ->
                        listener.onPrepared()
                        mediaInfo?.let { info -> listener.onMetadata(info) }
                    }

                    onPlaybackStateChangeListener?.onPlaybackStateChanged(
                        PlaybackState.PREPARED, source.uri, getTrackIndex()
                    )
                }

                if (playWhenReady) {
                    performPlay()
                    playWhenReady = false
                }
            } catch (e: Exception) {
                QYPlayerLogger.e("AudioTrack acquire error", e)
                listeners.forEach { it.onError(-100, "AudioTrack init failed: ${e.message}") }
            }
        }

        // ==========================================
        // 【核心】无缝切歌状态处理
        // ==========================================
        override fun onTrackTransition() {
            QYPlayerLogger.d("EngineCallback: onTrackTransition triggered (Gapless Switch)")

            if (nextMediaSource != null) {
                mediaSource = nextMediaSource
                nextMediaSource = null
            }

            val sampleRate = engine.getSampleRate()
            val channel = engine.getChannelCount()
            val bitPerSample = engine.getBitPerSample()
            val mediaInfo = engine.getMediaInfo()
            val targetEncoding = getAudioEncoding(bitPerSample)
            val targetChannelCount = if (channel == 4) 4 else 2

            var needRecreate = true
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED &&
                    track.sampleRate == sampleRate &&
                    track.audioFormat == targetEncoding &&
                    track.channelCount == targetChannelCount
                ) {
                    needRecreate = false
                }
            }

            if (needRecreate) {
                QYPlayerLogger.d("Gapless: Audio track parameters changed. Recreating AudioTrack...")
                releaseAudioTrack()
                try {
                    audioTrack = createAudioTrack(sampleRate, targetEncoding, channel)
                    audioTrack?.play()
                } catch (e: Exception) {
                    QYPlayerLogger.e("Gapless: Failed to recreate AudioTrack", e)
                }
            } else {
                QYPlayerLogger.d("Gapless: Audio parameters matched. Reusing existing AudioTrack!")
            }
            QYPlayerLogger.d("EngineCallback: onTrackTransition finished")
            listeners.forEach { listener ->
                listener.onTrackTransition()
                mediaInfo?.let { info -> listener.onMetadata(info) }
            }
        }

        override fun onProgress(track: Int, currentMs: Long, totalMs: Long, progress: Float) {
            if (!isPlayingNotified) {
                isPlayingNotified = true
                notifyStateChanged(PlaybackState.PLAYING)
            }
            listeners.forEach { it.onProgress(track, currentMs, totalMs, progress) }
            onProgressListener?.onProgress(PlaybackProgress(currentMs, progress, totalMs))
        }

        override fun onError(code: Int, msg: String) {
            QYPlayerLogger.e("Native Error: $code, $msg")
            playWhenReady = false
            isPlayingNotified = false
            listeners.forEach { it.onError(code, msg) }
            onPlaybackStateChangeListener?.onPlayerError(msg)
        }

        override fun onComplete() {
            QYPlayerLogger.d("onComplete")
            playWhenReady = false
            isPlayingNotified = false

            listeners.forEach { it.onComplete() }
            mediaSource?.let { source ->
                onPlaybackStateChangeListener?.onPlaybackStateChanged(
                    PlaybackState.COMPLETED, source.uri, getTrackIndex()
                )
            }
        }

        override fun onAudioData(data: ByteArray, size: Int) {
            val currentState = engine.getPlayerState()
            if (currentState == PlaybackState.COMPLETED ||
                currentState == PlaybackState.STOPPED ||
                currentState == PlaybackState.IDLE ||
                currentState == PlaybackState.ERROR
            ) {
                return
            }
            if (!isPlayingNotified) {
                if (currentState == PlaybackState.PLAYING) {
                    isPlayingNotified = true
                    notifyStateChanged(PlaybackState.PLAYING)
                }
            }
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        val ret = track.write(data, 0, size)
                        if (ret < 0) {
                            QYPlayerLogger.e("AudioTrack write error: $ret")
                        }
                    } catch (e: Exception) {
                        QYPlayerLogger.e("AudioTrack write error", e)
                    }
                }
            }
        }

        override fun onBuffering(isBuffering: Boolean) {
            if (isBuffering) {
                isPlayingNotified = false
                notifyStateChanged(PlaybackState.BUFFERING)
            } else {
                isPlayingNotified = true
                notifyStateChanged(PlaybackState.PLAYING)
            }
        }
    }

    @Deprecated("Use PlayerListener instead")
    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener) {
        onPlaybackStateChangeListener = listener
    }

    @Deprecated("Use PlayerListener instead")
    override fun setOnProgressListener(listener: OnProgressListener) {
        onProgressListener = listener
    }

    private fun getTrackIndex(): Int {
        val source = mediaSource ?: -1
        return when (source) {
            is CueMediaSource -> source.trackIndex + 1
            is SacdMediaSource -> source.trackIndex + 1
            else -> -1
        }
    }

    private fun releaseAudioTrack() {
        val track = audioTrack
        audioTrack = null

        track?.let {
            try {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.pause()
                    }
                    it.flush()
                    it.stop()
                }
            } catch (e: Exception) {
                QYPlayerLogger.e("AudioTrack stop/flush error", e)
            }

            try {
                it.release()
                QYPlayerLogger.d("AudioTrack released successfully")
            } catch (e: Exception) {
                QYPlayerLogger.e("AudioTrack release error", e)
            }
        }
    }

    private fun createAudioTrack(sampleRate: Int, encoding: Int, channel: Int): AudioTrack {
        val channelConfig =
            if (channel == 4) AudioFormat.CHANNEL_OUT_QUAD else AudioFormat.CHANNEL_OUT_STEREO
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }


    override fun setTailSkipMs(ms: Long) {
        engine.setTailSkipMs(ms)
    }
}