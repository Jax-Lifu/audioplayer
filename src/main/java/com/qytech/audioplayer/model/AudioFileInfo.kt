package com.qytech.audioplayer.model

/**
 * 音频文件的基本信息。
 * 包含多个音轨信息（专辑可以包含多个音轨）和音频文件的头部信息（如编码、时长、采样率等）。
 */
data class AudioFileInfo(
    val filePath: String,                    // 音频文件的路径
    val trackInfo: List<AudioTrackInfo?>,    // 音频文件的轨道信息列表
    val header: AudioFileHeader?,            // 音频文件的头部信息（如采样率、比特率等）
)

/**
 * 音频文件的标签信息。
 * 包含音频文件的元数据，如艺术家、专辑、标题、评论等。
 */
data class AudioFileTags(
    val album: String,                      // 专辑名称
    val artist: String,                     // 艺术家名称（如歌手或创作人）
    val title: String,                      // 音乐标题（如歌曲名称）
    val duration: Long,                      // 音乐时长（单位：秒）
    val genre: String? = "Other",           // 音乐类型/风格，使用枚举类型，避免无效的字符串
    val albumCover: String? = null,         // 专辑封面图片的路径
    val artistPictures: String? = null,     // 艺术家图片的路径
    val date: String? = null,               // 年份（专辑或歌曲发行的年份）
    val trackNumber: Int = 1,               // 当前轨道的编号
    val totalTracks: Int = 1,               // 专辑的总轨道数
    val composer: String? = null,           // 作曲家（如果不同于艺术家）
    val performer: String? = null,          // 演奏者（与艺术家不同）
    val conductor: String? = null,          // 指挥
    val albumArtist: String? = null,        // 专辑艺术家（用于多艺术家的专辑）
    val comment: String? = null,            // 评论信息（用户注释）
)


/**
 * 音频文件头部信息。
 * 包括音频文件的技术参数，如时长、采样率、声道数、比特率等。
 * - byteRate = sampleRate * channelCount * bitsPerSample / 8
 * - blockAlign = channelCount * bitsPerSample / 8.0f
 * - bitsPerSecond = sampleRate * channelCount * bitsPerSample
 */
data class AudioFileHeader(
    val sampleRate: Int,                // 音频文件的采样率（单位：Hz）
    val channelCount: Int,              // 音频文件的声道数（1 = 单声道，2 = 立体声等）
    val bitsPerSample: Int,             // 每个样本的位数（位深度，例如16位或24位）
    val bitsPerSecond: Int,             // 音频的比特率（单位：bps，表示每秒传输的比特数）
    val byteRate: Int,                  // 音频的字节率（单位：字节/秒，通常为：sampleRate * channelCount * bitsPerSample / 8）
    val blockAlign: Float,              // 快对齐大小（通常表示每个样本的字节数）
    val codec: String,                  // 编解码器（如DSD, PCM等）
    val encodingType: String? = null,   // 编解码格式（如MP3, AAC, PCM等）
)

/**
 * 音轨信息。
 * 包含每个轨道的索引、偏移量、时长和标签信息。
 */
data class AudioTrackInfo(
    val duration: Long,                  // 轨道的持续时长（单位：秒）
    val tags: AudioFileTags?,           // 轨道的标签信息（如标题、艺术家等）
    val trackIndex: Int = 1,                // 当前轨道的索引（例如，第1轨，第2轨等）
    val offset: AudioOffsetInfo? = null // DSD 文件才需要的偏移量
)

data class AudioOffsetInfo(
    val startOffset: Long,              // 轨道开始的偏移位置（单位：字节）
    val endOffset: Long,                // 轨道结束的偏移位置（单位：字节）
    val dataLength: Long,               // 轨道数据长度（单位：字节）
)
