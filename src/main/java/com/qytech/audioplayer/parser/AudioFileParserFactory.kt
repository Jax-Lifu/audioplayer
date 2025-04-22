package com.qytech.audioplayer.parser

import com.qytech.core.extensions.AUDIO_FILE_EXTENSIONS
import com.qytech.core.extensions.getFileExtension
import com.qytech.core.extensions.isRemoteUrl

/**
 * 音频文件解析工厂类，根据文件扩展名选择合适的解析策略。
 */
object AudioFileParserFactory {


    /**
     * 根据文件扩展名选择正确的解析策略。
     */
    fun createParser(source: String): AudioFileParserStrategy? {
        if (source.isRemoteUrl()) {
            return NetworkAudioFileParser(source)
        }
        val extension = source.getFileExtension()
        if (extension !in AUDIO_FILE_EXTENSIONS) {
            return null
        }
        return when (extension) {
            "dsf" -> DsfAudioFileParser(source)
            "dff" -> DffAudioFileParser(source)
            "iso" -> SacdAudioFileParser(source)
            else -> StandardAudioFileParser(source)
        }
    }
}

