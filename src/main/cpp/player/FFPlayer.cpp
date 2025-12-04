#include "FFPlayer.h"

// 默认缓冲区大小，不足时会自动扩容
#define DEFAULT_BUFFER_SIZE 2 * 1024 * 1024
#define DSD_BATCH_SIZE  16384

FFPlayer::FFPlayer(IPlayerCallback *callback) : BasePlayer(callback) {
    initFFmpeg();
    outBuffer.reserve(DEFAULT_BUFFER_SIZE);
    outBuffer.resize(DEFAULT_BUFFER_SIZE);
}

FFPlayer::~FFPlayer() {
    releaseInternal();
}

void FFPlayer::setDataSource(const char *path, const char *headers, int64_t startPositon,
                             int64_t endPosition) {
    this->mUrl = path;
    this->mHeaders = headers;
    this->mStartTimeMs = startPositon;
    this->mEndTimeMs = endPosition;
}

void FFPlayer::prepare() {
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        if (mState != STATE_IDLE && mState != STATE_STOPPED) {
            LOGE("FFPlayer::prepare: player is not idle or stopped");
            return;
        }
        mIsExit.store(false);
        mState = STATE_PREPARING;

        int ret = 0;
        AVDictionary *options = nullptr;

        // 1. 设置参数
        if (!mHeaders.empty()) {
            av_dict_set(&options, "headers", mHeaders.c_str(), 0);
        }
        // 增加网络超时设置 (5秒 = 5000000微秒)
        av_dict_set(&options, "timeout", "5000000", 0);
        av_dict_set(&options, "buffer_size", "4194304", 0); // 4MB


        // 2. 打开输入流
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

        // 3. 查找音频流
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

        // 4. 打开解码器
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

        // 5. 检查 DSD 配置
        mIsSourceDsd = isDsdCodec(codecCtx->codec_id);
        if (mIsSourceDsd) {
            isMsbf = isMsbfCodec(codecCtx->codec_id);
            is4ChannelSupported = SystemProperties::is4ChannelSupported();
        }

        timeBase = &fmtCtx->streams[audioStreamIndex]->time_base;

        // 6. 初始化重采样器 (非 DSD 直通模式需要)
        if ((!mIsSourceDsd) || (mIsSourceDsd && mDsdMode == DSD_MODE_D2P)) {
            if (initSwrContext() < 0) {
                goto error;
            }
        }

        extractAudioInfo();

        // 7. 处理 StartTime (CUE 分轨或断点续传)
        if (mStartTimeMs > 0) {
            int64_t timestamp = mStartTimeMs / 1000.0 / av_q2d(*timeBase);
            if (av_seek_frame(fmtCtx, audioStreamIndex, timestamp, AVSEEK_FLAG_BACKWARD) >= 0) {
                if (codecCtx) avcodec_flush_buffers(codecCtx);
                LOGD("FFPlayer: Seek to start: %ld ms success", mStartTimeMs);
            }
        }
        mState = STATE_PREPARED;
        mIsExit.store(false);
    }

    if (mIsExit.load()) {
        LOGW("FFPlayer::prepare: exit before success");
        goto error;
    }

    if (mCallback) {
        mCallback->onPrepared();
    }
    LOGD("FFPlayer::prepare: success");
    return;

    error:
    mState = STATE_ERROR;
    releaseFFmpeg(); // 统一清理资源
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

    // 使用 av_opt_set 设置参数
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
    LOGD("SwrInit Success: outRate=%d, outFmt=%s", outRate,
         av_get_sample_fmt_name(outputSampleFormat));
    return 0;
}

void FFPlayer::play() {
    if (mState == STATE_PREPARED || mState == STATE_PAUSED || mState == STATE_COMPLETED) {
        {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_PLAYING;
        }
        if (!decodeThread) {
            mIsExit.store(false);
            decodeThread = new std::thread(&FFPlayer::decodingLoop, this);
        } else {
            stateCond.notify_all();
        }
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
    mIsExit.store(true); // 1. 先设置退出标志，让线程循环能跳出

    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_STOPPED;
        stateCond.notify_all();
    }
    try {
        if (decodeThread) {
            if (decodeThread->joinable()) {
                decodeThread->join();
            }
            delete decodeThread;
            decodeThread = nullptr;
        }
    } catch (const std::exception &e) {
        LOGE("stop: %s", e.what());
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
    if (mEndTimeMs > 0 && targetMs > mEndTimeMs) targetMs = mEndTimeMs;
    if (targetMs < 0) targetMs = 0;

    // 只有在准备好后才能 seek
    if (mState == STATE_PLAYING || mState == STATE_PAUSED || mState == STATE_PREPARED) {
        std::lock_guard<std::mutex> lock(mSeekMutex);
        mSeekTargetMs = targetMs;
        mIsSeeking = true;
        // 如果处于暂停状态，需要唤醒线程去执行 seek
        stateCond.notify_all();
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
    return mChannelCount;
}

int FFPlayer::getBitPerSample() const {
    return mBitPerSample;
}

bool FFPlayer::isDsd() const {
    return mIsSourceDsd;
}

void FFPlayer::initFFmpeg() {
    // 新版 FFmpeg 不需要显式 register_all，但 network_init 需要
    avformat_network_init();
}

bool FFPlayer::isDsdCodec(AVCodecID id) {
    // 是否是DSD格式的音频
    return id == AV_CODEC_ID_DSD_LSBF || id == AV_CODEC_ID_DSD_MSBF ||
           id == AV_CODEC_ID_DSD_LSBF_PLANAR || id == AV_CODEC_ID_DSD_MSBF_PLANAR;
}

bool FFPlayer::isMsbfCodec(AVCodecID id) {
    // 是否是DFF格式的
    return id == AV_CODEC_ID_DSD_MSBF || id == AV_CODEC_ID_DSD_MSBF_PLANAR;
}

AVSampleFormat FFPlayer::getOutputSampleFormat(AVSampleFormat inputFormat) {
    // 只是用32位和16位, 默认使用16位
    return (inputFormat == AV_SAMPLE_FMT_S32 || inputFormat == AV_SAMPLE_FMT_S32P ||
            inputFormat == AV_SAMPLE_FMT_FLT || inputFormat == AV_SAMPLE_FMT_FLTP ||
            inputFormat == AV_SAMPLE_FMT_DBL || inputFormat == AV_SAMPLE_FMT_DBLP)
           ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_S16;
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

void FFPlayer::extractAudioInfo() {
    if (fmtCtx && fmtCtx->duration != AV_NOPTS_VALUE) {
        mDurationMs = (long) (fmtCtx->duration / (double) AV_TIME_BASE * 1000);
    }
    mChannelCount = codecCtx->ch_layout.nb_channels;

    // DSD 特殊计算逻辑...
    if (mIsSourceDsd) {
        switch (mDsdMode) {
            case DSD_MODE_NATIVE:
                mSampleRate = codecCtx->sample_rate / 4; // Native通常是 sample_rate/8 * 2ch? 需按业务逻辑确认
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

void FFPlayer::decodingLoop() {
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    try {
        while (!mIsExit) {
            // 1. 处理暂停等待
            if (mState == STATE_PAUSED) {
                std::unique_lock<std::mutex> lock(mStateMutex);
                stateCond.wait(lock, [this] {
                    return mState == STATE_PLAYING || mState == STATE_STOPPED || mIsExit;
                });
            }
            if (mIsExit || mState == STATE_STOPPED) break;

            // 2. 处理 Seek
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
                    if (av_seek_frame(fmtCtx, audioStreamIndex, timestamp, AVSEEK_FLAG_BACKWARD) >=
                        0) {
                        if (codecCtx) avcodec_flush_buffers(codecCtx);
                        LOGD("Seek success to %ld ms", target);
                    }
                }
            }

            // 3. 读取 Packet
            int ret = av_read_frame(fmtCtx, packet);
            if (ret < 0) {
                if (ret == AVERROR_EOF) {
                    LOGD("Decode loop EOF");
                    // 播放完成，退出循环
                    break;
                } else {
                    // 读取出错（网络抖动等），稍微 sleep 一下重试，避免 CPU 100%
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
                    continue;
                }
            }

            if (packet->stream_index != audioStreamIndex) {
                av_packet_unref(packet);
                continue;
            }

            // 4. 更新时间戳与 CUE 检查
            if (packet->pts != AV_NOPTS_VALUE) {
                mCurrentPositionMs = packet->pts * av_q2d(*timeBase) * 1000;
            }

            if (mEndTimeMs > 0 && mCurrentPositionMs >= mEndTimeMs) {
                LOGD("Reached CUE end time. Stopping.");
                av_packet_unref(packet);
                break;
            }

            updateProgress();

            // 5. 解码处理
            if (mIsSourceDsd && mDsdMode != DSD_MODE_D2P) {
                handleDsdAudioPacket(packet, frame);
            } else {
                handlePcmAudioPacket(packet, frame);
            }

            av_packet_unref(packet);
        }

        // 循环结束清理
        av_frame_free(&frame);
        av_packet_free(&packet);

        // 只有非手动停止且未出错的情况下，才回调 onComplete
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
        if (!codecCtx) {
            LOGE("handlePcmAudioPacket: codecCtx is null, skipping");
            return;
        }
        if (!packet) {
            LOGE("handlePcmAudioPacket: packet is null, skipping");
            return;
        }
        if (!frame) {
            LOGE("handlePcmAudioPacket: frame is null, skipping");
            return;
        }
        int ret = avcodec_send_packet(codecCtx, packet);
        if (ret < 0) {
            if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) LOGE("Send packet error");
            return;
        }

        while (ret >= 0) {
            ret = avcodec_receive_frame(codecCtx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) {
                LOGE("Receive frame error");
                break;
            }

            if (swrCtx) {
                // 1. 计算输出采样数 (包含 buffer 中的延迟数据)
                int actualOutRate = mIsSourceDsd ? mTargetD2pSampleRate : codecCtx->sample_rate;
                // 这是一个估算上界
                int out_samples = av_rescale_rnd(
                        swr_get_delay(swrCtx, codecCtx->sample_rate) + frame->nb_samples,
                        actualOutRate,
                        codecCtx->sample_rate,
                        AV_ROUND_UP
                );

                if (out_samples > 0) {
                    // 2. 计算所需字节数，确保缓冲区安全
                    int outSampleSize = av_get_bytes_per_sample(outputSampleFormat);
                    int channels = 2; // 我们在 initSwrContext 设置了输出是 Stereo
                    int requiredBufferSize = out_samples * outSampleSize * channels;
                    ensureBufferCapacity(requiredBufferSize);
                    if (outBuffer.empty()) return; // alloc failed
                    auto *rawBuffer = outBuffer.data();
                    uint8_t *outData[2] = {rawBuffer, nullptr};

                    // 3. 执行转换
                    int convertedSamples = swr_convert(swrCtx,
                                                       outData,
                                                       out_samples,
                                                       (const uint8_t **) frame->data,
                                                       frame->nb_samples);

                    if (convertedSamples > 0) {
                        int size = convertedSamples * outSampleSize * channels;
                        if (mCallback) {
                            mCallback->onAudioData(rawBuffer, size);
                        }
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

        if (!codecCtx) {
            LOGE("handleDsdAudioPacket: codecCtx is null, skipping");
            return;
        }
        if (!packet) {
            LOGE("handleDsdAudioPacket: packet is null, skipping");
            return;
        }
        if (!frame) {
            LOGE("handleDsdAudioPacket: frame is null, skipping");
            return;
        }
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
        if (outputSize <= 0) {
            return;
        }

        if (mCallback) {
            if (outputSize > outBuffer.size()) {
                LOGD("DSD output size %d exceeds buffer size %d, truncating", outputSize,
                     outBuffer.size());
                outputSize = outBuffer.size();
            }

            mCallback->onAudioData(rawBuffer, outputSize);
        }
    } catch (const std::exception &e) {
        LOGE("handleDsdAudioPacket: %s", e.what());
    }
}

void FFPlayer::updateProgress() {
    long relativePosition = mCurrentPositionMs - mStartTimeMs;
    if (relativePosition < 0) relativePosition = 0;

    long virtualDuration = getDuration();
    if (virtualDuration <= 0) virtualDuration = 1; // 避免除零

    float progress = (float) relativePosition / (float) virtualDuration;
    if (progress > 1.0f) progress = 1.0f; // 修正 clamp

    // 降低回调频率，或者交给上层去限频，这里直接回调
    if (mCallback) {
        mCallback->onProgress(0, relativePosition, virtualDuration, progress);
    }
}

bool FFPlayer::isExit() const {
    return mIsExit.load();
}

int FFPlayer::interrupt_cb(void *ctx) {
    auto *player = (FFPlayer *) ctx;
    // 如果返回 1，FFmpeg 会立即中断当前操作并返回错误
    if (player && player->isExit()) {
        return 1;
    }
    return 0;
}

void FFPlayer::ensureBufferCapacity(size_t requiredSize) {
    size_t safeSize = requiredSize + AV_INPUT_BUFFER_PADDING_SIZE;
    if (safeSize > outBuffer.size()) {
        size_t newSize = std::max(safeSize, outBuffer.size() * 3 / 2);
        LOGD("ensureBufferCapacity: requiredSize %d, safeSize %d, newSize %d", requiredSize,
             safeSize, newSize);
        outBuffer.resize(newSize);
    }
}
