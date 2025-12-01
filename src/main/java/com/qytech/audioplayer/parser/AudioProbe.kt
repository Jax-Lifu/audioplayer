package com.qytech.audioplayer.parser

import com.qytech.audioplayer.parser.model.AudioMetadata

object AudioProbe {
    init {
        System.loadLibrary("audioplayer") // 替换为你的库名
    }

    fun probeFile(
        path: String,
        headers: Map<String, String>? = null,
    ): AudioMetadata? = native_probe(path, headers)

    private external fun native_probe(
        path: String,
        headers: Map<String, String>?,
    ): AudioMetadata?
}