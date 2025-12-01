package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.strategy.CueMediaSource
import com.qytech.audioplayer.strategy.DefaultMediaSource
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.MediaSourceStrategy
import com.qytech.audioplayer.strategy.SacdMediaSource
import com.qytech.audioplayer.strategy.SonySelectMediaSource
import com.qytech.audioplayer.strategy.StreamingMediaSource
import com.qytech.audioplayer.utils.QYLogger
import java.util.Locale

object AudioPlayerFactory {

    /**
     * 对外统一创建入口
     */
    fun createAudioPlayer(
        context: Context,
        source: String,
        trackId: Int = -1,
        dsdMode: DSDMode = DSDMode.NATIVE,
        securityKey: String? = null,
        initVector: String? = null,
        headers: Map<String, String>? = null,
        clientId: String? = null,
        clientSecret: String? = null,
        credentialsKey: String? = null,
    ): AudioPlayer? {
        QYLogger.d("Factory: Create from String path=$source trackId=$trackId")
        val trackIndex = if (trackId > 0) trackId - 1 else -1
        val mediaSource = MediaSourceStrategy.create(
            uri = source,
            trackIndex = trackIndex,
            securityKey = securityKey,
            initVector = initVector,
            headers = headers
        ) ?: return null

        return buildPlayer(context, mediaSource, dsdMode)
    }

    /**
     * 供 Manager 内部调用 (例如 FFPlayer 失败后，手动创建 StreamPlayer 进行重试)
     */
    fun createStreamPlayer(context: Context, source: MediaSource): AudioPlayer {
        val player = StreamPlayer(context)
        player.setMediaSource(source)
        return player
    }

    /**
     * 内部路由逻辑：决定用哪个播放器
     */
    private fun buildPlayer(
        context: Context,
        source: MediaSource,
        dsdMode: DSDMode,
    ): AudioPlayer {
        val player = when (source) {
            // SACD ISO -> 专用播放器
            is SacdMediaSource -> {
                QYLogger.d("Route: SACD Player")
                SacdPlayer(context)
            }

            // Sony Select (加密) -> 只能用 ExoPlayer (StreamPlayer)
            is SonySelectMediaSource -> {
                QYLogger.d("Route: Stream Player (Sony Encrypted)")
                StreamPlayer(context)
            }

            // 网络流 -> 分情况讨论
            is StreamingMediaSource -> {
                val lowerUri = source.uri.lowercase(Locale.getDefault())
                // HLS (m3u8) -> 推荐用 ExoPlayer，FFmpeg 对 HLS 支持不如 Exo 完善
                if (lowerUri.contains("m3u8") || lowerUri.contains("u3m8")) {
                    QYLogger.d("Route: Stream Player (HLS)")
                    StreamPlayer(context)
                } else {
                    // 普通 HTTP 流 (MP3/FLAC) -> 默认用 FFmpeg (Native)
                    // 如果 FFmpeg 失败，Manager 层应捕获并切换到 StreamPlayer
                    QYLogger.d("Route: FFmpeg Player (Network Stream)")
                    FFPlayer(context)
                }
            }

            // CUE 分轨 -> FFmpeg (Native)
            is CueMediaSource -> {
                QYLogger.d("Route: FFmpeg Player (Segment/Cue)")
                FFPlayer(context)
            }

            // 本地普通文件 -> FFmpeg (Native)
            is DefaultMediaSource -> {
                QYLogger.d("Route: FFmpeg Player (Default)")
                FFPlayer(context)
            }
        }

        player.setDsdMode(dsdMode)
        player.setMediaSource(source)
        return player
    }
}