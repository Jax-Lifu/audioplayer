package com.qytech.audioplayer.parser.netstream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedInputStream

/**
 * SACD 文件检测器：判断远程资源是否为 SACD ISO 文件
 */
@OptIn(ExperimentalStdlibApi::class)
object SacdDetector {

    private val okHttpClient by lazy { OkHttpClient() }


    private const val SACD_MAGIC = "CD001"
    private const val SACD_OFFSET = 0x8001
    private const val SACD_MAGIC_LENGTH = SACD_MAGIC.length

    /**
     * 检查指定 URL 是否为 SACD ISO 文件
     * @param url 远程资源 URL
     * @param headers 可选请求头
     */
    suspend fun isSacdFile(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean = runCatching {
        withContext(Dispatchers.IO) {
            val rangeHeader = "bytes=$SACD_OFFSET-${SACD_OFFSET + SACD_MAGIC_LENGTH - 1}"

            // 构造带 Range 请求
            val request = Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .addHeader("Range", rangeHeader)
                .build()

            //Timber.d("isSacdFile: Requesting $rangeHeader for $url")

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("isSacdFile: Request failed with code ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                //Timber.d("isSacdFile: Response code: ${response.code} headers: ${response.headers}")
                // 支持 Range 返回 Partial Content (206)
                if (response.code == 206) {
                    val buffer = ByteArray(SACD_MAGIC_LENGTH)
                    val bytesRead = body.byteStream().read(buffer)

                    val magic = String(buffer, 0, bytesRead)
                    //Timber.d("isSacdFile (range): Read magic '$magic'")
                    return@withContext magic == SACD_MAGIC
                }

                // 不支持 Range，回退完整读 + seek 模式
                if (response.code == 200) {
                    //Timber.w("isSacdFile: Server does NOT support Range, fallback to seek-read")

                    val input = BufferedInputStream(body.byteStream())
                    val skipResult = input.skip(SACD_OFFSET.toLong())
                    if (skipResult < SACD_OFFSET) {
                        Timber.w("isSacdFile: Failed to skip to $SACD_OFFSET, only skipped $skipResult")
                        return@withContext false
                    }

                    val buffer = ByteArray(SACD_MAGIC_LENGTH)
                    val bytesRead = input.read(buffer)
                    if (bytesRead < SACD_MAGIC_LENGTH) {
                        //Timber.w("isSacdFile: Incomplete read at fallback path")
                        return@withContext false
                    }

                    val magic = String(buffer)
                    //Timber.d("isSacdFile (fallback): Read magic '$magic'")
                    return@withContext magic == SACD_MAGIC
                }

                Timber.w("isSacdFile: Unsupported response code ${response.code}")
                return@withContext false
            }
        }
    }.onFailure {
        Timber.e(it, "isSacdFile: Exception occurred")
    }.getOrDefault(false)
}
