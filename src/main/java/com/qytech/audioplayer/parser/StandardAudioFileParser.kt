package com.qytech.audioplayer.parser

import com.qytech.audioplayer.ffprobe.FFprobe
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.utils.AudioUtils
import com.qytech.core.extensions.getAbsoluteFolder
import com.qytech.core.extensions.isAudio
import timber.log.Timber
import java.io.File

open class StandardAudioFileParser(protected val filePath: String) : AudioFileParserStrategy {

    companion object {
        const val DSD_BITS_PER_SAMPLE = 1

        // 文件头标识符
        const val HEADER_ID_DSF = "DSD "     // DSF 文件头
        const val HEADER_ID_DFF = "FRM8"    // DFF 文件头

        // 文件区块标识符常量
        const val BLOCK_ID_FMT = "fmt "     // 格式块
        const val BLOCK_ID_DATA = "data"   // 数据块
        const val BLOCK_ID_FORMAT = "DSD " // 格式定义块
        const val BLOCK_ID_PROP = "PROP"   // 属性块
        const val BLOCK_ID_SOUND = "SND "  // 声音块
        const val BLOCK_ID_FS = "FS  "     // 采样频率块
        const val BLOCK_ID_CHANNEL = "CHNL" // 通道块
        const val BLOCK_ID_ID3 = "ID3 "    // ID3 标签块
        const val BLOCK_ID_COMMENTS = "COMT" // 注释块
    }

    protected val reader by lazy { AudioFileReader(filePath) }

    override suspend fun parse(): List<AudioInfo.Local>? {
        val file = File(filePath)
        if (!file.exists() || !file.isAudio()) {
            Timber.e("File not found or not an audio file: $filePath")
            return null
        }
        val ffMediaInfo = FFprobe.probeFile(filePath)
        if (ffMediaInfo == null) {
            return emptyList()
        }
        val albumCover = ffMediaInfo.image?.let { AudioUtils.saveCoverImage(it) }
        return listOf(
            ffMediaInfo.toAudioFileInfo(
                path = file.absolutePath,
                folder = file.getAbsoluteFolder(),
                fileSize = file.length(),
                albumCover = albumCover
            ) as AudioInfo.Local
        )
    }


}
