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
        d2pSampleRate: D2pSampleRate = D2pSampleRate.PCM_44100,
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

        return buildPlayer(context, mediaSource, dsdMode, d2pSampleRate)
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
     * 逻辑顺序：Sony -> ISO -> CUE -> TimeRange(Valid)  -> Standard
     */
    @Deprecated("Use AudioPlayerFactory.create(context, source, profile...)")
    fun createAudioPlayer(
        context: Context,
        source: String,
        trackId: Int = -1,
        dsdMode: DSDMode = DSDMode.NATIVE,
        d2pSampleRate: D2pSampleRate = D2pSampleRate.PCM_44100,
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
        QYPlayerLogger.d("source $source securityKey $securityKey initVector $initVector headers $headers")
        val lowerSource = source.lowercase(Locale.getDefault())

        val isRemoteProtocol = lowerSource.let { s ->
            s.startsWith("http://") || s.startsWith("https://") ||
                    s.startsWith("rtmp://") || s.startsWith("rtsp://") ||
                    s.startsWith("ftp://") || s.startsWith("udp://") ||
                    s.startsWith("mmsh://") || s.startsWith("mmst://") ||
                    s.startsWith("dav://") || s.startsWith("davs://")
        }

        val targetNameForDetection =
            (if (!filename.isNullOrEmpty()) filename else source).lowercase()

        val isIsoExt = targetNameForDetection.endsWith(".iso")

        val isCueExt = targetNameForDetection.endsWith(".cue")

        val isValidSonyParams = !securityKey.isNullOrEmpty() && !initVector.isNullOrEmpty()
        val isValidWebDavParams = !webDavUser.isNullOrEmpty() && !webDavPwd.isNullOrEmpty()

        val isValidTimeRange = (startPosition != null && endPosition != null) &&
                startPosition >= 0 &&
                (endPosition == -1L || endPosition > startPosition)

        val normalizedTrackId = trackId.coerceAtLeast(0)

        val coreProfile: AudioProfile = when {
            isValidSonyParams -> {
                AudioProfile.SonySelect(securityKey, initVector)
            }

            isIsoExt -> {
                AudioProfile.SacdIso(normalizedTrackId, filename)
            }

            isCueExt -> {
                if (isRemoteProtocol) {
                    if (isValidTimeRange) {
                        AudioProfile.CueByTime(startPosition, endPosition)
                    } else {
                        AudioProfile.CueByIndex(normalizedTrackId)
                    }
                } else {
                    AudioProfile.CueByIndex(normalizedTrackId)
                }
            }

            isValidTimeRange -> {
                AudioProfile.CueByTime(startPosition, endPosition)
            }

            else -> AudioProfile.Standard
        }

        val finalProfile = if (isValidWebDavParams) {
            AudioProfile.WebDav(
                username = webDavUser,
                password = webDavPwd,
                targetProfile = coreProfile
            )
        } else {
            coreProfile
        }

        QYPlayerLogger.d(
            "Legacy Routing Detail:\n" +
                    "  TargetFile: $targetNameForDetection\n" +
                    "  ValidSony: $isValidSonyParams\n" +
                    "  ValidTime: $isValidTimeRange (Start=$startPosition, End=$endPosition)\n" +
                    "  ValidWebDav: $isValidWebDavParams\n" +
                    "  IsIso: $isIsoExt, IsCue: $isCueExt\n" +
                    "  -> Result Profile: $finalProfile"
        )

        return create(context, source, finalProfile, headers, dsdMode, d2pSampleRate)
    }

    private fun buildPlayer(
        context: Context,
        source: MediaSource,
        dsdMode: DSDMode,
        d2pSampleRate: D2pSampleRate,
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
        player.setD2pSampleRate(d2pSampleRate)
        player.setMediaSource(source)
        return player
    }
}