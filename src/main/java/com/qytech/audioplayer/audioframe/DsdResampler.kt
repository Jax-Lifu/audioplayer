package com.qytech.audioplayer.audioframe

import java.nio.ByteBuffer

class DsdResampler {
    companion object {
        init {
            System.loadLibrary("audioplayer")
        }

        // ========================================================================
        // Static Methods - ByteArray
        // ========================================================================
        @JvmStatic
        external fun nativePackDoP(msbf: Boolean, src: ByteArray, size: Int, out: ByteArray): Int

        @JvmStatic
        external fun nativePackNative(msbf: Boolean, src: ByteArray, size: Int, out: ByteArray): Int

        @JvmStatic
        external fun nativePack4ChannelNative(msbf: Boolean, src: ByteArray, size: Int, out: ByteArray): Int

        // ========================================================================
        // Static Methods - DirectBuffer (零拷贝，DSD512 推荐)
        // ========================================================================

        // Native Stereo
        @JvmStatic
        external fun nativePackNativeDirect(
            msbf: Boolean,
            src: ByteBuffer,
            size: Int,
            out: ByteBuffer,
        ): Int

        // DoP Stereo
        @JvmStatic
        external fun nativePackDoPDirect(
            msbf: Boolean,
            src: ByteBuffer,
            size: Int,
            out: ByteBuffer,
        ): Int

        // Native 4-Channel
        @JvmStatic
        external fun nativePack4ChannelNativeDirect(
            msbf: Boolean, // 修正：方法名和参数修正
            src: ByteBuffer,
            size: Int,
            out: ByteBuffer,
        ): Int
    }

    // ========================================================================
    // Instance Members (FFmpeg D2P 转码)
    // ========================================================================

    private var nativeContext: Long = 0

    fun init(dsdRate: Int, targetPcmRate: Int, targetBitDepth: Int) {
        release()
        nativeContext = nativeInit(dsdRate, targetPcmRate, targetBitDepth)
    }

    /**
     * D2P: ByteArray 版本
     */
    fun processD2p(dsdData: ByteArray, size: Int, pcmOut: ByteArray): Int {
        if (nativeContext == 0L) return 0
        return nativePackD2p(nativeContext, dsdData, size, pcmOut)
    }

    /**
     * D2P: DirectBuffer 版本 
     */
    fun processD2pDirect(dsdData: ByteBuffer, size: Int, pcmOut: ByteBuffer): Int {
        if (nativeContext == 0L) return 0
        return nativePackD2pDirect(nativeContext, dsdData, size, pcmOut)
    }

    fun release() {
        if (nativeContext != 0L) {
            nativeRelease(nativeContext)
            nativeContext = 0L
        }
    }

    // --- Instance JNI Methods ---

    private external fun nativeInit(dsdRate: Int, targetPcmRate: Int, targetBitDepth: Int): Long

    // ByteArray D2P
    private external fun nativePackD2p(
        ctx: Long,
        dsdData: ByteArray,
        size: Int,
        pcmOut: ByteArray,
    ): Int

    // DirectBuffer D2P 
    private external fun nativePackD2pDirect(
        ctx: Long,
        dsdData: ByteBuffer,
        size: Int,
        pcmOut: ByteBuffer,
    ): Int

    private external fun nativeRelease(ctx: Long)
}