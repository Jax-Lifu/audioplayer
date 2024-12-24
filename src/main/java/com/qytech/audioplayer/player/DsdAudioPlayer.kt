package com.qytech.audioplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.qytech.audioplayer.audioframe.DffAudioFrame
import com.qytech.audioplayer.audioframe.DsfAudioFrame
import com.qytech.audioplayer.audioframe.SacdAudioFrame
import com.qytech.audioplayer.model.AudioFileInfo
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DsdAudioPlayer(context: Context) : AudioPlayer {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val lock = ReentrantLock()

    private val context = context.applicationContext
    private var playSpeed = 1f
    private var audioFileInfo: AudioFileInfo? = null
    private var audioTrack: AudioTrack? = null
    private var isDopEnable = false
    private var currentPosition = 0L
    private var offsetPreSeconds = -1L
    private var previousOffset = -1L
    private var seeking = false
    private var stopped = false
    private var paused = false
    private var currentOffset = 0L
    private var onProgressListener: OnProgressListener? = null
    private var onPlaybackStateChanged: OnPlaybackStateChangeListener? = null

    private val audioTrackInfo by lazy {
        audioFileInfo?.trackInfo
    }

    private val audioFileHeader by lazy {
        audioFileInfo?.header
    }


    @SuppressLint("InlinedApi")
    private fun initializeAudioTrack() = runCatching {
        Timber.d("initializeAudioTrack")
        val header = audioFileHeader ?: return@runCatching
        var sampleRate = header.sampleRate
        val encoding: Int = when {
            header.codec == "PCM" -> AudioFormat.ENCODING_PCM_16BIT
            isDopEnable -> AudioFormat.ENCODING_PCM_32BIT.also { sampleRate /= 16 }
            else -> AudioFormat.ENCODING_DSD.also { sampleRate /= 32 }
        }

        val channelMask = when (header.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
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

        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }.onFailure {
        onPlayerError(it)
    }

    /**
     * 处理音频数据并写入 AudioTrack
     */
    private fun writeAudioData(srcData: ByteArray, destData: ByteArray, bytesRead: Int) {
        runCatching {
            val fileFormat = audioFileHeader?.encodingType ?: return
            var lengthToWrite = if (isDopEnable) bytesRead * 2 else bytesRead
            val dataToWrite: ByteArray = when (fileFormat) {
                DsfAudioFileParser.ENCODING_TYPE_DSF -> {
                    DsfAudioFrame.read(srcData, destData, bytesRead, isDopEnable)
                    destData
                }

                DffAudioFileParser.ENCODING_TYPE_DFF -> {
                    DffAudioFrame.read(srcData, destData, bytesRead, isDopEnable)
                    destData
                }

                SacdAudioFileParser.ENCODING_TYPE_SACD -> {
                    lengthToWrite = SacdAudioFrame.read(srcData, destData, bytesRead, isDopEnable)
                    destData
                }

                else -> srcData // 默认使用源数据
            }
            audioTrack?.write(dataToWrite, 0, lengthToWrite, AudioTrack.WRITE_BLOCKING)
        }.onFailure {
            onPlayerError(it)
            Timber.e(it, "Error occurred during writing audio data")
        }
    }

    // 更新播放进度的函数
    private fun updatePlaybackProgress(position: Long) {
        if (position == currentPosition) {
            return
        }

        // 处理前一个偏移量和时间计算
        if (previousOffset != -1L && offsetPreSeconds == -1L) {
            offsetPreSeconds = currentOffset - previousOffset
        }
        currentPosition = position
        previousOffset = currentOffset
        val progress = PlaybackProgress(
            currentPosition,
            currentPosition.toFloat() / getDuration(),
            getDuration()
        )
        // 通知进度监听器
        Timber.d("updatePlaybackProgress: $progress")
        onProgressListener?.onProgress(progress)
    }

    private fun playAudio() = runCatching {
        // 确保音频数据正常获取到，否则提前返回
        val filePath = audioFileInfo?.filePath ?: return@runCatching
        val header = audioFileHeader ?: return@runCatching
        val offsetInfo = audioTrackInfo?.offset ?: return@runCatching

        val bufferSize = 8 * 1024
        val byteRate = header.byteRate
        val startOffset = offsetInfo.startOffset
        val endOffset = offsetInfo.endOffset
        val srcData = ByteArray(bufferSize)
        val destData = ByteArray(if (isDopEnable) bufferSize * 2 else bufferSize)
        var position: Long
        currentOffset = startOffset
        coroutineScope.launch {
            val audioFile = File(filePath)
            if (!audioFile.exists()) {
                updatePlaybackState(PlaybackState.ERROR)
                return@launch
            }
            audioTrack?.play()
            RandomAccessFile(audioFile, "r").use { file ->
                file.seek(startOffset) // 从指定偏移量开始读取
                while (!stopped && currentOffset < endOffset) {
                    if (paused) {
                        delay(100L)
                        continue
                    }

                    lock.withLock {
                        // 跳转处理
                        if (seeking) {
                            file.seek(currentOffset)
                            Timber.d("seekTo currentOffset $currentOffset")
                            seeking = false
                            audioTrack?.flush()
                        }

                        // 读取文件数据并处理异常
                        val bytesRead = file.read(srcData)
                        if (bytesRead == -1) {
                            updatePlaybackState(PlaybackState.COMPLETED)
                            return@launch
                        }

                        // 更新播放进度
                        position = (currentOffset - startOffset) / byteRate
                        updatePlaybackProgress(position)

                        // 写入音频数据
                        writeAudioData(srcData, destData, bytesRead)

                        // 更新当前偏移量
                        currentOffset += bytesRead
                    }
                }

                // 播放完成后，通知状态改变
                if (!stopped) {
                    updatePlaybackState(PlaybackState.COMPLETED)
                }
            }
        }
    }.onFailure {
        // 捕获并处理播放过程中发生的所有异常
        onPlayerError(it)
        Timber.e(it, "Error occurred during audio playback")
    }


    private fun onPlayerError(exception: Throwable) {
        onProgressListener?.onProgress(PlaybackProgress.DEFAULT)
        onPlaybackStateChanged?.onPlaybackStateChanged(PlaybackState.ERROR)
        onPlaybackStateChanged?.onPlayerError(exception.message ?: "Unknown error")
        release()
    }

    private fun updatePlaybackState(state: PlaybackState) {
        onPlaybackStateChanged?.onPlaybackStateChanged(state)
    }

    override fun setMediaItem(mediaItem: AudioFileInfo) {
        audioFileInfo = mediaItem
    }

    override fun prepare() {
        initializeAudioTrack()
    }

    override fun play() {
        retryPlay(5) // 尝试播放 5 次
    }

    private fun retryPlay(retryCount: Int) {
        runCatching {
            // 初始化 AudioTrack，如果尚未初始化
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initializeAudioTrack()
            }

            // 处理暂停状态
            lock.withLock {
                if (paused) {
                    paused = false // 解除暂停
                    audioTrack?.play()
                } else {
                    playAudio() // 播放音频
                }

                updatePlaybackState(PlaybackState.PLAYING)
            }
        }.onFailure { error ->
            // 处理播放过程中的错误，重试最多 retryCount 次
            if (retryCount > 1) {
                Timber.w("Retrying play, attempts remaining: ${retryCount - 1}")
                retryPlay(retryCount - 1) // 递归调用，减少重试次数
            } else {
                // 如果超过重试次数，则触发错误处理
                onPlayerError(error)
                Timber.e(error, "Error occurred while trying to play audio after retries")
            }
        }
    }


    override fun pause() {
        runCatching {
            lock.withLock {
                paused = true
                audioTrack?.pause()
                updatePlaybackState(PlaybackState.PAUSED) // 更新播放状态
            }
        }.onFailure {
            onPlayerError(it)
        }
    }

    override fun stop() {
        runCatching {
            lock.withLock {
                stopped = true
                paused = false
                audioTrack?.stop()
                audioTrack?.flush()
                audioTrack?.release()
                updatePlaybackState(PlaybackState.IDLE) // 更新播放状态
                onProgressListener?.onProgress(PlaybackProgress(0, 0f, getDuration()))
                audioTrack = null
            }
        }.onFailure {
            onPlayerError(it)
        }
    }

    override fun release() {
        runCatching {
            lock.withLock {
                stopped = true
                paused = false
                audioTrack?.stop()
                audioTrack?.release()
                updatePlaybackState(PlaybackState.IDLE) // 更新播放状态
                audioTrack = null
                audioFileInfo = null
                offsetPreSeconds = -1
                currentOffset = -1
                previousOffset = -1
                currentPosition = -1
                onProgressListener = null
                onPlaybackStateChanged = null
            }
        }.onFailure {
            onPlayerError(it)
        }
    }

    override fun seekTo(position: Long) {
        if (offsetPreSeconds == -1L) {
            return
        }
        val offsetInfo = audioTrackInfo?.offset ?: return
        lock.withLock {
            currentPosition = position
            currentOffset = offsetInfo.startOffset + position * offsetPreSeconds
            seeking = true
            Timber.d("seekTo: $position offsetPreSeconds $offsetPreSeconds currentOffset $currentOffset")
        }
    }

    override fun fastForward(milliseconds: Long) {
        lock.withLock {
            Timber.d("fastForward: $milliseconds currentPosition $currentPosition")
            var position = currentPosition + milliseconds
            if (position > getDuration()) {
                position = getDuration()
            }
            seekTo(position)
        }
    }

    override fun fastRewind(milliseconds: Long) {
        lock.withLock {
            Timber.d("fastRewind: $milliseconds currentPosition $currentPosition")
            var position = currentPosition - milliseconds
            if (position < 0) {
                position = 0
            }
            seekTo(position)
        }
    }

    override fun getCurrentPosition(): Long {
        return currentPosition
    }

    override fun getDuration(): Long {
        return audioTrackInfo?.duration ?: 0
    }

    override fun setPlaybackSpeed(speed: Float) {
        playSpeed = speed
    }

    override fun getPlaybackSpeed(): Float {
        return playSpeed
    }

    override fun setVolume(volume: Float) {
    }

    override fun getVolume(): Float {
        return 1f
    }

    override fun setMute(isMuted: Boolean) {
    }

    override fun isMuted(): Boolean {
        return false
    }

    override fun getBufferedPosition(): Long {
        return 0L
    }

    override fun isPlaying(): Boolean {
        return audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener?) {
        onPlaybackStateChanged = listener
    }

    override fun setOnProgressListener(listener: OnProgressListener?) {
        onProgressListener = listener
    }
}

