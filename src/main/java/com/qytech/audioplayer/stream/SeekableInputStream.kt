package com.qytech.audioplayer.stream

import java.io.InputStream

/**
 * 可 Seek 的输入流：支持读取 + 定位
 */
abstract class SeekableInputStream : InputStream() {
    abstract fun seek(position: Long)
    abstract fun getPosition(): Long

    abstract fun length(): Long
}
