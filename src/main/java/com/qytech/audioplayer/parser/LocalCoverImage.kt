package com.qytech.audioplayer.parser

import java.io.File
import java.util.Locale

/**
 * @author Administrator
 * @date 2025/12/23 15:02
 */
object LocalCoverImage {
    private val SUPPORTED_EXTENSIONS = setOf(
        // 标准格式
        "jpg", "jpeg", "png", "bmp",

        // 现代格式
        "webp", // Android 原生支持，且现在网页下载的封面越来越多是 WebP
        "heif", "heic", // 高效率图像格式，现代手机拍照或导出的常见格式 (Android 9+)

        // 动图格式
        "gif",

        // 较少见但存在的专业格式
        "tiff", "tif"
    )
    private val PRIORITY_KEYWORDS = listOf("cover", "folder", "front", "album")

    fun findLocalCoverImage(
        directory: File?,
        audioFileNameWithoutExt: String? = null,
    ): File? {
        if (directory == null ||
            !directory.exists() ||
            !directory.isDirectory
        ) {
            return null
        }

        val images = directory.listFiles { file ->
            file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
        } ?: return null

        if (images.isEmpty()) return null

        // 1. 优先级最高：与音频文件同名的图片 (例如: Song.mp3 -> Song.jpg)
        if (audioFileNameWithoutExt != null) {
            val sameName = images.find {
                it.nameWithoutExtension.equals(audioFileNameWithoutExt, true)
            }
            if (sameName != null) {
                return sameName
            }
        }

        // 2. 优先级次之：常见的封面关键字
        for (keyword in PRIORITY_KEYWORDS) {
            val match = images.find {
                it.nameWithoutExtension.contains(keyword, ignoreCase = true)
            }
            if (match != null) {
                return match
            }
        }

        return images.maxByOrNull { it.length() }
    }
}