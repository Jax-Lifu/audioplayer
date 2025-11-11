package com.qytech.audioplayer.parser

import java.io.File

/**
 * @author Administrator
 * @date 2025/11/11 11:51
 */
object LocalCoverImage {

    fun findLocalCoverImage(directory: File?): File? {
        if (directory?.exists() != true) {
            return null
        }
        val images = directory.listFiles { file ->
            // 常见的封面图片扩展名列表
            file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "bmp")
        } ?: return null
        val priorityKeywords = listOf("cover", "folder", "front", "album")
        for (keyword in priorityKeywords) {
            val match =
                images.firstOrNull { it.nameWithoutExtension.contains(keyword, ignoreCase = true) }
            if (match != null) return match
        }
        return images.firstOrNull()
    }
}