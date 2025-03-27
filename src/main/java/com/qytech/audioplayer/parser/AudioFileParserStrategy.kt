package com.qytech.audioplayer.parser

import com.qytech.audioplayer.model.AudioInfo

interface AudioFileParserStrategy {

    fun parse(): List<AudioInfo.Local>?
}