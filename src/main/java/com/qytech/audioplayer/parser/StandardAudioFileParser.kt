package com.qytech.audioplayer.parser

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.qytech.audioplayer.extension.*
import com.qytech.audioplayer.model.*
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

    override fun parse(): List<AudioFileInfo>? {
        val file = File(filePath)
        if (!file.exists() || !file.isAudioFile()) {
            Timber.e("File not found or not an audio file: $filePath")
            return null
        }
        return runCatching {
            listOf(
                AudioFileInfo(
                    filePath = filePath,
                    trackInfo = getAudioTrackInfo() ?: AudioTrackInfo(),
                    header = getAudioFileHeader() ?: AudioFileHeader()
                )
            )
        }.onFailure {
            Timber.e("Failed to parse audio file: $filePath")
        }.getOrNull()
    }

    /**
     * 获取音频文件头信息
     */
    private fun getAudioFileHeader(): AudioFileHeader? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            val format = (0 until extractor.trackCount).firstNotNullOfOrNull {
                extractor.getTrackFormat(it).takeIf { fmt ->
                    fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            } ?: return null

            extractAudioFileHeader(format)
        } catch (e: Exception) {
            Timber.e(e, "Error reading audio file header: $filePath")
            null
        } finally {
            extractor.release() // 确保资源被释放
        }
    }

    /**
     * 提取音频文件头部信息
     */
    private fun extractAudioFileHeader(format: MediaFormat): AudioFileHeader? {
        return runCatching {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bitsPerSample = format.getInteger("bits-per-sample", 16)

            val byteRate = sampleRate * channelCount * bitsPerSample / 8
            val blockAlign = channelCount * bitsPerSample / 8.0f
            val bitsPerSecond = sampleRate * channelCount * bitsPerSample
            val codec = sampleRate.getAudioCodec()
            val codingType = filePath.fileExtension().uppercase()

            AudioFileHeader(
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitsPerSample = bitsPerSample,
                bitsPerSecond = bitsPerSecond,
                byteRate = byteRate,
                blockAlign = blockAlign,
                codec = codec,
                encodingType = codingType
            )
        }.getOrElse {
            Timber.e(it, "Failed to parse audio file header: $filePath")
            null
        }
    }

    /**
     * 获取音轨信息和音频标签
     */
    private fun getAudioTrackInfo(): AudioTrackInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            extractAudioTrackInfo(retriever)
        } catch (e: Exception) {
            Timber.e(e, "Error reading audio file tags: $filePath")
            null
        } finally {
            retriever.release() // 确保资源被释放
        }
    }

    /**
     * 提取音轨信息
     */
    private fun extractAudioTrackInfo(retriever: MediaMetadataRetriever): AudioTrackInfo? {
        return runCatching {
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "Unknown album"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unknown artist"
            val genre =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "other"
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: filePath.filename()
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000) ?: 0
            val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            val albumArtist =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val totalTracks =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
                    ?.toIntOrNull() ?: 1

            val formattedDate = listOfNotNull(year, date).joinToString(" ")
            val artBytes = retriever.embeddedPicture
            val albumCover = artBytes?.let { saveCoverImage(it) }

            val tags = AudioFileTags(
                artist = artist,
                title = title,
                album = album,
                duration = duration,
                genre = genre,
                albumCover = albumCover,
                date = formattedDate,
                trackNumber = totalTracks,
                totalTracks = totalTracks,
                composer = composer,
                albumArtist = albumArtist
            )

            AudioTrackInfo(trackIndex = 1, duration = duration, tags = tags)
        }.getOrElse {
            Timber.e(it, "Failed to parse audio file tags: $filePath")
            null
        }
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
