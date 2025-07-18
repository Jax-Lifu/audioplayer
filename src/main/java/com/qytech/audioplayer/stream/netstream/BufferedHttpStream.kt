package com.qytech.audioplayer.stream.netstream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BufferedHttpStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val blockSize: Int = 256 * 1024,                    // 单次 Range 请求大小：256KB
    private val bufferSize: Int = 1024 * 1024,                  // 缓冲区大小：1MB
    private val minReadChunk: Int = 256 * 1024,                 // 最小读取块：256KB，防止频繁触发read()
) : InputStream() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    val length: Long by lazy {
        fetchContentLength()
    }

    private val buffer = ByteArray(bufferSize)
    private var bufferReadPos = 0
    private var bufferWritePos = 0
    private var bufferDataSize = 0

    private var streamOffset: Long = 0L     // 当前读取位置
    private var fetchOffset: Long = 0L      // 当前下载偏移

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var closed = false

    private val fetcher = scope.launch {
        while (!closed) {
            val data = try {
                fetchRange(fetchOffset, blockSize)
            } catch (e: Exception) {
                Timber.e(e, "下载 range 失败")
                break
            }

            if (data.isEmpty()) {
                Timber.d("HTTP 数据读取完毕")
                break
            }

            lock.withLock {
                while (bufferDataSize >= bufferSize && !closed) {
                    notFull.await()
                }

                val space = bufferSize - bufferDataSize
                val toWrite = minOf(data.size, space)

                val end = (bufferWritePos + toWrite) % bufferSize
                if (end >= bufferWritePos) {
                    System.arraycopy(data, 0, buffer, bufferWritePos, toWrite)
                } else {
                    val firstPart = bufferSize - bufferWritePos
                    System.arraycopy(data, 0, buffer, bufferWritePos, firstPart)
                    System.arraycopy(data, firstPart, buffer, 0, end)
                }

                bufferWritePos = end
                bufferDataSize += toWrite
                fetchOffset += toWrite
                notEmpty.signalAll()
            }
        }
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val result = read(b, 0, 1)
        return if (result == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        lock.withLock {
            while (bufferDataSize < minReadChunk && !closed) {
                notEmpty.await()
            }

            if (closed && bufferDataSize == 0) return -1

            val toRead = minOf(len, bufferDataSize)
            val end = (bufferReadPos + toRead) % bufferSize

            if (end >= bufferReadPos) {
                System.arraycopy(buffer, bufferReadPos, b, off, toRead)
            } else {
                val firstPart = bufferSize - bufferReadPos
                System.arraycopy(buffer, bufferReadPos, b, off, firstPart)
                System.arraycopy(buffer, 0, b, off + firstPart, end)
            }

            bufferReadPos = end
            bufferDataSize -= toRead
            streamOffset += toRead
            notFull.signalAll()
            return toRead
        }
    }

    /**
     * 支持 seek 到任意位置，会清空缓存并重新下载
     */
    fun seek(offset: Long) {
        Timber.d("执行 seek($offset)，清空缓存")

        lock.withLock {
            streamOffset = offset
            fetchOffset = offset
            bufferReadPos = 0
            bufferWritePos = 0
            bufferDataSize = 0
            notFull.signalAll()
        }
    }

    override fun close() {
        Timber.d("关闭 BufferedHttpStream")
        closed = true
        fetcher.cancel()
        lock.withLock {
            notEmpty.signalAll()
            notFull.signalAll()
        }
        super.close()
    }

    /**
     * 实际的 Range 请求下载函数
     */
    private fun fetchRange(start: Long, size: Int): ByteArray {

        val end = start + size - 1
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .addHeader("Range", "bytes=$start-$end")
            .build()
        // Timber.d("fetchRange: start=$start, end=$end, size=${size}")

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("Range 请求失败: ${response.code}")
                return ByteArray(0)
            }
            response.body?.bytes() ?: ByteArray(0)
        }

    }

    private fun fetchContentLength(): Long {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("HTTP 请求失败: ${response.code}")
                return -1
            }
            // Timber.d("response headers: ${response.headers}")
            response.header("Content-Length")?.toLongOrNull() ?: -1
        }
    }
}
