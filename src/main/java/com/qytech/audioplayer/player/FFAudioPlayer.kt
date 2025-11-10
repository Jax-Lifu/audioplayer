package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.utils.Logger
import com.qytech.audioplayer.utils.SystemPropUtil
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
    private val context: Context,
    override val audioInfo: AudioInfo,
    val headers: Map<String, String> = emptyMap(),
) : BaseAudioPlayer(audioInfo) {
    private var audioTrack: AudioTrack? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var progressJob: Job? = null

    private var isUsbReceiverRegister = false

    val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val path = intent?.data?.path ?: ""
            if (action == Intent.ACTION_MEDIA_UNMOUNTED ||
                action == Intent.ACTION_MEDIA_EJECT ||
                action == Intent.ACTION_MEDIA_REMOVED
            ) {
                if (audioInfo.sourceId.contains(path)) {
                    Logger.d("usbReceiver onReceive: $action $path")
                    stop()
                    release()
                }
            }
        }
    }


    init {
        if (!isUsbReceiverRegister) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addDataScheme("file")
            }
            context.registerReceiver(usbReceiver, filter)
            isUsbReceiverRegister = true
        }
    }

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
            native_init(audioInfo.sourceId, headers, dsdPlayMode.value, calculateD2pSampleRate())
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
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            return
        }
        runCatching {
            audioTrack?.play()
            native_play()
            startProgressUpdate()
            updateStateChange(PlaybackState.PLAYING)
        }
    }

    override fun pause() {
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            return
        }
        audioTrack?.pause()
        native_pause()
        stopProgressUpdate()
        updateStateChange(PlaybackState.PAUSED)
    }

    override fun stop() {
        SystemPropUtil.set("persist.vendor.dsd_mode", "NULL")
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
        if (isUsbReceiverRegister) {
            context.unregisterReceiver(usbReceiver)
            isUsbReceiverRegister = false
        }

    }

    override fun seekTo(positionMs: Long) {
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            return
        }
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

    private fun isDsd(): Boolean {
        val codecName = audioInfo.codecName.lowercase()
        return codecName.startsWith("dsd") || codecName.startsWith("dst")
    }


    private fun calculateD2pSampleRate(): Int {
        return if (dsdToPcmSampleRate == D2pSampleRate.AUTO) {
            audioInfo.sampleRate / 64
        } else {
            dsdToPcmSampleRate.hz
        }
    }

    @SuppressLint("InlinedApi")
    private fun initAudioTrack() = runCatching {
        SystemPropUtil.set("persist.vendor.dsd_mode", if (isDsd()) dsdPlayMode.name else "NULL")
        val encoding = if (!isDsd()) {
            if (native_getAudioFormat() == 2) {
                AudioFormat.ENCODING_PCM_32BIT
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        } else {
            when (dsdPlayMode) {
                DSDMode.NATIVE -> AudioFormat.ENCODING_DSD
                DSDMode.D2P -> AudioFormat.ENCODING_PCM_16BIT
                DSDMode.DOP -> AudioFormat.ENCODING_PCM_32BIT
            }
        }
        val sampleRate = if (!isDsd()) {
            audioInfo.sampleRate
        } else {
            when (dsdPlayMode) {
                DSDMode.NATIVE -> audioInfo.sampleRate / 32
                DSDMode.D2P -> calculateD2pSampleRate()
                DSDMode.DOP -> audioInfo.sampleRate / 16
            }
        }
        Logger.d("initAudioTrack: sampleRate=$sampleRate, encoding=$encoding bitPreSample ${audioInfo.bitPreSample}")
        val isI2s = SystemPropUtil.getBoolean("persist.sys.audio.i2s", false)

        val channelMask = when {
            // DSD + I2S 强制四声道
            isDsd() && isI2s -> AudioFormat.CHANNEL_OUT_QUAD
            // 其他默认立体声
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()

        val bufferSize =
            AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        native_setCallback(
            object : FFAudioPlayerCallback {
                override fun onAudioDataReceived(data: ByteArray) {
                    if (audioTrack?.state != AudioTrack.STATE_UNINITIALIZED &&
                        audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
                    ) {
                        audioTrack?.write(data, 0, data.size)
                    }
                }
            })
        native_setOnCompletionListener(
            object : OnCompletionListener {
                override fun onCompletion() {
                    updateStateChange(PlaybackState.COMPLETED)
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
    external fun native_getAudioFormat(): Int
    external fun native_setCallback(callback: FFAudioPlayerCallback)
    external fun native_setOnCompletionListener(listener: OnCompletionListener)
    external fun native_init(
        filePath: String,
        headers: Map<String, String> = emptyMap(),
        dsdMode: Int = DSDMode.NATIVE.value,
        d2pSampleRate: Int = D2pSampleRate.PCM_48000.hz,
    )

    external fun native_getPlayState(): Int
    external fun native_getCurrentPosition(): Long
}