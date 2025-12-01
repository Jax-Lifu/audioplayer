package com.qytech.audioplayer.parser

import com.qytech.audioplayer.model.AudioInfo

interface AudioFileParserStrategy {

    suspend fun parse(): List<AudioInfo>?
}