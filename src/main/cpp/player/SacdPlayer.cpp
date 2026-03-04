#include "SacdPlayer.h"
#include "FFmpegNetworkStream.h"


// 1. 静态回调函数
static int scarletbook_audio_callback(void *context, uint8_t *data, size_t size, int track_index) {
    if (!context) {
        LOGE("scarletbook_audio_callback: context is null");
        return -1;
    }
    SacdPlayer *player = static_cast<SacdPlayer *>(context);
    return player->onDecodeData(data, size, track_index);;
}

static void scarletbook_progress_callback(
        void *context, int track,
        uint32_t current, uint32_t total, float progress) {
    if (!context) {
        LOGE("scarletbook_progress_callback: context is null");
        return;
    }
    SacdPlayer *player = static_cast<SacdPlayer *>(context);
    player->onDecodeProgress(track, current, total, progress);
}

SacdPlayer::SacdPlayer(IPlayerCallback *callback) : BasePlayer(callback) {
    setCpuAffinity(2);
    outBuffer.resize(705600);
}

SacdPlayer::~SacdPlayer() {
    releaseInternal();
}

void SacdPlayer::setDataSource(const std::string &_isoPath, int track_index,
                               const std::map<std::string, std::string> &_headers) {
    isoPath = _isoPath;
    mHeaders = _headers; // 复制 map
    if (track_index >= 0) {
        trackIndex = track_index;
    } else {
        trackIndex = 0;
    }
}

void SacdPlayer::setNextDataSource(const std::string &isoPath, int trackIndex,
                                   const std::map<std::string, std::string> &headers) {
    if (!isoPath.empty()) {
        nextIsoPath = isoPath;
        nextTrackIndex = trackIndex;
        nextHeaders = headers;
        LOGD("SacdPlayer Set Next: %s Track: %d", nextIsoPath.c_str(), nextTrackIndex);
    } else {
        nextIsoPath.clear();
    }
}


void SacdPlayer::prepare() {
    LOGD("SacdPlayer::prepare: trackIndex=%d start", trackIndex);
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        if (mState != STATE_IDLE && mState != STATE_STOPPED) {
            LOGE("SacdPlayer::prepare: player is not idle or stopped");
            return;
        }
        mState = STATE_PREPARING;
        if (!openSacdHandle()) {
            LOGE("SacdPlayer::prepare: open sacd handle failed");
            mState = STATE_ERROR;
            if (mCallback) {
                mCallback->onError(-1, "Open sacd handle failed");
            }
            return;
        }
        extractAudioInfo();
        if (mDsdMode == DSD_MODE_D2P) {
            d2pDecoder = new FFmpegD2pDecoder();
            d2pDecoder->init(2822400, mTargetD2pSampleRate, 16);
        }
        is4ChannelSupported = SystemProperties::is4ChannelSupported();

        mState = STATE_PREPARED;
        mIsExit = false;
    }

    if (mCallback) {
        mCallback->onPrepared();
    }
    LOGD("SacdPlayer::prepare: trackIndex=%d finished", trackIndex);
}

void SacdPlayer::play() {
    LOGD("SacdPlayer::play: trackIndex=%d", trackIndex);
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        LOGD("SacdPlayer::play: mOutput=%p", mOutput);
        if (mOutput) {
            scarletbook_output_interrupt(mOutput);
            scarletbook_output_destroy(mOutput);
            mOutput = nullptr;
        }
        if (!mHandle) {
            LOGE("SacdPlayer::play: handle is null");
            mState = STATE_ERROR;
            if (mCallback) {
                mCallback->onError(-1, "Play failed: handle is null");
            }
            return;
        }

        LOGD("SacdPlayer::play: create output");
        mOutput = scarletbook_output_create_for_player(mHandle,
                                                       this,
                                                       scarletbook_audio_callback,
                                                       scarletbook_progress_callback);

        if (!mOutput) {
            LOGE("SacdPlayer::play: create output failed");
            mState = STATE_ERROR;
            if (mCallback) {
                mCallback->onError(-2, "Play failed: create output failed");
            }
            return;
        }
        LOGD("SacdPlayer::play: enqueue track");
        int ret = scarletbook_output_enqueue_track(mOutput, area_idx, trackIndex, nullptr, "dsdiff",
                                                   1);
        if (ret < 0) {
            LOGE("play(): Enqueue track failed ret=%d", ret);
            scarletbook_output_interrupt(mOutput);
            scarletbook_output_destroy(mOutput);
            mOutput = nullptr;
            mState = STATE_ERROR;
            if (mCallback) {
                mCallback->onError(-3, "Play failed: enqueue track failed");
            }
            return;
        }
        mIsExit = false;
        mState = STATE_PLAYING;
        LOGD("SacdPlayer::play: start output");
        scarletbook_output_start(mOutput);
    }
    LOGD("SacdPlayer::play: finished");
}

void SacdPlayer::pause() {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (mState == STATE_PLAYING) {
        mState = STATE_PAUSED;
        if (mOutput) {
            scarletbook_output_pause(mOutput);
        }
    }
}

void SacdPlayer::resume() {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (mState == STATE_PAUSED) {
        mState = STATE_PLAYING;
        if (mOutput) {
            scarletbook_output_resume(mOutput);
        }
    }
}

void SacdPlayer::stop() {
    LOGD("SacdPlayer::stop: trackIndex=%d", trackIndex);
    mIsExit = true;
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_STOPPED;
    }

    if (mOutput) {
        LOGD("SacdPlayer::stop: interrupt output");
        scarletbook_output_interrupt(mOutput);
        LOGD("SacdPlayer::stop: destroy output");
        scarletbook_output_destroy(mOutput);
    }
    mOutput = nullptr;
    LOGD("SacdPlayer::stop: finished");
}

void SacdPlayer::seek(long ms) {
    if (mState == STATE_PLAYING || mState == STATE_PAUSED) {
        mIsSeeking = true;
        mSeekTargetMs = ms;
        if (mOutput) {
            // 底层这边是进度百分比
            int progress = ((float) mSeekTargetMs * 100.0f) / mDurationMs;
            scarletbook_output_seek(mOutput, progress);
        }
        mIsSeeking = false;
        mSeekTargetMs = -1;
    }
}

void SacdPlayer::release() {
    releaseInternal();
}

void SacdPlayer::releaseInternal() {
    stop();
    closeSacdHandle();
    mState = STATE_IDLE;

    if (d2pDecoder) {
        d2pDecoder->release();
        delete d2pDecoder;
        d2pDecoder = nullptr;
    }
}

long SacdPlayer::getDuration() const {
    return mDurationMs;
}

long SacdPlayer::getCurrentPosition() const {
    return mCurrentPositionMs;
}

int SacdPlayer::getSampleRate() const {
    return mSampleRate;
}

MediaInfo SacdPlayer::getMediaInfo() const {
    MediaInfo info;

    info.sampleRate = 2822400;
    info.bitDepth = 1;

    if (mHandle && area_idx >= 0) {
        info.channels = mHandle->area[area_idx].area_toc->channel_count;
        info.format = mHandle->area[area_idx].area_toc->frame_format == FRAME_FORMAT_DST
                      ? "DST64" : "DSD64";
    } else {
        info.channels = 2; // 默认
        info.format = "DSD64";
    }
    info.bitrate = (long) info.sampleRate * info.channels * info.bitDepth;

    // --- 1. 先赋予兜底默认值 ---
    info.sourceId = isoPath;
    info.album = "Unknown Album";
    info.artist = "Unknown Artist";
    info.title = "Track " + std::to_string(trackIndex + 1);

    if (mHandle) {
        auto *mtext = &mHandle->master_text;
        if (mtext) {
            if (mtext->disc_title && mtext->disc_title[0] != '\0') {
                info.album = mtext->disc_title;
            }
            if (mtext->disc_artist && mtext->disc_artist[0] != '\0') {
                info.artist = mtext->disc_artist;
            }
        }

        if (area_idx >= 0 && area_idx < mHandle->area_count) {
            auto *area = &mHandle->area[area_idx];

            if (area->area_toc &&
                trackIndex >= 0 &&
                trackIndex < area->area_toc->track_count) {

                auto *trackText = &area->area_track_text[trackIndex];

                // 获取歌名
                if (trackText->track_type_title && trackText->track_type_title[0] != '\0') {
                    info.title = trackText->track_type_title;
                }
            }
        }
    }
    return info;
}

int SacdPlayer::getChannelCount() const {
    if (mDsdMode == DSD_MODE_NATIVE && is4ChannelSupported) {
        return 4;
    }
    return mChannelCount;
}

int SacdPlayer::getBitPerSample() const {
    return mBitPerSample;
}

int SacdPlayer::onDecodeData(uint8_t *data, size_t size, int track_index) {
    if (mIsExit) return -1;
    if (mIsSeeking) return 0;
    // LOGD("onDecodeData: track_index=%d, size=%zu", track_index, size);
    int out_size = 0;
    switch (mDsdMode) {
        case DSD_MODE_NATIVE:
            if (is4ChannelSupported) {
                out_size = DsdUtils::pack4ChannelNative(true, data, size,
                                                        outBuffer.data());
            } else {
                out_size = DsdUtils::packNative(true, data, size,
                                                outBuffer.data());
            }
            break;
        case DSD_MODE_D2P:
            out_size = d2pDecoder->process(data, size, outBuffer.data());
            break;
        case DSD_MODE_DOP:
            out_size = DsdUtils::packDoP(true, data, size, outBuffer.data());
            break;
    }
    if (out_size > 0) {
        if (mCallback) {
            mCallback->onAudioData(outBuffer.data(), out_size);
        }
    }
    return 0;
}

void SacdPlayer::onDecodeProgress(int track, uint32_t current, uint32_t total, float progress) {
    if (mIsSeeking) {
        LOGD("Skip progress update while seeking");
        return;
    }
    //    LOGD("onDecodeProgress: track=%d, current=%d, total=%d, progress=%.2f", track, current, total,
    //         progress);
    mCurrentPositionMs = current;
    float triggerThreshold = 0.999f;
    if (!nextIsoPath.empty() && mTailSkipMs > 0 && mDurationMs > 0) {
        // 提前触发的时间点对应的进度
        int64_t triggerMs = mDurationMs - mTailSkipMs;
        if (triggerMs > 0) {
            triggerThreshold = (float) triggerMs / (float) mDurationMs;
            // 确保阈值合法
            if (triggerThreshold < 0.0f) triggerThreshold = 0.0f;
            if (triggerThreshold > 0.999f) triggerThreshold = 0.999f;
        }
    }

    if ((float) current / (float) total >= triggerThreshold && mState != STATE_COMPLETED) {
        std::string nPath;
        int nTrack;
        std::map<std::string, std::string> nHeaders;
        {
            std::lock_guard<std::mutex> lock(mStateMutex);
            nPath = nextIsoPath;
            nTrack = nextTrackIndex;
            nHeaders = nextHeaders;
        }

        if (!nPath.empty()) {
            // 开辟独立线程执行无缝切换，避免阻塞当前的 scarletbook 回调线程
            std::thread([this, nPath, nTrack, nHeaders]() {
                performSeamlessSwitch(nPath, nTrack, nHeaders);
            }).detach();
        } else {
            {
                std::lock_guard<std::mutex> lock(mStateMutex);
                mState = STATE_COMPLETED;
            }
            if (mCallback) mCallback->onComplete();
        }
    }

    if (mState == STATE_PLAYING) {
        if (mCallback) {
            mCallback->onProgress(track, current, total, progress);
        }
    }
}

void SacdPlayer::performSeamlessSwitch(const std::string &nPath, int nTrack,
                                       const std::map<std::string, std::string> &nHeaders) {
    LOGD("SacdPlayer executing gapless switch to %s track %d", nPath.c_str(), nTrack);

    // 1. 交接参数
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        isoPath = nPath;
        trackIndex = nTrack;
        mHeaders = nHeaders;
        nextIsoPath.clear();
        nextHeaders.clear();
    }

    // 2. 销毁旧的解码引擎
    // scarletbook_output_interrupt 会安全地通知解码循环结束
    // scarletbook_output_destroy 会等待解码线程 join，因此需要放在独立线程执行
    if (mOutput) {
        scarletbook_output_interrupt(mOutput);
        scarletbook_output_destroy(mOutput);
        mOutput = nullptr;
    }

    // 3. 重新初始化
    closeSacdHandle();

    if (!openSacdHandle()) {
        LOGE("SacdPlayer Gapless open failed");
        std::lock_guard<std::mutex> lock(mStateMutex);
        mState = STATE_ERROR;
        if (mCallback) mCallback->onError(-1, "Gapless open failed");
        return;
    }

    extractAudioInfo();
    mCurrentPositionMs = 0;

    // 4. 重建输出并无缝起播
    mOutput = scarletbook_output_create_for_player(mHandle,
                                                   this,
                                                   scarletbook_audio_callback,
                                                   scarletbook_progress_callback);
    if (mOutput) {
        scarletbook_output_enqueue_track(mOutput, area_idx, trackIndex, nullptr, "dsdiff", 1);
        scarletbook_output_start(mOutput);

        // 可选：通知 UI 更新元数据信息
        if (mCallback) mCallback->onTrackTransition();
    }
}

bool SacdPlayer::openSacdHandle() {
    if (isoPath.empty()) return false;

    // 确保之前的 handle 和 stream 都已关闭
    closeSacdHandle();

    // 判断是否为网络路径
    bool isNetwork = (isoPath.find("http://") == 0 || isoPath.find("https://") == 0);

    if (isNetwork) {
        LOGD("SacdPlayer::openSacdHandle: Network stream detected");
        mNetStream = new FFmpegNetworkStream();

        if (!mNetStream->open(isoPath, mHeaders)) {
            LOGE("SacdPlayer::openSacdHandle: FFmpegNetworkStream open failed");
            delete mNetStream;
            mNetStream = nullptr;
            return false;
        }

        sacd_io_callbacks_t cb;
        cb.context = mNetStream;
        cb.read = FFmpegNetworkStream::read_cb;
        cb.seek = FFmpegNetworkStream::seek_cb;
        cb.tell = FFmpegNetworkStream::tell_cb;
        cb.get_size = FFmpegNetworkStream::get_size_cb;

        mReader = sacd_open_callbacks(&cb);
    } else {
        LOGD("SacdPlayer::openSacdHandle: Local file detected");
        mReader = sacd_open(isoPath.c_str());
    }

    if (!mReader) {
        LOGE("sacd_open failed: %s", isoPath.c_str());
        if (mNetStream) {
            mNetStream->close();
            delete mNetStream;
            mNetStream = nullptr;
        }
        return false;
    }

    mHandle = scarletbook_open(mReader);
    if (!mHandle) {
        LOGE("scarletbook_open failed");
        sacd_close(mReader);
        mReader = nullptr;
        // 清理网络流
        if (mNetStream) {
            delete mNetStream;
            mNetStream = nullptr;
        }
        return false;
    }

    area_idx = (mHandle->twoch_area_idx >= 0) ? mHandle->twoch_area_idx : mHandle->mulch_area_idx;
    if (area_idx < 0) {
        LOGE("No valid audio area found in ISO");
        return false;
    }
    return true;
}

void SacdPlayer::closeSacdHandle() {
    if (mHandle) {
        scarletbook_close(mHandle);
        mHandle = nullptr;
    }

    if (mReader) {
        sacd_close(mReader);
        mReader = nullptr;
    }

    if (mNetStream) {
        mNetStream->close();
        delete mNetStream;
        mNetStream = nullptr;
        LOGD("SacdPlayer::closeSacdHandle: Network stream released");
    }
}

long SacdPlayer::getTrackDurationMs(int track_index) {
    if (!mHandle || area_idx < 0) return 0;
    if (track_index < 0 || track_index >= mHandle->area[area_idx].area_toc->track_count) return 0;
    auto time = &mHandle->area[area_idx].area_tracklist_time->duration[track_index];
    return (uint32_t) ((uint64_t) TIME_FRAMECOUNT(time) * 1000ULL / 75ULL);
}


bool SacdPlayer::isDsd() const {
    return mIsSourceDsd;
}

void SacdPlayer::extractAudioInfo() {
    mDurationMs = getTrackDurationMs(trackIndex);
    mIsSourceDsd = true;
    mChannelCount = 2;
    switch (mDsdMode) {
        case DSD_MODE_NATIVE:
            // 64 * 44100 /32
            mBitPerSample = 1;
            mSampleRate = 88200;
            break;
        case DSD_MODE_D2P:
            mBitPerSample = 16;
            mSampleRate = mTargetD2pSampleRate;
            break;
        case DSD_MODE_DOP:
            // 64 * 44100 /16
            mBitPerSample = 32;
            mSampleRate = 176400;
            break;
    }

    LOGD("extractAudioInfo: sampleRate=%d, bitPerSample=%d, channelCount=%d",
         mSampleRate, mBitPerSample, mChannelCount);
}


