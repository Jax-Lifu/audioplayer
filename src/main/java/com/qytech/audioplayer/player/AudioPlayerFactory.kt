package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.strategy.AudioProfile
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.MediaSourceStrategy
import com.qytech.audioplayer.strategy.SacdMediaSource
import com.qytech.audioplayer.strategy.SonySelectMediaSource
import com.qytech.audioplayer.strategy.StreamingMediaSource
import com.qytech.audioplayer.strategy.WebDavUtils
import com.qytech.audioplayer.utils.QYPlayerLogger
import java.util.Locale

object AudioPlayerFactory {

    fun create(
        context: Context,
        source: String,
        profile: AudioProfile = AudioProfile.Standard,
        headers: Map<String, String>? = null,
        dsdMode: DSDMode = DSDMode.NATIVE,
    ): AudioPlayer? {
        val (finalSource, finalProfile, finalHeaders) = resolveWebDavProfile(
            source,
            profile,
            headers
        )

        val mediaSource = MediaSourceStrategy.create(
            uri = finalSource,
            profile = finalProfile,
            headers = finalHeaders
        ) ?: return null

        QYPlayerLogger.d("Factory: Created source: $mediaSource from profile: $finalProfile")

        return buildPlayer(context, mediaSource, dsdMode)
    }

    /**
     * 处理 WebDAV 逻辑，返回处理后的 (Url, Profile, Headers) 三元组
     */
    private fun resolveWebDavProfile(
        source: String,
        profile: AudioProfile,
        headers: Map<String, String>?,
    ): Triple<String, AudioProfile, Map<String, String>?> {

        if (profile !is AudioProfile.WebDav) {
            return Triple(source, profile, headers)
        }

        // 是 WebDAV Profile
        return if (profile.targetProfile is AudioProfile.Standard) {
            // 情况 A: 纯 WebDAV 文件播放 (MP3/FLAC等)
            // 保持 WebDav Profile 不变，Strategy 会识别并创建 WebDavMediaSource (透传账号密码给底层)
            Triple(source, profile, headers)
        } else {
            // 情况 B: WebDAV 嵌套复杂业务 (WebDAV -> ISO / WebDAV -> CUE)
            // 策略：将 WebDAV 账号密码转换为 HTTP Auth Header，然后"剥去" WebDav Profile 外壳，
            // 暴露出内部的 targetProfile (如 SacdIso 或 CueByTime) 给 Strategy 使用。
            val (encodedUrl, authHeaders) = WebDavUtils.process(
                source,
                profile.username,
                profile.password,
                headers ?: emptyMap()
            )
            QYPlayerLogger.d("Factory: Unwrapped WebDAV. New Url: $encodedUrl, Inner Profile: ${profile.targetProfile}")
            Triple(encodedUrl, profile.targetProfile, authHeaders)
        }
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

        // 1. 提取关键判定条件
        val isSonyEncrypted = !securityKey.isNullOrEmpty() && !initVector.isNullOrEmpty()
        val isExplicitTimeRange = startPosition != null && endPosition != null
        val isValidTrackId = trackId > 0
        // 判断是否为 ISO 文件 (检查 URL 后缀或 filename 参数)
        val isIsoSource = source.endsWith(".iso", ignoreCase = true) ||
                filename?.endsWith(".iso", ignoreCase = true) == true
        val isWebDav = !webDavUser.isNullOrEmpty() && !webDavPwd.isNullOrEmpty()

        // 2. 打印精简日志
        QYPlayerLogger.d(
            "Legacy createAudioPlayer invoked.\n" +
                    "Source: $source\n" +
                    "Type Hints: [ISO=$isIsoSource, Sony=$isSonyEncrypted, WebDAV=$isWebDav]\n" +
                    "Range: ${if (isExplicitTimeRange) "$startPosition-$endPosition" else "None"}\n" +
                    "TrackId: $trackId"
        )

        // 3. 构建核心业务 Profile
        val coreProfile: AudioProfile = when {
            isSonyEncrypted -> {
                AudioProfile.SonySelect(securityKey, initVector)
            }

            isIsoSource && isValidTrackId -> {
                AudioProfile.SacdIso(trackId, filename)
            }

            isExplicitTimeRange -> {
                AudioProfile.CueByTime(startPosition, endPosition)
            }

            isValidTrackId -> {
                AudioProfile.CueByIndex(trackId)
            }

            else -> AudioProfile.Standard
        }

        val finalProfile = if (isWebDav) {
            AudioProfile.WebDav(
                username = webDavUser,
                password = webDavPwd,
                targetProfile = coreProfile
            )
        } else {
            coreProfile
        }

        QYPlayerLogger.d("Legacy parameter mapping result: $finalProfile")

        // 5. 调用新接口
        return create(context, source, finalProfile, headers, dsdMode)
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
        player.setDsdMode(dsdMode)
        player.setMediaSource(source)
        return player
    }
}