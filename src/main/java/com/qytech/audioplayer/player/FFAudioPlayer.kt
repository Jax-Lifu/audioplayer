package com.qytech.audioplayer.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.qytech.audioplayer.model.AudioInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

interface FFAudioPlayerCallback {
    fun onAudioDataReceived(data: ByteArray)
}

interface OnCompletionListener {
    fun onCompletion()
}

class FFAudioPlayer : AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var onPlaybackStateChanged: OnPlaybackStateChangeListener? = null
    private var onProgressListener: OnProgressListener? = null
    private var progressJob: Job? = null

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch(Dispatchers.IO) { // 使用IO线程避免阻塞主线程
            while (isActive) {
                updateProgress()
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

    override fun setMediaItem(mediaItem: AudioInfo) {
        native_init(mediaItem.sourceId)
    }

    override fun prepare() {
        runCatching {
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val channelMask = when (native_getChannels()) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                4 -> AudioFormat.CHANNEL_OUT_QUAD
                else -> AudioFormat.CHANNEL_OUT_STEREO
            }
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(native_getSampleRate())
                .setEncoding(encoding)
                .setChannelMask(channelMask)
                .build()

            val bufferSize =
                AudioTrack.getMinBufferSize(native_getSampleRate(), channelMask, encoding)
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            native_setCallback(object : FFAudioPlayerCallback {
                override fun onAudioDataReceived(data: ByteArray) {
                    if (audioTrack?.state != AudioTrack.STATE_UNINITIALIZED &&
                        audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
                    ) {
                        audioTrack?.write(data, 0, data.size)
                    }
                }
            })
            native_setOnCompletionListener(object : OnCompletionListener {
                override fun onCompletion() {
                    onPlaybackStateChanged?.onPlaybackStateChanged(PlaybackState.COMPLETED)
                }
            })
        }.onFailure {
            Timber.e(it, "init audio track failed")
            updatePlaybackState(PlaybackState.ERROR)
        }
    }

    override fun play() {
        runCatching {
            audioTrack?.play()
            native_play()
            startProgressUpdate()
            updatePlaybackState(PlaybackState.PLAYING)
        }
    }

    override fun pause() {
        audioTrack?.pause()
        native_pause()
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.PAUSED)
    }

    override fun stop() {
        audioTrack?.stop()
        native_stop()
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.STOPPED)
    }

    override fun release() {
        audioTrack?.release()
        native_release()
        stopProgressUpdate()
        updatePlaybackState(PlaybackState.IDLE)
    }

    override fun seekTo(position: Long) {
        native_seek(position)
        updateProgress()
    }

    override fun fastForward(milliseconds: Long) {

    }

    override fun fastRewind(milliseconds: Long) {
    }

    override fun getCurrentPosition(): Long {
        return native_getCurrentPosition()
    }

    override fun getDuration(): Long {
        return native_getDuration()
    }

    override fun setPlaybackSpeed(speed: Float) {
    }

    override fun getPlaybackSpeed(): Float {
        return 0f
    }

    override fun isPlaying(): Boolean {
        return native_getPlayState() == 3
    }

    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener?) {
        onPlaybackStateChanged = listener
    }

    override fun setOnProgressListener(listener: OnProgressListener?) {
        onProgressListener = listener
    }

    private fun updateProgress() {
        val currentPosition = getCurrentPosition()
        val duration = getDuration()
        val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
        onProgressListener?.onProgress(PlaybackProgress(currentPosition, progress, duration))
    }

    companion object {
        init {
            System.loadLibrary("audioplayer")
        }
    }

    external fun native_pause()
    external fun native_play()
    external fun native_stop()
    external fun native_release()
    external fun native_seek(position: Long)
    external fun native_getSampleRate(): Int
    external fun native_getChannels(): Int
    external fun native_getDuration(): Long
    external fun native_setCallback(callback: FFAudioPlayerCallback)
    external fun native_setOnCompletionListener(listener: OnCompletionListener)
    external fun native_init(filePath: String)
    external fun native_getPlayState(): Int
    external fun native_getCurrentPosition(): Long
}