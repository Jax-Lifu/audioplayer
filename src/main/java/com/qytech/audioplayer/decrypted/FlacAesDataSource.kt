package com.qytech.audioplayer.decrypted

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@UnstableApi
class FlacAesDataSource(
    private val upstream: DataSource,
    private val securityKey: String,
    private val iv: String
) : DataSource {

    companion object {
        private const val BLOCK_SIZE = 2048
        private const val TRANSFORMATION = "AES/OFB/NoPadding"
        //        private const val DEBUG_DUMP_TO_FILE = false
    }

    private lateinit var cipher: Cipher
    private lateinit var dataSpec: DataSpec

    private var cipherBuf = ByteArray(0)
    private var cipherBufOffset = 0
    private var finished = false

    // private var debugOutput: FileOutputStream? = null

    private val encBuffer = ByteArray(BLOCK_SIZE)
    private var encBufferOffset = 0 // 当前缓冲偏移
    private var encBufferFilled = 0 // 当前已填充字节数

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        upstream.open(dataSpec)

        val keySpec = SecretKeySpec(securityKey.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        /*if (DEBUG_DUMP_TO_FILE) {
            val file = File(context.cacheDir, "decrypted_debug.flac")
            debugOutput = FileOutputStream(file)
            Timber.d("Debug output file: ${file.absolutePath}")
        }*/
//        Timber.d("dataSpec.length ${dataSpec.length}")
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
//        return 464233
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (finished) return C.RESULT_END_OF_INPUT
        var bytesReadTotal = 0

        while (bytesReadTotal < length) {
            // 先处理已解密但还未输出的数据
            if (cipherBufOffset < cipherBuf.size) {
                val toCopy = minOf(length - bytesReadTotal, cipherBuf.size - cipherBufOffset)
                System.arraycopy(
                    cipherBuf,
                    cipherBufOffset,
                    buffer,
                    offset + bytesReadTotal,
                    toCopy
                )
                cipherBufOffset += toCopy
                bytesReadTotal += toCopy
                continue
            }

            // 开始读取新一块加密数据直到凑满 BLOCK_SIZE
            encBufferOffset = 0
            encBufferFilled = 0

            while (encBufferFilled < BLOCK_SIZE) {
                val read = upstream.read(encBuffer, encBufferFilled, BLOCK_SIZE - encBufferFilled)
                if (read == C.RESULT_END_OF_INPUT) {
                    finished = true
                    // 如果缓冲区里还有残留数据，不能解密，不足一个完整块，直接结束
                    return if (bytesReadTotal > 0) bytesReadTotal else C.RESULT_END_OF_INPUT
                }
                encBufferFilled += read
            }

            // 满了2048后才解密
            val decrypted = cipher.doFinal(encBuffer)
            if (decrypted != null && decrypted.isNotEmpty()) {
                cipherBuf = decrypted
                cipherBufOffset = 0

                /*if (DEBUG_DUMP_TO_FILE) {
                    debugOutput?.write(decrypted)
                }*/
            }
        }

        return bytesReadTotal
    }

    override fun getUri() = upstream.uri

    override fun close() {
        upstream.close()
        /*if (DEBUG_DUMP_TO_FILE) {
            debugOutput?.flush()
            debugOutput?.close()
        }*/
        // 清理 Cipher 状态
        cipher.doFinal()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()


}
