package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.qytech.audioplayer.audioframe.DffAudioFrame
import com.qytech.audioplayer.audioframe.DsfAudioFrame
import com.qytech.audioplayer.audioframe.SacdAudioFrame
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.parser.DffAudioFileParser
import com.qytech.audioplayer.parser.DsfAudioFileParser
import com.qytech.audioplayer.parser.SacdAudioFileParser
import com.qytech.audioplayer.stream.SeekableInputStreamFactory
import com.qytech.audioplayer.utils.DsdInterleavedToPcm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong


class DsdAudioPlayer(
    context: Context,
    override val audioInfo: AudioInfo,
    private val headers: Map<String, String> = emptyMap(),
) : BaseAudioPlayer(audioInfo) {
    private val lock = ReentrantLock()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private var audioTrack: AudioTrack? = null

    private var offsetPreSeconds = -1L
    private var previousOffset = -1L
    private var currentOffset = 0L
    private var currentPosition = -1L
    private var seeking = false
    private var stopped = false
    private var paused = false

    private var shouldCancelDstDecode = AtomicBoolean(false)

    override fun prepare() {
        initializeAudioTrack()
    }

    override fun play() {
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            return
        }
        stopped = false
        runCatching {
            audioTrack?.let {
                lock.withLock {
                    if (paused) {
                        paused = false
                        it.play()
                    } else {
                        playDsd()
                    }
                    updateStateChange(PlaybackState.PLAYING)
                }
            }
        }.onFailure {
            Timber.e(it, "play error")
            onPlayerError(it)
        }
    }

    override fun pause() {
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            return
        }
        runCatching {
            lock.withLock {
                paused = true
                audioTrack?.pause()
                updateStateChange(PlaybackState.PAUSED)
            }
        }.onFailure {
            Timber.e(it, "pause error")
            onPlayerError(it)
        }
    }

    override fun stop() {
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            return
        }
        runCatching {
            shouldCancelDstDecode.set(true)
            lock.withLock {
                stopped = true
                paused = false
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                updateStateChange(PlaybackState.STOPPED)
                updateProgress(0)
            }
        }.onFailure {
            Timber.e(it, "stop error")
            onPlayerError(it)
        }
    }

    override fun release() {
        runCatching {
            shouldCancelDstDecode.set(true)
            lock.withLock {
                seeking = false
                paused = false
                stopped = true
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                offsetPreSeconds = -1L
                previousOffset = -1L
                currentOffset = 0L
                currentPosition = -1L
                updateStateChange(PlaybackState.IDLE)
                updateProgress(0)
            }
        }.onFailure {
            Timber.e(it, "release error")
            onPlayerError(it)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            return
        }
        if (offsetPreSeconds == -1L) {
            Timber.d("seek failed: offsetPreSeconds = $offsetPreSeconds")
            return
        }
        runCatching {
            val startOffset = audioInfo.startOffset ?: return
            shouldCancelDstDecode.set(true)
            lock.withLock {
                val seconds = positionMs / 1000
                currentPosition = seconds
                currentOffset = startOffset + seconds * offsetPreSeconds
                seeking = true
            }

        }.onFailure {
            Timber.e(it, "seekTo error")
        }
    }

    override fun fastForward(ms: Long) {
        lock.withLock {
            var pos = getCurrentPosition() + ms
            if (pos > getDuration()) pos = getDuration()
            seekTo(pos)
        }
    }

    override fun fastRewind(ms: Long) {
        lock.withLock {
            var pos = getCurrentPosition() - ms
            if (pos < 0) pos = 0
            seekTo(pos)
        }
    }

    override fun getCurrentPosition() = currentPosition * 1000L


    private fun isDstCodec(): Boolean {
        return audioInfo.codecName.lowercase().startsWith("dst")
    }

    private fun isDsdCodec(): Boolean {
        return audioInfo.codecName.lowercase().startsWith("dsd")
    }

    private fun getOversampleRate(): Int {
        return when (audioInfo.codecName.lowercase()) {
            "dsd128" -> 128
            "dsd256" -> 256
            "dsd512" -> 512
            else -> 64
        }.apply {
            Timber.d("getOversampleRate: $this")
        }
    }


    @SuppressLint("InlinedApi")
    private fun initializeAudioTrack() = runCatching {
        if (!isDsdCodec() && !isDstCodec()) {
            throw IllegalStateException("Codec is not DSD")
        }
        val sampleRate = when (dsdPlayMode) {
            DSDMode.NATIVE -> {
                audioInfo.sampleRate / 32
            }

            DSDMode.D2P -> {
                44100
            }

            DSDMode.DOP -> {
                audioInfo.sampleRate / 16
            }
        }
        val encoding = when (dsdPlayMode) {
            DSDMode.NATIVE -> {
                AudioFormat.ENCODING_DSD
            }

            DSDMode.D2P -> {
                AudioFormat.ENCODING_PCM_16BIT
            }

            DSDMode.DOP -> {
                AudioFormat.ENCODING_PCM_32BIT
            }
        }

        val channelMask = when (audioInfo.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        Timber.d("sampleRate: $sampleRate, channelMask: $channelMask, encoding: $encoding, bufferSize: $bufferSize")
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.onFailure {
        Timber.e(it, "initializeAudioTrack error")
        onPlayerError(it)
    }

    //    private var testFile = File(context.filesDir, "test.pcm")
    private fun playDsd() = runCatching {
        val sourceId = audioInfo.sourceId
        val bufferSize = 8 * 1024
        val byteRate = audioInfo.bitRate / 8
        val startOffset = audioInfo.startOffset ?: 0L
        val endOffset = audioInfo.endOffset ?: 0L
        val dataByte = (audioInfo.dataLength ?: 0L) * 8f
        val srcData = ByteArray(bufferSize)
        val destData = ByteArray(if (dsdPlayMode == DSDMode.DOP) bufferSize * 2 else bufferSize)
        val compressionRate = (audioInfo.bitRate * getDuration() / 1000) / dataByte
        var position: Long
        currentOffset = startOffset
        SacdAudioFrame.frameIndex = 0
        coroutineScope.launch {
            val stream = SeekableInputStreamFactory.create(sourceId, headers) ?: return@launch
            /*testFile.delete()
            testFile.createNewFile()*/
            audioTrack?.play()
            stream.seek(startOffset)
            while (!stopped && currentOffset < endOffset) {
                if (paused) {
                    delay(200L)
                    continue
                }
                lock.withLock {
                    if (seeking) {
                        stream.seek(currentOffset)
                        seeking = false
                        audioTrack?.flush()
                        shouldCancelDstDecode.set(false)
                    }
                }

                val bytesRead = stream.read(srcData)
                if (bytesRead == -1) {
                    Timber.d("EOF reached")
                    updateStateChange(PlaybackState.COMPLETED)
                    break
                }

                // 更新时间 & 进度
                position = if (isDstCodec()) {
                    ((currentOffset - startOffset) * compressionRate / byteRate).roundToLong()
                } else {
                    (currentOffset - startOffset) / byteRate
                }

                if (position != currentPosition) {
                    if (previousOffset != -1L && offsetPreSeconds == -1L) {
                        offsetPreSeconds = currentOffset - previousOffset
                    }
                    updateProgress(position * 1000L)
                    currentPosition = position
                    previousOffset = currentOffset
                }

                writeAudioData(srcData, destData, bytesRead)
                currentOffset += bytesRead
            }
            updateStateChange(PlaybackState.COMPLETED)
        }
    }.onFailure {
        Timber.e(it, "playDsd error")
        onPlayerError(it)
    }

    private fun writeAudioData(srcData: ByteArray, destData: ByteArray, length: Int) = runCatching {

        var lengthToWrite = if (dsdPlayMode == DSDMode.DOP) length * 2 else length
        if (isDstCodec()) {
            SacdAudioFrame.readDstFrame(srcData, length, shouldCancelDstDecode) { data, size ->
                if (!shouldCancelDstDecode.get()) {
                    audioTrack?.write(data, 0, size)
                }
            }
            return@runCatching
        }
        val dataToWrite: ByteArray = when (audioInfo.formatName) {
            DsfAudioFileParser.ENCODING_TYPE_DSF -> {
                DsfAudioFrame.read(srcData, destData, length, dsdPlayMode == DSDMode.DOP)
                destData
            }

            DffAudioFileParser.ENCODING_TYPE_DFF -> {
                DffAudioFrame.read(srcData, destData, length, dsdPlayMode == DSDMode.DOP)
                destData
            }

            SacdAudioFileParser.ENCODING_TYPE_SACD -> {
                lengthToWrite =
                    SacdAudioFrame.read(srcData, destData, length, dsdPlayMode == DSDMode.DOP)
                destData
            }

            else -> srcData // 默认使用源数据
        }
        if (dsdLoopbackDataCallback != null || dsdPlayMode == DSDMode.D2P) {
            val pcmData = DsdInterleavedToPcm.convert(
                srcData.copyOf(lengthToWrite),
                audioInfo.sampleRate
            )
            dsdLoopbackDataCallback?.onDataReceived(pcmData.copyOf())
            if (dsdPlayMode == DSDMode.D2P) {
                audioTrack?.write(pcmData, 0, pcmData.size)
            }
        }
        if (dsdPlayMode != DSDMode.D2P) {
            audioTrack?.write(dataToWrite, 0, lengthToWrite)
        }
    }.onFailure {
        Timber.e(it, "writeAudioData error")
        onPlayerError(it)
    }
}

