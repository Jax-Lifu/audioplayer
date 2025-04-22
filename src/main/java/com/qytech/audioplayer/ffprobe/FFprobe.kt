package com.qytech.audioplayer.ffprobe

object FFprobe {
    init {
        System.loadLibrary("audioplayer")
    }

    external fun probeFile(source: String): FFMediaInfo?

}