package com.qytech.audioplayer.transition

import com.qytech.audioplayer.player.AudioPlayer

/**
 * 音量过渡接口 (极简版)
 * 只定义音量如何进入(In)和退出(Out)
 */
interface AudioTransition {
    /**
     * 淡入逻辑 (用于：切歌时新歌入场、暂停后恢复)
     * 目标：将音量从 0 变为 1
     */
    suspend fun fadeIn()

    /**
     * 淡出逻辑 (用于：切歌时旧歌退场、点击暂停、停止)
     * 目标：将音量从 1 变为 0
     */
    suspend fun fadeOut()
}

