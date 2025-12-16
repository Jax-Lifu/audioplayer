package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.strategy.CueMediaSource
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.strategy.StreamingMediaSource
import com.qytech.audioplayer.strategy.WebDavMediaSource
import com.qytech.audioplayer.strategy.WebDavUtils // 确保导入这个工具类

class FFPlayer(
    context: Context,
) : BaseNativePlayer(
    context,
    PlayerStrategy.FFmpeg
) {
    override fun setMediaSource(mediaSource: MediaSource) {
        super.setMediaSource(mediaSource)

        if (mediaSource is WebDavMediaSource) {
            val (encodedUrl, newHeaders) = WebDavUtils.process(mediaSource)
            engine.setSource(encodedUrl, newHeaders)
            return
        }

        val header = if (mediaSource is StreamingMediaSource) mediaSource.headers else emptyMap()
        val startPos = (mediaSource as? CueMediaSource)?.startPosition ?: 0L
        val endPos = (mediaSource as? CueMediaSource)?.endPosition ?: -1L

        engine.setSource(mediaSource.uri, headers = header, startPos = startPos, endPos = endPos)
    }
}