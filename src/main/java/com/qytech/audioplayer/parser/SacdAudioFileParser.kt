package com.qytech.audioplayer.parser

import com.qytech.audioplayer.extension.filename
import com.qytech.audioplayer.extension.getAudioCodec
import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.extension.skip
import com.qytech.audioplayer.model.AudioFileHeader
import com.qytech.audioplayer.model.AudioFileInfo
import com.qytech.audioplayer.model.AudioFileTags
import com.qytech.audioplayer.model.AudioOffsetInfo
import com.qytech.audioplayer.model.AudioTrackInfo
import com.qytech.audioplayer.model.ScarletBook
import com.qytech.audioplayer.sacd.SacdAlbumInfo
import com.qytech.audioplayer.sacd.SacdAreaToc
import com.qytech.audioplayer.sacd.SacdToc
import com.qytech.audioplayer.sacd.SacdTrackOffset
import com.qytech.audioplayer.sacd.SacdTrackText
import com.qytech.audioplayer.sacd.SacdTrackTime
import com.qytech.audioplayer.utils.AudioUtils
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.Locale

class SacdAudioFileParser(val filePath: String) : AudioFileParserStrategy {
    companion object {
        // 默认参数常量
        const val DSD_BITS_PER_SAMPLE = 1

        // SACD 特定参数
        const val SACD_TOC_START = 510    // 主 TOC 起始位置
        const val SACD_LOGICAL_SECTOR_SIZE = 2048  // 逻辑扇区大小
        const val SACD_PHYSICAL_SECTOR_SIZE = 2064 // 物理扇区大小
        const val DEFAULT_SAMPLE_RATE = 44100

        const val ENCODING_TYPE_SACD = "ISO"
    }

    private val reader = AudioFileReader(filePath)
    private var sacdSectorSize = SACD_LOGICAL_SECTOR_SIZE
    private var buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)

    private var isMultiChannel = false
    private var toc: SacdToc? = null
    private var albumInfo: SacdAlbumInfo? = null
    private var areaToc: SacdAreaToc? = null
    private var trackOffsetList: List<SacdTrackOffset>? = null
    private var trackTimeList: List<SacdTrackTime>? = null
    private var trackTextList: List<SacdTrackText>? = null

    override fun parse(): AudioFileInfo? {
        sacdSectorSize = detectSectorSize() ?: return null
        //Timber.d("SACD sector size detected: $sacdSectorSize")

        buffer = reader.readBuffer(absoluteOffset = SACD_TOC_START * sacdSectorSize.toLong())
            ?: return null
        updateBufferPosition()

        toc = SacdToc.read(buffer)
        if (toc == null) {
            Timber.e("Failed to read SACD TOC.")
            return null
        }
        //Timber.d("SACD TOC: $toc")

        albumInfo = readSacdAlbumInfo()
        areaToc = readSacdAreaToc()

        areaToc?.let {
            trackOffsetList = readSacdTrackOffsets()
            trackTimeList = readSacdTrackTimes()
            trackTextList = readSacdTrackTexts()
        }
        val sampleRate = areaToc?.sampleFrequency ?: 0
        val channelCount = areaToc?.channelCount ?: 0
        val bitsPerSecond =
            AudioUtils.getBitsPerSecond(sampleRate, channelCount, DSD_BITS_PER_SAMPLE)
        val byteRate = AudioUtils.getByteRate(sampleRate, channelCount, DSD_BITS_PER_SAMPLE)
        val blockAlign = AudioUtils.getBlockAlign(channelCount, DSD_BITS_PER_SAMPLE)
        val codec = sampleRate.getAudioCodec()
        val header = AudioFileHeader(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = DSD_BITS_PER_SAMPLE,
            bitsPerSecond = bitsPerSecond,
            byteRate = byteRate,
            blockAlign = blockAlign,
            encodingType = ENCODING_TYPE_SACD,
            codec = codec
        )
        return AudioFileInfo(
            filePath = filePath,
            trackInfo = generateTrackInfo(),
            header = header
        )
    }

    private val genre by lazy {
        toc?.albumGenreList?.firstOrNull()?.genre?.name?.lowercase()?.replace("_", " ")
    }
    private val album by lazy {
        albumInfo?.albumInfo?.albumTitle ?: "Unknown album"
    }

    private val date by lazy {
        val date =
            String.format(Locale.getDefault(), "%02d%02d", toc?.discDateMonth, toc?.discDateDay)
        "${toc?.discDateYear}${date}"
    }

    private val filename by lazy {
        filePath.filename()
    }

    private fun generateTrackInfo(): List<AudioTrackInfo> = List(getTrackCount()) { index ->
        val trackOffset = trackOffsetList?.get(index)
        val trackTime = trackTimeList?.get(index)
        val trackText = trackTextList?.get(index)
        val startOffset = trackOffset?.trackStart?.times(sacdSectorSize) ?: 0
        val endOffset = trackOffset?.trackEnd?.times(sacdSectorSize) ?: 0
        val dataLength = endOffset - startOffset

        val duration = trackTime?.durationTime?.getDuration() ?: 0L

        val tags = AudioFileTags(
            title = trackText?.title ?: "${filename}_Track${index + 1}",
            artist = trackText?.performer ?: "Unknown artist",
            album = album,
            duration = duration,
            genre = genre,
            date = date,
            trackNumber = index + 1,
            totalTracks = getTrackCount()
        )
        val offset = AudioOffsetInfo(
            startOffset = startOffset,
            endOffset = endOffset,
            dataLength = dataLength
        )

        AudioTrackInfo(
            trackIndex = index + 1,
            duration = duration,
            tags = tags,
            offset = offset
        )
    }

    private fun detectSectorSize(): Int? {
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
        // Timber.d("isValidSacdToc: $id")
        return id == ScarletBook.SACD_TOC
    }

    private fun updateBufferPosition() {
        buffer.position(if (isMultiChannel) 12 else 0)
    }

    private fun readSacdAlbumInfo(): SacdAlbumInfo? {
        buffer = reader.readBuffer() ?: return null
        updateBufferPosition()
        return SacdAlbumInfo.read(buffer)
    }

    private fun readSacdAreaToc(): SacdAreaToc? {
        val areaStart = toc?.area1Toc1Start.takeIf { it != 0 } ?: toc?.area2Toc2Start ?: return null
        buffer =
            reader.readBuffer(absoluteOffset = areaStart * sacdSectorSize.toLong()) ?: return null
        updateBufferPosition()
        return SacdAreaToc.read(buffer)
    }

    private fun readSacdTrackOffsets(): List<SacdTrackOffset>? {
        buffer = reader.readBuffer() ?: return null
        updateBufferPosition()
        return SacdTrackOffset.read(buffer, getTrackCount(), isMultiChannel)
    }

    private fun readSacdTrackTimes(): List<SacdTrackTime>? {
        buffer = reader.readBuffer() ?: return null
        updateBufferPosition()
        return SacdTrackTime.read(buffer, getTrackCount(), isMultiChannel)
    }

    private fun readSacdTrackTexts(): List<SacdTrackText>? {
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