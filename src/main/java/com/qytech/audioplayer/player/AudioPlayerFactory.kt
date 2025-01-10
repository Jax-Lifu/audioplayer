package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.model.AudioFileInfo


data class PlaybackProgress(
    val currentPosition: Long,     // 当前播放进度（秒）
    val progress: Float,           // 播放进度百分比 (0.0 - 1.0)
    val duration: Long             // 媒体总时长（秒）
) {
    companion object {
        val DEFAULT = PlaybackProgress(0, 0f, 0)
    }

    fun isAvailable(): Boolean = currentPosition >= 0 && duration > 0 && progress in 0f..1f
}

enum class PlaybackState {
    IDLE,           // 空闲状态
    PREPARING,      // 准备中
    PLAYING,        // 播放中
    PAUSED,         // 暂停
    COMPLETED,      // 播放完成
    STOPPED,        // 停止
    BUFFERING,      // 缓冲中 (用于网络流媒体)
    ERROR           // 发生错误
}

interface OnProgressListener {
    /**
     * 当播放进度更新时调用
     * @param progress 包含当前播放进度、总时长和其他相关信息
     */
    fun onProgress(progress: PlaybackProgress)
}

interface OnPlaybackStateChangeListener {
    /**
     * 当播放状态发生变化时调用
     * @param state 新的播放状态
     */
    fun onPlaybackStateChanged(state: PlaybackState)

    /**
     * 当播放发生错误时调用
     * @param errorMessage 错误信息
     */
    fun onPlayerError(errorMessage: String)
}

interface AudioPlayer {

    // 设置要播放的媒体项
    fun setMediaItem(mediaItem: AudioFileInfo)

    // 准备播放
    fun prepare()

    // 开始播放
    fun play()

    // 暂停播放
    fun pause()

    fun stop()

    // 停止播放并释放资源
    fun release()

    // 跳转到指定位置
    fun seekTo(position: Long)

    // 快进
    fun fastForward(milliseconds: Long)

    // 快退
    fun fastRewind(milliseconds: Long)

    // 获取当前播放进度
    fun getCurrentPosition(): Long

    // 获取媒体文件的总时长
    fun getDuration(): Long

    // 设置播放速度
    fun setPlaybackSpeed(speed: Float)

    // 获取当前播放速度
    fun getPlaybackSpeed(): Float

    // 判断是否正在播放
    fun isPlaying(): Boolean

    // 设置播放状态监听器 (如播放、暂停、完成等状态变化)
    fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener?)

    // 设置播放进度监听器
    fun setOnProgressListener(listener: OnProgressListener?)
}


object AudioPlayerFactory {

    fun createAudioPlayer(
        context: Context,
        audioFileInfo: AudioFileInfo,
    ): AudioPlayer {
        val codec = audioFileInfo.header.codec.lowercase()
        return when {
            codec.startsWith("dsd") == true -> DsdAudioPlayer(context)
            codec in setOf(
                "mp1",
                "mp2",
                "mp3",
                "aac",
                "ape",
                "wmalossless",
                "wmapro",
                "wmav1",
                "wmav2",
                "adpcm_ima_qt",
                "vorbis",
                "pcm_s16le",
                "pcm_s24le",
                "pcm_s32le",
                "flac"
            ) -> RockitPlayer(context)

            codec in setOf(
                "opus",
                "alac",
                "pcm_mulaw",
                "pcm_alaw",
                "amrnb",
                "amrwb",
                "ac3",
                "dca"
            ) -> ExoAudioPlayer(context)

            else -> FFAudioPlayer()
        }
    }
}