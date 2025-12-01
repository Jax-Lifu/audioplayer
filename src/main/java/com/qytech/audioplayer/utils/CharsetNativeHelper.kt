package com.qytech.audioplayer.utils


import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object CharsetNativeHelper {

    @JvmStatic
    fun decodeRawDataStrict(rawData: ByteArray): String? {
        if (rawData.isEmpty()) return ""

        return try {
            // 1. 尝试 UTF-8 Strict
            decodeBytes(rawData, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                // 2. 尝试 GBK Strict (兼容 CP936)
                decodeBytes(rawData, Charset.forName("GBK"))
            } catch (e2: Exception) {
                Log.e("CueNativeHelper", "Decode failed for both UTF-8 and GBK")
                null
            }
        }
    }

    private fun decodeBytes(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        val byteBuffer = ByteBuffer.wrap(bytes)
        val charBuffer = decoder.decode(byteBuffer)
        return charBuffer.toString()
    }
}