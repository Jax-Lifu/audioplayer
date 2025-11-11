package com.qytech.audioplayer.parser

import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.extension.skip
import com.qytech.audioplayer.model.AudioInfo
import com.qytech.audioplayer.model.ScarletBook
import com.qytech.audioplayer.sacd.SacdAlbumInfo
import com.qytech.audioplayer.sacd.SacdAreaToc
import com.qytech.audioplayer.sacd.SacdToc
import com.qytech.audioplayer.sacd.SacdTrackOffset
import com.qytech.audioplayer.sacd.SacdTrackText
import com.qytech.audioplayer.sacd.SacdTrackTime
import com.qytech.audioplayer.utils.AudioUtils
import com.qytech.audioplayer.utils.Logger
import com.qytech.core.extensions.getAbsoluteFolder
import com.qytech.core.extensions.getFileName
import com.qytech.core.extensions.toAudioCodec
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

class SacdAudioFileParser(
    val sourceId: String,
    val headers: Map<String, String> = emptyMap(),
) : AudioFileParserStrategy {
    companion object {
        // 默认参数常量
        const val DSD_BITS_PER_SAMPLE = 1

        // SACD 特定参数
        const val SACD_TOC_START = 510    // 主 TOC 起始位置
        const val SACD_LOGICAL_SECTOR_SIZE = 2048  // 逻辑扇区大小
        const val SACD_PHYSICAL_SECTOR_SIZE = 2064 // 物理扇区大小
        const val DEFAULT_SAMPLE_RATE = 2822400

        const val ENCODING_TYPE_SACD = "SACD"
    }

    private val reader = AudioFileReader(sourceId, headers)
    private var sacdSectorSize = SACD_LOGICAL_SECTOR_SIZE
    private var buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)

    private var isMultiChannel = false
    private var toc: SacdToc? = null
    private var albumInfo: SacdAlbumInfo? = null
    private var areaToc: SacdAreaToc? = null
    private var trackOffsetList: List<SacdTrackOffset>? = null
    private var trackTimeList: List<SacdTrackTime>? = null
    private var trackTextList: List<SacdTrackText>? = null

    private var coverImage: File? = null

    override suspend fun parse(): List<AudioInfo>? {
        sacdSectorSize = detectSectorSize() ?: return null
        //Logger.d("SACD sector size detected: $sacdSectorSize")

        buffer = reader.readBuffer(absoluteOffset = SACD_TOC_START * sacdSectorSize.toLong())
            ?: return null
        updateBufferPosition()

        toc = SacdToc.read(buffer)
        if (toc == null) {
            Logger.e("Failed to read SACD TOC.")
            return null
        }

        albumInfo = readSacdAlbumInfo()
        areaToc = readSacdAreaToc()

        areaToc?.let {
            trackOffsetList = readSacdTrackOffsets()
            trackTimeList = readSacdTrackTimes()
            trackTextList = readSacdTrackTexts()
        }
        Logger.d("SACD TOC: $toc\nalbumInfo $albumInfo\nareaToc $areaToc trackOffsetList $trackOffsetList\ntrackTimeList $trackTimeList\n trackTextList $trackTextList")
        coverImage = LocalCoverImage.findLocalCoverImage(File(folder))
        return generateTrackInfo()
    }

    private val sampleRate by lazy {
        areaToc?.sampleFrequency ?: DEFAULT_SAMPLE_RATE
    }

    private val channelCount by lazy {
        areaToc?.channelCount ?: 2
    }

    private val album by lazy {
        albumInfo?.albumInfo?.albumTitle ?: folder
    }

    private val date by lazy {
        val date =
            String.format(Locale.getDefault(), "%02d%02d", toc?.discDateMonth, toc?.discDateDay)
        "${toc?.discDateYear}${date}"
    }

    private val filename by lazy {
        sourceId.getFileName()
    }

    private val folder by lazy {
        sourceId.getAbsoluteFolder()
    }
    private val codecName by lazy {
        sampleRate.toAudioCodec(areaToc?.frameFormat?.getFormatName())
    }
    private val bitRate by lazy {
        AudioUtils.getBitRate(sampleRate, channelCount, DSD_BITS_PER_SAMPLE)
    }

    private val genre by lazy {
        toc?.albumGenreList?.firstOrNull()?.genre?.toLocalizedString() ?: "other"
    }

    private suspend fun generateTrackInfo(): List<AudioInfo> =
        List(getTrackCount()) { index ->
            val trackOffset = trackOffsetList?.get(index)
            val trackTime = trackTimeList?.get(index)
            val trackText = trackTextList?.get(index)
            val startOffset = trackOffset?.trackStart?.times(sacdSectorSize) ?: 0
            val endOffset = trackOffset?.trackEnd?.times(sacdSectorSize) ?: 0
            val dataLength = endOffset - startOffset

            val duration = (trackTime?.durationTime?.getDuration() ?: 0L) * 1000

            if (reader.isHttp()) {
                AudioInfo.Remote(
                    sourceId = sourceId,
                    url = sourceId,
                    codecName = codecName,
                    formatName = ENCODING_TYPE_SACD,
                    channels = channelCount,
                    sampleRate = sampleRate,
                    bitRate = bitRate,
                    bitPreSample = DSD_BITS_PER_SAMPLE,
                    duration = duration,
                    title = trackText?.title ?: "${filename}_Track${index + 1}",
                    album = album,
                    artist = trackText?.performer ?: "Unknown artist",
                    genre = genre,
                    date = date,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    dataLength = dataLength,
                    trackId = index + 1,
                )
            } else {
                AudioInfo.Local(
                    filepath = sourceId,
                    folder = folder,
                    codecName = codecName,
                    formatName = ENCODING_TYPE_SACD,
                    channels = channelCount,
                    sampleRate = sampleRate,
                    bitRate = bitRate,
                    bitPreSample = DSD_BITS_PER_SAMPLE,
                    duration = duration,
                    title = trackText?.title ?: "${filename}_Track${index + 1}",
                    album = album,
                    artist = trackText?.performer ?: "Unknown artist",
                    genre = genre,
                    albumImageUrl = coverImage?.absolutePath,
                    date = date,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    dataLength = dataLength,
                    fileSize = reader.getFileSize(),
                    trackId = index + 1,
                )
            }

        }

    private suspend fun detectSectorSize(): Int? {
        // 尝试使用逻辑扇区大小读取
        buffer =
            reader.readBuffer(absoluteOffset = SACD_TOC_START * SACD_LOGICAL_SECTOR_SIZE.toLong())
                ?: return null
        if (isValidSacdToc(buffer)) {
            isMultiChannel = false
            return SACD_LOGICAL_SECTOR_SIZE
        }

        // 尝试使用物理扇区大小读取
        buffer =
            reader.readBuffer(absoluteOffset = SACD_TOC_START * SACD_PHYSICAL_SECTOR_SIZE.toLong())
                ?: return null
        buffer.skip(12) // 跳过多声道偏移
        if (isValidSacdToc(buffer)) {
            isMultiChannel = true
            return SACD_PHYSICAL_SECTOR_SIZE
        }

        Timber.e("Failed to detect SACD sector size.")
        return null
    }

    private fun isValidSacdToc(buffer: ByteBuffer): Boolean {
        val id = buffer.getString(ScarletBook.SACD_ID_LENGTH)
        //Logger.d("isValidSacdToc: $id")
        return id == ScarletBook.SACD_TOC
    }

    private fun updateBufferPosition() {
        buffer.position(if (isMultiChannel) 12 else 0)
    }

    private suspend fun readSacdAlbumInfo(): SacdAlbumInfo? {
        buffer = reader.readBuffer() ?: return null
        updateBufferPosition()
        return SacdAlbumInfo.read(buffer)
    }

    private suspend fun readSacdAreaToc(): SacdAreaToc? {
        val areaStart = toc?.area1Toc1Start.takeIf { it != 0 } ?: toc?.area2Toc2Start ?: return null
        buffer =
            reader.readBuffer(absoluteOffset = areaStart * sacdSectorSize.toLong()) ?: return null
        updateBufferPosition()
        return SacdAreaToc.read(buffer)
    }

    private suspend fun readSacdTrackOffsets(): List<SacdTrackOffset>? {
        buffer = reader.readBuffer() ?: return null
        updateBufferPosition()
        return SacdTrackOffset.read(buffer, getTrackCount(), isMultiChannel)
    }

    private suspend fun readSacdTrackTimes(): List<SacdTrackTime>? {
        buffer = reader.readBuffer() ?: return null
        updateBufferPosition()
        return SacdTrackTime.read(buffer, getTrackCount(), isMultiChannel)
    }

    private suspend fun readSacdTrackTexts(): List<SacdTrackText>? {
        val areaStart = toc?.area1Toc1Start.takeIf { it != 0 } ?: toc?.area2Toc2Start ?: return null
        val trackTextOffset = areaToc?.trackTextOffset ?: return null
        reader.resetBufferSize(sacdSectorSize * 4)
        buffer =
            reader.readBuffer(absoluteOffset = (trackTextOffset + areaStart) * sacdSectorSize.toLong())
                ?: return null
        updateBufferPosition()
        return SacdTrackText.read(buffer, getTrackCount(), isMultiChannel)
    }

    private fun getTrackCount() = areaToc?.trackCount ?: 0
}
