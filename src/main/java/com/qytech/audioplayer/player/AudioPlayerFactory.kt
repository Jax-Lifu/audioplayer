package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.player.AudioPlayerFactory.create
import com.qytech.audioplayer.player.AudioPlayerFactory.createAudioPlayer
import com.qytech.audioplayer.strategy.AudioProfile
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
     * 统一创建入口，使用 AudioProfile 进行类型安全的场景配置。
     *
     * 相比旧的 [createAudioPlayer] 方法，此方法通过 [AudioProfile] 强制约束了不同场景下的必填参数，
     * 避免了"传了 trackId 却忘了传 originalFileName" 或 "CUE 模式下混淆了 trackId 和时间" 等错误。
     *
     * @param context 上下文
     * @param source 播放源 (本地路径 或 网络 URL)
     * @param profile 播放场景配置，默认为 [AudioProfile.Standard]。
     *                - [AudioProfile.SacdIso]: 播放 ISO 镜像 (需传 trackIndex, originalFileName)
     *                - [AudioProfile.CueByTime]: 网盘 CUE 免解析播放 (需传 start/end position, originalFileName)
     *                - [AudioProfile.Encrypted]: Sony Select 加密流 (需传 key, iv)
     * @param headers HTTP 请求头 (可选，用于网盘鉴权)
     * @param dsdMode DSD 输出模式 (默认 Native)
     */
    fun create(
        context: Context,
        source: String,
        profile: AudioProfile = AudioProfile.Standard,
        headers: Map<String, String>? = null,
        dsdMode: DSDMode = DSDMode.NATIVE,
    ): AudioPlayer? {
        // 1. 解构 Profile 参数
        var trackIndex = -1
        var startPos: Long? = null
        var endPos: Long? = null
        var fileName: String? = null

        // 加密参数
        var securityKey: String? = null
        var initVector: String? = null

        when (profile) {
            is AudioProfile.Standard -> {
            }

            is AudioProfile.SacdIso -> {
                // 用户传入的是 TrackId (1-based)，内部转为 Index (0-based)
                trackIndex = if (profile.trackId > 0) profile.trackId - 1 else -1
                fileName = profile.filename
            }

            is AudioProfile.CueByTime -> {
                startPos = profile.startPosition
                endPos = profile.endPosition
            }

            is AudioProfile.CueByIndex -> {
                trackIndex = if (profile.trackIndex > 0) profile.trackIndex - 1 else -1
            }

            is AudioProfile.SonySelect -> {
                securityKey = profile.securityKey
                initVector = profile.initVector
            }
        }

        // 2. 统一构建 MediaSource
        val mediaSource = MediaSourceStrategy.create(
            uri = source,
            trackIndex = trackIndex,
            headers = headers,
            originalFileName = fileName,
            startPosition = startPos,
            endPosition = endPos,
            securityKey = securityKey,
            initVector = initVector,
        ) ?: return null

        // 3. 路由分发
        return buildPlayer(context, mediaSource, dsdMode)
    }

    /**
     * 传统创建入口。
     * 建议新代码使用 [create] 方法配合 [AudioProfile]。
     *
     * @param source 播放源
     * @param trackId 轨道 ID (1-based)。如果不涉及分轨，传 -1。
     * @param originalFileName      原始文件名。
     *                              **关键参数**：对于网盘链接（URL无后缀），必须传入此参数 (如 "test.iso", "test.wav")，
     *                              否则底层无法识别 SACD 格式或 CUE 原始文件格式。
     * @param startPosition         CUE 播放起始时间 (ms)。仅用于网盘 CUE 优化播放模式。
     * @param endPosition           CUE 播放结束时间 (ms)。
     */
    @Deprecated(
        message = "Use AudioPlayerFactory.create(context, source, profile...) instead for better type safety.",
        replaceWith = ReplaceWith("AudioPlayerFactory.create(context, source, AudioProfile.Standard)")
    )
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
        // 针对网盘的整轨文件,无法获取原始文件信息
        originalFileName: String? = null,
        startPosition: Long? = null,
        endPosition: Long? = null,
    ): AudioPlayer? {
        QYLogger.d("Factory: Legacy create from path=$source trackId=$trackId file=$originalFileName")

        val trackIndex = if (trackId > 0) trackId - 1 else -1

        val mediaSource = MediaSourceStrategy.create(
            uri = source,
            trackIndex = trackIndex,
            securityKey = securityKey,
            initVector = initVector,
            headers = headers,
            originalFileName = originalFileName,
            startPosition = startPosition,
            endPosition = endPosition
        ) ?: return null

        return buildPlayer(context, mediaSource, dsdMode)
    }

    // =================================================================================
    // 3. 内部辅助方法
    // =================================================================================

    /**
     * 内部路由逻辑：根据 MediaSource 类型决定实例化哪个 Player
     */
    private fun buildPlayer(
        context: Context,
        source: MediaSource,
        dsdMode: DSDMode,
    ): AudioPlayer {
        val player = when (source) {
            // SACD ISO -> 专用播放器 (Libsacd / Scarletbook)
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
                    QYLogger.d("Route: FFmpeg Player (Network Stream)")
                    FFPlayer(context)
                }
            }

            // CUE 分轨 (时间定位模式 或 索引模式) -> FFmpeg (Native)
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