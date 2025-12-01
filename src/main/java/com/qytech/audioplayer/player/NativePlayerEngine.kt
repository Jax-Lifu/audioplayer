package com.qytech.audioplayer.player

import androidx.annotation.Keep
import com.qytech.audioplayer.utils.QYLogger

@Keep
internal interface EngineCallback {
    fun onPrepared()
    fun onProgress(track: Int, currentMs: Long, totalMs: Long, progress: Float)
    fun onError(code: Int, msg: String)
    fun onComplete()
    fun onAudioData(data: ByteArray, size: Int)
}

internal class NativePlayerEngine {
    companion object {
        init {
            try {
                System.loadLibrary("audioplayer")
            } catch (e: UnsatisfiedLinkError) {
                QYLogger.e("Failed to load library: ${e.message}")
            }
        }
    }

    private var nativeHandle: Long = 0
    private var callback: EngineCallback? = null


    fun setCallback(cb: EngineCallback) {
        this.callback = cb
    }

    fun init(type: PlayerStrategy, callback: EngineCallback) {
        if (nativeHandle != 0L) release()
        nativeHandle = native_init(type.value, callback)
    }

    fun setSource(
        path: String,
        headers: String? = null,
        trackIndex: Int = -1,
        startPos: Long = 0L,
        endPos: Long = -1L
    ) {
        if (nativeHandle != 0L) native_setSource(nativeHandle, path, headers, trackIndex, startPos, endPos)
    }

    fun setDsdConfig(mode: Int, d2pSampleRate: Int) {
        if (nativeHandle != 0L) native_setDsdConfig(nativeHandle, mode, d2pSampleRate)
    }

    fun prepare() {
        if (nativeHandle != 0L) native_prepare(nativeHandle)
    }

    fun play() {
        if (nativeHandle != 0L) native_play(nativeHandle)
    }

    fun pause() {
        if (nativeHandle != 0L) native_pause(nativeHandle)
    }

    fun resume() {
        if (nativeHandle != 0L) native_resume(nativeHandle)
    }

    fun stop() {
        if (nativeHandle != 0L) native_stop(nativeHandle)
    }

    fun seek(ms: Long) {
        if (nativeHandle != 0L) native_seek(nativeHandle, ms)
    }

    fun release() {
        if (nativeHandle != 0L) {
            native_release(nativeHandle)
            nativeHandle = 0
        }
        callback = null
    }


    // Getters
    fun getSampleRate(): Int = if (nativeHandle != 0L) native_getSampleRate(nativeHandle) else 0
    fun getChannelCount(): Int = if (nativeHandle != 0L) native_getChannelCount(nativeHandle) else 0
    fun getBitPerSample(): Int = if (nativeHandle != 0L) native_getBitPerSample(nativeHandle) else 0
    fun getDuration(): Long = if (nativeHandle != 0L) native_getDuration(nativeHandle) else 0
    fun getPosition(): Long = if (nativeHandle != 0L) native_getCurrentPosition(nativeHandle) else 0

    fun getPlayerState(): PlaybackState =
        if (nativeHandle != 0L) PlaybackState.fromValue(native_getPlayerState(nativeHandle)) else PlaybackState.IDLE

    fun isDsd(): Boolean = if (nativeHandle != 0L) native_isDsd(nativeHandle) else false

    // JNI External Methods
    private external fun native_init(type: Int, callback: EngineCallback): Long
    private external fun native_setSource(
        handle: Long,
        path: String,
        headers: String?,
        trackIndex: Int,
        startPos: Long,
        endPos: Long
    )

    private external fun native_prepare(handle: Long)
    private external fun native_play(handle: Long)
    private external fun native_pause(handle: Long)

    private external fun native_resume(handle: Long)
    private external fun native_stop(handle: Long)
    private external fun native_seek(handle: Long, ms: Long)
    private external fun native_release(handle: Long)
    private external fun native_setDsdConfig(handle: Long, mode: Int, sampleRate: Int)

    private external fun native_getSampleRate(handle: Long): Int
    private external fun native_getChannelCount(handle: Long): Int
    private external fun native_getBitPerSample(handle: Long): Int
    private external fun native_getDuration(handle: Long): Long
    private external fun native_getCurrentPosition(handle: Long): Long

    private external fun native_getPlayerState(handle: Long): Int

    private external fun native_isDsd(handle: Long): Boolean
}