package com.qytech.audioplayer.strategy

import com.qytech.audioplayer.parser.AudioProbe
import com.qytech.audioplayer.utils.QYLogger
import java.io.File
import java.util.Locale

object MediaSourceStrategy {

    /**
     * @param uri 播放地址 (物理文件路径 或 URL)
     * @param trackIndex 轨道索引 (ISO 选轨用，0-based)
     */
    fun create(
        uri: String,
        trackIndex: Int = -1,
        securityKey: String? = null,
        initVector: String? = null,
        headers: Map<String, String>? = null,
    ): MediaSource? {
        val lowerUri = uri.lowercase(Locale.getDefault())
        if (uri.isEmpty()) {
            return null
        }
        val safeHeaders = headers ?: emptyMap()

        // 1. 加密流 (Sony Select / DRM) -> 优先级最高
        if (!securityKey.isNullOrEmpty() && !initVector.isNullOrEmpty()) {
            return SonySelectMediaSource(uri, securityKey, initVector, safeHeaders)
        }

        // 2. SACD ISO (容器)
        if (lowerUri.endsWith(".iso")) {
            return SacdMediaSource(uri, trackIndex = trackIndex.coerceAtLeast(0))
        }

        // 3. CUE 分段播放 (两种情况)
        // 情况 A: 传入的是 .cue 文件路径 -> 需要解析获取实际音频路径和时间
        // trackIndex 表示CUE文件中的轨道索引
        val isCueExtension = lowerUri.endsWith(".cue")

        if (isCueExtension || trackIndex >= 0) {
            var targetCuePath: String? = null

            if (isCueExtension) {
                // 如果原本就是 cue 结尾，直接使用
                targetCuePath = uri
            } else {
                // 如果是音频文件 (如 .flac) 但指定了 trackIndex，则去同目录下查找同名 CUE
                val originalFile = File(uri)
                val parentDir = originalFile.parentFile
                val baseName = originalFile.nameWithoutExtension // 获取文件名（不含扩展名）

                if (parentDir != null && parentDir.exists()) {
                    // 使用 listFiles 配合过滤器查找，忽略大小写
                    val foundFile = parentDir.listFiles { _, name ->
                        name.equals("$baseName.cue", ignoreCase = true)
                    }?.firstOrNull()

                    targetCuePath = foundFile?.absolutePath
                }
            }

            if (targetCuePath != null) {
                QYLogger.d("Found CUE path: $targetCuePath")
                val metadata = AudioProbe.probeFile(targetCuePath, safeHeaders)
                QYLogger.d("CUE metadata: $metadata")

                if (metadata != null && metadata.tracks.isNotEmpty()) {
                    val validIndex = trackIndex.coerceIn(0, metadata.tracks.lastIndex)
                    val realTrackItem = metadata.tracks[validIndex]
                    QYLogger.d("CUE track: $realTrackItem")

                    return CueMediaSource(
                        realTrackItem.path,
                        validIndex,
                        realTrackItem.startMs,
                        realTrackItem.endMs,
                        safeHeaders
                    )
                }
            }
        }

        // 4. 网络流媒体 (http/rtmp/rtsp)
        if (isRemote(lowerUri)) {
            return StreamingMediaSource(uri, safeHeaders)
        }

        // 5. 默认普通本地文件 (MP3, FLAC, WAV, DSF)
        return DefaultMediaSource(uri)
    }

    private fun isRemote(uri: String): Boolean {
        return uri.startsWith("http") || uri.startsWith("rtmp") || uri.startsWith("rtsp")
    }
}