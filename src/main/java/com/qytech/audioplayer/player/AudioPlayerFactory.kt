package com.qytech.audioplayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.parser.AudioFileParserFactory
import timber.log.Timber
import java.io.File

@OptIn(UnstableApi::class)
object AudioPlayerFactory {

    private var simpleCache: SimpleCache? = null

    val mediaPlayerCodecs = setOf(
        "mp1", "mp2", "mp3", "aac", "ape",
        "wmalossless", "wmapro", "wmav1", "wmav2", "adpcm_ima_qt",
        "vorbis", "pcm_s16le", "pcm_s24le", "pcm_s32le", "flac"
    )
    val exoPlayerCodecs = setOf(
        "opus", "alac", "pcm_mulaw", "pcm_alaw", "amrnb",
        "amrwb", "ac3", "dca"
    )

    suspend fun createAudioPlayer(
        context: Context,
        source: String,
        trackId: Int = 0,
        headers: Map<String, String> = emptyMap(),
    ): AudioPlayer? {
        Timber.d("createAudioPlayer: $source")
        val parser = AudioFileParserFactory.createParser(source, headers) ?: return null
        val index = if (trackId == 0) 0 else trackId - 1
        val audioInfo = parser.parse()?.getOrNull(index) ?: return null
        Timber.d("createAudioPlayer: $audioInfo")
        return createInternal(context, audioInfo, headers)
    }

    @Deprecated(
        "Ued createAudioPlayer(context, source, trackId, headers) instead",
        ReplaceWith("createAudioPlayer(context, source, trackId, headers)")
    )
    fun createAudioPlayer(
        context: Context,
        audioInfo: AudioInfo,
        headers: Map<String, String> = emptyMap(),
    ): AudioPlayer {
        val headers = if (
            audioInfo is AudioInfo.Remote &&
            audioInfo.headers?.isNotEmpty() == true
        ) {
            audioInfo.headers
        } else {
            headers
        }
        return createInternal(context, audioInfo, headers)
    }

    private fun createInternal(
        context: Context,
        audioInfo: AudioInfo,
        headers: Map<String, String> = emptyMap(),
    ): AudioPlayer {
        initCache(context)
        return when (audioInfo) {
            is AudioInfo.Local -> buildLocalPlayer(context, audioInfo)
            is AudioInfo.Remote -> buildRemotePlayer(context, audioInfo, headers)
        }
    }

    private fun buildLocalPlayer(context: Context, info: AudioInfo.Local): AudioPlayer {
        val codec = info.codecName.lowercase()
        return when {
            codec.startsWith("dst") -> DsdAudioPlayer(context, info)
            codec in mediaPlayerCodecs -> RockitPlayer(context, info)
            codec in exoPlayerCodecs -> ExoAudioPlayer(context, simpleCache, info)
            else -> FFAudioPlayer(info)
        }
    }

    private fun buildRemotePlayer(
        context: Context,
        info: AudioInfo.Remote,
        headers: Map<String, String> = emptyMap(),
    ): AudioPlayer {
        val codec = info.codecName.lowercase()
        if (codec in exoPlayerCodecs && !info.sourceId.contains("115cdn")) {
            return ExoAudioPlayer(context, simpleCache, info, headers)
        }
        return FFAudioPlayer(info, headers)
    }

    private fun initCache(context: Context) {
        if (simpleCache == null) {
            simpleCache = SimpleCache(
                File(context.cacheDir, "media_cache"),
                LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024), // 500MB
                StandaloneDatabaseProvider(context)
            )
        }
    }
}