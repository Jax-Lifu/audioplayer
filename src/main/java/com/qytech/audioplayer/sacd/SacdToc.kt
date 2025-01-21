package com.qytech.audioplayer.sacd

import com.qytech.audioplayer.extension.getFixedSizeStringOrEmpty
import com.qytech.audioplayer.extension.getString
import com.qytech.audioplayer.extension.skip
import com.qytech.audioplayer.model.ScarletBook
import java.nio.ByteBuffer

data class SacdToc(
    val id: String, // SACDMTOC: 标识符，用于标识这是一个 SACD 主目录记录
    val version: ScarletBook.Version, // 版本信息，描述了此 SACD 的版本号
    val albumSetSize: Int, // 专辑集大小，表示该 SACD 是一个多碟集的一部分时，总共的碟片数量
    val albumSequenceNumber: Int, // 专辑序号，指明当前碟片在整个多碟集中的序列号
    val albumCatalogNumber: String, // 专辑目录号，用于标识专辑的唯一编号（通常是一个商品号）
    val albumGenreList: List<ScarletBook.GenreTable>, // 专辑的音乐流派列表，表示专辑包含的音乐类型
    val area1Toc1Start: Int, // 区域 1（通常为立体声区）的第一部分 TOC（目录表）的起始位置
    val area1Toc2Start: Int, // 区域 1 的第二部分 TOC 的起始位置
    val area2Toc1Start: Int, // 区域 2（通常为多声道区）的第一部分 TOC 的起始位置
    val area2Toc2Start: Int, // 区域 2 的第二部分 TOC 的起始位置
    val discTypeHybrid: Boolean, // 碟片类型是否为混合盘，标识该 SACD 是否包含 CD 兼容层
    val area1TocSize: Int, // 区域 1 的 TOC 大小，表示该区域目录信息的大小
    val area2TocSize: Int, // 区域 2 的 TOC 大小
    val discCatalogNumber: String, // 碟片目录号，用于标识具体碟片的唯一编号
    val discGenreList: List<ScarletBook.GenreTable>, // 碟片的音乐流派列表
    val discDateYear: Int, // 碟片的发行年份
    val discDateMonth: Byte, // 碟片的发行月份
    val discDateDay: Byte, // 碟片的发行日期
    val textAreaCount: Byte, // 文本区域数量，表示碟片上包含多少个文本信息区域
    val localeList: List<ScarletBook.LocaleTable> // 语言区域列表，表示碟片支持的语言或地区信息
) {
    companion object {
        @OptIn(ExperimentalStdlibApi::class)
        fun read(buffer: ByteBuffer): SacdToc? {
            val id = buffer.getString(ScarletBook.SACD_ID_LENGTH)
            // Timber.d("read id: $id")
            if (id != ScarletBook.SACD_TOC) {
                return null
            }
            val version = ScarletBook.Version(buffer.get(), buffer.get())
            buffer.skip(6)
            val albumSetSize = buffer.short.toInt()
            val albumSequenceNumber = buffer.short.toInt()
            buffer.skip(4)
            val albumCatalogNumber = buffer.getFixedSizeStringOrEmpty(16)
            val albumGenreList = List(4) { ScarletBook.GenreTable.read(buffer) }
            buffer.skip(8)
            val area1Toc1Start = buffer.int
            val area1Toc2Start = buffer.int
            val area2Toc1Start = buffer.int
            val area2Toc2Start = buffer.int
            val discTypeHybrid = buffer.get() == 0x01.toByte()
            buffer.skip(3)
            val area1TocSize = buffer.short.toInt()
            val area2TocSize = buffer.short.toInt()
            val discCatalogNumber = buffer.getFixedSizeStringOrEmpty(16)
            val discGenreList = List(4) { ScarletBook.GenreTable.read(buffer) }
            val discDateYear = buffer.short.toInt()
            val discDateMonth = buffer.get()
            val discDateDay = buffer.get()
            buffer.skip(4)
            val textAreaCount = buffer.get()
            buffer.skip(7)
            val localeList = List(ScarletBook.MAX_LANGUAGE_COUNT) {
                ScarletBook.LocaleTable.read(
                    buffer
                )
            }
            return SacdToc(
                id,
                version,
                albumSetSize,
                albumSequenceNumber,
                albumCatalogNumber,
                albumGenreList,
                area1Toc1Start,
                area1Toc2Start,
                area2Toc1Start,
                area2Toc2Start,
                discTypeHybrid,
                area1TocSize,
                area2TocSize,
                discCatalogNumber,
                discGenreList,
                discDateYear,
                discDateMonth,
                discDateDay,
                textAreaCount,
                localeList
            )
        }
    }
}