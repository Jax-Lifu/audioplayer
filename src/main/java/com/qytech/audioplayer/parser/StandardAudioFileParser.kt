package com.qytech.audioplayer.parser

import android.os.Environment
import com.qytech.audioplayer.extension.calculateMd5
import com.qytech.audioplayer.ffprobe.FFprobe
import com.qytech.audioplayer.model.AudioInfo
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

    override fun parse(): List<AudioInfo.Local>? {
        val file = File(filePath)
        if (!file.exists() || !file.isAudio()) {
            Timber.e("File not found or not an audio file: $filePath")
            return null
        }
        val ffMediaInfo = FFprobe.probeFile(filePath)
        if (ffMediaInfo == null) {
            return emptyList()
        }
        val albumCover = ffMediaInfo.image?.let { saveCoverImage(it) }
        return listOf(
            ffMediaInfo.toAudioFileInfo(
                path = file.absolutePath,
                folder = file.getAbsoluteFolder(),
                fileSize = file.length(),
                albumCover = albumCover
            )
        )
    }

    /**
     * 保存封面图片
     */
    private fun saveCoverImage(artBytes: ByteArray): String? {
        if (artBytes.isEmpty()) return null

        val md5 = artBytes.calculateMd5()
        val dir = File(Environment.getExternalStorageDirectory().absolutePath, ".qytech/cover/")
        val outputFile = File(dir, "$md5.jpg")

        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                Timber.e("Failed to create directory for cover images")
                return@runCatching null
            }
            if (!outputFile.exists()) {
                outputFile.writeBytes(artBytes)
            }
            outputFile.absolutePath
        }.getOrElse {
            Timber.e(it, "Failed to save cover image")
            null
        }
    }
}
