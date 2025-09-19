package com.qytech.audioplayer.parser

import com.qytech.audioplayer.ffprobe.FFprobe
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.utils.AudioUtils
import com.qytech.core.extensions.getAbsoluteFolder
import com.qytech.core.extensions.isAudio
import timber.log.Timber
import java.io.File

open class StandardAudioFileParser(
    protected val sourceId: String,
    protected val headers: Map<String, String> = emptyMap(),
) : AudioFileParserStrategy {

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

    protected val reader by lazy { AudioFileReader(sourceId, headers) }

    override suspend fun parse(): List<AudioInfo.Local>? {
        //Timber.d("StandardAudioFileParser source id $sourceId")
        val file = File(sourceId)
        if (!file.exists() || !file.isAudio()) {
            Timber.e("File not found or not an audio file: $sourceId")
            return null
        }
        val ffMediaInfo = FFprobe.probeFile(sourceId)
        if (ffMediaInfo == null) {
            return emptyList()
        }
        val fingerprint = FFprobe.getFingerprint(sourceId, 30)
        val albumCover = ffMediaInfo.image?.let { AudioUtils.saveCoverImage(it) }
            ?: findLocalCoverImage(file.parentFile)?.absolutePath
        return listOf(
            ffMediaInfo.toLocalAudioFileInfo(
                path = file.absolutePath,
                folder = file.getAbsoluteFolder(),
                fileSize = file.length(),
                albumCover = albumCover,
                fingerprint = fingerprint,
            ) as AudioInfo.Local
        )
    }

    private fun findLocalCoverImage(directory: File?): File? {
        if (directory?.exists() != true) {
            return null
        }
        val images = directory.listFiles { file ->
            // 常见的封面图片扩展名列表
            file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "bmp")
        } ?: return null
        val priorityKeywords = listOf("cover", "folder", "front", "album")
        for (keyword in priorityKeywords) {
            val match =
                images.firstOrNull { it.nameWithoutExtension.contains(keyword, ignoreCase = true) }
            if (match != null) return match
        }
        return images.firstOrNull()
    }
}
