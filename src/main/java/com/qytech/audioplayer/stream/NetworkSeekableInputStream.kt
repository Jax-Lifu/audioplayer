package com.qytech.audioplayer.stream

import com.qytech.audioplayer.stream.netstream.BufferedHttpStream


class NetworkSeekableInputStream(
    url: String,
    headers: Map<String, String> = emptyMap(),
) : SeekableInputStream() {

    private val stream = BufferedHttpStream(
        url = url,
        headers = headers,
    )

    private var currentOffset = 0L

    override fun read(): Int {
        val b = ByteArray(1)
        val result = read(b, 0, 1)
        return if (result == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
//        Logger.d("current offset $currentOffset")
        val read = stream.read(b, off, len)
        if (read > 0) currentOffset += read
        return read
    }

    override fun seek(position: Long) {
        stream.seek(position)
        currentOffset = position
    }

    override fun getPosition(): Long = currentOffset
    override fun length(): Long = stream.length
    override fun close() {
        stream.close()
        super.close()
    }
}
