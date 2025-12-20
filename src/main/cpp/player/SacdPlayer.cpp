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
    if (progress >= 0.999f && mState != STATE_COMPLETED) {
        {
            std::lock_guard<std::mutex> lock(mStateMutex);
            mState = STATE_COMPLETED;
        }
        if (mCallback) mCallback->onComplete();
    }

    if (mState == STATE_PLAYING) {
        if (mCallback) {
            mCallback->onProgress(track, current, total, progress);
        }
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


