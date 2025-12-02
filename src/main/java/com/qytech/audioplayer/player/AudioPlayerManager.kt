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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

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
    // 1. CONFLATED 通道：自动丢弃积压的请求，只保留最新的，实现“防抖”
    private val actionChannel = Channel<PlayRequest>(Channel.CONFLATED)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 2. 任务控制：控制所有过渡动画（切歌、暂停、恢复共用一个 Job，实现互斥）
    private var currentTransitionJob: Job? = null

    // 3. 播放器引用
    private var currentPlayer: AudioPlayer? = null

    // 4. 当前生效的策略 (用于 Pause/Resume)
    private var activeTransition: AudioTransition? = null

    // 5. 重试相关
    private var lastPlayRequest: PlayRequest? = null

    @Volatile
    private var hasRetriedWithStreamPlayer = false

    private var uiListener: PlayerListener? = null

    // ==========================================
    // 初始化：启动消费者循环
    // ==========================================
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

    // ==========================================
    // 1. 切歌核心流程
    // ==========================================
    private suspend fun processPlayRequest(request: PlayRequest) {
        currentTransitionJob?.cancelAndJoin()


        val oldPlayer = currentPlayer

        val newPlayer = withContext(Dispatchers.IO) {
            createPlayerInternal(request)
        }

        if (newPlayer == null) {
            return
        }
        newPlayer.addListener(proxyListener)
        activeTransition = request.transition
        currentPlayer = newPlayer

        try {
            newPlayer.prepare()
        } catch (e: Exception) {
            newPlayer.release()
            return
        }

        // 4. 启动过渡任务
        currentTransitionJob = scope.launch {
            val time = measureTimeMillis {
                activeTransition?.fadeOut()
                oldPlayer?.stop()
                oldPlayer?.release()

                newPlayer.play()
                activeTransition?.fadeIn()
            }
            QYLogger.d("Transition time: $time ms")
        }

    }

    // ==========================================
    // 2. 暂停流程
    // ==========================================
    fun pause() {
        val player = currentPlayer ?: return

        // 抢占：取消正在进行的动画
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
        transition: AudioTransition? = null, // 用户可以在这里传入 Crossfade
        listener: PlayerListener? = null,
        securityKey: String? = null,
        initVector: String? = null,
        headers: Map<String, String>? = null,
    ) {
        this.uiListener = listener

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
            uiListener?.onPrepared()
        }

        override fun onProgress(track: Int, current: Long, total: Long, progress: Float) {
            uiListener?.onProgress(track, current, total, progress)
        }

        override fun onComplete() {
            uiListener?.onComplete()
        }

        override fun onStateChanged(state: PlaybackState) {
            uiListener?.onStateChanged(state)
        }

        override fun onError(code: Int, msg: String) {
            QYLogger.e("Player Error: $code, $msg")
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

        // 重试时，通常强制使用 Gapless 策略以确保快速响应，避免复杂的淡入淡出干扰排查
        val retryReq = lastReq.copy(
            forceStreamPlayer = true,
        )
        actionChannel.trySend(retryReq)
    }
}