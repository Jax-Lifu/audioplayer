package com.qytech.audioplayer.parser

import com.qytech.audioplayer.utils.SuspendLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class AudioFileReader(
    val source: String,
    val headers: Map<String, String> = emptyMap(),
) : AutoCloseable {

    companion object {
        const val DEFAULT_BUFFER_SIZE = 2048
    }

    private var buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
    private var currentOffset = 0L

    private val isHttp = source.startsWith("http://") || source.startsWith("https://")

    private val file: RandomAccessFile? = if (!isHttp) {
        val f = File(source)
        require(f.exists()) { "File does not exist: $source" }
        RandomAccessFile(f, "r")
    } else {
        null
    }

    private val httpClient = if (isHttp) OkHttpClient() else null
    private val fileSizeLazy = SuspendLazy {
        if (isHttp) {
            withContext(Dispatchers.IO) {
                fetchHttpContentLength()
            }
        } else {
            file?.length() ?: 0L
        }
    }

    suspend fun getFileSize(): Long = fileSizeLazy.get()

    fun isHttp(): Boolean = isHttp

    /**
     * 支持绝对位置或相对位置的读取
     */
    suspend fun readBuffer(
        absoluteOffset: Long? = null,
        relativeOffset: Long? = null,
    ): ByteBuffer? {
        val startPosition = when {
            absoluteOffset != null -> absoluteOffset
            relativeOffset != null -> currentOffset + relativeOffset
            else -> currentOffset
        }

        return readData(startPosition)
    }

    /**
     * 根据位置读取数据并填充到 ByteBuffer
     */
    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun readData(position: Long): ByteBuffer? {
        if (position < 0) return null

        currentOffset = position
        buffer.clear()

        return if (isHttp) {
            readHttpRange(position)
        } else {
            val bytesRead = file?.channel?.read(buffer, position) ?: -1
            if (bytesRead < 0) return null
            buffer.flip()
            currentOffset += bytesRead
            buffer
        }
    }

    /**
     * 网络读取指定 Range 数据
     */
    private suspend fun readHttpRange(position: Long): ByteBuffer? = withContext(Dispatchers.IO) {
        val endPosition = position + buffer.capacity() - 1

        try {
            val request = Request.Builder()
                .url(source)
                .headers(headers.toHeaders())
                .addHeader("Range", "bytes=$position-$endPosition")
                .build()

            val response = httpClient?.newCall(request)?.execute()
            if (response == null || !response.isSuccessful) {
                Timber.e("HTTP Range 请求失败: code=${response?.code}")
                return@withContext null
            }

            val bytes = response.body?.bytes() ?: return@withContext null

            buffer.put(bytes)
            buffer.flip()
            currentOffset += bytes.size
            buffer
        } catch (e: Exception) {
            Timber.e(e, "读取 HTTP Range [$position-$endPosition] 失败")
            null
        }
    }

    /**
     * 获取远程文件大小
     */
    private suspend fun fetchHttpContentLength(): Long = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(source)
                .headers(headers.toHeaders())
                .build()

            val response = httpClient?.newCall(request)?.execute()
            if (response == null || !response.isSuccessful) {
                Timber.e("获取 Content-Length 失败: ${response?.code}")
                return@withContext -1
            }
            response.header("Content-Length")?.toLongOrNull() ?: -1
        } catch (e: Exception) {
            Timber.e(e, "请求 Content-Length 失败")
            -1
        }
    }

    fun resetBufferSize(newSize: Int) {
        require(newSize > 0) { "Buffer size must be greater than 0." }
        buffer = ByteBuffer.allocate(newSize)
    }

    override fun close() {
        file?.close()
    }
}
