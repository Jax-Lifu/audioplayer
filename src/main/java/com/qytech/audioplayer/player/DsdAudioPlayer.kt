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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong


class DsdAudioPlayer(
    context: Context,
    override val audioInfo: AudioInfo.Local,
) : BaseAudioPlayer(audioInfo) {
    private val lock = ReentrantLock()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private var audioTrack: AudioTrack? = null

    private var isDopEnable = false

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
            onPlayerError(Exception("AudioTrack not initialized"))
            return
        }
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
            onPlayerError(Exception("AudioTrack not initialized"))
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
        if (audioTrack?.state!= AudioTrack.STATE_INITIALIZED) {
            onPlayerError(Exception("AudioTrack not initialized"))
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
        if (audioTrack?.state!= AudioTrack.STATE_INITIALIZED) {
            onPlayerError(Exception("AudioTrack not initialized"))
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

    @SuppressLint("InlinedApi")
    private fun initializeAudioTrack() = runCatching {
        if (!isDsdCodec() && !isDstCodec()) {
            throw IllegalStateException("Codec is not DSD")
        }
        val sampleRate = if (isDopEnable) {
            audioInfo.sampleRate / 16
        } else {
            audioInfo.sampleRate / 32
        }
        val encoding = if (isDopEnable) {
            AudioFormat.ENCODING_PCM_32BIT
        } else {
            AudioFormat.ENCODING_DSD
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

    private fun playDsd() = runCatching {
        val filePath = audioInfo.filepath
        val bufferSize = 8 * 1024
        val byteRate = audioInfo.bitRate / 8
        val startOffset = audioInfo.startOffset ?: 0L
        val endOffset = audioInfo.endOffset ?: 0L
        val dataByte = (audioInfo.dataLength ?: 0L) * 8f
        val srcData = ByteArray(bufferSize)
        val destData = ByteArray(if (isDopEnable) bufferSize * 2 else bufferSize)
        val compressionRate = (audioInfo.bitRate * getDuration() / 1000) / dataByte
        var position: Long
        currentOffset = startOffset
        SacdAudioFrame.frameIndex = 0
        coroutineScope.launch {
            val audioFile = File(filePath)
            if (!audioFile.exists()) {
                updateStateChange(PlaybackState.ERROR)
                return@launch
            }
            audioTrack?.play()
            RandomAccessFile(audioFile, "r").use { file ->
                file.seek(startOffset)
                while (!stopped && currentOffset < endOffset) {
                    if (paused) {
                        delay(200L)
                        continue
                    }

                    lock.withLock {
                        if (seeking) {
                            file.seek(currentOffset)
                            seeking = false
                            audioTrack?.flush()
                            shouldCancelDstDecode.set(false)
                        }

                        // 读取文件数据并处理异常
                        val bytesRead = file.read(srcData)
                        if (bytesRead == -1) {
                            Timber.d("EOF reached")
                            updateStateChange(PlaybackState.COMPLETED)
                            return@launch
                        }

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
                }

                updateStateChange(PlaybackState.COMPLETED)
            }
        }
    }.onFailure {
        Timber.e(it, "playDsd error")
        onPlayerError(it)
    }

    private fun writeAudioData(srcData: ByteArray, destData: ByteArray, length: Int) = runCatching {
        var lengthToWrite = if (isDopEnable) length * 2 else length
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
                DsfAudioFrame.read(srcData, destData, length, isDopEnable)
                destData
            }

            DffAudioFileParser.ENCODING_TYPE_DFF -> {
                DffAudioFrame.read(srcData, destData, length, isDopEnable)
                destData
            }

            SacdAudioFileParser.ENCODING_TYPE_SACD -> {
                lengthToWrite =
                    SacdAudioFrame.read(srcData, destData, length, isDopEnable)
                destData
            }

            else -> srcData // 默认使用源数据
        }
        audioTrack?.write(dataToWrite, 0, lengthToWrite)
    }.onFailure {
        Timber.e(it, "writeAudioData error")
        onPlayerError(it)
    }
}

