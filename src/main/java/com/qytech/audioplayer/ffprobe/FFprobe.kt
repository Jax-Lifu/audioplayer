package com.qytech.audioplayer.ffprobe

object FFprobe {
    init {
        System.loadLibrary("audioplayer")
    }

    external fun probeFile(source: String, headers: Map<String, String> = emptyMap()): FFMediaInfo?

    external fun getFingerprint(source: String, durationSeconds: Int): String?

}