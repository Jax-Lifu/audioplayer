package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.SacdMediaSource

/**
 * 专门用于播放 SACD ISO 的播放器
 */
class SacdPlayer(
    context: Context,
) : BaseNativePlayer(
    context,
    PlayerStrategy.SACD
) {
    override fun setMediaSource(mediaSource: MediaSource) {
        super.setMediaSource(mediaSource)
        if (mediaSource is SacdMediaSource) {
            engine.setSource(
                mediaSource.uri,
                trackIndex = mediaSource.trackIndex,
                headers = mediaSource.headers
            )
        }
    }

    override fun setNextMediaSource(mediaSource: MediaSource) {
        if (!isNextMediaSourceAccepted(mediaSource)) return

        if (mediaSource is SacdMediaSource) {
            engine.setNextSource(
                mediaSource.uri,
                trackIndex = mediaSource.trackIndex,
                headers = mediaSource.headers
            )
        }
    }
}