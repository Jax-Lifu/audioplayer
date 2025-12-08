package com.qytech.audioplayer.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.qytech.audioplayer.utils.QYLogger

/**
 * 全局 AudioTrack 资源管理器 (带 Session ID 保护)
 * 修复了快速切歌时，旧的 release 操作误停止新歌曲的问题。
 */
object GlobalAudioTrackManager {
    @Volatile
    private var audioTrack: AudioTrack? = null

    // 记录当前 AudioTrack 的参数
    private var currentSampleRate = 0
    private var currentEncoding = 0
    private var currentChannel = 2

    // [关键修复] 当前活跃的会话 ID
    // 每次 acquire 成功后自增，用于区分不同的播放请求
    private var currentSessionId: Long = 0

    /**
     * 申请 AudioTrack 和 SessionID。
     * @return Pair<AudioTrack, Long> -> (AudioTrack实例, 本次分配的SessionId)
     */
    @Synchronized
    fun acquireAudioTrack(
        sampleRate: Int,
        encoding: Int,
        channel: Int,
    ): Pair<AudioTrack, Long> {
        // 1. 生成新的 Session ID (代表“新一代”的播放请求)
        currentSessionId++
        val newSessionId = currentSessionId

        val track = audioTrack

        // 2. 尝试复用
        if (track != null &&
            track.state == AudioTrack.STATE_INITIALIZED &&
            currentSampleRate == sampleRate &&
            currentEncoding == encoding &&
            currentChannel == channel
        ) {
            QYLogger.i("GlobalAudioTrackManager: >>> Reuse Hit! (SR: $sampleRate, Session: $newSessionId) <<<")
            try {
                // 复用流程：暂停 -> 清空 -> 播放
                // 这里做的操作实际上已经替“上一个Session”完成了清理工作
                track.pause()
                track.flush()
                track.play()
                return Pair(track, newSessionId)
            } catch (e: Exception) {
                QYLogger.w("Reuse failed (exception), fallback to create new: ${e.message}")
            }
        }

        // 3. 无法复用或参数变更，创建新的
        QYLogger.i("GlobalAudioTrackManager: >>> Create New (SR: $sampleRate, ENC: $encoding, Session: $newSessionId) <<<")

        // 销毁旧的 AudioTrack 对象
        destroyInternal()

        val newTrack = createTrack(sampleRate, encoding, channel)

        // 更新全局状态
        audioTrack = newTrack
        currentSampleRate = sampleRate
        currentEncoding = encoding
        currentChannel = channel

        // 立即启动
        try {
            newTrack.play()
        } catch (e: Exception) {
            QYLogger.e("AudioTrack play failed immediately after creation", e)
        }

        return Pair(newTrack, newSessionId)
    }

    /**
     * 软释放 (Soft Release) - 带版本校验
     * @param callerSessionId 调用者持有的 Session ID
     */
    @Synchronized
    fun softRelease(callerSessionId: Long) {
        // [关键修复] 只有 Session ID 匹配，才允许操作 AudioTrack
        if (callerSessionId != currentSessionId) {
            QYLogger.w(
                "GlobalAudioTrackManager: Ignored stale release request. " +
                        "CallerSession: $callerSessionId, CurrentSession: $currentSessionId"
            )
            return
        }

        val track = audioTrack ?: return
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                QYLogger.d("GlobalAudioTrackManager: Soft releasing session $callerSessionId")
                track.pause()
                track.flush()
            }
        } catch (e: Exception) {
            QYLogger.e("GlobalAudioTrackManager softRelease error", e)
        }
    }

    /**
     * 硬销毁 (Hard Destroy)
     * 彻底释放资源，重置所有状态。
     */
    @Synchronized
    fun destroy() {
        QYLogger.d("GlobalAudioTrackManager: Destroying hardware resource...")
        destroyInternal()
        currentSampleRate = 0
        currentEncoding = 0
        currentChannel = 2
        currentSessionId = 0 // 重置 ID
    }

    private fun destroyInternal() {
        audioTrack?.let {
            try {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
                QYLogger.d("GlobalAudioTrackManager: Old track released.")
            } catch (e: Exception) {
                QYLogger.e("AudioTrack release failed", e)
            }
        }
        audioTrack = null
    }

    private fun createTrack(sampleRate: Int, encoding: Int, channel: Int): AudioTrack {
        val channelConfig =
            if (channel == 4) AudioFormat.CHANNEL_OUT_QUAD else AudioFormat.CHANNEL_OUT_STEREO
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        // 适当增大 Buffer 以防止高码率 DSD 播放卡顿
        val bufferSize = if (minBufferSize > 0) minBufferSize * 4 else sampleRate * 4

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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}