package com.qytech.audioplayer.parser

import com.qytech.core.extensions.AUDIO_FILE_EXTENSIONS
import com.qytech.core.extensions.getFileExtension
import com.qytech.core.extensions.isRemoteUrl
import java.io.File

/**
 * 音频文件解析工厂类，根据文件扩展名选择合适的解析策略。
 */
object AudioFileParserFactory {


    /**
     * 根据文件扩展名选择正确的解析策略。
     */
    fun createParser(
        source: String,
        headers: Map<String, String> = emptyMap(),
    ): AudioFileParserStrategy? {
        //Timber.d("createParser: source = $source")
        if (source.isRemoteUrl()) {
            return NetworkAudioFileParser(source, headers)
        }
        val extension = source.getFileExtension().lowercase()
        //Timber.d("createParser: extension = $extension")
        if (extension !in AUDIO_FILE_EXTENSIONS) {
            //Timber.d("createParser: extension = $extension not support")
            return null
        }
        // 查找当前目录下有没有对应的CUE文件，如果有就走 Cue 解析器
        val cueAudioFile = findCueFile(File(source))
        if (cueAudioFile?.exists() == true) {
            return CueAudioFileParser(source)
        }
        return when (extension) {
            "dsf" -> DsfAudioFileParser(source)
            "dff" -> DffAudioFileParser(source)
            "iso" -> SacdAudioFileParser(source)
            else -> StandardAudioFileParser(source)
        }
    }

    private fun findCueFile(file: File): File? {
        val parentDir = file.parentFile ?: return null
        val basename = file.nameWithoutExtension
        val cueFiles = parentDir.listFiles { _, name ->
            // 判断当前音频目录下是否存在与音频文件同名的 .cue 文件
            name.equals("$basename.cue", ignoreCase = true)
        }
        return cueFiles?.firstOrNull()
    }
}

