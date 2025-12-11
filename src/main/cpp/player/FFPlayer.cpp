#include "FFPlayer.h"

#define DEFAULT_BUFFER_SIZE 2 * 1024 * 1024

FFPlayer::FFPlayer(IPlayerCallback *callback) : BasePlayer(callback) {
    setCpuAffinity(1);
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

void FFPlayer::prepare() {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (mState != STATE_IDLE && mState != STATE_STOPPED) {
        LOGE("FFPlayer::prepare: player is not idle or stopped");
        return;
    }

    mIsExit.store(false);
    mIsEOF.store(false); // 重置 EOF
    mState = STATE_PREPARING;
    audioQueue.start();

    int ret = 0;
    AVDictionary *options = nullptr;

    // 1. 设置 Headers
//    if (!mHeaders.empty()) {
//        av_dict_set(&options, "headers", mHeaders.c_str(), 0);
//    }
    std::string customHeaders;
    bool hasUserAgent = false;

    // 1. 处理传入的 Headers
    for (const auto &pair: mHeaders) {
        if (strcasecmp(pair.first.c_str(), "User-Agent") == 0) {
            av_dict_set(&options, "user_agent", pair.second.c_str(), 0);
            hasUserAgent = true;
            LOGD("Using provided User-Agent: %s", pair.second.c_str());
        } else {
            customHeaders += pair.first + ": " + pair.second + "\r\n";
        }
    }
    if (!customHeaders.empty()) {
        av_dict_set(&options, "headers", customHeaders.c_str(), 0);
    }

    // 2. 网络优化参数
    av_dict_set(&options, "timeout", "10000000", 0); // 10s
    av_dict_set(&options, "rw_timeout", "10000000", 0); // 10s
    av_dict_set(&options, "reconnect", "1", 0);
    av_dict_set(&options, "reconnect_at_eof", "1", 0);
    av_dict_set(&options, "reconnect_streamed", "1", 0);
    av_dict_set(&options, "reconnect_delay_max", "5", 0);
    if (!hasUserAgent) {
        av_dict_set(&options, "user_agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", 0);
    }
    av_dict_set(&options, "buffer_size", "4194304", 0);
    av_dict_set(&options, "probesize", "1024000", 0);
    av_dict_set(&options, "analyzeduration", "2000000", 0);

    // 3. 打开输入流
    fmtCtx = avformat_alloc_context();
    if (!fmtCtx) {
        LOGE("FFPlayer::prepare: avformat_alloc_context failed");
        if (mCallback) mCallback->onError(-1, "avformat_alloc_context failed");
        goto error;
    }

    fmtCtx->interrupt_callback.callback = interrupt_cb;
    fmtCtx->interrupt_callback.opaque = this;

    if ((ret = avformat_open_input(&fmtCtx, mUrl.c_str(), nullptr, &options)) != 0) {
        LOGE("Open input %s failed: %d ", mUrl.c_str(), ret);
        if (mCallback) mCallback->onError(-1, "Open input failed");
        goto error;
    }

    if ((ret = avformat_find_stream_info(fmtCtx, nullptr)) < 0) {
        LOGE("Find stream info failed: %d", ret);
        if (mCallback) mCallback->onError(-2, "Find stream info failed");
        goto error;
    }

    // 4. 查找音频流
    audioStreamIndex = -1;
    for (int i = 0; i < fmtCtx->nb_streams; i++) {
        if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = i;
            break;
        }
    }

    if (audioStreamIndex == -1) {
        if (mCallback) mCallback->onError(-3, "No audio stream");
        goto error;
    }

    // 5. 打开解码器
    {
        AVCodecParameters *codecPar = fmtCtx->streams[audioStreamIndex]->codecpar;
        const AVCodec *codec = avcodec_find_decoder(codecPar->codec_id);
        if (!codec) {
            if (mCallback) mCallback->onError(-4, "Decoder not found");
            goto error;
        }

        codecCtx = avcodec_alloc_context3(codec);
        if (!codecCtx) goto error;

        avcodec_parameters_to_context(codecCtx, codecPar);

        if ((ret = avcodec_open2(codecCtx, codec, nullptr)) < 0) {
            LOGE("Open codec failed: %d", ret);
            if (mCallback) mCallback->onError(-5, "Open codec failed");
            goto error;
        }
    }

    // 6. DSD 配置
    mIsSourceDsd = isDsdCodec(codecCtx->codec_id);
    if (mIsSourceDsd) {
        isMsbf = isMsbfCodec(codecCtx->codec_id);
        is4ChannelSupported = SystemProperties::is4ChannelSupported();
    }

    timeBase = &fmtCtx->streams[audioStreamIndex]->time_base;

    // 7. 初始化重采样
    if ((!mIsSourceDsd) || (mIsSourceDsd && mDsdMode == DSD_MODE_D2P)) {
        if (initSwrContext() < 0) {
            goto error;
        }
    }

    extractAudioInfo();

    // 8. 预设置当前位置 (实际Seek在线程中)
    if (mStartTimeMs > 0) {
        mCurrentPositionMs = mStartTimeMs;
    }

    mState = STATE_PREPARED;
    if (mCallback) mCallback->onPrepared();
    LOGD("FFPlayer::prepare: success");
    return;

    error:
    mState = STATE_ERROR;
    releaseFFmpeg();
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

        if (!readThread) {
            readThread = new std::thread(&FFPlayer::readLoop, this);
        }
        if (!decodeThread) {
            decodeThread = new std::thread(&FFPlayer::decodingLoop, this);
        }
        stateCond.notify_all();
    }
}

void FFPlayer::pause() {
    if (mState == STATE_PLAYING) {
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
    try {
        stop();
        releaseFFmpeg();
        mState = STATE_IDLE;
    } catch (const std::exception &e) {
        LOGE("releaseInternal: %s", e.what());
    }
}

void FFPlayer::seek(long ms) {
    long targetMs = mStartTimeMs + ms;
    if (mEndTimeMs > 0 && targetMs > mEndTimeMs) {
        targetMs = mEndTimeMs;
    } else if (targetMs > mDurationMs) {
        targetMs = mDurationMs;
    }
    if (targetMs < 0) targetMs = 0;

    if (mState == STATE_PLAYING || mState == STATE_PAUSED || mState == STATE_PREPARED) {
        std::lock_guard<std::mutex> lock(mSeekMutex);
        mSeekTargetMs = targetMs;
        mIsSeeking = true;
        // 如果暂停中，唤醒线程去处理 Seek
        stateCond.notify_all();
    }
}

// === 生产者：下载线程 ===
void FFPlayer::readLoop() {
    AVPacket *packet = nullptr;
    long long totalBytesRead = 0;
    auto lastSpeedTime = std::chrono::steady_clock::now();

    // 初始 StartTime 处理
    if (mStartTimeMs > 0) {
        int64_t timestamp = mStartTimeMs / 1000.0 / av_q2d(*timeBase);
        av_seek_frame(fmtCtx, audioStreamIndex, timestamp, AVSEEK_FLAG_BACKWARD);
    }

    while (!mIsExit) {
        // 1. 状态检查
        if (mState == STATE_STOPPED || mState == STATE_ERROR) break;
        if (mState == STATE_PAUSED) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        // 2. 处理 Seek (由 readLoop 执行，避免多线程冲突)
        if (mIsSeeking) {
            long target = -1;
            {
                std::lock_guard<std::mutex> lock(mSeekMutex);
                if (mIsSeeking) {
                    target = mSeekTargetMs;
                    mIsSeeking = false;
                    mSeekTargetMs = -1;
                }
            }
            if (target >= 0) {
                int64_t timestamp = target / 1000.0 / av_q2d(*timeBase);
                if (av_seek_frame(fmtCtx, audioStreamIndex, timestamp, AVSEEK_FLAG_BACKWARD) >= 0) {
                    audioQueue.flush();
                    mFlushCodec.store(true);
                    mIsEOF.store(false); // Seek 成功重置 EOF
                    LOGD("Seek success to %ld ms", target);
                } else {
                    LOGE("Seek failed to %ld ms", target);
                }
            }
        }

        // 3. 高水位检查 (防止内存爆)
        if (audioQueue.getSize() > MAX_QUEUE_SIZE) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        // 4. 读取 Packet
        if (!packet) packet = av_packet_alloc();
        int ret = av_read_frame(fmtCtx, packet);

        if (ret < 0) {
            if (ret == AVERROR_EOF) {
                // 文件读完了
                if (!mIsEOF.load()) {
                    LOGD("File EOF reached.");
                    mIsEOF.store(true);
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            } else {
                // 网络错误，稍微等待
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                continue;
            }
        }

        // 5. 速度统计
        totalBytesRead += packet->size;
        auto now = std::chrono::steady_clock::now();
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - lastSpeedTime).count();
        if (elapsedMs >= 1000) {
            double speedKB = (totalBytesRead / 1024.0) / (elapsedMs / 1000.0);
            if (speedKB > 1024.0) LOGD("Download Speed: %.2f MB/s", speedKB / 1024.0);
            else
                LOGD("Download Speed: %.2f KB/s", speedKB);
            totalBytesRead = 0;
            lastSpeedTime = now;
        }

        if (packet->stream_index == audioStreamIndex) {
            AVPacket *pktToQueue = av_packet_alloc();
            av_packet_move_ref(pktToQueue, packet);
            audioQueue.put(pktToQueue);
        } else {
            av_packet_unref(packet);
        }
    }

    if (packet) av_packet_free(&packet);
}

// === 消费者：解码线程 ===
void FFPlayer::decodingLoop() {
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();

    try {
        while (!mIsExit) {
            // 1. 暂停等待 (解决暂停进度条乱跑问题)
            if (mState == STATE_PAUSED) {
                std::unique_lock<std::mutex> lock(mStateMutex);
                stateCond.wait(lock, [this] {
                    return mState == STATE_PLAYING || mState == STATE_STOPPED || mIsExit;
                });
            }
            if (mIsExit || mState == STATE_STOPPED) break;

            if (mFlushCodec.load()) {
                if (codecCtx) avcodec_flush_buffers(codecCtx);
                mFlushCodec.store(false);
            }

            // 2. 从队列取包 (非阻塞，自己控制等待)
            int ret = audioQueue.get(packet, false);

            // 队列空处理
            if (ret == 0) {
                // 如果在播放中，且文件没完，进入缓冲
                if (mState == STATE_PLAYING && !mIsEOF.load()) {
                    LOGW("Buffer underrun, start buffering...");
                    mState = STATE_BUFFERING;
                    // if (mCallback) mCallback->onBufferingStart();
                }

                // 如果文件完了且队列空了，退出循环
                if (mIsEOF.load() && audioQueue.getPacketCount() == 0) {
                    LOGD("Playback finished.");
                    break;
                }

                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            } else if (ret < 0) {
                break; // Abort
            }

            // 3. 缓冲恢复逻辑 (解决震荡卡顿问题)
            if (mState == STATE_BUFFERING) {
                bool isHighWater = (audioQueue.getPacketCount() > MIN_BUFFER_PACKETS) ||
                                   (audioQueue.getSize() > MIN_BUFFER_BYTES);

                // 只有存够了，或者文件读完了，才恢复播放
                if (isHighWater || mIsEOF.load()) {
                    LOGW("Buffering filled (count=%d, size=%d), resume playing.",
                         audioQueue.getPacketCount(), audioQueue.getSize());
                    mState = STATE_PLAYING;
                } else {
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
                }
            }

            // 4. 进度更新
            if (packet->pts != AV_NOPTS_VALUE) {
                mCurrentPositionMs = packet->pts * av_q2d(*timeBase) * 1000;
            }
            if (mEndTimeMs > 0 && mCurrentPositionMs >= mEndTimeMs) {
                av_packet_unref(packet);
                break;
            }

            // 只有播放状态才回调进度
            if (mState == STATE_PLAYING) {
                updateProgress();
            }

            // 5. 解码
            if (mIsSourceDsd && mDsdMode != DSD_MODE_D2P) {
                handleDsdAudioPacket(packet, frame);
            } else {
                handlePcmAudioPacket(packet, frame);
            }

            av_packet_unref(packet);
        }

        av_frame_free(&frame);
        av_packet_free(&packet);

        if (!mIsExit && mState != STATE_STOPPED && mState != STATE_ERROR) {
            mState = STATE_COMPLETED;
            if (mCallback) mCallback->onComplete();
        }
    } catch (const std::exception &e) {
        LOGE("decodingLoop: %s", e.what());
    }
}

void FFPlayer::handlePcmAudioPacket(AVPacket *packet, AVFrame *frame) {
    try {
        if (!codecCtx || !packet || !frame) return;

        int ret = avcodec_send_packet(codecCtx, packet);
        if (ret < 0) {
            if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) LOGE("Send packet error");
            return;
        }

        while (ret >= 0) {
            ret = avcodec_receive_frame(codecCtx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) break;

            if (swrCtx) {
                int actualOutRate = mIsSourceDsd ? mTargetD2pSampleRate : codecCtx->sample_rate;
                int out_samples = av_rescale_rnd(
                        swr_get_delay(swrCtx, codecCtx->sample_rate) + frame->nb_samples,
                        actualOutRate,
                        codecCtx->sample_rate,
                        AV_ROUND_UP
                );

                if (out_samples > 0) {
                    int outSampleSize = av_get_bytes_per_sample(outputSampleFormat);
                    int channels = 2;
                    int requiredBufferSize = out_samples * outSampleSize * channels;
                    ensureBufferCapacity(requiredBufferSize);
                    if (outBuffer.empty()) return;

                    auto *rawBuffer = outBuffer.data();
                    uint8_t *outData[2] = {rawBuffer, nullptr};

                    int convertedSamples = swr_convert(swrCtx,
                                                       outData,
                                                       out_samples,
                                                       (const uint8_t **) frame->data,
                                                       frame->nb_samples);

                    if (convertedSamples > 0) {
                        int size = convertedSamples * outSampleSize * channels;
                        if (mCallback) mCallback->onAudioData(rawBuffer, size);
                    }
                }
            }
        }
    } catch (const std::exception &e) {
        LOGE("handlePcmAudioPacket: %s", e.what());
    }
}

void FFPlayer::handleDsdAudioPacket(AVPacket *packet, AVFrame *frame) {
    try {
        if (!codecCtx || !packet || !frame) return;

        ensureBufferCapacity(frame->nb_samples * 4);
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
    } catch (const std::exception &e) {
        LOGE("handleDsdAudioPacket: %s", e.what());
    }
}

void FFPlayer::updateProgress() {
    long relativePosition = mCurrentPositionMs - mStartTimeMs;
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
}

void FFPlayer::releaseFFmpeg() {
    try {
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
    } catch (const std::exception &e) {
        LOGE("releaseFFmpeg: %s", e.what());
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
    long pos = mCurrentPositionMs - mStartTimeMs;
    return pos < 0 ? 0 : pos;
}

int FFPlayer::getSampleRate() const {
    return mSampleRate;
}

int FFPlayer::getChannelCount() const {
    if (mDsdMode == DSD_MODE_NATIVE && is4ChannelSupported) {
        return 4;
    }
    return mChannelCount;
}

int FFPlayer::getBitPerSample() const {
    return mBitPerSample;
}

bool FFPlayer::isDsd() const {
    return mIsSourceDsd;
}

bool FFPlayer::isExit() const {
    return mIsExit.load();
}

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

int FFPlayer::interrupt_cb(void *ctx) {
    auto *player = (FFPlayer *) ctx;
    if (player && player->isExit()) {
        return 1;
    }
    return 0;
}