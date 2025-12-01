package com.qytech.audioplayer.parser.model

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize


/**
 * 统一轨道模型
 * 代表播放列表中的一项
 */
@Keep
@Parcelize
data class AudioTrackItem(
    // --- 索引 ---
    val trackId: Int,             // 轨道号

    // --- 核心元数据 ---
    val title: String,          // 标题
    val artist: String,         // 歌手
    val album: String,          // 专辑名
    val genre: String? = null,  // 流派

    // --- 播放路径 ---
    val path: String,           // 实际播放路径

    // --- 时间 ---
    val startMs: Long = 0,
    val endMs: Long = -1,
    val durationMs: Long,

    // --- 技术参数 ---
    val format: String? = null, // e.g. "flac", "dsd"
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitDepth: Int = 0,
    val bitRate: Long = 0
) : Parcelable

/**
 * 统一音频元数据实体
 * 对应一次解析请求的结果
 */
@Keep
@Parcelize
data class AudioMetadata(
    // --- 核心识别 ---
    val uri: String,                 // 原始文件路径

    // --- 专辑通用信息 ---
    val albumTitle: String,
    val albumArtist: String,
    val genre: String? = null,
    val date: String? = null,        // 发行/制作日期

    // --- 封面 ---
    val coverPath: String? = null,   // 封面文件本地路径

    // --- 轨道列表 ---
    val tracks: List<AudioTrackItem>,

    // 1. 适用于 Standard (普通文件)
    val lyrics: String? = null,      // 内嵌歌词
) : Parcelable