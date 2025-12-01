package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.strategy.CueMediaSource
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.StreamingMediaSource

class FFPlayer(
    context: Context,
) : BaseNativePlayer(
    context,
    PlayerStrategy.FFmpeg
) {
    override fun setMediaSource(mediaSource: MediaSource) {
        super.setMediaSource(mediaSource)
        val header = if (mediaSource is StreamingMediaSource) {
            mediaSource.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
        } else {
            null
        }
        val startPos = (mediaSource as? CueMediaSource)?.startPosition ?: 0L
        val endPos = (mediaSource as? CueMediaSource)?.endPosition ?: -1L
        engine.setSource(mediaSource.uri, headers = header, startPos = startPos, endPos = endPos)
    }
}