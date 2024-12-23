package com.qytech.audioplayer.model

/**
 * 音频文件的基本信息，包含多个音轨信息和音频文件的头部信息。
 */
data class AudioFileInfo(
    val filePath: String,                  // 音频文件的路径
    val trackInfo: AudioTrackInfo = AudioTrackInfo(), // 音频文件的轨道信息
    val header: AudioFileHeader = AudioFileHeader()  // 音频文件的头部信息
) {
    // 验证音频文件信息的有效性
    fun isValid(): Boolean = filePath.isNotEmpty() && trackInfo.isValid() && header.isValid()
}

/**
 * 音频文件的标签信息，包含元数据如艺术家、专辑、标题等。
 */
data class AudioFileTags(
    val album: String = "Unknown album",                // 专辑名称
    val artist: String = "Unknown artist",              // 艺术家名称
    val title: String = "Unknown title",                // 音乐标题
    val duration: Long = 0,                             // 音乐时长（秒）
    val genre: String = "other",                        // 音乐类型（避免过度复杂化）
    val albumCover: String? = null,                     // 专辑封面图片路径
    val artistPictures: String? = null,                 // 艺术家图片路径
    val date: String? = null,                           // 发行年份
    val trackNumber: Int = 1,                           // 当前轨道编号
    val totalTracks: Int = 1,                           // 专辑总轨道数
    val composer: String? = null,                       // 作曲家
    val performer: String? = null,                      // 演奏者
    val conductor: String? = null,                      // 指挥
    val albumArtist: String? = null,                    // 专辑艺术家
    val comment: String? = null                         // 评论
) {
    // 验证标签信息的有效性，确保时长大于0
    fun isValid(): Boolean = duration > 0
}

/**
 * 音频文件的头部信息，包含技术参数如采样率、声道数、比特率等。
 */
data class AudioFileHeader(
    val sampleRate: Int = 0,              // 采样率（Hz）
    val channelCount: Int = 0,            // 声道数（1 = 单声道，2 = 立体声等）
    val bitsPerSample: Int = 0,           // 每个样本的位数（如16位或24位）
    val bitsPerSecond: Int = 0,           // 音频比特率（bps）
    val byteRate: Int = 0,                // 字节率（字节/秒）
    val blockAlign: Float = 0f,           // 快对齐大小（字节）
    val codec: String = "",              // 编解码器（如DSD, PCM等）
    val encodingType: String? = null      // 编解码格式（如MP3, AAC, PCM等）
) {
    // 验证头部信息的有效性，确保采样率、声道数、位深度都大于0
    fun isValid(): Boolean =
        sampleRate > 0 && channelCount > 0 && bitsPerSample > 0
}

/**
 * 音轨信息，包含轨道索引、偏移量、时长和标签信息。
 */
data class AudioTrackInfo(
    val duration: Long = 0,                         // 轨道时长（秒）
    val tags: AudioFileTags = AudioFileTags(),      // 轨道标签信息
    val trackIndex: Int = 1,                        // 轨道索引
    val offset: AudioOffsetInfo = AudioOffsetInfo() // 偏移量（DSD文件特有）
) {
    // 验证音轨信息的有效性，确保时长大于0且标签有效
    fun isValid(): Boolean = duration > 0 && tags.isValid()
}

/**
 * 音轨偏移信息，包含轨道在文件中的位置、长度等数据。
 */
data class AudioOffsetInfo(
    val startOffset: Long = 0,             // 轨道开始偏移位置（字节）
    val endOffset: Long = 0,               // 轨道结束偏移位置（字节）
    val dataLength: Long = 0              // 轨道数据长度（字节）
)
