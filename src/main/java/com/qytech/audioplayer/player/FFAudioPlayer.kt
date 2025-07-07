package com.qytech.audioplayer.player

import android.media.AudioAttributes
import android.media.AudioFormat
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

class FFAudioPlayer(
    override val audioInfo: AudioInfo,
    val headers: Map<String, String> = emptyMap(),
) : BaseAudioPlayer(audioInfo) {
    private var audioTrack: AudioTrack? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var onPlaybackStateChanged: OnPlaybackStateChangeListener? = null
    private var onProgressListener: OnProgressListener? = null
    private var progressJob: Job? = null

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch(Dispatchers.IO) { // 使用IO线程避免阻塞主线程
            while (isActive) {
                // 需要考虑到CUE文件，播放到CUE当前轨道结束的时候应该Stop
                if (needsCueSeek() && getCurrentPosition() >= getDuration()) {
                    stop()
                }
                updateProgress(getCurrentPosition())
                // 延迟更新，每500毫秒更新一次
                delay(500L)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }


    override fun prepare() {
        runCatching {
            native_init(audioInfo.sourceId, headers)
            initAudioTrack()
            if (needsCueSeek()) {
                seekTo(0)
            }
        }.onFailure {
            Timber.e(it, "prepare error")
            updateStateChange(PlaybackState.ERROR)
        }
    }

    override fun play() {
        runCatching {
            audioTrack?.play()
            native_play()
            startProgressUpdate()
            updateStateChange(PlaybackState.PLAYING)
        }
    }

    override fun pause() {
        audioTrack?.pause()
        native_pause()
        stopProgressUpdate()
        updateStateChange(PlaybackState.PAUSED)
    }

    override fun stop() {
        audioTrack?.stop()
        native_stop()
        stopProgressUpdate()
        updateStateChange(PlaybackState.STOPPED)
    }

    override fun release() {
        audioTrack?.release()
        native_release()
        stopProgressUpdate()
        updateStateChange(PlaybackState.IDLE)
    }

    override fun seekTo(positionMs: Long) {
        val position = if (needsCueSeek()) {
            positionMs + getCueStartTime()
        } else {
            positionMs
        }
        native_seek(position)
        updateProgress(getCurrentPosition())
    }

    override fun fastForward(ms: Long) {
        val currentPosition = getCurrentPosition()
        val newPosition = currentPosition + ms
        if (newPosition < getDuration()) {
            seekTo(newPosition)
        } else {
            seekTo(getDuration())
        }

    }

    override fun fastRewind(ms: Long) {
        val currentPosition = getCurrentPosition()
        val newPosition = currentPosition - ms
        if (newPosition > 0) {
            seekTo(newPosition)
        } else {
            seekTo(0)
        }
    }

    override fun getCurrentPosition(): Long {
        var position = native_getCurrentPosition()
        if (needsCueSeek()) {
            position -= getCueStartTime()
        }
        return position
    }


    override fun isPlaying(): Boolean {
        return native_getPlayState() == 3
    }


    private fun initAudioTrack() = runCatching {
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
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
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
        updateStateChange(PlaybackState.ERROR)
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
    external fun native_init(filePath: String, headers: Map<String, String> = emptyMap())
    external fun native_getPlayState(): Int
    external fun native_getCurrentPosition(): Long
}