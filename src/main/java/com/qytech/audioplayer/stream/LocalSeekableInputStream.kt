package com.qytech.audioplayer.stream


import java.io.File
import java.io.RandomAccessFile

class LocalSeekableInputStream(file: File) : SeekableInputStream() {
    private val raf = RandomAccessFile(file, "r")

    override fun read(): Int = raf.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)

    override fun seek(position: Long) {
        raf.seek(position)
    }

    override fun getPosition(): Long = raf.filePointer
    override fun length(): Long = raf.length()

    override fun close() {
        raf.close()
        super.close()
    }
}
