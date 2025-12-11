package com.qytech.audioplayer.parser

import com.qytech.audioplayer.parser.model.AudioMetadata
import com.qytech.audioplayer.strategy.ScanProfile

object AudioProbe {
    init {
        System.loadLibrary("audioplayer")
    }

    /**
     * 统一探测入口
     *
     * @param source 文件路径 或 URL
     * @param profile 扫描配置，默认为标准模式
     */
    fun probe(
        source: String,
        profile: ScanProfile = ScanProfile.Standard,
    ): AudioMetadata? {
        var headers: Map<String, String>? = null
        var filename: String? = null
        var audioSourceUrl: String? = null
        if (profile is ScanProfile.RemoteProfile) {
            headers = profile.headers
            filename = profile.filename

            if (profile is ScanProfile.RemoteCue) {
                audioSourceUrl = profile.audioSourceUrl
            }
        }
        return nativeProbe(source, headers, filename, audioSourceUrl)
    }

    @Deprecated(
        "Use probe instead",
        ReplaceWith("AudioProbe.probe(source, profile)", "com.qytech.audioplayer.parser.AudioProbe")
    )
    fun probeFile(
        path: String,
        headers: Map<String, String>? = null,
        filename: String? = null,
        audioSourceUrl: String? = null,
    ): AudioMetadata? = nativeProbe(path, headers, filename, audioSourceUrl)

    private external fun nativeProbe(
        source: String,
        headers: Map<String, String>?,
        filename: String?,
        audioSourceUrl: String?,
    ): AudioMetadata?
}