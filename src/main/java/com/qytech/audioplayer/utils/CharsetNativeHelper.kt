package com.qytech.audioplayer.utils

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object CharsetNativeHelper {

    /**
     * 智能解码二进制数据为字符串
     * 策略：BOM -> UTF-8 Strict -> GBK Strict -> GBK (Lenient/Replace)
     */
    @JvmStatic
    fun decodeRawDataStrict(rawData: ByteArray): String {
        if (rawData.isEmpty()) return ""

        // 1. 【最优先】检查 BOM (Byte Order Mark)
        // 这是最准确的判断方式，处理 UTF-8 BOM, UTF-16 LE, UTF-16 BE
        val bomResult = detectAndRemoveBom(rawData)
        if (bomResult != null) {
            try {
                // 如果检测到 BOM，直接用对应的编码解析（跳过 BOM 头）
                return String(rawData, bomResult.offset, rawData.size - bomResult.offset, bomResult.charset)
            } catch (e: Exception) {
                Log.w("CueNativeHelper", "BOM detected but decode failed: ${e.message}")
                // 继续向下尝试
            }
        }

        // 2. 尝试 UTF-8 (Strict)
        // 绝大多数现代 CUE 都是 UTF-8
        if (isConvertible(rawData, Charsets.UTF_8)) {
            return String(rawData, Charsets.UTF_8)
        }

        // 3. 尝试 GBK (Strict)
        // 国内老资源常是 GBK
        val gbkCharset = try {
            Charset.forName("GBK")
        } catch (e: Exception) {
            Charset.defaultCharset()
        }

        if (isConvertible(rawData, gbkCharset)) {
            return String(rawData, gbkCharset)
        }

        // 4. 【兜底方案】强制解码 (Best Effort)
        // 如果上面都失败了（比如文件有轻微损坏，或者混合编码），不要返回 null。
        // 对于中文环境，通常假设它是 GBK 并不报错地解码（无法识别的字符替换为 ）
        Log.w("CueNativeHelper", "Strict decode failed, falling back to GBK lenient mode.")
        return decodeBytesLenient(rawData, gbkCharset)
    }

    /**
     * 检测数据是否符合特定编码规范 (Strict模式)
     */
    private fun isConvertible(bytes: ByteArray, charset: Charset): Boolean {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT) // 格式错误就报错
                .onUnmappableCharacter(CodingErrorAction.REPORT) // 无法映射就报错
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 宽容模式解码：遇到错误不崩溃，而是替换字符
     */
    private fun decodeBytesLenient(bytes: ByteArray, charset: Charset): String {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE) // 替换错误
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // 万一还不行，直接用 String 构造函数（虽然大概率结果一样）
            String(bytes, charset)
        }
    }

    /**
     * BOM 检测结果类
     */
    private data class BomInfo(val charset: Charset, val offset: Int)

    /**
     * 检测 BOM 头
     */
    private fun detectAndRemoveBom(bytes: ByteArray): BomInfo? {
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF) {
            return BomInfo(Charsets.UTF_8, 3)
        }
        if (bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xFE) {
            return BomInfo(Charset.forName("UTF-16LE"), 2)
        }
        if (bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFE &&
            (bytes[1].toInt() and 0xFF) == 0xFF) {
            return BomInfo(Charset.forName("UTF-16BE"), 2)
        }
        return null
    }
}