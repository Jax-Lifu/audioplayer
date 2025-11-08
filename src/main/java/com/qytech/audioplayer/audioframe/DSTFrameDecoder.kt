package com.qytech.audioplayer.audioframe


import com.qytech.audioplayer.player.FFmpegDstDecoder
import com.qytech.audioplayer.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 协程版 DST 异步解码器
 * ------------------------
 * - 保证解码顺序一致
 * - 防止主线程阻塞
 * - 自动管理缓冲区和取消状态
 *
 * 用法：
 * val decoder = DSTFrameDecoder(scope, onDecoded = { data, size -> ... })
 * decoder.submitFrame(dstFrame)
 * decoder.cancel()
 */
class DSTFrameDecoder(
    private val scope: CoroutineScope,
    private val onDecoded: (ByteArray, Int) -> Unit,
) {
    private val frameChannel = Channel<ByteArray>(capacity = 8) // 预缓冲 8 帧
    private val isCancelled = AtomicBoolean(false)
    private var decodeJob: Job? = null

    init {
        startDecoder()
    }

    /**
     * 启动后台解码协程
     */
    private fun startDecoder() {
        decodeJob = scope.launch(Dispatchers.IO) {
            Logger.i("DSTFrameDecoder started.")
            try {
                for (frameData in frameChannel) {
                    if (isCancelled.get()) break
                    // 逐帧解码
                    runCatching {
                        val dsdData = FFmpegDstDecoder.decodeDstFrame(frameData)
                        if (dsdData != null) {
                            onDecoded(dsdData, dsdData.size)
                        } else {
                            Logger.w("DST decode returned null.")
                        }
                    }.onFailure {
                        Logger.e(it, "DST frame decode failed.")
                    }
                }
            } catch (e: CancellationException) {
                Logger.w("DSTFrameDecoder cancelled.")
            } finally {
                frameChannel.close()
                Logger.i("DSTFrameDecoder stopped.")
            }
        }
    }

    /**
     * 投递一帧 DST 数据（顺序安全）
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun submitFrame(frame: ByteArray) {
        if (isCancelled.get() || frameChannel.isClosedForSend) return
        frameChannel.send(frame)
    }

    /**
     * 取消解码并清理资源
     */
    fun cancel() {
        if (isCancelled.compareAndSet(false, true)) {
            decodeJob?.cancel()
            Logger.i("DSTFrameDecoder cancel requested.")
        }
    }
}
