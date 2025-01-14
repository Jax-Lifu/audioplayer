package com.qytech.audioplayer.parser

import com.qytech.core.extensions.AUDIO_FILE_EXTENSIONS
import com.qytech.core.extensions.getFileExtension

/**
 * 音频文件解析工厂类，根据文件扩展名选择合适的解析策略。
 */
object AudioFileParserFactory {

    /**
     * 根据文件扩展名选择正确的解析策略。
     */
    fun createParser(filePath: String): AudioFileParserStrategy? {
        val extension = filePath.getFileExtension()
        if (extension !in AUDIO_FILE_EXTENSIONS) {
            return null
        }
        return when (extension) {
            "dsf" -> DsfAudioFileParser(filePath)
            "dff" -> DffAudioFileParser(filePath)
            "iso" -> SacdAudioFileParser(filePath)
            else -> StandardAudioFileParser(filePath)
        }
    }
}

