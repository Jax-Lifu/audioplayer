package com.qytech.audioplayer.utils

import java.nio.charset.Charset

object StringEncodingUtil {

    /**
     * 判断字符串是否可读（ASCII、中文、日文、韩文占比 > 70%，替换字符 < 30%）
     */
    private fun isReadable(str: String): Boolean {
        if (str.isEmpty()) return false
        // 如果全是替换字符也应该返回 false
        if (str.all { it == '\uFFFD' || it.code == 0x3f || it.code == 0x63 }) return false

        var readable = 0
        var suspicious = 0
        for (ch in str) {
            when {
                ch.code in 0x20..0x7E -> readable++ // 可见 ASCII
                ch.code in 0x4E00..0x9FFF -> readable++ // 中文
                ch.code in 0x3040..0x30FF -> readable++ // 日文
                ch.code in 0xAC00..0xD7AF -> readable++ // 韩文
                ch == '\uFFFD' || ch.code == 0x3f -> suspicious++ // 替换字符
            }
        }
        val ratioReadable = readable.toDouble() / str.length
        val ratioSuspicious = suspicious.toDouble() / str.length
        return ratioReadable > 0.7 && ratioSuspicious < 0.3
    }

    /**
     * 针对 FFmpeg 返回的 String 做智能修复
     */
    fun fixEncoding(data: ByteArray?): String? {
        val bytes = data ?: return null

        // 尝试 UTF-8
        val utf8Str = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (!utf8Str.isNullOrEmpty() && isReadable(utf8Str)) return utf8Str

        // 尝试 UTF-8 -> ISO-8859-1 -> GBK
        val utf8GbkStr = runCatching {
            String(
                String(bytes, Charsets.UTF_8).toByteArray(Charsets.ISO_8859_1),
                Charset.forName("GBK")
            )
        }.getOrNull()
        if (!utf8GbkStr.isNullOrEmpty() && isReadable(utf8GbkStr)) return utf8GbkStr

        // 尝试直接 GBK
        val gbkStr = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull()
        if (!gbkStr.isNullOrEmpty() && isReadable(gbkStr)) return gbkStr

        return bytes.toString(Charsets.UTF_8)
    }


    /**
     * 简单评分：可读字符越多，替换字符越少越好
     */
    private fun calculateReadableScore(str: String): Double {
        var readable = 0
        var suspicious = 0
        for (ch in str) {
            when {
                ch.code in 0x20..0x7E -> readable++
                ch.code in 0x4E00..0x9FFF -> readable++
                ch.code in 0x3040..0x30FF -> readable++
                ch.code in 0xAC00..0xD7AF -> readable++
                ch == '\uFFFD' || ch == '?' -> suspicious++
            }
        }
        return readable - suspicious * 2.0
    }
}
