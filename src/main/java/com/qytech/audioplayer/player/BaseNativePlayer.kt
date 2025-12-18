package com.qytech.audioplayer.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import com.qytech.audioplayer.strategy.CueMediaSource
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.SacdMediaSource
import com.qytech.audioplayer.utils.QYLogger
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

    // 标记是否需要在 prepare 完成后自动播放
    private var playWhenReady = false

    // 标记是否已经通知过 PLAYING 状态，防止在 onAudioData 中频繁回调
    @Volatile
    private var isPlayingNotified = false

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
        isPlayingNotified = false
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

    // [优化] 仅执行底层播放逻辑，不负责通知 UI 状态
    private fun performPlay() {
        QYLogger.d("BaseNativePlayer: performPlay state=${engine.getPlayerState()}")

        // 重置标记，等待数据到来时再次通知
        isPlayingNotified = false

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
                QYLogger.d("AudioTrack check: state=${track.state}, playState=${track.playState}")
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                }
            }
        } catch (e: Exception) {
            QYLogger.e("AudioTrack play failed", e)
        }
    }

    override fun pause() {
        QYLogger.d("BaseNativePlayer: pause")
        playWhenReady = false
        isPlayingNotified = false

        engine.pause()

        // AudioTrack 暂停
        if (currentTrackRef?.state == AudioTrack.STATE_INITIALIZED &&
            currentTrackRef?.playState == AudioTrack.PLAYSTATE_PLAYING
        ) {
            currentTrackRef?.pause()
        }

        // PAUSED 是用户主动触发的，立即通知
        notifyStateChanged(PlaybackState.PAUSED)
    }

    override fun stop() {
        QYLogger.d("BaseNativePlayer: stop")
        playWhenReady = false
        isPlayingNotified = false

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

        // STOPPED 是用户主动触发的，立即通知
        notifyStateChanged(PlaybackState.STOPPED)
    }

    override fun release() {
        QYLogger.d("BaseNativePlayer: release (Triggering Soft Release)")
        playWhenReady = false
        isPlayingNotified = false

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
        // seek 后通常需要缓冲，重置标记
        isPlayingNotified = false
        engine.seek(positionMs)
    }

    override fun getDuration(): Long = engine.getDuration()

    override fun getPosition(): Long = engine.getPosition()

    override fun getState(): PlaybackState = engine.getPlayerState()

    override fun addListener(listener: PlayerListener) {
        listeners.add(listener)

        val currentState = engine.getPlayerState()
        // 如果已经准备好（包含准备好、播放中、暂停中），立即补发回调
        if (currentState == PlaybackState.PREPARED ||
            currentState == PlaybackState.PLAYING ||
            currentState == PlaybackState.PAUSED ||
            currentState == PlaybackState.BUFFERING
        ) {
            QYLogger.d("addListener: Player is active, notifying new listener immediately.")
            try {
                // 必定补发 onPrepared
                listener.onPrepared()
                // 如果不是单纯的 prepared 状态，补发当前具体状态
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

    private fun notifyStateChanged(state: PlaybackState) {
        mediaSource?.let { source ->
            onPlaybackStateChangeListener?.onPlaybackStateChanged(
                state,
                source.uri,
                getTrackIndex()
            )
            listeners.forEach { it.onStateChanged(state) }
        }
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
            if (!isPlayingNotified) {
                QYLogger.d("onProgress triggers PLAYING notification (Fallback)")
                isPlayingNotified = true
                notifyStateChanged(PlaybackState.PLAYING)
            }

            listeners.forEach { it.onProgress(track, currentMs, totalMs, progress) }
            onProgressListener?.onProgress(PlaybackProgress(currentMs, progress, totalMs))
        }

        override fun onError(code: Int, msg: String) {
            QYLogger.e("Native Error: $code, $msg")
            playWhenReady = false
            isPlayingNotified = false
            listeners.forEach { it.onError(code, msg) }
            onPlaybackStateChangeListener?.onPlayerError(msg)
        }

        override fun onComplete() {
            QYLogger.d("onComplete")
            playWhenReady = false
            isPlayingNotified = false

            listeners.forEach { it.onComplete() }
            mediaSource?.let { source ->
                onPlaybackStateChangeListener?.onPlaybackStateChanged(
                    PlaybackState.COMPLETED,
                    source.uri,
                    getTrackIndex()
                )
            }
        }

        override fun onAudioData(data: ByteArray, size: Int) {
            if (!isPlayingNotified) {
                if (engine.getPlayerState() != PlaybackState.BUFFERING) {
                    QYLogger.d("onAudioData: First data received, notifying PLAYING")
                    isPlayingNotified = true
                    notifyStateChanged(PlaybackState.PLAYING)
                }
            }

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

        override fun onBuffering(isBuffering: Boolean) {
            if (isBuffering) {
                QYLogger.d("onBuffering: START")
                // 开始缓冲，不再是播放状态
                isPlayingNotified = false
                notifyStateChanged(PlaybackState.BUFFERING)
            } else {
                QYLogger.d("onBuffering: END -> notifying PLAYING")
                // 缓冲结束，恢复播放
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