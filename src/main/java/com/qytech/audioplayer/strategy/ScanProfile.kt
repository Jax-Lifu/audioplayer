package com.qytech.audioplayer.strategy

/**
 * 扫描场景配置
 */
sealed interface ScanProfile {

    /**
     * 1. 标准模式
     * 适用：本地文件、标准 HTTP URL (不需要 Header 或特殊文件名处理)
     */
    data object Standard : ScanProfile

    /**
     * 为了方便代码处理，定义一个包含 headers 的接口
     */
    interface RemoteProfile : ScanProfile {
        val headers: Map<String, String>?
        val filename: String
    }

    /**
     * 2. 网盘/流媒体文件模式
     * 适用：网盘单曲、网盘 ISO (SACD)
     *
     * @param headers HTTP 请求头 (关键鉴权信息)
     * @param filename 原始文件名 (辅助格式识别)
     */
    data class RemoteFile(
        override val filename: String,
        override val headers: Map<String, String>? = null,
    ) : RemoteProfile

    /**
     * 3. 网盘 CUE 模式
     * 适用：网盘 CUE 分轨
     *
     * @param headers HTTP 请求头 (用于下载 CUE 和 音频)
     * @param filename CUE 文件名
     * @param audioSourceUrl CUE 对应的原始音频 URL
     */
    data class RemoteCue(
        override val filename: String,
        val audioSourceUrl: String,
        override val headers: Map<String, String>? = null,
    ) : RemoteProfile

    /**
     * 4. WebDAV 模式 (装饰器)
     *
     * @param username 账号
     * @param password 密码
     * @param targetProfile 目标文件类型 (Standard, RemoteFile, RemoteCue)
     *                      例如：如果是 WebDAV 里的 CUE 文件，这里传入 RemoteCue
     */
    data class WebDav(
        val username: String,
        val password: String,
        val targetProfile: ScanProfile = Standard,
    ) : ScanProfile
}