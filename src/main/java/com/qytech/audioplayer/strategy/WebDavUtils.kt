package com.qytech.audioplayer.strategy

import android.util.Base64
import com.qytech.audioplayer.utils.QYPlayerLogger
import java.net.URI
import java.net.URLEncoder

object WebDavUtils {

    fun process(
        username: String,
        password: String,
        rawUrl: String,
        existingHeaders: Map<String, String>?,
    ): Pair<String, Map<String, String>> {
        return process(
            WebDavMediaSource(
                uri = rawUrl,
                username = username,
                password = password,
                headers = existingHeaders ?: emptyMap()
            )
        )
    }

    /**
     * 处理 WebDAV：
     * 1. 生成 Basic Auth Header
     * 2. 编码 URL 中的中文路径
     */
    fun process(source: WebDavMediaSource): Pair<String, Map<String, String>> {
        // --- 1. 处理 Headers (生成 Authorization) ---
        val credentials = "${source.username}:${source.password}"
        // NO_WRAP 防止生成换行符
        val base64Params = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        val authHeader = "Basic $base64Params"

        val newHeaders = source.headers.toMutableMap()
        newHeaders["Authorization"] = authHeader
        // 建议添加 User-Agent 防止被某些网盘防火墙拦截
        if (!newHeaders.containsKey("User-Agent")) {
            newHeaders["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        }

        // --- 2. 处理 URL 编码 (解决中文 404) ---
        // 这里的逻辑参考了你之前提供的 Kotlin 代码
        val encodedUrl = try {
            val uriObj = URI.create(source.uri)
            val scheme = uriObj.scheme ?: "https"
            val host = uriObj.host ?: ""
            val port = if (uriObj.port != -1) ":${uriObj.port}" else ""
            val rawPath = uriObj.path ?: ""

            val encodedPath = rawPath.split("/").joinToString("/") { segment ->
                if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8")
            }

            // 重新拼接: http://192.168.1.1:5005/music/%E6%88%91.mp3
            "$scheme://$host$port$encodedPath"
        } catch (e: Exception) {
            // 如果解析失败，回退到原始 URL (或者打印日志)
            QYPlayerLogger.e("WebDAV URL encoding failed: ${e.message}")
            source.uri
        }

        return Pair(encodedUrl, newHeaders)
    }
}