package com.qytech.audioplayer.strategy

import com.qytech.audioplayer.parser.AudioProbe
import com.qytech.audioplayer.utils.QYPlayerLogger
import java.io.File
import java.util.Locale

object MediaSourceStrategy {

    /**
     * 根据 Profile 创建 MediaSource
     * @param uri 播放地址
     * @param profile 播放策略配置 (核心修改点)
     * @param headers HTTP Headers
     */
    fun create(
        uri: String,
        profile: AudioProfile,
        headers: Map<String, String>? = null,
    ): MediaSource? {
        if (uri.isEmpty()) return null

        val safeHeaders = headers ?: emptyMap()
        val lowerUri = uri.lowercase(Locale.getDefault())

        // 利用 sealed interface 的特性进行模式匹配
        return when (profile) {
            // 1. 加密流 (Sony Select / DRM)
            is AudioProfile.SonySelect -> {
                SonySelectMediaSource(
                    uri,
                    profile.securityKey,
                    profile.initVector,
                    safeHeaders
                )
            }

            is AudioProfile.WebDav -> {
                WebDavMediaSource(
                    uri,
                    profile.username,
                    profile.password,
                    safeHeaders
                )
            }

            // 3. 显式时间范围 (CUE / 分轨 / 片段) - 优先级高，直接返回
            is AudioProfile.CueByTime -> {
                QYPlayerLogger.d("Strategy: Explicit time range: ${profile.startPosition} - ${profile.endPosition}")
                CueMediaSource(
                    uri,
                    trackIndex = -1, // 时间模式通常不依赖 index
                    startPosition = profile.startPosition,
                    endPosition = profile.endPosition,
                    headers = safeHeaders
                )
            }

            // 4. SACD ISO
            is AudioProfile.SacdIso -> {
                val trackIndex = (profile.trackId - 1).coerceAtLeast(0)
                SacdMediaSource(
                    uri,
                    trackIndex = trackIndex,
                    headers = safeHeaders
                )
            }

            // 5. CUE By Index (或本地自动探测)
            is AudioProfile.CueByIndex -> {
                val trackIndex = (profile.trackIndex - 1).coerceAtLeast(0)
                // 尝试解析 CUE 逻辑提取
                resolveCueByIndex(uri, trackIndex, safeHeaders)
            }

            // 6. 普通模式 (Standard)
            is AudioProfile.Standard -> {
                resolveStandardSource(uri, safeHeaders)
            }
        }
    }

    /**
     * 处理 CUE 索引查找逻辑 (抽离出来复用)
     */
    private fun resolveCueByIndex(
        uri: String,
        trackIndex: Int,
        headers: Map<String, String>,
    ): MediaSource? {
        val lowerUri = uri.lowercase(Locale.getDefault())
        // 网络流不支持文件探测，直接按流处理 (除非后续支持远程读取 CUE)
        if (isRemote(lowerUri)) {
            // 如果是网络 CUE 但只给了 Index 没给 Time，目前无法处理，只能回退到流播放
            // 或者如果业务层能保证网络 CUE Index 模式下已经转成了 Time 模式，则不会走到这里
            return StreamingMediaSource(uri, headers)
        }

        // --- 本地文件探测逻辑 ---
        var targetCuePath: String? = null
        val isCueExtension = lowerUri.endsWith(".cue")

        if (isCueExtension) {
            targetCuePath = uri
        } else if (trackIndex >= 0) {
            // 尝试查找同名 CUE
            try {
                val originalFile = File(uri)
                val parentDir = originalFile.parentFile
                val baseName = originalFile.nameWithoutExtension
                if (parentDir != null && parentDir.exists()) {
                    targetCuePath = parentDir.listFiles { _, name ->
                        name.equals("$baseName.cue", ignoreCase = true)
                    }?.firstOrNull()?.absolutePath
                }
            } catch (e: Exception) {
                QYPlayerLogger.e("Error probing local cue", e)
            }
        }

        if (targetCuePath != null) {
            val metadata = AudioProbe.probeFile(targetCuePath, headers)
            if (metadata != null && metadata.tracks.isNotEmpty()) {
                // 如果 trackIndex 为 -1 (Standard模式进来的)，默认取第0首？或者整轨？这里假设取第0首
                val validIndex = trackIndex.coerceAtLeast(0).coerceAtMost(metadata.tracks.lastIndex)
                val track = metadata.tracks[validIndex]
                return CueMediaSource(
                    track.path,
                    validIndex,
                    track.startMs,
                    track.endMs,
                    headers
                )
            }
        }

        return DefaultMediaSource(uri)
    }

    private fun resolveStandardSource(uri: String, headers: Map<String, String>): MediaSource {
        return if (isRemote(uri.lowercase(Locale.getDefault()))) {
            StreamingMediaSource(uri, headers)
        } else {
            DefaultMediaSource(uri)
        }
    }

    private fun isRemote(uri: String): Boolean {
        return uri.startsWith("http") || uri.startsWith("rtmp") || uri.startsWith("rtsp")
    }
}