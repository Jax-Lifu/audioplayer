#include "FFPlayer.h"

#define DEFAULT_BUFFER_SIZE 2 * 1024 * 1024

// 辅助函数：当前时间毫秒
static int64_t getNowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

FFPlayer::FFPlayer(IPlayerCallback *callback) : BasePlayer(callback) {
    initFFmpeg();
    outBuffer.reserve(DEFAULT_BUFFER_SIZE);
    outBuffer.resize(DEFAULT_BUFFER_SIZE);
}

FFPlayer::~FFPlayer() {
    releaseInternal();
}

void FFPlayer::setDataSource(const char *path, const std::map<std::string, std::string> &headers,
                             int64_t startPositon, int64_t endPosition) {
    this->mUrl = path;
    this->mHeaders = headers;
    this->mStartTimeMs = startPositon;
    this->mEndTimeMs = endPosition;
}

void FFPlayer::initFFmpeg() {
    avformat_network_init();
}

// ---------------------------------------------------------------------
// 中断回调
// ---------------------------------------------------------------------
int FFPlayer::interrupt_cb(void *ctx) {
    auto *player = (FFPlayer *) ctx;
    if (!player) return 0;
    if (player->mIsExit.load()) return 1;
    // Seek 时打断当前的 av_read_frame，以便 readLoop 快速响应 Seek
    if (player->mIsSeeking.load()) return 1;
    return 0;
}

void FFPlayer::prepare() {
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        if (mState != STATE_IDLE && mState != STATE_STOPPED) return;
        mIsExit.store(false);
        mIsEOF.store(false);
        mIsSeeking.store(false);
        mState = STATE_PREPARING;
        audioQueue.start();
    }

    AVDictionary *options = nullptr;
    bool isNetwork = false;
    const char *url = mUrl.c_str();
    if (strncasecmp(url, "http", 4) == 0 ||
        strncasecmp(url, "rtmp", 4) == 0 ||
        strncasecmp(url, "rtsp", 4) == 0 ||
        strncasecmp(url, "udp", 3) == 0) {
        isNetwork = true;
    }

    if (isNetwork) {
        // 构造 Headers
        std::string customHeaders;
        bool hasUserAgent = false;
        for (const auto &pair: mHeaders) {
            if (strcasecmp(pair.first.c_str(), "User-Agent") == 0) {
                av_dict_set(&options, "user_agent", pair.second.c_str(), 0);
                hasUserAgent = true;
            } else {
                customHeaders += pair.first + ": " + pair.second + "\r\n";
            }
        }
        if (!customHeaders.empty()) {
            av_dict_set(&options, "headers", customHeaders.c_str(), 0);
        }

        // 网络参数优化
        av_dict_set(&options, "timeout", "10000000", 0);    // TCP 连接超时 10s
        av_dict_set(&options, "rw_timeout", "15000000", 0); // 读写超时
        av_dict_set(&options, "reconnect", "1", 0);         // 断线重连

        if (!hasUserAgent) {
            av_dict_set(&options, "user_agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", 0);
        }
        av_dict_set(&options, "buffer_size", "4194304", 0); // 4MB 输入缓冲
        av_dict_set(&options, "seekable", "1", 0);
    }
    fmtCtx = avformat_alloc_context();
    fmtCtx->interrupt_callback.callback = interrupt_cb;
    fmtCtx->interrupt_callback.opaque = this;

    int ret;
    if ((ret = avformat_open_input(&fmtCtx, mUrl.c_str(), nullptr, &options)) != 0) {
        if (mIsExit.load()) {
            releaseFFmpeg();
            return;
        }
        LOGE("Open input failed: %d path %s", ret, mUrl.c_str());
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_ERROR;
        if (mCallback) mCallback->onError(-1, "Open input failed");
        releaseFFmpeg();
        return;
    }

    if ((ret = avformat_find_stream_info(fmtCtx, nullptr)) < 0) {
        if (mIsExit.load()) {
            releaseFFmpeg();
            return;
        }
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_ERROR;
        if (mCallback) mCallback->onError(-2, "Find stream info failed");
        releaseFFmpeg();
        return;
    }

    // 查找音频流
    audioStreamIndex = -1;
    for (int i = 0; i < fmtCtx->nb_streams; i++) {
        if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = i;
            break;
        }
    }

    if (audioStreamIndex == -1) {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_ERROR;
        if (mCallback) mCallback->onError(-3, "No audio stream");
        releaseFFmpeg();
        return;
    }

    // 打开解码器
    {
        AVCodecParameters *codecPar = fmtCtx->streams[audioStreamIndex]->codecpar;
        const AVCodec *codec = avcodec_find_decoder(codecPar->codec_id);
        if (!codec) {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_ERROR;
            releaseFFmpeg();
            return;
        }
        codecCtx = avcodec_alloc_context3(codec);
        avcodec_parameters_to_context(codecCtx, codecPar);

        if ((ret = avcodec_open2(codecCtx, codec, nullptr)) < 0) {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_ERROR;
            releaseFFmpeg();
            return;
        }
    }

    // DSD 配置
    mIsSourceDsd = isDsdCodec(codecCtx->codec_id);
    if (mIsSourceDsd) {
        isMsbf = isMsbfCodec(codecCtx->codec_id);
        is4ChannelSupported = SystemProperties::is4ChannelSupported();
    }
    timeBase = &fmtCtx->streams[audioStreamIndex]->time_base;

    // Resampler
    if ((!mIsSourceDsd) || (mIsSourceDsd && mDsdMode == DSD_MODE_D2P)) {
        if (initSwrContext() < 0) {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_ERROR;
            releaseFFmpeg();
            return;
        }
    }

    extractAudioInfo();
    if (mStartTimeMs > 0) mCurrentPositionMs.store(mStartTimeMs);

    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        if (mIsExit.load()) {
            releaseFFmpeg();
            return;
        }
        mState = STATE_PREPARED;
    }
    if (mCallback) mCallback->onPrepared();
}

int FFPlayer::initSwrContext() {
    if (swrCtx) swr_free(&swrCtx);
    swrCtx = swr_alloc();

    AVChannelLayout inLayout = codecCtx->ch_layout;
    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, CHANNEL_OUT_STEREO);

    int outRate = mIsSourceDsd ? mTargetD2pSampleRate : codecCtx->sample_rate;
    outputSampleFormat = mIsSourceDsd ? AV_SAMPLE_FMT_S16 : getOutputSampleFormat(
            codecCtx->sample_fmt);

    av_opt_set_chlayout(swrCtx, "in_chlayout", &inLayout, 0);
    av_opt_set_int(swrCtx, "in_sample_rate", codecCtx->sample_rate, 0);
    av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", codecCtx->sample_fmt, 0);

    av_opt_set_chlayout(swrCtx, "out_chlayout", &outLayout, 0);
    av_opt_set_int(swrCtx, "out_sample_rate", outRate, 0);
    av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", outputSampleFormat, 0);

    if (swr_init(swrCtx) < 0) {
        LOGE("SwrInit Failed");
        if (mCallback) mCallback->onError(-6, "Resampler init failed");
        return -1;
    }
    return 0;
}

void FFPlayer::play() {
    if (mState == STATE_PREPARED || mState == STATE_PAUSED || mState == STATE_COMPLETED) {
        {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_PLAYING;
        }
        if (!readThread) readThread = new std::thread(&FFPlayer::readLoop, this);
        if (!decodeThread) decodeThread = new std::thread(&FFPlayer::decodingLoop, this);
        stateCond.notify_all();
    }
}

void FFPlayer::pause() {
    if (mState == STATE_PLAYING || mState == STATE_BUFFERING) {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_PAUSED;
    }
}

void FFPlayer::resume() {
    if (mState == STATE_PAUSED) {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_PLAYING;
        stateCond.notify_all();
    }
}

void FFPlayer::stop() {
    mIsExit.store(true);
    audioQueue.abort();
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_STOPPED;
        stateCond.notify_all();
    }
    {
        // 唤醒可能卡在 seek wait 的线程
        std::lock_guard<std::mutex> lock(mSeekMutex);
        stateCond.notify_all();
    }

    if (readThread && readThread->joinable()) {
        readThread->join();
        delete readThread;
        readThread = nullptr;
    }
    if (decodeThread && decodeThread->joinable()) {
        decodeThread->join();
        delete decodeThread;
        decodeThread = nullptr;
    }
}

void FFPlayer::release() {
    releaseInternal();
}

void FFPlayer::releaseInternal() {
    stop();
    releaseFFmpeg();
    mState = STATE_IDLE;
}

void FFPlayer::seek(long ms) {
    long targetRelativeMs = ms;
    auto duration = getDuration(); // 获取的是当前分轨的虚拟时长
    if (duration > 0 && targetRelativeMs > duration) targetRelativeMs = duration;
    if (targetRelativeMs < 0) targetRelativeMs = 0;
    long targetAbsoluteMs = targetRelativeMs + mStartTimeMs;

    if (mState != STATE_IDLE && mState != STATE_ERROR && mState != STATE_STOPPED) {
        std::lock_guard<std::mutex> lock(mSeekMutex);
        mSeekTargetMs = targetAbsoluteMs;
        mIsSeeking.store(true);
        stateCond.notify_all();
    }
}

// ---------------------------------------------------------------------
// 核心逻辑: readLoop (生产者)
// 包含 Seek 修复、智能 EOF、贪婪缓存
// ---------------------------------------------------------------------
void FFPlayer::readLoop() {
    AVPacket *packet = av_packet_alloc();
    int consecutiveErrors = 0;
    const int MAX_ERRORS = 10;
    long lastReadPosMs = 0;

    // 起始位置跳转
    if (mStartTimeMs > 0) {
        lastReadPosMs = mStartTimeMs;
        int64_t timestamp = av_rescale(mStartTimeMs, timeBase->den, timeBase->num * 1000LL);
        avformat_seek_file(fmtCtx, audioStreamIndex, INT64_MIN, timestamp, INT64_MAX, 0);
    }

    while (!mIsExit.load()) {
        // --- 1. Seek 处理 ---
        if (mIsSeeking.load()) {
            long targetMs = -1;
            {
                std::lock_guard<std::mutex> lock(mSeekMutex);
                if (mIsSeeking.load()) {
                    targetMs = mSeekTargetMs;
                    mIsSeeking.store(false); // 必须在调用 API 前关闭标记，防止被 interrupt_cb 误杀
                    mSeekTargetMs = -1;
                }
            }

            if (targetMs >= 0) {
                mIsEOF.store(false);
                consecutiveErrors = 0;

                // IO 复位：防止因中断导致的 Error 状态残留
                if (fmtCtx->pb) {
                    fmtCtx->pb->eof_reached = 0;
                    fmtCtx->pb->error = 0;
                    avio_flush(fmtCtx->pb); // 丢弃旧数据，强制发新请求
                }

                audioQueue.flush();
                mFlushCodec.store(true);

                int64_t targetPts = av_rescale(targetMs, timeBase->den, timeBase->num * 1000LL);
                int64_t minPts =
                        targetPts - av_rescale(1000, timeBase->den, timeBase->num * 1000LL);
                int64_t maxPts =
                        targetPts + av_rescale(1000, timeBase->den, timeBase->num * 1000LL);

                // 优先使用 avformat_seek_file
                int ret = avformat_seek_file(fmtCtx, audioStreamIndex, minPts, targetPts, maxPts,
                                             0);
                if (ret < 0) {
                    LOGW("Precise seek failed, trying vague seek...");
                    ret = av_seek_frame(fmtCtx, audioStreamIndex, targetPts, AVSEEK_FLAG_BACKWARD);
                }

                if (ret >= 0) {
                    LOGD("Seek success to %ld ms", targetMs);
                    lastReadPosMs = targetMs;
                } else {
                    LOGE("Seek failed: %d", ret);
                }
            }
            continue; // Seek 完立即开始下载
        }

        // --- 2. 缓存控制 ---
        // 移除了 STATE_PAUSED 的检查，实现“暂停时继续下载”
        if (audioQueue.getSize() > MAX_QUEUE_SIZE) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        // --- 3. 读取 Packet ---
        if (!packet) packet = av_packet_alloc();
        int ret = av_read_frame(fmtCtx, packet);

        if (ret < 0) {
            if (mIsExit.load()) break;
            if (mIsSeeking.load()) {
                consecutiveErrors = 0;
                continue;
            }

            bool isRealEOF = (ret == AVERROR_EOF);

            // 智能 EOF 判定：IO 标记 check
            if (!isRealEOF && fmtCtx->pb && fmtCtx->pb->eof_reached) isRealEOF = true;

            // 智能 EOF 判定：临近结尾的错误视为结束
            if (!isRealEOF && mDurationMs > 0) {
                long remaining = mDurationMs - lastReadPosMs;
                if (remaining < 500) { // 剩余不足500ms
                    LOGW("Network error near end (%ld/%ld). Treating as EOF.", lastReadPosMs,
                         mDurationMs);
                    isRealEOF = true;
                }
            }

            if (isRealEOF) {
                if (!mIsEOF.load()) {
                    LOGD("Stream EOF reached.");
                    mIsEOF.store(true);
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                continue;
            }

            consecutiveErrors++;
            LOGW("Read error: %d, count: %d", ret, consecutiveErrors);
            if (consecutiveErrors > MAX_ERRORS) {
                // 错误太多，强制结束或重连（此处选择 EOF 以结束）
                mIsEOF.store(true);
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
            continue;
        }

        // --- 4. 读取成功 ---
        consecutiveErrors = 0;
        if (packet->stream_index == audioStreamIndex) {
            // 更新读取进度
            if (packet->pts != AV_NOPTS_VALUE) {
                long ptsMs = (long) (packet->pts * av_q2d(*timeBase) * 1000);
                if (ptsMs > lastReadPosMs) lastReadPosMs = ptsMs;
            }

            mIsEOF.store(false);
            AVPacket *pktToQueue = av_packet_alloc();
            av_packet_move_ref(pktToQueue, packet);

            if (audioQueue.put(pktToQueue) < 0) {
                av_packet_free(&pktToQueue);
                break;
            }
        } else {
            av_packet_unref(packet);
        }
    }
    if (packet) av_packet_free(&packet);
}

// ---------------------------------------------------------------------
// 核心逻辑: decodingLoop (消费者)
// 包含水位线控制、缓冲转圈逻辑
// ---------------------------------------------------------------------
void FFPlayer::decodingLoop() {
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    bool isDraining = false;

    while (!mIsExit.load()) {
        if (mState == STATE_STOPPED) break;

        // 1. Seek Flush
        if (mFlushCodec.load()) {
            if (codecCtx) avcodec_flush_buffers(codecCtx);
            mFlushCodec.store(false);
            isDraining = false;
        }

        // 2. 暂停逻辑 (用户主动)
        if (mState == STATE_PAUSED) {
            std::unique_lock<std::mutex> lock(mStateMutex);
            stateCond.wait(lock, [this] {
                return mState != STATE_PAUSED;
            });
            // 醒来后重新检查 flush 等状态
            continue;
        }

        // 3. 自动缓冲逻辑 (水位线)
        int qSize = audioQueue.getSize();
        bool isEOF = mIsEOF.load();

        // 没数据且没下完 -> 进入缓冲
        if (qSize == 0 && !isEOF && !isDraining) {
            if (mState != STATE_BUFFERING) {
                std::lock_guard<std::mutex> lock(mStateMutex);
                mState = STATE_BUFFERING;
                LOGD("Buffering start...");
                if (mCallback) mCallback->onBuffering(true);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        // 正在缓冲 -> 检查是否达到起播阈值
        if (mState == STATE_BUFFERING) {
            if (qSize > minStartThresholdBytes || isEOF) {
                std::lock_guard<std::mutex> lock(mStateMutex);
                mState = STATE_PLAYING;
                LOGD("Buffering end. Resuming playback.");
                if (mCallback) mCallback->onBuffering(false);
            } else {
                // 水位不够，继续等
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
        }

        // 4. 获取数据
        int ret = audioQueue.get(packet, false); // 非阻塞

        if (ret == 0) {
            if (isEOF) {
                // EOF 了，开始排空解码器
                isDraining = true;
                if (codecCtx) avcodec_send_packet(codecCtx, nullptr);
            } else {
                // 理论上不会走到这，因为上面处理了 buffering，但防守一下
                continue;
            }
        } else if (ret < 0) {
            break; // Abort
        } else {
            // 正常获取
            isDraining = false;
        }

        // 5. 解码
        if (isDraining) {
            // Drain 模式：只收帧
            int rxRet = avcodec_receive_frame(codecCtx, frame);
            if (rxRet == AVERROR_EOF) {
                LOGD("Playback Complete.");
                // 真正的播放结束
                if (mState != STATE_COMPLETED && mState != STATE_STOPPED && !mIsExit.load()) {
                    mState = STATE_COMPLETED;
                    if (mCallback) mCallback->onComplete();
                }
                // 等待 Seek 或 Stop
                std::unique_lock<std::mutex> lock(mStateMutex);
                stateCond.wait(lock, [this] {
                    return mIsExit.load() || mIsSeeking.load() || mState == STATE_STOPPED;
                });
            } else if (rxRet >= 0) {
                // 播放残余
                if (mIsSourceDsd && mDsdMode != DSD_MODE_D2P) handleDsdAudioPacket(nullptr, frame);
                else handlePcmAudioPacket(nullptr, frame);
            }
        } else {
            // 普通模式
            if (packet->pts != AV_NOPTS_VALUE) {
                mCurrentPositionMs.store((long) (packet->pts * av_q2d(*timeBase) * 1000));
            }
            // EndTime 检查
            if (mEndTimeMs > 0 && mCurrentPositionMs.load() >= mEndTimeMs) {
                av_packet_unref(packet);
                // 触发结束
                mIsEOF.store(true);
                continue;
            }

            if (mState == STATE_PLAYING) updateProgress();

            if (mIsSourceDsd && mDsdMode != DSD_MODE_D2P) handleDsdAudioPacket(packet, frame);
            else handlePcmAudioPacket(packet, frame);

            av_packet_unref(packet);
        }
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
}

void FFPlayer::handlePcmAudioPacket(AVPacket *packet, AVFrame *frame) {
    if (!codecCtx || !frame) return;

    // 1. Send Packet
    if (packet) {
        int ret = avcodec_send_packet(codecCtx, packet);
        if (ret < 0) {
            if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) LOGE("Send packet error");
            return;
        }
    }

    // 2. Receive Frames
    while (true) {
        int ret = avcodec_receive_frame(codecCtx, frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) break;

        if (frame->nb_samples <= 0) continue;

        // --- [核心修复 1] 强制标准化 Frame 布局 ---
        // 很多崩溃是因为 layout.order 是 UNSPEC，导致 swr 计算矩阵失败
        if (frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || frame->ch_layout.nb_channels <= 0) {
            // 如果没布局，根据声道数猜一个默认布局 (例如 2 -> Stereo)
            av_channel_layout_default(&frame->ch_layout, frame->ch_layout.nb_channels);
        }

        // --- [核心修复 2] 严格的数据指针检查 ---
        // 确保 swr_convert 读取的每一个 input 指针都是有效的
        bool isPlanar = av_sample_fmt_is_planar((AVSampleFormat)frame->format);
        int planesToCheck = isPlanar ? frame->ch_layout.nb_channels : 1;
        bool hasBadPointer = false;

        // 必须检查 extended_data，因为 swr_convert 用的是这个
        if (!frame->extended_data) {
            hasBadPointer = true;
        } else {
            for (int i = 0; i < planesToCheck; i++) {
                if (!frame->extended_data[i]) {
                    hasBadPointer = true;
                    break;
                }
            }
        }

        if (hasBadPointer) {
            LOGE("Frame data corrupt: null pointers in extended_data. Skipping.");
            continue;
        }

        // --- [核心修复 3] 动态重建 SwrContext (完全脱离 codecCtx) ---
        // 这里的逻辑是：只看 Frame，不看 CodecCtx。只要 Frame 变了，Swr 必须变。

        // 获取当前 Frame 的布局掩码 (用于比较)
        // 注意：这里我们比较 layout 的 mask 值，如果 layout 结构比较复杂，可以比较 nb_channels
        // 但最安全的是：只要参数变了，就重建。
        bool swrNeedsReinit = false;

        // 比较参数
        if (!swrCtx ||
            mSwrInSampleRate != frame->sample_rate ||
            mSwrInFormat != frame->format ||
            mSwrInChannels != frame->ch_layout.nb_channels ||
            av_channel_layout_compare(&codecCtx->ch_layout, &frame->ch_layout) != 0) {
            swrNeedsReinit = true;
        }
        if (mSwrInSampleRate != frame->sample_rate ||
            mSwrInFormat != frame->format ||
            mSwrInChannels != frame->ch_layout.nb_channels) {
            swrNeedsReinit = true;
        }

        if (swrNeedsReinit) {
            // 如果存在旧的，先释放
            if (swrCtx) {
                swr_free(&swrCtx);
                swrCtx = nullptr;
            }

            swrCtx = swr_alloc();
            if (!swrCtx) {
                LOGE("Failed to allocate swrCtx");
                continue;
            }

            // --- 配置 Output ---
            AVChannelLayout outLayout;
            av_channel_layout_default(&outLayout, CHANNEL_OUT_STEREO);
            int actualOutRate = mIsSourceDsd ? mTargetD2pSampleRate : frame->sample_rate; // 使用 frame 的 rate

            av_opt_set_chlayout(swrCtx, "out_chlayout", &outLayout, 0);
            av_opt_set_int(swrCtx, "out_sample_rate", actualOutRate, 0);
            av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", outputSampleFormat, 0);

            // --- 配置 Input (完全基于当前 Frame) ---
            // 这一点至关重要：告诉 Swr 实际进来的数据到底是什么
            av_opt_set_chlayout(swrCtx, "in_chlayout", &frame->ch_layout, 0);
            av_opt_set_int(swrCtx, "in_sample_rate", frame->sample_rate, 0);
            av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", (AVSampleFormat)frame->format, 0);

            // 显式设置声道数，防止 rematrix 混淆
            av_opt_set_int(swrCtx, "ich", frame->ch_layout.nb_channels, 0);

            if (swr_init(swrCtx) < 0) {
                LOGE("Failed to swr_init");
                swr_free(&swrCtx);
                continue;
            }

            // 更新缓存状态
            mSwrInSampleRate = frame->sample_rate;
            mSwrInFormat = frame->format;
            mSwrInChannels = frame->ch_layout.nb_channels;
            // 同步 codecCtx 防止外部逻辑混乱 (可选)
            codecCtx->sample_rate = frame->sample_rate;
            av_channel_layout_uninit(&codecCtx->ch_layout);
            av_channel_layout_copy(&codecCtx->ch_layout, &frame->ch_layout);

            LOGD("Swr Re-initialized: %dHz %dch fmt%d -> %dHz Stereo",
                 frame->sample_rate, frame->ch_layout.nb_channels, frame->format, actualOutRate);
        }

        // --- 执行转换 ---
        if (swrCtx) {
            // 计算输出 Buffer 大小
            int actualOutRate = mIsSourceDsd ? mTargetD2pSampleRate : frame->sample_rate;
            int out_samples = av_rescale_rnd(
                    swr_get_delay(swrCtx, frame->sample_rate) + frame->nb_samples,
                    actualOutRate,
                    frame->sample_rate,
                    AV_ROUND_UP
            );

            if (out_samples > 0) {
                int outSampleSize = av_get_bytes_per_sample(outputSampleFormat);
                int outChannels = 2; // Stereo
                int requiredBufferSize = out_samples * outSampleSize * outChannels;

                ensureBufferCapacity(requiredBufferSize);
                if (outBuffer.empty()) continue;

                uint8_t *rawBuffer = outBuffer.data();
                uint8_t *outData[2] = {rawBuffer, nullptr};
                const uint8_t **inData = (const uint8_t **)frame->extended_data;

                // 转换
                int convertedSamples = swr_convert(swrCtx,
                                                   outData,
                                                   out_samples,
                                                   inData,
                                                   frame->nb_samples);

                if (convertedSamples > 0) {
                    int size = convertedSamples * outSampleSize * outChannels;
                    if (mCallback) mCallback->onAudioData(rawBuffer, size);
                }
            }
        }
    }
}

void FFPlayer::handleDsdAudioPacket(AVPacket *packet, AVFrame *frame) {
    if (!codecCtx || !frame) return;
    // DSD 模式一般不需要 Drain 处理残余帧，因为没有 buffer delay
    if (!packet) return;

    ensureBufferCapacity(packet->size * 2);
    int outputSize = 0;
    uint8_t *rawBuffer = outBuffer.data();

    if (mDsdMode == DSD_MODE_NATIVE) {
        if (is4ChannelSupported) {
            outputSize = DsdUtils::pack4ChannelNative(isMsbf, packet->data, packet->size,
                                                      rawBuffer);
        } else {
            outputSize = DsdUtils::packNative(isMsbf, packet->data, packet->size, rawBuffer);
        }
    } else if (mDsdMode == DSD_MODE_DOP) {
        outputSize = DsdUtils::packDoP(isMsbf, packet->data, packet->size, rawBuffer);
    }

    if (outputSize > 0) {
        if (outputSize > outBuffer.size()) outputSize = outBuffer.size();
        if (mCallback) {
            mCallback->onAudioData(rawBuffer, outputSize);
        }
    }
}

void FFPlayer::updateProgress() {
    long cur = mCurrentPositionMs.load();
    long relativePosition = cur - mStartTimeMs;
    if (relativePosition < 0) relativePosition = 0;

    long virtualDuration = getDuration();
    if (virtualDuration <= 0) virtualDuration = 1;

    float progress = (float) relativePosition / (float) virtualDuration;
    if (progress > 1.0f) progress = 1.0f;

    if (mCallback) {
        mCallback->onProgress(0, relativePosition, virtualDuration, progress);
    }
}

void FFPlayer::extractAudioInfo() {
    if (fmtCtx && fmtCtx->duration != AV_NOPTS_VALUE) {
        mDurationMs = (long) (fmtCtx->duration / (double) AV_TIME_BASE * 1000);
    }
    mChannelCount = codecCtx->ch_layout.nb_channels;

    if (mIsSourceDsd) {
        switch (mDsdMode) {
            case DSD_MODE_NATIVE:
                mSampleRate = codecCtx->sample_rate / 4;
                mBitPerSample = 1;
                break;
            case DSD_MODE_D2P:
                mSampleRate = mTargetD2pSampleRate;
                mBitPerSample = 16;
                break;
            case DSD_MODE_DOP:
                mSampleRate = codecCtx->sample_rate / 2;
                mBitPerSample = 32;
                break;
        }
    } else {
        mSampleRate = codecCtx->sample_rate;
        mBitPerSample = outputSampleFormat == AV_SAMPLE_FMT_S32 ? 32 : 16;
    }

    int64_t bitRate = 0;
    if (fmtCtx->streams[audioStreamIndex]->codecpar->bit_rate > 0) {
        bitRate = fmtCtx->streams[audioStreamIndex]->codecpar->bit_rate;
    } else if (fmtCtx->bit_rate > 0) {
        bitRate = fmtCtx->bit_rate;
    }
    int targetBytes = 0;
    if (bitRate > 0) {
        targetBytes = (int) ((bitRate / 8) * 3);
    } else {
        int bytesPerSec = mSampleRate * mChannelCount * 2;
        targetBytes = bytesPerSec * 3;
    }
    if (targetBytes < 256 * 1024) {
        // 最小3s 或者 256KB缓冲
        minStartThresholdBytes = 256 * 1024;
    } else if (targetBytes > 5 * 1024 * 1024) {
        // 最大 5MB缓冲
        minStartThresholdBytes = 5 * 1024 * 1024;
    } else {
        minStartThresholdBytes = targetBytes;
    }
    LOGD("Buffering Config: BitRate=%ld, Target 3s=%d, Final Threshold=%d",
         bitRate, targetBytes, minStartThresholdBytes);
}

void FFPlayer::releaseFFmpeg() {
    if (swrCtx) {
        swr_free(&swrCtx);
        swrCtx = nullptr;
    }
    if (codecCtx) {
        avcodec_free_context(&codecCtx);
        codecCtx = nullptr;
    }
    if (fmtCtx) {
        avformat_close_input(&fmtCtx);
        fmtCtx = nullptr;
    }
}

void FFPlayer::ensureBufferCapacity(size_t requiredSize) {
    size_t safeSize = requiredSize + AV_INPUT_BUFFER_PADDING_SIZE;
    if (safeSize > outBuffer.size()) {
        size_t newSize = std::max(safeSize, outBuffer.size() * 3 / 2);
        outBuffer.resize(newSize);
    }
}

long FFPlayer::getDuration() const {
    if (mEndTimeMs > 0 && mStartTimeMs >= 0) return (long) (mEndTimeMs - mStartTimeMs);
    if (mStartTimeMs > 0) return (long) (mDurationMs - mStartTimeMs);
    return mDurationMs;
}

long FFPlayer::getCurrentPosition() const {
    long pos = mCurrentPositionMs.load() - mStartTimeMs;
    return pos < 0 ? 0 : pos;
}

int FFPlayer::getSampleRate() const { return mSampleRate; }

int FFPlayer::getChannelCount() const {
    if (mDsdMode == DSD_MODE_NATIVE && is4ChannelSupported) return 4;
    return mChannelCount;
}

int FFPlayer::getBitPerSample() const { return mBitPerSample; }

bool FFPlayer::isDsd() const { return mIsSourceDsd; }

bool FFPlayer::isExit() const { return mIsExit.load(); }

bool FFPlayer::isDsdCodec(AVCodecID id) {
    return id == AV_CODEC_ID_DSD_LSBF || id == AV_CODEC_ID_DSD_MSBF ||
           id == AV_CODEC_ID_DSD_LSBF_PLANAR || id == AV_CODEC_ID_DSD_MSBF_PLANAR;
}

bool FFPlayer::isMsbfCodec(AVCodecID id) {
    return id == AV_CODEC_ID_DSD_MSBF || id == AV_CODEC_ID_DSD_MSBF_PLANAR;
}

AVSampleFormat FFPlayer::getOutputSampleFormat(AVSampleFormat inputFormat) {
    return (inputFormat == AV_SAMPLE_FMT_S32 || inputFormat == AV_SAMPLE_FMT_S32P ||
            inputFormat == AV_SAMPLE_FMT_FLT || inputFormat == AV_SAMPLE_FMT_FLTP ||
            inputFormat == AV_SAMPLE_FMT_DBL || inputFormat == AV_SAMPLE_FMT_DBLP)
           ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_S16;
}