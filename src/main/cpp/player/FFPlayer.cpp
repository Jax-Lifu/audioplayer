#include "FFPlayer.h"

#define DEFAULT_BUFFER_SIZE 2 * 1024 * 1024

// 辅助函数：当前时间毫秒
static int64_t getNowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

static inline void msToMinSec(long ms, long &min, long &sec) {
    long totalSec = ms / 1000;
    min = totalSec / 60;
    sec = totalSec % 60;
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
    LOGD("setDataSource mUrl %s mStartTimeMs %ld mEndTimeMs %ld", mUrl.c_str(), mStartTimeMs,
         mEndTimeMs);
}

void FFPlayer::setNextDataSource(const char *path,
                                 const std::map<std::string, std::string> &headers,
                                 int64_t startPositon, int64_t endPosition) {
    if (path != nullptr) {
        mNextUrl = path;
        mNextHeaders = headers;
        mNextStartTimeMs = startPositon;
        mNextEndTimeMs = endPosition;
        LOGD("Set Next DataSource: %s, Start: %ld", mNextUrl.c_str(), mNextStartTimeMs);
    } else {
        mNextUrl.clear();
    }
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

bool FFPlayer::prepareInternal() {
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
                LOGD("Set User-Agent: %s", pair.second.c_str());
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
            return false;
        }
        LOGE("Open input failed: %d path %s", ret, mUrl.c_str());
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_ERROR;
        if (mCallback) mCallback->onError(-1, "Open input failed");
        releaseFFmpeg();
        return false;
    }

    if ((ret = avformat_find_stream_info(fmtCtx, nullptr)) < 0) {
        if (mIsExit.load()) {
            releaseFFmpeg();
            return false;
        }
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_ERROR;
        if (mCallback) mCallback->onError(-2, "Find stream info failed");
        releaseFFmpeg();
        return false;
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
        return false;
    }

    // 打开解码器
    {
        AVCodecParameters *codecPar = fmtCtx->streams[audioStreamIndex]->codecpar;
        const AVCodec *codec = avcodec_find_decoder(codecPar->codec_id);
        if (!codec) {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_ERROR;
            releaseFFmpeg();
            return false;
        }
        codecCtx = avcodec_alloc_context3(codec);
        avcodec_parameters_to_context(codecCtx, codecPar);

        if ((ret = avcodec_open2(codecCtx, codec, nullptr)) < 0) {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_ERROR;
            releaseFFmpeg();
            return false;
        }

        // DTS-in-FLAC 检测
        if (codecCtx->codec_id == AV_CODEC_ID_FLAC) {
            AVPacket *testPkt = av_packet_alloc();
            AVFrame *testFrame = av_frame_alloc();

            if (av_read_frame(fmtCtx, testPkt) >= 0) {
                if (testPkt->stream_index == audioStreamIndex) {
                    if (avcodec_send_packet(codecCtx, testPkt) >= 0) {
                        if (avcodec_receive_frame(codecCtx, testFrame) >= 0) {
                            if (testFrame->nb_samples > 4 && testFrame->data[0]) {

                                // ============ 修正：跳过前导零，查找 DTS 同步字 ============
                                int dataSize = av_samples_get_buffer_size(
                                        nullptr, testFrame->ch_layout.nb_channels,
                                        testFrame->nb_samples,
                                        (AVSampleFormat) testFrame->format, 1);

                                uint8_t *data = testFrame->data[0];
                                bool foundDts = false;
                                int dtsOffset = -1;

                                // 在前 4KB 范围内查找同步字（通常在前几百字节）
                                int searchLimit = std::min(dataSize - 4, 4096);

                                for (int offset = 0; offset < searchLimit; offset += 4) {
                                    uint32_t sync = *(uint32_t *) (data + offset);

                                    // DTS Core 同步字
                                    bool isDtsCore = (sync == 0x7FFE8001 || sync == 0xFE7F0180 ||
                                                      sync == 0x01807FFE || sync == 0x0180FE7F);

                                    // DTS-HD 同步字
                                    bool isDtsHD = (sync == 0x64582025 || sync == 0x25205864 ||
                                                    sync == 0xFF1F00E8 || sync == 0xE8001FFF);

                                    if (isDtsCore || isDtsHD) {
                                        foundDts = true;
                                        dtsOffset = offset;
                                        LOGD("Found DTS sync 0x%08X at offset %d (%s)",
                                             sync, offset, isDtsHD ? "DTS-HD" : "DTS-Core");
                                        break;
                                    }

                                    // 如果遇到非零但不是 DTS 同步字，可能不是 DTS-in-FLAC
                                    if (sync != 0 && offset > 1024) {
                                        break; // 避免无限搜索
                                    }
                                }

                                if (foundDts) {
                                    mIsDtsInFlac = true;
                                    mDtsDataOffset = dtsOffset; // 保存偏移量

                                    // 打开 DTS 解码器
                                    const AVCodec *dtsCodec = avcodec_find_decoder(AV_CODEC_ID_DTS);
                                    if (dtsCodec) {
                                        dtsCodecCtx = avcodec_alloc_context3(dtsCodec);
                                        dtsCodecCtx->request_sample_fmt = AV_SAMPLE_FMT_S16;

                                        AVDictionary *opts = nullptr;
                                        av_dict_set(&opts, "request_channel_layout", "stereo", 0);

                                        if (avcodec_open2(dtsCodecCtx, dtsCodec, &opts) < 0) {
                                            LOGE("Failed to open DTS decoder");
                                            avcodec_free_context(&dtsCodecCtx);
                                            mIsDtsInFlac = false;
                                        } else {
                                            dtsFrame = av_frame_alloc();
                                            if (dtsParserCtx) av_parser_close(dtsParserCtx);
                                            dtsParserCtx = av_parser_init(AV_CODEC_ID_DTS);
                                            LOGD("DTS decoder opened, data starts at offset %d",
                                                 dtsOffset);
                                        }
                                        av_dict_free(&opts);
                                    }
                                } else {
                                    LOGD("No DTS sync found in first %d bytes", searchLimit);
                                }
                            }
                        }
                    }
                }
            }

            av_packet_free(&testPkt);
            av_frame_free(&testFrame);

            // Seek 回起始
            avformat_seek_file(fmtCtx, audioStreamIndex, INT64_MIN, 0, INT64_MAX, 0);
            avcodec_flush_buffers(codecCtx);
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
            return false;
        }
    }

    extractAudioInfo();
    if (mStartTimeMs > 0) {
        mCurrentPositionMs.store(mStartTimeMs);
        mAudioClockMs = (double) mStartTimeMs;
    } else {
        mAudioClockMs = 0.0;
    }

    return true;
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

    if (prepareInternal()) {
        {
            std::lock_guard<std::mutex> lock(mStateMutex);
            if (mIsExit.load()) {
                releaseFFmpeg();
                return;
            }
            mState = STATE_PREPARED;
        }
        if (mCallback) mCallback->onPrepared();
    } else {
        releaseFFmpeg();
    }
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
        if (!mProgressThread) mProgressThread = new std::thread(&FFPlayer::progressHeartbeat, this);
        stateCond.notify_all();
    }
}

void FFPlayer::pause() {
    if (mState == STATE_PLAYING || mState == STATE_BUFFERING) {
        std::lock_guard<std::mutex> lock(mStateMutex);

        // ⚠️ 先计算并冻结当前精确位置
        int64_t basePts = mBasePtsMs.load();
        int64_t baseSys = mBaseSystemMs.load();
        int64_t frozenPosition = basePts;

        if (baseSys > 0) {
            int64_t elapsed = getNowMs() - baseSys;
            if (elapsed > 0 && elapsed < 5000) {
                frozenPosition = basePts + elapsed;
            }
        }

        // 立即保存冻结位置
        mCurrentPositionMs.store(frozenPosition);
        mAudioClockMs = (double) frozenPosition;  // ⚠️ 关键：同步音频时钟

        mState = STATE_PAUSED;
        mBaseSystemMs.store(0);

        LOGD("Paused at %ld ms (basePts=%ld, elapsed=%ld)",
             frozenPosition, basePts, baseSys > 0 ? (getNowMs() - baseSys) : 0);
    }
}

void FFPlayer::resume() {
    if (mState == STATE_PAUSED) {
        std::lock_guard<std::mutex> lock(mStateMutex);

        int64_t resumePosition = mCurrentPositionMs.load();
        mBasePtsMs.store(resumePosition);
        mAudioClockMs = (double) resumePosition;

        if (codecCtx && codecCtx->sample_rate > 0) {
            mTotalSamplesPlayed.store(
                    (resumePosition * codecCtx->sample_rate) / 1000
            );
        }

        mBaseSystemMs.store(0);
        mUseManualClock.store(true);  // 启用手动时钟模式
        mState = STATE_PLAYING;
        stateCond.notify_all();

        LOGD("Resumed from %ld ms (samples=%ld)",
             resumePosition, mTotalSamplesPlayed.load());
    }
}

void FFPlayer::stop() {
    LOGD("FFPlayer stop");
    mIsExit.store(true);
    audioQueue.abort();
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_STOPPED;
        stateCond.notify_all();
    }
    {
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
    if (mProgressThread && mProgressThread->joinable()) {
        mProgressThread->join();
        delete mProgressThread;
        mProgressThread = nullptr;
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
    LOGD("seek %ld ms to %ld ms", ms, targetAbsoluteMs);

    if (mState != STATE_IDLE && mState != STATE_ERROR && mState != STATE_STOPPED) {
        std::lock_guard<std::mutex> lock(mSeekMutex);
        mSeekTargetMs = targetAbsoluteMs;
        mIsSeeking.store(true);
        stateCond.notify_all();
    }
}

// ---------------------------------------------------------------------
// 核心逻辑: readLoop (生产者)
// 修复方案：相对对齐 Seek (Relative Alignment Seek)
// 解决 DSD Header 偏移导致的声道反转/杂音问题
// ---------------------------------------------------------------------
void FFPlayer::readLoop() {
    AVPacket *packet = av_packet_alloc();
    int consecutiveErrors = 0;
    const int MAX_ERRORS = 10;
    long lastReadPosMs = 0;
    int dropFrameCount = 0;

    // -----------------------------------------------------------------
    // [修复点 1] 准确计算 DSD 码率 (ByteRate)
    // -----------------------------------------------------------------
    int64_t dsdByteRate = 0;

    // 优先策略：直接使用容器层面的 bit_rate (通常最准，DSF/DFF 文件头里有)
    if (fmtCtx->bit_rate > 0) {
        dsdByteRate = fmtCtx->bit_rate / 8;
    }
        // 兜底策略：如果容器没有 bit_rate，则通过 sample_rate 推算
    else if (mIsSourceDsd && codecCtx->sample_rate > 0 && codecCtx->ch_layout.nb_channels > 0) {
        // [重要修正]
        // 根据你的 getMediaInfo 逻辑，codecCtx->sample_rate 已经是 (DSD_Rate / 8)
        // 所以这里不需要再除以 8 了，直接乘以声道数即可。
        dsdByteRate = (int64_t) codecCtx->sample_rate * codecCtx->ch_layout.nb_channels;
    }

    LOGD("DSD ByteRate Calculated: %ld", dsdByteRate);

    mAudioDataStartPos.store(-1);

    if (mStartTimeMs > 0) {
        bool dsdSeekDone = false;

        if (mIsSourceDsd && dsdByteRate > 0) {
            // 1. 探测文件头：先读取第一帧，获取数据区的物理起始位置 (Data Start Position)
            //    此时文件指针还在开头，读取到的 packet->pos 就是 Header 的大小
            AVPacket *probePkt = av_packet_alloc();
            int probeRet = av_read_frame(fmtCtx, probePkt);
            if (probeRet >= 0) {
                if (probePkt->stream_index == audioStreamIndex && probePkt->pos >= 0) {
                    mAudioDataStartPos.store(probePkt->pos);
                    LOGD("DSD Start Probe: Header Size / Data Start Pos = %ld", probePkt->pos);
                }
                av_packet_unref(probePkt);
            } else {
                LOGW("DSD Start Probe failed: ret=%d", probeRet);
            }
            av_packet_free(&probePkt);

            // 2. 如果探测成功，执行 Byte Seek
            if (mAudioDataStartPos.load() >= 0) {
                double targetSeconds = mStartTimeMs / 1000.0;
                int64_t relativeOffset = (int64_t) (targetSeconds * dsdByteRate);

                // 对齐处理 (4096 or block_align)
                int align = codecCtx->block_align > 0 ? codecCtx->block_align : 4096;
                relativeOffset -= (relativeOffset % align);

                int64_t finalBytePos = mAudioDataStartPos.load() + relativeOffset;

                // 边界检查
                int64_t fileSize = avio_size(fmtCtx->pb);
                if (fileSize > 0 && finalBytePos >= fileSize) finalBytePos = fileSize - align;
                if (finalBytePos < mAudioDataStartPos.load())
                    finalBytePos = mAudioDataStartPos.load();

                // 执行 IO Seek (物理字节跳转)
                if (avio_seek(fmtCtx->pb, finalBytePos, SEEK_SET) >= 0) {
                    avio_flush(fmtCtx->pb); // 清空 IO 缓冲
                    if (fmtCtx->pb) {
                        fmtCtx->pb->eof_reached = 0;
                        fmtCtx->pb->error = 0;
                    }

                    // 刷新解码器 (防止残余)
                    avcodec_flush_buffers(codecCtx);

                    // 扔掉 Seek 后的头两帧，避免可能的 Pop 音
                    dropFrameCount = 2;
                    dsdSeekDone = true;

                    // 强制同步时钟，防止进度条跳回 0
                    mBasePtsMs.store(mStartTimeMs);
                    mCurrentPositionMs.store(mStartTimeMs);
                    mAudioClockMs = (double) mStartTimeMs;
                    lastReadPosMs = mStartTimeMs;

                    LOGD("DSD Start Byte Seek Success: to %ld (Time: %ld ms)", finalBytePos,
                         mStartTimeMs);
                }
            }
        }

        // 3. 兜底逻辑：如果不是 DSD 或 Byte Seek 失败，使用原来的 Time Seek
        if (!dsdSeekDone) {
            int64_t targetPts = av_rescale(mStartTimeMs, timeBase->den, timeBase->num * 1000LL);
            avformat_seek_file(fmtCtx, audioStreamIndex, INT64_MIN, targetPts, INT64_MAX, 0);
        }
    }

    while (!mIsExit.load()) {
        // 1. Seek 处理逻辑 (保持不变)
        if (mIsSeeking.load()) {
            long targetMs = -1;
            {
                std::lock_guard<std::mutex> lock(mSeekMutex);
                if (mIsSeeking.load()) {
                    targetMs = mSeekTargetMs;
                    mIsSeeking.store(false);
                    mSeekTargetMs = -1;
                }
            }

            if (targetMs >= 0) {
                LOGD("Seek requested to %ld ms", targetMs);
                mIsEOF.store(false);
                consecutiveErrors = 0;
                audioQueue.flush();
                mFlushCodec.store(true);
                bool seekSuccess = false;

                // --- DSD 线性字节 Seek ---
                if (mIsSourceDsd && mAudioDataStartPos.load() > 0 && dsdByteRate > 0) {
                    double targetSeconds = targetMs / 1000.0;
                    int64_t relativeOffset = (int64_t) (targetSeconds * dsdByteRate);

                    int align = codecCtx->block_align > 0 ? codecCtx->block_align : 4096;
                    relativeOffset -= (relativeOffset % align);

                    int64_t finalBytePos = mAudioDataStartPos.load() + relativeOffset;
                    int64_t fileSize = avio_size(fmtCtx->pb);
                    if (fileSize > 0 && finalBytePos >= fileSize) finalBytePos = fileSize - align;
                    if (finalBytePos < mAudioDataStartPos.load())
                        finalBytePos = mAudioDataStartPos.load();

                    if (avio_seek(fmtCtx->pb, finalBytePos, SEEK_SET) >= 0) {
                        avio_flush(fmtCtx->pb);
                        if (fmtCtx->pb) {
                            fmtCtx->pb->eof_reached = 0;
                            fmtCtx->pb->error = 0;
                        }
                        dropFrameCount = 2;
                        seekSuccess = true;
                        LOGD("DSD Byte Seek Success: to %ld", finalBytePos);
                    }
                }

                if (!seekSuccess) {
                    int64_t targetPts = av_rescale(targetMs, timeBase->den, timeBase->num * 1000LL);
                    if (avformat_seek_file(fmtCtx, audioStreamIndex, INT64_MIN, targetPts,
                                           INT64_MAX, 0) < 0) {
                        av_seek_frame(fmtCtx, audioStreamIndex, targetPts, AVSEEK_FLAG_BACKWARD);
                    }
                    dropFrameCount = 0;
                }

                if (seekSuccess) {
                    mUseManualClock.store(false);  // 只有精准 Seek 成功才敢切回 PTS 模式
                }

                if (codecCtx && codecCtx->sample_rate > 0) {
                    int64_t calculationRate = codecCtx->sample_rate;

                    if (mIsDtsInFlac && dtsCodecCtx && dtsCodecCtx->sample_rate > 0) {
                        calculationRate = dtsCodecCtx->sample_rate;
                    }

                    mTotalSamplesPlayed.store(
                            (targetMs * calculationRate) / 1000
                    );
                }
                // ===================================

                mBasePtsMs.store(targetMs);
                mBaseSystemMs.store(0);
                mCurrentPositionMs.store(targetMs);
                lastReadPosMs = targetMs;
            }
            continue;
        }

        if (mState.load() == STATE_STOPPED || mState.load() == STATE_COMPLETED) break;
        if (audioQueue.getSize() > MAX_QUEUE_SIZE) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        // 2. 读取数据
        if (!packet) packet = av_packet_alloc();
        int ret = av_read_frame(fmtCtx, packet);

        if (ret < 0) {
            if (mIsExit.load()) break;
            if (mIsSeeking.load()) {
                consecutiveErrors = 0;
                continue;
            }
            if (ret == AVERROR_EOF || (fmtCtx->pb && fmtCtx->pb->eof_reached)) {
                if (!mIsEOF.load()) mIsEOF.store(true);
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
            continue;
        }

        // 3. 数据处理与 [关键] PTS 修正
        if (packet->stream_index == audioStreamIndex) {

            // 记录起始位置
            if (mIsSourceDsd && mAudioDataStartPos.load() == -1 && packet->pos > 0) {
                // 1. 确保码率已计算
                if (dsdByteRate <= 0 && codecCtx->sample_rate > 0) {
                    dsdByteRate = (int64_t) codecCtx->sample_rate * codecCtx->ch_layout.nb_channels;
                }

                // 2. [核心修复] 倒推文件真实的物理起始点 (Time 0)
                // 如果是从 CUE 分轨中间开始播放 (mStartTimeMs > 0)，当前的 packet->pos 实际上对应的是 mStartTimeMs
                // 我们需要减去这部分偏移，让 mAudioDataStartPos 代表文件头（0秒）的位置。
                int64_t calculatedStartPos = packet->pos;

                if (mStartTimeMs > 0 && dsdByteRate > 0) {
                    // 计算 431s 对应的字节偏移量
                    int64_t timeOffsetBytes = (int64_t) (mStartTimeMs / 1000.0 * dsdByteRate);

                    // 倒推回 0秒 时的物理位置
                    if (calculatedStartPos > timeOffsetBytes) {
                        calculatedStartPos -= timeOffsetBytes;
                    } else {
                        // 防御性代码：理论上不应发生，除非 header 极小或计算误差，归零处理
                        calculatedStartPos = 0;
                    }
                    LOGD("DSD CUE Fix: PacketPos=%ld, StartTime=%ld, ByteRate=%ld, CorrectedStartPos=%ld",
                         packet->pos, mStartTimeMs, dsdByteRate, calculatedStartPos);
                }

                mAudioDataStartPos.store(calculatedStartPos);
            }

            if (mIsSourceDsd && dropFrameCount > 0) {
                av_packet_unref(packet);
                dropFrameCount--;
                continue;
            }

            long ptsMs = 0;

            // =================================================================
            // [核心修复] 使用修正后的 dsdByteRate 计算时间
            // =================================================================
            if (mIsSourceDsd && dsdByteRate > 0 && packet->pos >= mAudioDataStartPos.load()) {
                int64_t offset = packet->pos - mAudioDataStartPos.load();

                // 这里计算出的 realTimeSec 之前偏大 8 倍，现在应该正常了
                double realTimeSec = (double) offset / dsdByteRate;
                ptsMs = (long) (realTimeSec * 1000);

                packet->pts = (int64_t) (realTimeSec / av_q2d(*timeBase));
                packet->dts = packet->pts;
            } else if (packet->pts != AV_NOPTS_VALUE) {
                ptsMs = (long) (packet->pts * av_q2d(*timeBase) * 1000);
            }

            if (ptsMs > lastReadPosMs) lastReadPosMs = ptsMs;

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
        consecutiveErrors = 0;
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
        if (mState.load() == STATE_STOPPED ||
            mState.load() == STATE_COMPLETED) {
            break;
        }

        // 1. Seek Flush
        if (mFlushCodec.load()) {
            if (codecCtx) {
                avcodec_flush_buffers(codecCtx);
            }
            if (dtsCodecCtx) {
                avcodec_flush_buffers(dtsCodecCtx);
            }

            if (dtsParserCtx) {
                av_parser_close(dtsParserCtx);
                dtsParserCtx = av_parser_init(AV_CODEC_ID_DTS);
            }
            if (dtsFrame) {
                av_frame_unref(dtsFrame);
            }
            mFlushCodec.store(false);
            isDraining = false;

            mAudioClockMs = (double) mBasePtsMs.load();
            mBaseSystemMs.store(0);
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
                std::string nextUrlLocal;
                {
                    std::lock_guard<std::mutex> lock(mStateMutex);
                    nextUrlLocal = mNextUrl;
                }

                if (!nextUrlLocal.empty()) {
                    LOGD("Gapless transition triggered for: %s", nextUrlLocal.c_str());
                    switchToNextSource();
                    isDraining = false;
                    continue; // 切换完毕，继续消费新的 packet
                }


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
            // EndTime 检查
            {
                int64_t effectiveEndMs = mEndTimeMs;
                bool hasNext = !mNextUrl.empty();

                if (hasNext && mTailSkipMs > 0) {
                    if (effectiveEndMs > 0) {
                        // CUE 分轨模式：从显式 EndTime 提前
                        effectiveEndMs = std::max(mEndTimeMs - mTailSkipMs, mStartTimeMs + 1);
                    } else if (mDurationMs > 0) {
                        // 单文件模式：从总时长推算
                        effectiveEndMs = std::max(mDurationMs - mTailSkipMs, mStartTimeMs + 1);
                    }
                }

                if (effectiveEndMs > 0 && mCurrentPositionMs.load() >= effectiveEndMs) {
                    av_packet_unref(packet);
                    audioQueue.flush();
                    mIsEOF.store(true);
                    LOGD("EndTime triggered: pos=%ld >= effectiveEnd=%ld (rawEnd=%ld, tailSkip=%ld, hasNext=%d)",
                         mCurrentPositionMs.load(), effectiveEndMs, mEndTimeMs, mTailSkipMs, hasNext);
                    continue;
                }
            }
            if (mIsSourceDsd && mDsdMode != DSD_MODE_D2P) handleDsdAudioPacket(packet, frame);
            else handlePcmAudioPacket(packet, frame);

            av_packet_unref(packet);
        }
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
}

void FFPlayer::switchToNextSource() {
    // 1. 安全停止当前的 readThread
    mIsExit.store(true);    // 让 readLoop 退出当前循环
    audioQueue.abort();     // 打断可能阻塞的队列

    if (readThread && readThread->joinable()) {
        readThread->join();
        delete readThread;
        readThread = nullptr;
    }

    // 2. 释放当前的 FFmpeg 资源
    releaseFFmpeg();

    // 3. 变量交接
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mUrl = mNextUrl;
        mHeaders = mNextHeaders;
        mStartTimeMs = mNextStartTimeMs;
        mEndTimeMs = mNextEndTimeMs;

        mNextUrl.clear();
        mNextHeaders.clear();
    }

    // 4. 重置状态
    mIsExit.store(false);
    mIsEOF.store(false);
    mIsSeeking.store(false);
    mFlushCodec.store(false);
    mBasePtsMs.store(0);
    mBaseSystemMs.store(0);
    mAudioDataStartPos.store(-1);
    mTotalSamplesPlayed.store(0);
    mCurrentPositionMs.store(0);
    mAudioClockMs = 0.0;
    audioQueue.flush();
    audioQueue.start();

    // 5. 重新初始化底层的 FFmpeg 解析器
    if (prepareInternal()) {
        LOGD("Gapless switch success, restarting read thread.");
        // 重启读取线程
        readThread = new std::thread(&FFPlayer::readLoop, this);

        if (mCallback) {
            mCallback->onTrackTransition();
        }
    } else {
        LOGE("Gapless switch failed to prepare next source.");
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_ERROR;
        if (mCallback) mCallback->onError(-1, "Gapless transition failed");
    }
}

void FFPlayer::progressHeartbeat() {
    while (!mIsExit.load()) {
        if (mState == STATE_PLAYING) {
            updateProgress();
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
    }
}

void FFPlayer::handlePcmAudioPacket(AVPacket *packet, AVFrame *frame) {
    if (!codecCtx || !frame) return;

    // 1. 发送 FLAC 包并接收解码后的 PCM (其实是 DTS bitstream)
    if (packet) {
        int ret = avcodec_send_packet(codecCtx, packet);
        if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
            LOGE("Send packet error");
            return;
        }
    }

    while (true) {
        int ret = avcodec_receive_frame(codecCtx, frame);
        if (ret < 0) break; // EAGAIN or EOF

        // ============ DTS-in-FLAC 核心修复逻辑 ============
        if (mIsDtsInFlac && dtsCodecCtx && dtsFrame && dtsParserCtx) {

            // A. 准备数据 Buffer (处理 Planar 格式和 Offset)
            uint8_t *inData = nullptr;
            int inSize = 0;
            std::vector<uint8_t> tempBuffer; // 用于 Planar 转换的临时容器

            // 计算有效数据的起始位置
            // 注意：mDtsDataOffset 只有在第一帧(StartPos)有效，后续帧应该为 0
            // 为了安全，如果 offset 超过数据大小，则归零
            int currentOffset = mDtsDataOffset;
            if (currentOffset >= frame->nb_samples * frame->ch_layout.nb_channels * 2) {
                currentOffset = 0;
            }
            // 简单处理：使用一次后归零，避免切掉后续帧的头部
            mDtsDataOffset = 0;

            if (av_sample_fmt_is_planar((AVSampleFormat) frame->format)) {
                // 如果是 Planar，必须先交织成线性 Buffer
                int bytesPerSample = av_get_bytes_per_sample((AVSampleFormat) frame->format);
                int channels = frame->ch_layout.nb_channels;
                int samples = frame->nb_samples;
                int totalBytes = samples * channels * bytesPerSample;

                tempBuffer.resize(totalBytes);
                uint8_t *dst = tempBuffer.data();

                for (int i = 0; i < samples; i++) {
                    for (int ch = 0; ch < channels; ch++) {
                        // 简单的交织逻辑
                        if (frame->data[ch]) {
                            memcpy(dst, frame->data[ch] + i * bytesPerSample, bytesPerSample);
                        }
                        dst += bytesPerSample;
                    }
                }

                // 应用 Offset
                if (currentOffset < totalBytes) {
                    inData = tempBuffer.data() + currentOffset;
                    inSize = totalBytes - currentOffset;
                }
            } else {
                // 如果是 Packed，直接使用 data[0]
                int totalBytes = frame->linesize[0];
                if (currentOffset < totalBytes) {
                    inData = frame->data[0] + currentOffset;
                    inSize = totalBytes - currentOffset;
                }
            }

            // B. 使用 Parser 循环解析所有 DTS 帧
            // 一个 FLAC 帧可能包含 2-4 个 DTS 帧，必须全部解出来！
            if (inData && inSize > 0) {
                uint8_t *curPtr = inData;
                int curSize = inSize;

                AVPacket *parsedPkt = av_packet_alloc();

                while (curSize > 0) {
                    uint8_t *outData = nullptr;
                    int outSize = 0;

                    // 解析器核心：从流中切出一个完整的 DTS 包
                    int len = av_parser_parse2(dtsParserCtx, dtsCodecCtx,
                                               &outData, &outSize,
                                               curPtr, curSize,
                                               AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);

                    curPtr += len;
                    curSize -= len;

                    if (outSize > 0 && outData) {
                        // 构造 DTS Packet
                        parsedPkt->data = outData;
                        parsedPkt->size = outSize;

                        // 发送给 DTS 解码器
                        if (avcodec_send_packet(dtsCodecCtx, parsedPkt) >= 0) {
                            while (avcodec_receive_frame(dtsCodecCtx, dtsFrame) >= 0) {
                                // 成功解码一帧 DTS，送去播放
                                // 注意：此时我们把 dtsFrame 传给 processPcmFrame
                                processPcmFrame(dtsFrame);
                            }
                        }
                    }
                }
                av_packet_free(&parsedPkt);
            }

            // 当前 FLAC 帧已完全处理完毕（转换为 DTS 并播放了），继续下一个 FLAC 帧
            continue;
        }

        // 普通 PCM 处理 (非 DTS)
        processPcmFrame(frame);
    }
}

void FFPlayer::processPcmFrame(AVFrame *frame) {
    if (!frame) return;

    double durationMs = 0;
    if (frame->sample_rate > 0) {
        durationMs = (frame->nb_samples * 1000.0) / frame->sample_rate;
    }

    // --- 1. 时钟同步逻辑 (保持不变) ---
    if (mState == STATE_PLAYING) {
        bool forceManualClock = mIsDtsInFlac || mUseManualClock.load();

        if (forceManualClock && frame->sample_rate > 0) {
            mTotalSamplesPlayed.fetch_add(frame->nb_samples);
            mAudioClockMs = (mTotalSamplesPlayed.load() * 1000.0) / frame->sample_rate;
        } else if (mAudioClockMs == 0.0 && frame->pts != AV_NOPTS_VALUE) {
            mAudioClockMs = frame->pts * av_q2d(*timeBase) * 1000.0;
        } else {
            // 普通模式下的 PTS 矫正
            if (frame->pts != AV_NOPTS_VALUE) {
                double ptsMs = frame->pts * av_q2d(*timeBase) * 1000.0;
                double diff = std::abs(ptsMs - mAudioClockMs);
                if (diff > 10000.0) {
                    // 时间差异太大才同步，微小的差异忽略，保持平滑
                    mAudioClockMs = ptsMs;
                }
            }
        }
        mBasePtsMs.store((int64_t) mAudioClockMs);
        mBaseSystemMs.store(getNowMs());

        if (!forceManualClock) {
            mAudioClockMs += durationMs;
        }
    }

    if (frame->nb_samples <= 0) return;

    // --- 2. 修正输入 Frame 的布局缺失 ---
    if (frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || frame->ch_layout.nb_channels <= 0) {
        // 如果解码出来没有布局，给一个默认值
        av_channel_layout_default(&frame->ch_layout, frame->ch_layout.nb_channels);
    }

    // --- 3. 检查数据指针有效性 ---
    bool isPlanar = av_sample_fmt_is_planar((AVSampleFormat) frame->format);
    int planesToCheck = isPlanar ? frame->ch_layout.nb_channels : 1;
    bool hasBadPointer = false;
    if (!frame->extended_data) hasBadPointer = true;
    else {
        for (int i = 0; i < planesToCheck; i++) {
            if (!frame->extended_data[i]) {
                hasBadPointer = true;
                break;
            }
        }
    }
    if (hasBadPointer) return;

    // =========================================================================
    // [核心修复] 强制对齐声卡参数
    // 不管输入是什么（6ch, 48k...），输出必须强制转为声卡初始化的参数（2ch, 44.1k）
    // =========================================================================

    // 1. 目标采样率：强制使用 Player 初始化时的采样率 (mSampleRate)
    // 即使 DTS 是 48k/44.1k，如果 mSampleRate 是 44.1k，我们都要输出 44.1k
    int targetSampleRate = mIsSourceDsd ? mTargetD2pSampleRate : mSampleRate;
    if (targetSampleRate <= 0) targetSampleRate = 44100; // 绝对兜底

    // 2. 目标声道布局：强制使用立体声 (Stereo/2ch)
    // 因为你的声卡是按 2声道打开的。如果这里不强制，6声道数据喂给2声道声卡 = 3倍速播放。
    AVChannelLayout targetLayout;
    av_channel_layout_default(&targetLayout, 2); // 强制定义为 2 声道

    bool swrNeedsReinit = false;

    // 检查是否需要重新初始化 Swr
    // 只要输入变了，或者 Swr 还没创建，就重置
    if (!swrCtx ||
        mSwrInSampleRate != frame->sample_rate ||
        mSwrInFormat != frame->format ||
        mSwrInChannels != frame->ch_layout.nb_channels) {
        swrNeedsReinit = true;
    }

    if (swrNeedsReinit) {
        if (swrCtx) {
            swr_free(&swrCtx);
            swrCtx = nullptr;
        }

        swrCtx = swr_alloc();
        if (!swrCtx) {
            av_channel_layout_uninit(&targetLayout);
            return;
        }

        // --- 配置 INPUT (根据当前解码出来的 Frame) ---
        // 告诉 Swr：输入是 6 声道，float/s32, 44100/48000Hz
        av_opt_set_chlayout(swrCtx, "in_chlayout", &frame->ch_layout, 0);
        av_opt_set_int(swrCtx, "in_sample_rate", frame->sample_rate, 0);
        av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", (AVSampleFormat) frame->format, 0);

        // --- 配置 OUTPUT (强制固定为声卡参数) ---
        // 告诉 Swr：无论输入如何，请给我转换成 2 声道，S16，44100Hz
        av_opt_set_chlayout(swrCtx, "out_chlayout", &targetLayout, 0);
        av_opt_set_int(swrCtx, "out_sample_rate", targetSampleRate, 0);
        av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", outputSampleFormat, 0); // 通常是 S16

        LOGD("Swr Re-init: In[%dHz %dch] -> Out[%dHz 2ch(Stereo)]",
             frame->sample_rate, frame->ch_layout.nb_channels, targetSampleRate);

        if (swr_init(swrCtx) < 0) {
            LOGE("Failed to swr_init");
            swr_free(&swrCtx);
            av_channel_layout_uninit(&targetLayout);
            return;
        }

        // 更新缓存状态
        mSwrInSampleRate = frame->sample_rate;
        mSwrInFormat = frame->format;
        mSwrInChannels = frame->ch_layout.nb_channels;
    }

    // --- 执行转换 ---
    if (swrCtx) {
        // 计算转换后的样本数量
        // 这里的 out_samples 是“每个声道”的样本数
        int out_samples = av_rescale_rnd(
                swr_get_delay(swrCtx, frame->sample_rate) + frame->nb_samples,
                targetSampleRate,
                frame->sample_rate,
                AV_ROUND_UP
        );

        if (out_samples > 0) {
            int outSampleSize = av_get_bytes_per_sample(outputSampleFormat);
            int outChannels = 2; // 我们已经强制目标为 2 声道

            // 总字节数 = 样本数 * 每个样本字节数 * 声道数
            int requiredBufferSize = out_samples * outSampleSize * outChannels;

            ensureBufferCapacity(requiredBufferSize);
            if (outBuffer.empty()) {
                av_channel_layout_uninit(&targetLayout);
                return;
            }

            uint8_t *rawBuffer = outBuffer.data();
            uint8_t *outData[2] = {rawBuffer, nullptr};
            const uint8_t **inData = (const uint8_t **) frame->extended_data;

            // 执行转换 (Downmix 6ch -> 2ch 就在这里自动发生)
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

    av_channel_layout_uninit(&targetLayout);
}


void FFPlayer::handleDsdAudioPacket(AVPacket *packet, AVFrame *frame) {
    if (!codecCtx || !frame) return;
    // DSD 模式一般不需要 Drain 处理残余帧，因为没有 buffer delay
    if (!packet) return;

    if (mState == STATE_PLAYING && packet->pts != AV_NOPTS_VALUE) {
        mBasePtsMs.store((int64_t) (packet->pts * av_q2d(*timeBase) * 1000));
        mBaseSystemMs.store(getNowMs());
    }

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
    int64_t basePts = mBasePtsMs.load();
    int64_t baseSys = mBaseSystemMs.load();
    int64_t currentPosMs = mCurrentPositionMs.load();

    // 如果在播放状态且有有效的时间基准
    if (mState == STATE_PLAYING && baseSys > 0) {
        int64_t now = getNowMs();
        int64_t elapsed = now - baseSys;

        // 扩大时间范围限制，处理系统时间跳变
        if (elapsed > 0 && elapsed < 10000) {  // 改为 10 秒
            currentPosMs = basePts + elapsed;
        } else if (elapsed >= 10000) {
            // 时间跳变过大，可能是系统时间异常或长时间暂停
            LOGW("Large time jump detected: elapsed=%ld, resetting time base", elapsed);
            currentPosMs = basePts;
            mBaseSystemMs.store(now);
        } else {
            currentPosMs = basePts;
        }

        mCurrentPositionMs.store(currentPosMs);
    }
        // 暂停状态使用上次保存的位置
    else if (mState == STATE_PAUSED) {
        // currentPosMs 已经是暂停时保存的值，不需要修改
    }

    long relativePosition = (long) (currentPosMs - mStartTimeMs);
    if (relativePosition < 0) relativePosition = 0;

    long virtualDuration = getDuration();
    if (virtualDuration <= 0) virtualDuration = 1;

    float progress = (float) relativePosition / (float) virtualDuration;
    if (progress > 1.0f) progress = 1.0f;

//    LOGD("State=%d, currentPosMs=%ld, basePts=%ld, baseSys=%ld, relativePos=%ld, progress=%.2f",
//         mState.load(), currentPosMs, basePts, baseSys, relativePosition, progress);

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
#ifdef DEBUG
    long dMin, dSec, sMin, sSec;
    msToMinSec(mDurationMs, dMin, dSec);
    msToMinSec(mStartTimeMs, sMin, sSec);

    LOGD(
            "File Duration: %ld ms (%02ld:%02ld), Track Start: %ld ms (%02ld:%02ld)",
            mDurationMs, dMin, dSec,
            mStartTimeMs, sMin, sSec
    );
#endif
}

void FFPlayer::releaseFFmpeg() {
    if (dtsParserCtx) {
        av_parser_close(dtsParserCtx);
        dtsParserCtx = nullptr;
    }
    if (dtsFrame) {
        av_frame_free(&dtsFrame);
        dtsFrame = nullptr;
    }
    if (dtsCodecCtx) {
        avcodec_free_context(&dtsCodecCtx);
        dtsCodecCtx = nullptr;
    }
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

MediaInfo FFPlayer::getMediaInfo() const {
    MediaInfo info;

    if (!codecCtx) return info;

    info.channels = codecCtx->ch_layout.nb_channels;

    // 获取原始 codec 名称 (例如: "dsd_lsbf", "pcm_s16le", "flac")
    const AVCodecDescriptor *desc = avcodec_descriptor_get(codecCtx->codec_id);
    std::string rawFormat = (desc && desc->name) ? desc->name : "unknown";

    // 判断是否为 DSD
    bool isDsd = (codecCtx->codec_id == AV_CODEC_ID_DSD_LSBF ||
                  codecCtx->codec_id == AV_CODEC_ID_DSD_MSBF ||
                  codecCtx->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
                  codecCtx->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR);

    if (isDsd) {
        // --- DSD 规格处理 ---
        info.bitDepth = 1;

        // 修正采样率 (FFmpeg 报告的是 ByteRate，需要 * 8)
        info.sampleRate = codecCtx->sample_rate * 8;

        // 计算 DSD 规格名称 (DSD64, DSD128...)
        // 标准 DSD 基频是 44100 Hz
        long multiple = info.sampleRate / 44100;

        // 容错处理：处理 48kHz 基频的 DSD (较少见，但存在)
        if (multiple == 0 && info.sampleRate > 0) {
            multiple = info.sampleRate / 48000;
        }

        if (multiple >= 512) {
            info.format = "DSD512";
        } else if (multiple >= 256) {
            info.format = "DSD256";
        } else if (multiple >= 128) {
            info.format = "DSD128";
        } else {
            info.format = "DSD64";
        }

    } else {
        // --- PCM / 其他格式处理 ---
        info.sampleRate = codecCtx->sample_rate;

        // 计算位深
        if (codecCtx->bits_per_raw_sample > 0) {
            info.bitDepth = codecCtx->bits_per_raw_sample;
        } else {
            info.bitDepth = av_get_bytes_per_sample(codecCtx->sample_fmt) * 8;
        }

        // --- 格式名称标准化 ---
        // 1. 如果是 PCM 系列 (pcm_s16le, pcm_f32be 等)，统一显示 "PCM"
        if (rawFormat.find("pcm") == 0) {
            info.format = "PCM";
        } else {
            info.format = rawFormat;
        }
    }

    // --- 比特率计算 ---
    if (fmtCtx && fmtCtx->bit_rate > 0) {
        info.bitrate = fmtCtx->bit_rate;
    } else if (codecCtx && codecCtx->bit_rate > 0) {
        info.bitrate = codecCtx->bit_rate;
    } else {
        info.bitrate = (long) info.sampleRate * info.channels * info.bitDepth;
    }

    // --- 1. 先赋予默认值 (兜底策略) ---
    info.sourceId = mUrl;
    std::string defaultTitle = "Unknown Title";
    if (!mUrl.empty()) {
        // 1. 找到最后一个斜杠 '/' 或 '\'，截取后面的部分
        size_t slashPos = mUrl.find_last_of("/\\");
        defaultTitle = (slashPos != std::string::npos) ? mUrl.substr(slashPos + 1) : mUrl;

//        size_t dotPos = defaultTitle.find_last_of('.');
//        if (dotPos != std::string::npos && dotPos > 0) {
//            defaultTitle = defaultTitle.substr(0, dotPos);
//        }
    }
    info.title = defaultTitle;
    info.artist = "Unknown Artist";
    info.album = "Unknown Album";

    // --- 2. 尝试提取真实数据并覆盖 ---
    if (fmtCtx && fmtCtx->metadata) {
        AVDictionaryEntry *tag = nullptr;

        // 获取 Title (改为 MATCH_CASE_OPEN 以忽略大小写)
        tag = av_dict_get(fmtCtx->metadata, "title", nullptr, AV_DICT_IGNORE_SUFFIX);
        if (tag && tag->value && tag->value[0] != '\0') {
            info.title = tag->value;
        }

        // 获取 Artist
        tag = av_dict_get(fmtCtx->metadata, "artist", nullptr, AV_DICT_IGNORE_SUFFIX);
        if (tag && tag->value && tag->value[0] != '\0') {
            info.artist = tag->value;
        } else {
            // 尝试回退到 Album Artist
            tag = av_dict_get(fmtCtx->metadata, "album_artist", nullptr, AV_DICT_IGNORE_SUFFIX);
            if (tag && tag->value && tag->value[0] != '\0') {
                info.artist = tag->value;
            }
        }

        // 获取 Album
        tag = av_dict_get(fmtCtx->metadata, "album", nullptr, AV_DICT_IGNORE_SUFFIX);
        if (tag && tag->value && tag->value[0] != '\0') {
            info.album = tag->value;
        }
    }
    return info;
}

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