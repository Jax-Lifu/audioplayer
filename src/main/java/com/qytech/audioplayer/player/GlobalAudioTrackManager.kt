package com.qytech.audioplayer.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.qytech.audioplayer.utils.QYLogger

/**
 * 全局 AudioTrack 资源管理器
 * 目的：在不同的 BaseNativePlayer 实例之间复用 AudioTrack，实现无缝切歌。
 */
object GlobalAudioTrackManager {
    @Volatile
    private var audioTrack: AudioTrack? = null

    // 记录当前 AudioTrack 的参数，用于判断是否匹配
    private var currentSampleRate = 0
    private var currentEncoding = 0

    /**
     * 申请一个 AudioTrack。
     * 如果现有的 AudioTrack 参数与需求一致，则复用（Flush & Play）；
     * 如果不一致，则销毁旧的创建新的。
     */
    @Synchronized
    fun acquireAudioTrack(sampleRate: Int, encoding: Int): AudioTrack {
        val track = audioTrack

        // 1. 尝试复用
        if (track != null &&
            track.state == AudioTrack.STATE_INITIALIZED &&
            currentSampleRate == sampleRate &&
            currentEncoding == encoding
        ) {
            QYLogger.i("GlobalAudioTrackManager: >>> Reuse Hit! (SR: $sampleRate) <<<")
            try {
                // 复用标准流程：暂停 -> 清空缓冲区 -> 重新开始
                // 这样可以清除上一曲的残留数据，防止爆音或串音
                track.pause()
                track.flush()
                track.play()
                return track
            } catch (e: Exception) {
                QYLogger.w("Reuse failed (exception), fallback to create new: ${e.message}")
                // 如果复用失败（极其罕见），则走下方创建流程
            }
        }

        // 2. 无法复用，创建新的
        QYLogger.i("GlobalAudioTrackManager: >>> Create New (SR: $sampleRate, ENC: $encoding) <<<")

        // 销毁旧的（如果有）
        destroyInternal()

        val newTrack = createTrack(sampleRate, encoding)
        // 更新引用
        audioTrack = newTrack
        currentSampleRate = sampleRate
        currentEncoding = encoding

        // 立即启动，允许预缓冲数据
        newTrack.play()

        return newTrack
    }

    /**
     * 软释放 (Soft Release)
     * 供 Player.release() 调用。
     * 行为：暂停并清空数据，但不释放硬件资源，保留对象以供下次复用。
     */
    @Synchronized
    fun softRelease() {
        val track = audioTrack ?: return
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                // 仅暂停和 Flush，保持 Session
                track.pause()
                track.flush()
            }
        } catch (e: Exception) {
            QYLogger.e("GlobalAudioTrackManager softRelease error", e)
        }
        // 注意：这里绝不置空 audioTrack，也不调用 release()
    }

    /**
     * 硬销毁 (Hard Destroy)
     * 供 ViewModel.onCleared() 或 App 退出时调用。
     * 行为：彻底释放硬件资源。
     */
    @Synchronized
    fun destroy() {
        QYLogger.d("GlobalAudioTrackManager: Destroying hardware resource...")
        destroyInternal()
        currentSampleRate = 0
        currentEncoding = 0
    }

    private fun destroyInternal() {
        audioTrack?.let {
            try {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                QYLogger.e("AudioTrack release failed", e)
            }
        }
        audioTrack = null
    }

    private fun createTrack(sampleRate: Int, encoding: Int): AudioTrack {
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        // 适当增大 Buffer (4倍) 以防止高码率 DSD 播放卡顿
        val bufferSize = minBufferSize * 4

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