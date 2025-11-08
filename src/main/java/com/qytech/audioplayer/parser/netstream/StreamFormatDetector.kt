package com.qytech.audioplayer.parser.netstream

import com.qytech.audioplayer.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 网络流格式类型
 */
enum class NetStreamFormat {
    SACD,
    HLS,
    PCM,
    DSD,
    UNKNOWN
}

/**
 * 网络流格式探测器（仅根据响应判断）
 * 不依赖 URL、query、文件名，只检查响应头和数据内容。
 */
object StreamFormatDetector {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private const val PROBE_SIZE = 8192
    private const val SACD_MAGIC = "CD001"
    private const val SACD_OFFSET = 0x8001

    suspend fun detect(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): NetStreamFormat = withContext(Dispatchers.IO) {
        try {
            // 直接读取文件头数据（支持范围请求）
            val request = Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .addHeader("Range", "bytes=0-${PROBE_SIZE - 1}")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext NetStreamFormat.UNKNOWN

                val contentType =
                    resp.header("Content-Type")?.lowercase(Locale.getDefault()).orEmpty()
                if (contentType.contains("mpegurl") || contentType.contains("m3u8")) {
                    return@withContext NetStreamFormat.HLS
                }

                val bytes = resp.body?.byteStream()?.use { input ->
                    val buf = ByteArray(PROBE_SIZE)
                    val len = input.read(buf)
                    if (len > 0) buf.copyOf(len) else ByteArray(0)
                } ?: return@withContext NetStreamFormat.UNKNOWN

                if (bytes.isEmpty()) return@withContext NetStreamFormat.UNKNOWN

                // ---- Magic 检测 ----
                val headText = try {
                    String(bytes, StandardCharsets.US_ASCII)
                } catch (_: Exception) {
                    ""
                }

                // HLS
                if (headText.contains("#EXTM3U", ignoreCase = true)) {
                    return@withContext NetStreamFormat.HLS
                }

                // DSD
                if (bytes.startsWithMagic("DSD ") || bytes.startsWithMagic("FRM8")) {
                    return@withContext NetStreamFormat.DSD
                }

                // PCM
                if (bytes.startsWithMagic("RIFF") || bytes.startsWithMagic("fLaC") ||
                    bytes.startsWithMagic("ID3") || bytes.startsWithMagic("OggS")
                ) {
                    return@withContext NetStreamFormat.PCM
                }

                // SACD: 再次请求 offset 检查 "CD001"
                try {
                    val sacdReq = Request.Builder()
                        .url(url)
                        .headers(headers.toHeaders())
                        .addHeader(
                            "Range",
                            "bytes=$SACD_OFFSET-${SACD_OFFSET + SACD_MAGIC.length - 1}"
                        )
                        .get()
                        .build()
                    okHttpClient.newCall(sacdReq).execute().use { sacdResp ->
                        if (sacdResp.isSuccessful) {
                            val buf = ByteArray(SACD_MAGIC.length)
                            val r = sacdResp.body?.byteStream()?.read(buf) ?: -1
                            if (r == SACD_MAGIC.length && buf.decodeToAsciiOrNull() == SACD_MAGIC) {
                                return@withContext NetStreamFormat.SACD
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(e, "StreamFormatDetector: SACD probe failed")
                }

                // fallback
                return@withContext if (contentType.startsWith("audio/")) {
                    NetStreamFormat.PCM
                } else {
                    NetStreamFormat.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "StreamFormatDetector detect failed")
            NetStreamFormat.UNKNOWN
        }
    }

    private fun ByteArray.startsWithMagic(magic: String): Boolean {
        if (this.size < magic.length) return false
        for (i in magic.indices) {
            if (this[i].toInt().and(0xFF) != magic[i].code) return false
        }
        return true
    }

    private fun ByteArray.decodeToAsciiOrNull(): String? = try {
        String(this, StandardCharsets.US_ASCII)
    } catch (_: Exception) {
        null
    }
}
