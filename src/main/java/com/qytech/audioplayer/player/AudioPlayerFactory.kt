package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.strategy.AudioProfile
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.MediaSourceStrategy
import com.qytech.audioplayer.strategy.SacdMediaSource
import com.qytech.audioplayer.strategy.SonySelectMediaSource
import com.qytech.audioplayer.strategy.StreamingMediaSource
import com.qytech.audioplayer.strategy.WebDavUtils
import com.qytech.audioplayer.utils.QYLogger
import timber.log.Timber
import java.util.Locale

object AudioPlayerFactory {

    fun create(
        context: Context,
        source: String,
        profile: AudioProfile = AudioProfile.Standard,
        headers: Map<String, String>? = null,
        dsdMode: DSDMode = DSDMode.NATIVE,
    ): AudioPlayer? {

        // --- 1. WebDAV 解包与预处理 ---
        var finalSource = source
        var finalHeaders = headers ?: emptyMap()
        var actualProfile = profile // 实际用于解析参数的 profile

        // 用于传递给 Strategy 的 WebDAV 凭证
        // 只有在 WebDAV + Standard 模式下才赋值，否则我们转换成 Header
        var strategyWebDavUser: String? = null
        var strategyWebDavPwd: String? = null

        if (profile is AudioProfile.WebDav) {
            if (profile.targetProfile is AudioProfile.Standard) {
                // [路径 A]: WebDAV 播放普通文件 -> 保持原样
                // 将账号密码传给 Strategy，生成 WebDavMediaSource
                strategyWebDavUser = profile.username
                strategyWebDavPwd = profile.password
                actualProfile = profile.targetProfile
            } else {
                // [路径 B]: WebDAV 播放 ISO / CUE -> 转换为 HTTP Header 模式
                // 底层 SACD/CUE 播放器不认识 WebDAV 对象，但支持 HTTP Header
                // 所以我们在这里把 WebDAV 转换成带 Auth Header 的普通 HTTP 请求
                val (encodedUrl, authHeaders) = WebDavUtils.process(
                    source,
                    profile.username,
                    profile.password,
                    finalHeaders
                )

                finalSource = encodedUrl
                finalHeaders = authHeaders

                // 剥去 WebDav 外壳，指向内部的 ISO/CUE 配置
                actualProfile = profile.targetProfile

                QYLogger.d("Factory: Unwrapped WebDAV for complex profile. New Url: $finalSource")
            }
        }

        // --- 2. 解构具体的 Profile 参数 ---
        var trackIndex = -1
        var startPos: Long? = null
        var endPos: Long? = null
        var fileName: String? = null
        var securityKey: String? = null
        var initVector: String? = null

        when (actualProfile) {
            is AudioProfile.Standard -> { /* No extra params */
            }

            is AudioProfile.SacdIso -> {
                trackIndex = if (actualProfile.trackId > 0) actualProfile.trackId - 1 else -1
                fileName = actualProfile.filename
            }

            is AudioProfile.CueByTime -> {
                startPos = actualProfile.startPosition
                endPos = actualProfile.endPosition
            }

            is AudioProfile.CueByIndex -> {
                trackIndex = if (actualProfile.trackIndex > 0) actualProfile.trackIndex - 1 else -1
            }

            is AudioProfile.SonySelect -> {
                securityKey = actualProfile.securityKey
                initVector = actualProfile.initVector
            }

            is AudioProfile.WebDav -> {
                // 不会走到这里，因为上面已经解包处理了
            }
        }

        // --- 3. 构建 MediaSource ---
        val mediaSource = MediaSourceStrategy.create(
            uri = finalSource,
            trackIndex = trackIndex,
            headers = finalHeaders,
            originalFileName = fileName,
            startPosition = startPos,
            endPosition = endPos,
            securityKey = securityKey,
            initVector = initVector,
            webDavUser = strategyWebDavUser,
            webDavPwd = strategyWebDavPwd
        ) ?: return null

        // --- 4. 路由分发 ---
        return buildPlayer(context, mediaSource, dsdMode)
    }

    /**
     * 兼容老接口
     */
    @Deprecated("Use AudioPlayerFactory.create(context, source, profile...)")
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
        filename: String? = null,
        startPosition: Long? = null,
        endPosition: Long? = null,
        webDavUser: String? = null,
        webDavPwd: String? = null,
    ): AudioPlayer? {

        QYLogger.d(
            "createAudioPlayer context = $context, source = $source, trackId = $trackId " +
                    "dsdMode = $dsdMode, securityKey = $securityKey, initVector = $initVector," +
                    " headers = $headers, clientId = $clientId, clientSecret = $clientSecret," +
                    " credentialsKey = $credentialsKey" +
                    " filename = $filename, startPosition = $startPosition, endPosition = $endPosition," +
                    " webDavUser = $webDavUser, webDavPwd = $webDavPwd"
        )
        val trackIndex = if (trackId > 0) trackId - 1 else -1

        // 老接口直接透传参数，Strategy 会处理
        val mediaSource = MediaSourceStrategy.create(
            uri = source,
            trackIndex = trackIndex,
            securityKey = securityKey,
            initVector = initVector,
            headers = headers,
            originalFileName = filename,
            startPosition = startPosition,
            endPosition = endPosition,
            webDavUser = webDavUser,
            webDavPwd = webDavPwd
        ) ?: return null

        return buildPlayer(context, mediaSource, dsdMode)
    }

    private fun buildPlayer(
        context: Context,
        source: MediaSource,
        dsdMode: DSDMode,
    ): AudioPlayer {
        val player = when (source) {
            is SacdMediaSource -> SacdPlayer(context)
            is SonySelectMediaSource -> StreamPlayer(context)
            is StreamingMediaSource -> {
                val lowerUri = source.uri.lowercase(Locale.getDefault())
                if (lowerUri.contains("m3u8")) StreamPlayer(context) else FFPlayer(context)
            }

            else -> FFPlayer(context)
        }
        Timber.d("buildPlayer $player")
        player.setDsdMode(dsdMode)
        player.setMediaSource(source)
        return player
    }
}