package com.qytech.audioplayer.extension

/**
 * 解码十六进制字符串为 ByteArray。
 */
fun String.hexToByteArray(): ByteArray {
    val cleanInput = this.replace(" ", "")
    require(cleanInput.length % 2 == 0) { "Odd number of characters." }

    return ByteArray(cleanInput.length / 2) { i ->
        val index = i * 2
        val byte = cleanInput.substring(index, index + 2).toInt(16)
        byte.toByte()
    }
}
