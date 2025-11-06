package com.qytech.audioplayer.parser

import com.qytech.audioplayer.ffprobe.FFprobe
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.parser.netstream.NetStreamFormat
import com.qytech.audioplayer.parser.netstream.StreamFormatDetector
import com.qytech.audioplayer.utils.AudioUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 网络音频文件解析策略：
 * 1. 自动探测流类型（SACD/HLS/PCM/DSD）
 * 2. SACD → 专用解析器
 * 3. HLS → Exo 播放（直接返回 Remote Info）
 * 4. 其他音频 → FFprobe 分析
 */
class NetworkAudioFileParser(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : AudioFileParserStrategy {

    override suspend fun parse(): List<AudioInfo>? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext emptyList()
        if (url.contains("sonyselect")) {
            return@withContext listOf(
                AudioInfo.Remote(
                    sourceId = url,
                    url = url,
                    codecName = "sonyselect",
                    formatName = "",
                    duration = 0,
                    channels = 2,
                    sampleRate = 44100,
                    bitRate = 1,
                    bitPreSample = -1,
                    title = "",
                    album = "",
                    artist = "",
                    genre = "",
                    date = "",
                    headers = headers
                )
            )
        }

        Timber.d("NetworkAudioFileParser: start detecting format for $url")

        // 1️⃣ 使用统一的网络探测器检测格式
        val format = StreamFormatDetector.detect(url, headers)
        Timber.d("NetworkAudioFileParser: detected format = $format")

        when (format) {
            NetStreamFormat.SACD -> {
                // SACD → SACD 解析器
                Timber.d("Detected SACD stream, using SacdAudioFileParser")
                return@withContext SacdAudioFileParser(url, headers).parse()
            }

            NetStreamFormat.HLS -> {
                // HLS 走 Exo 播放，直接返回 Remote AudioInfo
                Timber.d("Detected HLS stream, returning Remote AudioInfo")
                return@withContext listOf(
                    AudioInfo.Remote(
                        sourceId = url,
                        url = url,
                        codecName = "aac",
                        formatName = "hls",
                        duration = 0,
                        channels = 2,
                        sampleRate = 44100,
                        bitRate = 1,
                        bitPreSample = -1,
                        title = "",
                        album = "",
                        artist = "",
                        genre = "",
                        date = "",
                        headers = headers
                    )
                )
            }

            NetStreamFormat.PCM, NetStreamFormat.DSD -> {
                // 其他音频格式 → FFprobe 获取详细信息
                Timber.d("Detected PCM/DSD stream, using FFprobe analysis")
                val ffMediaInfo = FFprobe.probeFile(url, headers) ?: return@withContext emptyList()
                val albumCover = ffMediaInfo.image?.let { AudioUtils.saveCoverImage(it) }
                return@withContext listOf(
                    ffMediaInfo.toRemoteAudioFileInfo(
                        url,
                        albumCover,
                        headers
                    )
                )
            }

            else -> {
                Timber.w("Unknown or unsupported stream format, attempting fallback FFprobe parse")
                val ffMediaInfo = FFprobe.probeFile(url, headers)
                if (ffMediaInfo != null) {
                    val albumCover = ffMediaInfo.image?.let { AudioUtils.saveCoverImage(it) }
                    return@withContext listOf(
                        ffMediaInfo.toRemoteAudioFileInfo(
                            url,
                            albumCover,
                            headers
                        )
                    )
                }
            }
        }

        emptyList()
    }
}
