package com.qytech.audioplayer.player

/**
 * @author Administrator
 * @date 2025/9/19 16:41
 */

object FFmpegDstDecoder {
    init {
        System.loadLibrary("audioplayer")
    }

    external fun initDstDecoder(): Boolean

    external fun decodeDstFrame(data: ByteArray): ByteArray?

    external fun releaseDstDecoder()
}