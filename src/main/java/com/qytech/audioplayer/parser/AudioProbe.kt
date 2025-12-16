package com.qytech.audioplayer.parser

import com.qytech.audioplayer.parser.model.AudioMetadata
import com.qytech.audioplayer.strategy.ScanProfile
import com.qytech.audioplayer.strategy.WebDavUtils
import com.qytech.audioplayer.utils.QYLogger

object AudioProbe {
    init {
        System.loadLibrary("audioplayer")
    }

    /**
     * 1. 核心方法：基于 Profile 的探测
     * 负责解析 Profile，处理 WebDAV 鉴权、URL编码以及 Header 合并
     */
    fun probe(
        source: String,
        profile: ScanProfile = ScanProfile.Standard,
    ): AudioMetadata? {
        // 最终传给 native 的参数
        var finalUrl = source
        val finalHeaders: MutableMap<String, String> = mutableMapOf()
        var filename: String? = null
        var audioSourceUrl: String? = null

        // --- A. WebDAV 层处理 (解包) ---
        val actualProfile = if (profile is ScanProfile.WebDav) {
            // 1. 获取内部 Profile 的 headers (如果有)
            val innerHeaders = (profile.targetProfile as? ScanProfile.RemoteProfile)?.headers

            // 2. WebDAV 处理：生成 Auth Header，并对 URL 进行 UTF-8 编码
            val (encodedUrl, authHeaders) = WebDavUtils.process(
                username = profile.username,
                password = profile.password,
                rawUrl = source,
                existingHeaders = innerHeaders
            )

            // 3. 应用处理结果
            finalUrl = encodedUrl
            finalHeaders.putAll(authHeaders)

            // 4. 返回内部被包裹的 Profile 用于后续处理
            profile.targetProfile
        } else {
            // 非 WebDAV，直接透传
            profile
        }

        // --- B. 具体文件类型层处理 ---
        if (actualProfile is ScanProfile.RemoteProfile) {
            // 合并 Headers (无论是 WebDAV 自动生成的，还是外部传入的)
            actualProfile.headers?.let { finalHeaders.putAll(it) }

            filename = actualProfile.filename
            if (actualProfile is ScanProfile.RemoteCue) {
                audioSourceUrl = actualProfile.audioSourceUrl
            }
        }

        // 转换 headers 参数
        val headersParam = finalHeaders.ifEmpty { null }

        QYLogger.d("probe: finalUrl = $finalUrl, headers = $headersParam, filename = $filename, audioSourceUrl = $audioSourceUrl")

        return nativeProbe(finalUrl, headersParam, filename, audioSourceUrl)
    }

    /**
     * 2. 统一的 probeFile 入口
     * 包含 WebDAV 参数，默认为 null
     */
    fun probeFile(
        path: String,
        headers: Map<String, String>? = null,
        filename: String? = null,
        audioSourceUrl: String? = null,
        webDavUser: String? = null,
        webDavPwd: String? = null,
    ): AudioMetadata? {
        // 1. 构建基础 Profile (不含 WebDAV)
        val baseProfile = when {
            // CUE 模式：必须有 audioSourceUrl 和 filename
            !audioSourceUrl.isNullOrEmpty() && !filename.isNullOrEmpty() -> {
                ScanProfile.RemoteCue(filename, audioSourceUrl, headers)
            }
            // 网盘/流媒体文件模式：必须有 filename 才能携带 headers
            !filename.isNullOrEmpty() -> {
                ScanProfile.RemoteFile(filename, headers)
            }
            // 标准模式：本地文件 或 不需要特殊处理的 URL (headers 参数会被忽略)
            else -> {
                ScanProfile.Standard
            }
        }

        // 2. 根据是否传入账号密码，决定是否包裹 WebDav Profile
        val finalProfile = if (!webDavUser.isNullOrEmpty() && !webDavPwd.isNullOrEmpty()) {
            ScanProfile.WebDav(
                username = webDavUser,
                password = webDavPwd,
                targetProfile = baseProfile
            )
        } else {
            baseProfile
        }

        // 3. 调用核心接口
        return probe(path, finalProfile)
    }

    private external fun nativeProbe(
        source: String,
        headers: Map<String, String>?,
        filename: String?,
        audioSourceUrl: String?,
    ): AudioMetadata?
}