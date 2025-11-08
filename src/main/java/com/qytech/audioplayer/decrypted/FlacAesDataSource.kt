package com.qytech.audioplayer.decrypted

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okio.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@UnstableApi
class FlacAesDataSource(
    private val upstream: DataSource,
    private val securityKey: String,
    private val iv: String,
) : DataSource {

    companion object {
        private const val BLOCK_SIZE = 2048
        private const val TRANSFORMATION = "AES/OFB/NoPadding"
    }

    private lateinit var cipher: Cipher
    private var cipherBuf = ByteArray(0)
    private var cipherBufOffset = 0
    private var finished = false
    private var pendingInitialSkipBytes = 0

    private val encBuffer = ByteArray(BLOCK_SIZE)
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // BLOCK_SIZE 对齐
        val alignedPosition = (dataSpec.position / BLOCK_SIZE) * BLOCK_SIZE
        // 解密后我们要跳过的前置无效解密字节
        pendingInitialSkipBytes = (dataSpec.position - alignedPosition).toInt()

        // 调整 DataSpec，向下对齐 seek
        val adjustedDataSpec = dataSpec.buildUpon()
            .setPosition(alignedPosition)
            .build()
        val length = upstream.open(adjustedDataSpec)

        // 初始化 Cipher
        val keySpec = SecretKeySpec(securityKey.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        }

        var totalRead = 0
        while (totalRead < BLOCK_SIZE) {
            val read = upstream.read(encBuffer, totalRead, BLOCK_SIZE - totalRead)
            if (read == C.RESULT_END_OF_INPUT) throw IOException("EOF during initial read")
            totalRead += read
        }

        // 解密第一块数据
        cipherBuf = cipher.update(encBuffer, 0, BLOCK_SIZE)
        cipherBufOffset = pendingInitialSkipBytes

        // 返回实际可读的剩余长度
        return if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else length - (dataSpec.position - alignedPosition)
    }


    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (finished) return C.RESULT_END_OF_INPUT
        var bytesCopied = 0

        while (bytesCopied < length) {
            if (cipherBufOffset < cipherBuf.size) {
                val toCopy = minOf(length - bytesCopied, cipherBuf.size - cipherBufOffset)
                System.arraycopy(cipherBuf, cipherBufOffset, buffer, offset + bytesCopied, toCopy)
                cipherBufOffset += toCopy
                bytesCopied += toCopy
                continue
            }

            var totalRead = 0
            while (totalRead < BLOCK_SIZE) {
                val read = upstream.read(encBuffer, totalRead, BLOCK_SIZE - totalRead)
                if (read == C.RESULT_END_OF_INPUT) {
                    finished = true
                    return if (bytesCopied > 0) bytesCopied else C.RESULT_END_OF_INPUT
                }
                totalRead += read
            }

            cipherBuf = cipher.doFinal(encBuffer)
            cipherBufOffset = 0
        }

        return bytesCopied
    }

    override fun getUri() = upstream.uri

    override fun close() {
        upstream.close()
    }
}