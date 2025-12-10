package com.qytech.audioplayer.player

import android.content.Context
import com.qytech.audioplayer.strategy.MediaSource
import com.qytech.audioplayer.transition.AudioTransition
import com.qytech.audioplayer.utils.QYLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

class AudioPlayerManager private constructor(private val context: Context) : AudioPlayer {

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
    // 数据结构
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
    // 使用 Channel.CONFLATED 保证只处理最新的播放请求，快速切歌时丢弃中间的
    private val actionChannel = Channel<PlayRequest>(Channel.CONFLATED)

    // 独立的 Scope，建议绑定到 Service 的生命周期，这里假设是全局单例
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("AudioPlayerManager"))

    // 互斥锁，保护 currentPlayer 和 critical state 的原子性操作
    private val playerMutex = Mutex()

    @Volatile
    private var currentPlayer: AudioPlayer? = null

    private var activeTransition: AudioTransition? = null
    private var currentTransitionJob: Job? = null

    // 记录上一次请求，用于重试
    private var lastPlayRequest: PlayRequest? = null

    // 标记当前 URL 是否已经触发过降级重试
    @Volatile
    private var hasRetriedWithStreamPlayer = false

    // 使用并发安全的 List，支持多个监听者 (UI, Service, Notification)
    private val listeners = CopyOnWriteArrayList<PlayerListener>()

    // 使用 StateFlow 管理状态，更适合响应式编程 (可选，内部保持 internalState 也可以)
    private var _internalState: PlaybackState = PlaybackState.IDLE

    init {
        startPlayLoop()
    }

    private fun startPlayLoop() {
        scope.launch {
            for (request in actionChannel) {
                try {
                    processPlayRequest(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    QYLogger.e(e, "Fatal error in play loop")
                    notifyError(-1, "Fatal error: ${e.message}")
                }
            }
        }
    }

    private suspend fun processPlayRequest(request: PlayRequest) {
        Timber.d("Processing PlayRequest: $request")

        // 1. 如果是切歌（新的 sourcePath），重置重试标记
        if (lastPlayRequest?.sourcePath != request.sourcePath) {
            hasRetriedWithStreamPlayer = false
        }

        // 2. 更新最后一次请求（如果是重试请求，forceStreamPlayer 会不同，也需要更新）
        lastPlayRequest = request

        // 3. 取消上一次正在进行的过渡动画/初始化任务
        currentTransitionJob?.cancelAndJoin()

        currentTransitionJob = scope.launch {
            // A. 淡出旧播放器
            activeTransition?.fadeOut()

            playerMutex.withLock {
                // B. 停止并释放旧播放器
                currentPlayer?.stop()
                currentPlayer?.release()
                currentPlayer = null

                updateState(PlaybackState.IDLE)
            }

            // C. 硬件/底层缓冲延时 (Magic Number，视硬件情况保留)
            delay(100)

            // D. 创建新播放器
            val newPlayer = createPlayerInternal(request)

            playerMutex.withLock {
                currentPlayer = newPlayer
                // 绑定监听器 (使用内部代理监听器)
                newPlayer?.addListener(proxyListener)
                activeTransition = request.transition
            }

            if (newPlayer != null) {
                try {
                    newPlayer.prepare()
                    newPlayer.play()
                    // 只有 play 成功后才执行淡入
                    request.transition?.fadeIn()
                } catch (e: Exception) {
                    QYLogger.e(e, "Player prepare/play failed")
                    proxyListener.onError(-2, "Prepare failed: ${e.message}")
                }
            } else {
                proxyListener.onError(-3, "Failed to create player")
            }
        }
    }

    // ==========================================
    // 控制逻辑 (Pause/Resume/Stop)
    // ==========================================

    override fun pause() {
        if (_internalState != PlaybackState.PLAYING && _internalState != PlaybackState.BUFFERING) {
            return
        }
        currentTransitionJob?.cancel()
        currentTransitionJob = scope.launch {
            playerMutex.withLock {
                val player = currentPlayer ?: return@withLock
                try {
                    activeTransition?.fadeOut()
                    player.pause()
                } catch (e: Exception) {
                    QYLogger.e(e, "Pause failed")
                }
            }
        }
    }

    fun resume() {
        if (_internalState != PlaybackState.PAUSED) {
            return
        }

        currentTransitionJob?.cancel()
        currentTransitionJob = scope.launch {
            playerMutex.withLock {
                val player = currentPlayer ?: return@withLock
                try {
                    player.play()
                    activeTransition?.fadeIn()
                } catch (e: Exception) {
                    QYLogger.e(e, "Resume failed")
                }
            }
        }
    }

    override fun stop() {
        if (_internalState == PlaybackState.IDLE || _internalState == PlaybackState.STOPPED) {
            return
        }

        currentTransitionJob?.cancel()
        currentTransitionJob = scope.launch {
            // 淡出
            activeTransition?.fadeOut()

            playerMutex.withLock {
                val player = currentPlayer ?: return@withLock
                try {
                    player.stop()
                } catch (e: Exception) {
                    QYLogger.e(e, "Stop failed")
                }
            }
        }
    }

    override fun release() {
        scope.launch {
            currentTransitionJob?.cancel()
            playerMutex.withLock {
                currentPlayer?.release()
                currentPlayer = null
                instance = null
                listeners.clear()
            }
        }
    }

    fun destroy() {
        scope.cancel() // 取消所有协程
        try {
            currentPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentPlayer = null
        instance = null
        listeners.clear()
    }

    // ==========================================
    // 公开 API 实现 (Delegation)
    // ==========================================

    override fun play() = resume() // 接口定义的 play 通常指无参播放/恢复

    fun play(
        sourcePath: String,
        trackIndex: Int = 0,
        dsdMode: DSDMode = DSDMode.NATIVE,
        transition: AudioTransition? = null,
        listener: PlayerListener? = null, // 单次播放的监听器
        securityKey: String? = null,
        initVector: String? = null,
        headers: Map<String, String>? = null,
    ) {
        listener?.let {
            if (!listeners.contains(it)) listeners.add(it)
        }

        actionChannel.trySend(
            PlayRequest(
                sourcePath, trackIndex, dsdMode, transition,
                securityKey, initVector, headers, forceStreamPlayer = false
            )
        )
    }

    override fun seekTo(positionMs: Long) {
        scope.launch {
            playerMutex.withLock { currentPlayer?.seekTo(positionMs) }
        }
    }

    override fun getDuration(): Long = currentPlayer?.getDuration() ?: 0L

    override fun getPosition(): Long = currentPlayer?.getPosition() ?: 0L

    override fun getState(): PlaybackState = _internalState

    override fun setMediaSource(mediaSource: MediaSource) {
        currentPlayer?.setMediaSource(mediaSource)
    }

    override fun setDsdMode(mode: DSDMode) {
        currentPlayer?.setDsdMode(mode)
    }

    override fun setD2pSampleRate(sampleRate: D2pSampleRate) {
        currentPlayer?.setD2pSampleRate(sampleRate)
    }

    override fun prepare() {
        scope.launch { currentPlayer?.prepare() }
    }

    // ==========================================
    // 监听器管理
    // ==========================================
    override fun addListener(listener: PlayerListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }

    @Deprecated("Use PlayerListener instead")
    override fun setOnPlaybackStateChangeListener(listener: OnPlaybackStateChangeListener) {
    }

    @Deprecated("Use PlayerListener instead")
    override fun setOnProgressListener(listener: OnProgressListener) {
    }

    // ==========================================
    // 内部逻辑：工厂与重试
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

    private fun shouldRetry(): Boolean {
        // 如果已经降级重试过了，就不再重试，避免死循环
        if (hasRetriedWithStreamPlayer) return false
        // 如果当前本身就是 StreamPlayer，说明降级也没用，或者一开始就是 StreamPlayer
        if (currentPlayer is StreamPlayer) return false
        return true
    }

    private fun triggerRetry() {
        val lastReq = lastPlayRequest ?: return
        QYLogger.w("Triggering Retry with StreamPlayer...")

        // 标记：针对当前这首歌已经重试过了
        hasRetriedWithStreamPlayer = true

        val retryReq = lastReq.copy(
            forceStreamPlayer = true,
        )
        actionChannel.trySend(retryReq)
    }

    private fun updateState(newState: PlaybackState) {
        _internalState = newState
        notifyStateChanged(newState)
    }

    // ==========================================
    // Proxy Listener (分发事件给所有 listeners)
    // ==========================================

    private val proxyListener = object : PlayerListener {
        override fun onPrepared() {
            listeners.forEach { it.onPrepared() }
        }

        override fun onProgress(track: Int, current: Long, total: Long, progress: Float) {
            listeners.forEach { it.onProgress(track, current, total, progress) }
        }

        override fun onComplete() {
            listeners.forEach { it.onComplete() }
        }

        override fun onStateChanged(state: PlaybackState) {
            // 更新内部状态
            _internalState = state
            // 分发状态
            notifyStateChanged(state)
        }

        override fun onError(code: Int, msg: String) {
            QYLogger.e("Player Error: $code, $msg")
            updateState(PlaybackState.ERROR)

            if (shouldRetry()) {
                triggerRetry()
            } else {
                notifyError(code, msg)
            }
        }
    }

    private fun notifyStateChanged(state: PlaybackState) {
        listeners.forEach { it.onStateChanged(state) }
    }

    private fun notifyError(code: Int, msg: String) {
        listeners.forEach { it.onError(code, msg) }
    }
}