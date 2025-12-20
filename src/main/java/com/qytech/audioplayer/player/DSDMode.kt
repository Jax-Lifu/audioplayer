package com.qytech.audioplayer.player

import com.qytech.audioplayer.utils.SystemPropUtil

/**
 * D2P 输出采样率。AUTO 表示根据输入 DSD 自动匹配适配 PCM 输出。
 */
enum class D2pSampleRate(val hz: Int) {
    AUTO(-1),
    PCM_44100(44100),
    PCM_88200(88200),
    PCM_176400(176400),
    PCM_352800(352800),
    PCM_705600(705600),
    PCM_48000(48000),
    PCM_96000(96000),
    PCM_192000(192000),
    PCM_384000(384000),
    PCM_768000(768000);

    companion object {
        fun fromValue(value: Int): D2pSampleRate {
            return D2pSampleRate.entries.firstOrNull { it.hz == value } ?: PCM_44100
        }
    }
}

/**
 * 播放模式：SACD/DSD 可选择 Native, DoP, D2P
 */

enum class DSDMode(
    val value: Int,
) {
    NATIVE(0),
    D2P(1),
    DOP(2);

    companion object {
        fun fromValue(value: Int): DSDMode {
            return DSDMode.entries.firstOrNull { it.value == value } ?: NATIVE
        }
    }
}

object DsdPlaybackProperty {
    private const val KEY = "persist.vendor.dsd_mode"

    fun setDsdPlaybackMode(mode: DSDMode?) {
        SystemPropUtil.set(KEY, mode?.name ?: "NULL")
    }
}
