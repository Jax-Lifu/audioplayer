package com.qytech.audioplayer.extension

import android.media.AudioFormat
import java.io.File

/**
 * 扩展函数，根据采样率返回音频编码格式
 */
fun Int.getAudioCodec(): String {
    return if (this >= 2822400) {
        "DSD${this / 44100}" // 采样率大于等于 2822400 时，返回 DSD 编码格式
    } else {
        "PCM" // 其他情况返回 PCM
    }
}

/**
 * 支持的音频文件扩展名集合
 *
 * 压缩格式: .aac, .mp3, .ogg, .m4a, .wma, .opus
 * 无损格式: .flac, .alac, .wav, .aif, .aiff, .ape, .tak
 * DSD 格式: .dsf, .dff, .iso
 * 模块音乐格式: .mod, .xm, .it, .s3m
 * MIDI 格式: .mid, .midi, .kar
 * 其他少见格式: .caf, .wv, .rm, .ra, .au, .mqa, .wave
 * 编解码器: .dts, .mp1, .mp2, .mpc, .speex, .sbc, .g722, .g726, .gsm, .gsm_ms, .g723_1, .g729, .qcelp, .qdm2, .qdmc, .qoa
 * 高级格式: .truehd, .wmalossless, .wmav1, .wmav2, .xma1, .xma2
 *
 */
val audioFileExtensions: Set<String> = setOf(
    "aac", "adpcm", "amr", "ape", "au",
    "caf", "cue", "dff", "dsf", "dts", "flac",
    "g722", "g726", "gsm",
    "gsm_ms", "iso",
    "m4a", "m4b", "mid", "midi", "mp1", "mp2", "mp3", "mp3adu", "mp3adufloat", "mp3on4",
    "mka", "mod", "mpc", "opus", "qcelp", "qdm2", "qdmc", "qoa",
    "real_144", "ralf", "speex", "sbc", "shorten",
    "sol_dpcm", "sonic", "s3m", "tak", "tta", "truehd",
    "vma", "vorbis", "wav", "wma", "wmav1", "wmav2", "wv",
    "xan_dpcm", "xma", "xma1", "xma2", "rm"
)

/**
 * 扩展函数，判断文件是否为已知的音频文件
 * @return 如果文件扩展名在支持列表中，返回 true；否则返回 false
 */
fun File.isAudioFile(): Boolean {
    return this.extension.lowercase() in audioFileExtensions
}

fun Int.toEncodingType(): String {
    return when (this) {
        AudioFormat.ENCODING_INVALID -> "ENCODING_INVALID"
        AudioFormat.ENCODING_PCM_16BIT -> "ENCODING_PCM_16BIT"
        AudioFormat.ENCODING_PCM_8BIT -> "ENCODING_PCM_8BIT"
        AudioFormat.ENCODING_PCM_FLOAT -> "ENCODING_PCM_FLOAT"
        AudioFormat.ENCODING_AC3 -> "ENCODING_AC3"
        AudioFormat.ENCODING_E_AC3 -> "ENCODING_E_AC3"
        AudioFormat.ENCODING_DTS -> "ENCODING_DTS"
        AudioFormat.ENCODING_DTS_HD -> "ENCODING_DTS_HD"
        AudioFormat.ENCODING_MP3 -> "ENCODING_MP3"
        AudioFormat.ENCODING_AAC_LC -> "ENCODING_AAC_LC"
        AudioFormat.ENCODING_AAC_HE_V1 -> "ENCODING_AAC_HE_V1"
        AudioFormat.ENCODING_AAC_HE_V2 -> "ENCODING_AAC_HE_V2"
        AudioFormat.ENCODING_IEC61937 -> "ENCODING_IEC61937"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "ENCODING_DOLBY_TRUEHD"
        AudioFormat.ENCODING_AAC_ELD -> "ENCODING_AAC_ELD"
        AudioFormat.ENCODING_AAC_XHE -> "ENCODING_AAC_XHE"
        AudioFormat.ENCODING_AC4 -> "ENCODING_AC4"
        AudioFormat.ENCODING_E_AC3_JOC -> "ENCODING_E_AC3_JOC"
        AudioFormat.ENCODING_DOLBY_MAT -> "ENCODING_DOLBY_MAT"
        AudioFormat.ENCODING_OPUS -> "ENCODING_OPUS"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "ENCODING_PCM_24BIT_PACKED"
        AudioFormat.ENCODING_PCM_32BIT -> "ENCODING_PCM_32BIT"
        AudioFormat.ENCODING_MPEGH_BL_L3 -> "ENCODING_MPEGH_BL_L3"
        AudioFormat.ENCODING_MPEGH_BL_L4 -> "ENCODING_MPEGH_BL_L4"
        AudioFormat.ENCODING_MPEGH_LC_L3 -> "ENCODING_MPEGH_LC_L3"
        AudioFormat.ENCODING_MPEGH_LC_L4 -> "ENCODING_MPEGH_LC_L4"
        AudioFormat.ENCODING_DTS_UHD_P1 -> "ENCODING_DTS_UHD_P1"
        AudioFormat.ENCODING_DRA -> "ENCODING_DRA"
        AudioFormat.ENCODING_DTS_HD_MA -> "ENCODING_DTS_HD_MA"
        AudioFormat.ENCODING_DTS_UHD_P2 -> "ENCODING_DTS_UHD_P2"
        AudioFormat.ENCODING_DSD -> "ENCODING_DSD"
        else -> "invalid encoding $this"
    }
}

fun String.fileExtension(): String {
    return this.substringAfterLast('.', "").lowercase()
}

fun String.filename(): String {
    return this.substringAfterLast('\\').substringAfterLast('/')
}