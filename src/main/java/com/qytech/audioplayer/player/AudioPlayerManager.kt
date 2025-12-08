package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.transition.AudioTransition
import com.qytech.audioplayer.utils.QYLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class AudioPlayerManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: AudioPlayerManager? = null

        fun getInstance(context: Context): AudioPlayerManager {
            return instance ?: synchronized(this) {
                instance ?: AudioPlayerManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==========================================
    // 数据结构：播放请求
    // ==========================================
    private data class PlayRequest(
        val sourcePath: String,
        val trackIndex: Int,
        val dsdMode: DSDMode,
        val transition: AudioTransition?,
        val securityKey: String?,
        val initVector: String?,
        val headers: Map<String, String>?,
        val forceStreamPlayer: Boolean = false,
    )

    // ==========================================
    // 核心成员
    // ==========================================
    private val actionChannel = Channel<PlayRequest>(Channel.CONFLATED)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentTransitionJob: Job? = null
    private var currentPlayer: AudioPlayer? = null
    private var activeTransition: AudioTransition? = null
    private var lastPlayRequest: PlayRequest? = null

    @Volatile
    private var hasRetriedWithStreamPlayer = false

    private var uiListener: PlayerListener? = null

    @Volatile
    private var internalState: PlaybackState = PlaybackState.IDLE

    init {
        scope.launch {
            for (request in actionChannel) {
                try {
                    processPlayRequest(request)
                } catch (e: Exception) {
                    QYLogger.e(e, "Fatal error in play loop")
                }
            }
        }
    }

    private suspend fun processPlayRequest(request: PlayRequest) {
        lastPlayRequest = request // 记录请求以便重试
        Timber.d("processPlayRequest $request")
        currentTransitionJob?.cancelAndJoin()
        currentTransitionJob = scope.launch {
            // 切歌开始，先处理淡出
            request.transition?.fadeOut()
            Timber.d("processPlayRequest fadeOut ----")

            currentPlayer?.stop()
            currentPlayer?.release()

            // 状态重置
            internalState = PlaybackState.IDLE

            delay(100)

            currentPlayer = createPlayerInternal(request)
            currentPlayer?.addListener(proxyListener)

            activeTransition = request.transition

            // 准备和播放
            currentPlayer?.prepare()
            currentPlayer?.play()

            // 注意：play() 调用后，State通常会很快变为 PLAYING，由 proxyListener 更新 internalState

            request.transition?.fadeIn()
            Timber.d("processPlayRequest fadeIn ++++")
        }
    }

    // ==========================================
    // 2. 暂停流程 
    // ==========================================
    fun pause() {
        val player = currentPlayer ?: return

        // 防止重复调用
        // 如果当前不是 播放中 或 缓冲中，说明已经暂停或停止了，无需再次操作（避免重复 FadeOut）
        if (internalState != PlaybackState.PLAYING && internalState != PlaybackState.BUFFERING) {
            Timber.w("忽略重复/无效的 Pause 请求。当前状态: $internalState")
            return
        }

        // 抢占：取消正在进行的动画（比如正在 FadeIn 还没结束，立刻打断）
        currentTransitionJob?.cancel()
        currentTransitionJob = scope.launch {
            try {
                // 1. 接口淡出
                activeTransition?.fadeOut()

                // 2. 系统暂停
                player.pause()
            } catch (_: Exception) {
            }
        }
    }

    // ==========================================
    // 3. 恢复流程 
    // ==========================================
    fun resume() {
        val player = currentPlayer ?: return

        if (internalState != PlaybackState.PAUSED) {
            Timber.w("忽略无效的 Resume 请求。当前状态: $internalState")
            return
        }

        currentTransitionJob?.cancel()
        currentTransitionJob = scope.launch {
            // 1. 静音起播
            player.play()
            // 2. 接口淡入
            activeTransition?.fadeIn()
        }
    }

    // ==========================================
    // 4. 停止流程 
    // ==========================================
    fun stop() {
        val player = currentPlayer ?: return

        if (internalState == PlaybackState.IDLE || internalState == PlaybackState.STOPPED) {
            return
        }

        currentTransitionJob?.cancel()
        currentTransitionJob = scope.launch {
            try {
                activeTransition?.fadeOut()
                player.stop()
            } catch (_: Exception) {
                player.stop()
            }
        }
    }

    // ==========================================
    // 公开 API
    // ==========================================
    fun play(
        sourcePath: String,
        trackIndex: Int = 0,
        dsdMode: DSDMode = DSDMode.NATIVE,
        transition: AudioTransition? = null,
        listener: PlayerListener? = null,
        securityKey: String? = null,
        initVector: String? = null,
        headers: Map<String, String>? = null,
    ) {
        this.uiListener = listener
        Timber.d("play sourcePath: $sourcePath, trackIndex: $trackIndex, dsdMode: $dsdMode, transition: $transition, securityKey: $securityKey, initVector: $initVector, headers: $headers")
        actionChannel.trySend(
            PlayRequest(
                sourcePath, trackIndex, dsdMode, transition,
                securityKey, initVector, headers, forceStreamPlayer = false
            )
        )
    }

    fun destroy() {
        scope.cancel()
        currentPlayer?.release()
        instance = null
        uiListener = null
    }

    fun seekTo(pos: Long) = currentPlayer?.seekTo(pos)
    fun getDuration(): Long = currentPlayer?.getDuration() ?: 0L

    // ==========================================
    // 内部创建与重试逻辑
    // ==========================================
    private fun createPlayerInternal(request: PlayRequest): AudioPlayer? {
        return try {
            if (request.forceStreamPlayer) {
                QYLogger.w("Creating StreamPlayer explicitly for fallback...")
                val player = StreamPlayer(context)
                player.setDsdMode(request.dsdMode)
                player
            } else {
                Timber.d("createPlayerInternal sourcePath: ${request.sourcePath}, trackId: ${request.trackIndex}, dsdMode: ${request.dsdMode}, securityKey: ${request.securityKey}, initVector: ${request.initVector}, headers: ${request.headers}")
                AudioPlayerFactory.createAudioPlayer(
                    context = context,
                    source = request.sourcePath,
                    trackId = request.trackIndex,
                    dsdMode = request.dsdMode,
                    securityKey = request.securityKey,
                    initVector = request.initVector,
                    headers = request.headers
                )
            }
        } catch (e: Exception) {
            QYLogger.e(e, "Factory create error")
            null
        }
    }

    private val proxyListener = object : PlayerListener {
        override fun onPrepared() {
            // 状态更新逻辑在 onStateChanged 中处理，这里只做透传
            uiListener?.onPrepared()
        }

        override fun onProgress(track: Int, current: Long, total: Long, progress: Float) {
            uiListener?.onProgress(track, current, total, progress)
        }

        override fun onComplete() {
            uiListener?.onComplete()
        }

        override fun onStateChanged(state: PlaybackState) {
            // 实时同步内部状态
            internalState = state
            uiListener?.onStateChanged(state)
        }

        override fun onError(code: Int, msg: String) {
            QYLogger.e("Player Error: $code, $msg")
            internalState = PlaybackState.ERROR // 标记错误状态

            if (shouldRetry()) {
                triggerRetry()
            } else {
                uiListener?.onError(code, msg)
            }
        }
    }

    private fun shouldRetry(): Boolean {
        if (hasRetriedWithStreamPlayer) return false
        if (currentPlayer is StreamPlayer) return false
        return true
    }

    private fun triggerRetry() {
        val lastReq = lastPlayRequest ?: return
        QYLogger.w("Triggering Retry with StreamPlayer...")
        hasRetriedWithStreamPlayer = true

        val retryReq = lastReq.copy(
            forceStreamPlayer = true,
        )
        actionChannel.trySend(retryReq)
    }
}