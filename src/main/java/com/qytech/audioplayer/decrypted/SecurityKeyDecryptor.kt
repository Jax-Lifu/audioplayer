package com.qytech.audioplayer.decrypted

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.qytech.audioplayer.extension.hexToByteArray
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@OptIn(UnstableApi::class)
object SecurityKeyDecryptor {
    private const val KEY = "DCBBEC53951E2E72D6BC708604EA705200CD829B6CB18F48A29A5A0E67E44D87"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

    /**
     * 解密安全密钥
     * @param encryptedKey 加密的密钥字符串，格式为"security:IV部分:加密数据部分"
     * @return 解密后的原始字符串，如果解密失败则返回null
     */
    @JvmStatic
    fun decryptSecurityKey(encryptedKey: String): String? {
        // 定义加密字符串的前缀
        val prefix = "security:"
        // 获取前缀后的内容
        val content = encryptedKey.substringAfter(prefix)
        // 分割IV和加密数据部分
        val (ivPart, encryptedPart) = content.split(":")

        return try {
            // 将十六进制字符串转换为字节数组
            val ivBytes = ivPart.hexToByteArray()
            val encryptedBytes = encryptedPart.hexToByteArray()
            // 从固定KEY生成16字节的密钥
            val keyBytes = KEY.hexToByteArray().copyOf(16)

            // 初始化AES/GCM解密器
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(128, ivBytes.copyOf(12)) // GCM需要12字节的IV
                )
            }

            // 执行解密并转换为UTF-8字符串
            String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            // 捕获所有异常并返回null
            null
        }
    }
}