package com.qytech.audioplayer.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import com.qytech.audioplayer.strategy.CueMediaSource
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.SacdMediaSource
import com.qytech.audioplayer.utils.QYLogger
import timber.log.Timber
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
    protected var currentTrackRef: AudioTrack? = null
    private var trackSessionId: Long = -1

    private var dsdMode: DSDMode? = null
    private var mediaSource: MediaSource? = null
    private var d2pSampleRate: D2pSampleRate? = null

    private var playWhenReady = false

    private val engineCallback = EngineCallbackImpl()

    init {
        engine.init(playerStrategy, engineCallback)
    }

    override fun setMediaSource(mediaSource: MediaSource) {
        this.mediaSource = mediaSource
    }

    override fun setDsdMode(mode: DSDMode) {
        dsdMode = mode
    }

    override fun setD2pSampleRate(sampleRate: D2pSampleRate) {
        d2pSampleRate = sampleRate
    }

    override fun prepare() {
        QYLogger.d("BaseNativePlayer: prepare")
        playWhenReady = false
        // 设置 DSD 模式和 D2P 采样率
        dsdMode?.let { engine.setDsdConfig(it.value, d2pSampleRate?.hz ?: -1) }
        engine.prepare()
    }

    override fun play() {
        val currentState = engine.getPlayerState()
        QYLogger.d("BaseNativePlayer: play call. state=$currentState")

        if (currentState == PlaybackState.PREPARING || currentState == PlaybackState.IDLE) {
            QYLogger.d("BaseNativePlayer: Not ready yet. Set playWhenReady=true")
            playWhenReady = true
            return
        }

        // 状态正常，执行播放
        performPlay()
    }

    // [修复1] 提取实际播放逻辑
    private fun performPlay() {
        QYLogger.d("BaseNativePlayer: performPlay state=${engine.getPlayerState()}")

        // Native 引擎控制
        if (engine.getPlayerState() == PlaybackState.PAUSED) {
            QYLogger.d("engine $engine resume")
            engine.resume()
        } else {
            QYLogger.d("engine $engine play")
            engine.play()
        }

        try {
            currentTrackRef?.let { track ->
                Timber.d("AudioTrack check: state=${track.state}, playState=${track.playState}")
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                }
            }
        } catch (e: Exception) {
            QYLogger.e("AudioTrack play failed", e)
        }

        // 通知状态变更
        QYLogger.d("BaseNativePlayer: notifying PLAYING")
        mediaSource?.let { source ->
            onPlaybackStateChangeListener?.onPlaybackStateChanged(
                PlaybackState.PLAYING,
                source.uri,
                getTrackIndex()
            )
            listeners.forEach { it.onStateChanged(PlaybackState.PLAYING) }
        }
    }

    override fun pause() {
        QYLogger.d("BaseNativePlayer: pause")
        playWhenReady = false
        engine.pause()

        // AudioTrack 暂停
        if (currentTrackRef?.state == AudioTrack.STATE_INITIALIZED &&
            currentTrackRef?.playState == AudioTrack.PLAYSTATE_PLAYING
        ) {
            currentTrackRef?.pause()
        }

        mediaSource?.let { source ->
            onPlaybackStateChangeListener?.onPlaybackStateChanged(
                PlaybackState.PAUSED,
                source.uri,
                getTrackIndex()
            )
            listeners.forEach { it.onStateChanged(PlaybackState.PAUSED) }
        }
    }

    override fun stop() {
        QYLogger.d("BaseNativePlayer: stop")
        playWhenReady = false
        engine.stop()

        // stop 时只做 flush，随时准备下次播放
        if (currentTrackRef?.state == AudioTrack.STATE_INITIALIZED) {
            try {
                if (currentTrackRef?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    currentTrackRef?.pause()
                }
                currentTrackRef?.flush()
                currentTrackRef?.stop()
            } catch (e: Exception) {
                QYLogger.e("AudioTrack stop failed", e)
            }
        }

        mediaSource?.let { source ->
            onPlaybackStateChangeListener?.onPlaybackStateChanged(
                PlaybackState.STOPPED,
                source.uri,
                getTrackIndex()
            )
            listeners.forEach { it.onStateChanged(PlaybackState.STOPPED) }
        }
    }

    override fun release() {
        QYLogger.d("BaseNativePlayer: release (Triggering Soft Release)")
        playWhenReady = false
        engine.release()

        // 调用管理器的软释放，不销毁 AudioTrack 硬件资源
        GlobalAudioTrackManager.softRelease(trackSessionId)
        currentTrackRef = null
        trackSessionId = -1
        listeners.clear()
    }

    override fun seekTo(positionMs: Long) {
        // seek 前清理缓冲区，防止听到 seek 前的残留声音
        if (currentTrackRef?.state == AudioTrack.STATE_INITIALIZED) {
            currentTrackRef?.flush()
        }
        engine.seek(positionMs)
    }

    override fun getDuration(): Long = engine.getDuration()

    override fun getPosition(): Long = engine.getPosition()

    override fun getState(): PlaybackState = engine.getPlayerState()

    override fun addListener(listener: PlayerListener) {
        listeners.add(listener)

        val currentState = engine.getPlayerState()
        // 如果已经准备好（包含准备好、播放中、暂停中），立即补发 onPrepared
        if (currentState == PlaybackState.PREPARED ||
            currentState == PlaybackState.PLAYING ||
            currentState == PlaybackState.PAUSED
        ) {
            QYLogger.d("addListener: Player is already prepared, notifying new listener immediately.")
            try {
                listener.onPrepared()
                // 如果是播放或暂停状态，也可以选择补充回调 onStateChanged
                if (currentState != PlaybackState.PREPARED) {
                    listener.onStateChanged(currentState)
                }
            } catch (e: Exception) {
                QYLogger.e("Error notifying new listener", e)
            }
        }
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }

    // 辅助方法：将位深转换为 AudioFormat 编码
    private fun getAudioEncoding(bitPerSample: Int): Int {
        return when (bitPerSample) {
            1 -> AudioFormat.ENCODING_DSD // 需要系统支持或特定的 AudioTrack 配置
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
            val targetEncoding = getAudioEncoding(bitPerSample)

            QYLogger.d("onPrepared: sampleRate=$sampleRate, bitPerSample=$bitPerSample")

            try {
                // 尝试复用 AudioTrack
                val result = GlobalAudioTrackManager.acquireAudioTrack(
                    sampleRate,
                    targetEncoding,
                    channel
                )
                currentTrackRef = result.first
                trackSessionId = result.second

                QYLogger.d("onPrepared $mediaSource listeners:${listeners.size}")

                mediaSource?.let { source ->
                    // 1. 通知所有监听器
                    listeners.forEach { it.onPrepared() }

                    onPlaybackStateChangeListener?.onPlaybackStateChanged(
                        PlaybackState.PREPARED,
                        source.uri,
                        getTrackIndex()
                    )
                }

                if (playWhenReady) {
                    QYLogger.d("onPrepared: playWhenReady is true -> Auto starting play")
                    performPlay()
                    playWhenReady = false // 消费标记
                }

            } catch (e: Exception) {
                QYLogger.e("AudioTrack acquire error", e)
                listeners.forEach { it.onError(-100, "AudioTrack init failed: ${e.message}") }
            }
        }

        override fun onProgress(track: Int, currentMs: Long, totalMs: Long, progress: Float) {
            listeners.forEach { it.onProgress(track, currentMs, totalMs, progress) }
            onProgressListener?.onProgress(PlaybackProgress(currentMs, progress, totalMs))
        }

        override fun onError(code: Int, msg: String) {
            QYLogger.e("Native Error: $code, $msg")
            playWhenReady = false // 发生错误，重置自动播放
            listeners.forEach { it.onError(code, msg) }
            onPlaybackStateChangeListener?.onPlayerError(msg)
        }

        override fun onComplete() {
            QYLogger.d("onComplete")
            playWhenReady = false
            mediaSource?.let { source ->
                listeners.forEach { it.onComplete() }
                onPlaybackStateChangeListener?.onPlaybackStateChanged(
                    PlaybackState.COMPLETED,
                    source.uri,
                    getTrackIndex()
                )
            }
        }

        override fun onAudioData(data: ByteArray, size: Int) {
            currentTrackRef?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    try {
                        val ret = track.write(data, 0, size)
                        if (ret < 0) {
                            QYLogger.e("AudioTrack write error: $ret")
                        }
                    } catch (e: Exception) {
                        QYLogger.e("AudioTrack write error", e)
                    }
                }
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
            is CueMediaSource -> {
                source.trackIndex + 1
            }

            is SacdMediaSource -> {
                source.trackIndex + 1
            }

            else -> {
                -1
            }
        }
    }
}