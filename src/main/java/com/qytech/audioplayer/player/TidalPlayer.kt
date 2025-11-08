package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.model.AudioInfo


/**
 * @author Administrator
 * @date 2025/9/28 16:34
 */

@Deprecated("TidalPlayer 已被废弃，建议使用 ExoPlayer 替代")
class TidalPlayer(
    private val context: Context,
    override val audioInfo: AudioInfo.Tidal,
) : BaseAudioPlayer(audioInfo) {
    override fun prepare() {
    }

    override fun play() {
    }

    override fun pause() {
    }

    override fun stop() {
    }

    override fun release() {
    }

    override fun seekTo(positionMs: Long) {
    }

    override fun fastForward(ms: Long) {
    }

    override fun fastRewind(ms: Long) {
    }

    override fun getCurrentPosition(): Long = 0L

}