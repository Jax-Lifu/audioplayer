package com.qytech.audioplayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.parser.AudioFileParserFactory
import com.qytech.audioplayer.utils.SystemPropUtil
import timber.log.Timber
import java.io.File


enum class DSDMode(val value: Int) {
    NATIVE(0),
    D2P(1),
    DOP(2);

    companion object {
        fun fromValue(value: Int) = DSDMode.entries.firstOrNull { it.value == value } ?: NATIVE
    }
}

data class PlayerExtras(
    val encryptedSecurityKey: String? = null,
    val encryptedInitVector: String? = null,
)

@OptIn(UnstableApi::class)
object AudioPlayerFactory {

    private var simpleCache: SimpleCache? = null

    val mediaPlayerCodecs = setOf(
        "mp1", "mp2", "mp3", "aac", "ape",
        "wmalossless", "wmapro", "wmav1", "wmav2", "adpcm_ima_qt",
        "vorbis", "pcm_s16le", "pcm_s24le", "pcm_s32le", "flac"
    )
    val exoPlayerCodecs = setOf(
        "opus", "pcm_mulaw", "pcm_alaw", "amrnb",
        "amrwb", "ac3", "dca"
    )

    suspend fun createAudioPlayer(
        context: Context,
        source: String,
        trackId: Int = 0,
        clientId: String? = null,
        clientSecret: String? = null,
        credentialsKey: String? = null,
        headers: Map<String, String> = emptyMap(),
        playerExtras: PlayerExtras? = null,
    ): AudioPlayer? {
        Timber.d("createAudioPlayer: $source")
        var audioInfo: AudioInfo
        if (source.isNotEmpty() &&
            clientId?.isNotEmpty() == true &&
            clientSecret?.isNotEmpty() == true
        ) {
            audioInfo = AudioInfo.Tidal(
                productId = source,
                clientId = clientId,
                clientSecret = clientSecret,
                credentialsKey = credentialsKey ?: "storage",
            )
        } else {
            val parser = AudioFileParserFactory.createParser(source, headers) ?: return null
            val index = if (trackId == 0) 0 else trackId - 1
            audioInfo = parser.parse()?.getOrNull(index) ?: return null
            if (audioInfo is AudioInfo.Remote && playerExtras != null) {
                audioInfo = audioInfo.copy(
                    encryptedSecurityKey = playerExtras.encryptedSecurityKey,
                    encryptedInitVector = playerExtras.encryptedInitVector,
                )
            }
        }
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
        SystemPropUtil.set("persist.vendor.dsd_mode", "NULL")
        return when (audioInfo) {
            is AudioInfo.Local -> buildLocalPlayer(context, audioInfo)
            is AudioInfo.Remote -> buildRemotePlayer(context, audioInfo, headers)
            is AudioInfo.Tidal -> TidalPlayer(context, audioInfo)
        }
    }

    private fun buildLocalPlayer(context: Context, info: AudioInfo.Local): AudioPlayer {
        val codec = info.codecName.lowercase()
        val formatName = info.formatName.lowercase()
        return when {
            formatName == "sacd" -> DsdAudioPlayer(context, info)
            codec in mediaPlayerCodecs -> RockitPlayer(context, info)
            codec in exoPlayerCodecs -> ExoAudioPlayer(context, simpleCache, info)
            else -> FFAudioPlayer(context, info)
        }
    }

    private fun buildRemotePlayer(
        context: Context,
        info: AudioInfo.Remote,
        headers: Map<String, String> = emptyMap(),
    ): AudioPlayer {
        val codec = info.codecName.lowercase()
        val formatName = info.formatName.lowercase()
        Timber.d("buildRemotePlayer: $codec $formatName")
        if (formatName == "sacd") {
            return DsdAudioPlayer(context, info, headers)
        }
        if (codec in exoPlayerCodecs || formatName == "hls") {
            return ExoAudioPlayer(context, simpleCache, info, headers)
        }
        return FFAudioPlayer(context, info, headers)
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