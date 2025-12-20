package com.qytech.audioplayer.player

import android.content.Context
import androidx.core.net.toUri
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

        // --- 1. 数据清洗 ---
        val targetNameForDetection = if (!filename.isNullOrEmpty()) {
            filename
        } else {
            // 只有当 source 明确以常见的网络协议头开头时，才使用 Uri 解析器去剥离参数。
            // 常见的流媒体/网络协议：http, https, rtmp, rtsp, ftp, udp, mmsh, mmst, dav, davs
            val lowerSource = source.lowercase(Locale.getDefault())
            val isRemoteProtocol = lowerSource.startsWith("http://") ||
                    lowerSource.startsWith("https://") ||
                    lowerSource.startsWith("rtmp://") ||
                    lowerSource.startsWith("rtsp://") ||
                    lowerSource.startsWith("ftp://") ||
                    lowerSource.startsWith("udp://") ||
                    lowerSource.startsWith("mmsh://") ||
                    lowerSource.startsWith("mmst://")

            if (isRemoteProtocol) {
                // 情况 A: 标准网络 URL
                // 行为：严格剥离 ?query 和 #fragment
                try {
                    source.toUri().path ?: source.substringBefore("?")
                } catch (_: Exception) {
                    source.substringBefore("?")
                }
            } else {
                source
            }
        }.lowercase(Locale.getDefault())

        // --- 2. 参数有效性严格校验 (Validations) ---

        // [校验 1] Sony DRM: Key 和 IV 必须同时存在且不为空
        val isValidSonyParams = !securityKey.isNullOrEmpty() && !initVector.isNullOrEmpty()

        // [校验 2] WebDAV: 账号密码必须同时存在
        val isValidWebDavParams = !webDavUser.isNullOrEmpty() && !webDavPwd.isNullOrEmpty()

        // [校验 3] 时间范围: 
        // 1. start 和 end 不能为 null
        // 2. start 必须 >= 0 (排除 -1 或负数)
        // 3. end 必须为 -1 (表示直到末尾) 或者 end > start (结束时间必须大于开始时间)
        val isValidTimeRange = startPosition != null && endPosition != null &&
                startPosition >= 0 &&
                (endPosition == -1L || endPosition > startPosition)

        // [校验 4] 文件后缀
        val isIsoExt = targetNameForDetection.endsWith(".iso")
        val isCueExt = targetNameForDetection.endsWith(".cue")

        // --- 3. 严格路由逻辑 ---
        val coreProfile: AudioProfile = when {
            // [Priority 1]: Sony Select (参数有效才进入)
            isValidSonyParams -> {
                AudioProfile.SonySelect(securityKey, initVector)
            }

            // [Priority 2]: ISO 文件 (格式优先)
            // 只要是 .iso，必须走 SacdIso，忽略时间参数（因为 SacdPlayer 负责处理 ISO）
            isIsoExt -> {
                val validTrackId = if (trackId > 0) trackId else 0
                AudioProfile.SacdIso(validTrackId, filename)
            }

            // [Priority 3]: CUE 文件 (格式优先)
            // 只要是 .cue，必须走 CueByIndex 进行解析
            isCueExt -> {
                val validTrackId = if (trackId > 0) trackId else 0
                AudioProfile.CueByIndex(validTrackId)
            }

            // [Priority 4]: 显式时间范围 (仅当参数合法时)
            // 场景：普通音频文件 (flac/mp3/wav) 的片段播放
            isValidTimeRange -> {
                AudioProfile.CueByTime(startPosition, endPosition)
            }

            // [Priority 5]: 标准播放 (兜底)
            // 如果 startPosition = -1，isValidTimeRange 为 false，会落到这里，符合预期
            else -> AudioProfile.Standard
        }

        // --- 4. WebDAV 包装 ---
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