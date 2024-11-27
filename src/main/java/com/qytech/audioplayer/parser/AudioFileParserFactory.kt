package com.qytech.audioplayer.parser

import android.content.Context
import com.qytech.audioplayer.extension.audioFileExtensions
import com.qytech.audioplayer.extension.fileExtension

/**
 * 音频文件解析工厂类，根据文件扩展名选择合适的解析策略。
 */
object AudioFileParserFactory {

    /**
     * 根据文件扩展名选择正确的解析策略。
     */
    fun createParser(filePath: String): AudioFileParserStrategy? {
        val extension = filePath.fileExtension()
        if (extension !in audioFileExtensions) return null
        return when (extension) {
            "dsf" -> DsfAudioFileParser(filePath)
            "dff" -> DffAudioFileParser(filePath)
            "iso" -> SacdAudioFileParser(filePath)
            else -> StandardAudioFileParser(filePath)
        }
    }
}

