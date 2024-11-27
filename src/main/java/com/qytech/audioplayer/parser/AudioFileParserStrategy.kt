package com.qytech.audioplayer.parser

import com.qytech.audioplayer.model.AudioFileInfo

interface AudioFileParserStrategy {

    fun parse(): AudioFileInfo?
}