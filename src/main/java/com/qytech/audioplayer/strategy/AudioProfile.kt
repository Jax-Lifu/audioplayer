package com.qytech.audioplayer.strategy

/**
 * @author Administrator
 * @date 2025/12/11 14:39
 */
sealed interface AudioProfile {
    data object Standard : AudioProfile

    data class SacdIso(
        val trackId: Int,
        val filename: String? = null,
    ) : AudioProfile

    data class CueByTime(
        val startPosition: Long,
        val endPosition: Long,
    ) : AudioProfile

    data class CueByIndex(
        val trackIndex: Int,
    ) : AudioProfile

    data class SonySelect(
        val securityKey: String,
        val initVector: String,
    ) : AudioProfile

    data class WebDav(
        val username: String,
        val password: String,
        val targetProfile: AudioProfile = Standard,
    ) : AudioProfile
}