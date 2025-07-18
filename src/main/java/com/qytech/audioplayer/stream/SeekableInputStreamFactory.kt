package com.qytech.audioplayer.stream


import java.io.File

object SeekableInputStreamFactory {

    private fun fromLocalFile(file: File): SeekableInputStream {
        return LocalSeekableInputStream(file)
    }

    private fun fromHttpUrl(url: String, headers: Map<String, String> = emptyMap()): SeekableInputStream {
        return NetworkSeekableInputStream(url, headers)
    }

    fun create(
        sourceId: String,
        headers: Map<String, String> = emptyMap(),
    ): SeekableInputStream? {
        if (sourceId.startsWith("http://") || sourceId.startsWith("https://")) {
            return fromHttpUrl(sourceId, headers)
        }
        val file = File(sourceId)
        if (!file.exists()) return null
        return fromLocalFile(file)
    }
}
