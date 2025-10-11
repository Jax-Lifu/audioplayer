package com.qytech.audioplayer.player

import android.app.Application
import android.content.Context
import com.qytech.audioplayer.model.AudioInfo
import com.tidal.sdk.auth.TidalAuth
import com.tidal.sdk.auth.model.AuthConfig
import com.tidal.sdk.eventproducer.EventSender
import com.tidal.sdk.eventproducer.model.ConsentCategory
import com.tidal.sdk.player.Player
import com.tidal.sdk.player.common.model.MediaProduct
import com.tidal.sdk.player.common.model.ProductType
import com.tidal.sdk.player.playbackengine.PlaybackEngine
import com.tidal.sdk.player.playbackengine.model.Event
import com.tidal.sdk.player.playbackengine.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

object TidalPlayerManager {
    private var player: Player? = null

    fun getPlayer(context: Context, auth: TidalAuth, eventSender: EventSender): Player {
        if (player == null) {
            player = Player(
                application = context.applicationContext as Application,
                credentialsProvider = auth.credentialsProvider,
                eventSender = eventSender
            )
        }
        return player!!
    }

    fun release() {
        player?.release()
        player = null
    }
}

/**
 * @author Administrator
 * @date 2025/9/28 16:34
 */
class TidalPlayer(
    private val context: Context,
    override val audioInfo: AudioInfo.Tidal,
) : BaseAudioPlayer(audioInfo) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var progressJob: Job? = null

    private var playbackEngine: PlaybackEngine? = null

    private val eventSender = object : EventSender {
        override fun sendEvent(
            eventName: String,
            consentCategory: ConsentCategory,
            payload: String,
            headers: Map<String, String>,
        ) {
            Timber.d("sendEvent: $eventName, $consentCategory, $payload, $headers")
        }

        override fun setBlockedConsentCategories(blockedConsentCategories: Set<ConsentCategory>) {
            Timber.d("setBlockedConsentCategories: $blockedConsentCategories")
        }

    }

    override fun prepare() {
        val auth = TidalAuth.getInstance(
            context = context,
            config = AuthConfig(
                clientId = audioInfo.clientId,
                clientSecret = audioInfo.clientSecret,
                credentialsKey = audioInfo.credentialsKey,
                enableCertificatePinning = true,
            )
        )
        val tidalSdk = TidalPlayerManager.getPlayer(
            context = context,
            auth = auth,
            eventSender = eventSender
        )
        playbackEngine = tidalSdk.playbackEngine
        playbackEngine?.events?.onEach { event ->
            if (event is Event.PlaybackStateChange) {
                val playState = when (event.playbackState) {
                    PlaybackState.IDLE -> com.qytech.audioplayer.player.PlaybackState.IDLE
                    PlaybackState.PLAYING -> com.qytech.audioplayer.player.PlaybackState.PLAYING
                    PlaybackState.NOT_PLAYING -> com.qytech.audioplayer.player.PlaybackState.STOPPED
                    PlaybackState.STALLED -> com.qytech.audioplayer.player.PlaybackState.PREPARING
                }
                updateStateChange(playState)
            }
        }?.launchIn(coroutineScope)
        playbackEngine?.load(MediaProduct(ProductType.TRACK, audioInfo.productId))
    }

    override fun play() {
        startProgressJob()
        playbackEngine?.play()
    }

    override fun pause() {
        stopProgressJob()
        playbackEngine?.pause()
    }

    override fun stop() {
        stopProgressJob()
        playbackEngine?.play()
    }

    override fun release() {
        stopProgressJob()
        playbackEngine?.reset()
    }

    override fun seekTo(positionMs: Long) {
        playbackEngine?.seek(positionMs.toFloat())
    }

    override fun fastForward(ms: Long) {
        val currentPosition = getCurrentPosition()
        playbackEngine?.seek((currentPosition + ms).toFloat())
    }

    override fun fastRewind(ms: Long) {
        playbackEngine?.seek((getCurrentPosition() - ms).toFloat())
    }


    override fun getCurrentPosition(): Long {
        return playbackEngine?.assetPosition?.toLong() ?: 0L
    }

    override fun getDuration(): Long {
        return playbackEngine?.playbackContext?.duration?.toLong() ?: 0L
    }

    private fun startProgressJob() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch {
            while (isActive) {
                withContext(Dispatchers.Main) {
                    // 需要考虑到CUE文件，播放到CUE当前轨道结束的时候应该Stop
                    if (getCurrentPosition() >= getDuration()) {
                        stop()
                    }
                    updateProgress(getCurrentPosition())
                }
                delay(500)
            }
        }
    }

    private fun stopProgressJob() {
        progressJob?.cancel()
        progressJob = null
    }

}