#ifndef QYPLAYER_BASEPLAYER_H
#define QYPLAYER_BASEPLAYER_H

#include "PlayerDefines.h"
#include "DsdUtils.h"
#include "Logger.h"
#include <mutex>
#include <atomic>
#include <string>
#include "CpuAffinity.h"

#ifndef CHANNEL_OUT_STEREO
#define CHANNEL_OUT_STEREO 2
#endif


class BasePlayer {
public:
    explicit BasePlayer(IPlayerCallback *callback) : mCallback(callback) {
        setCpuAffinity();
    }

    virtual ~BasePlayer() = default;

    virtual void setDsdConfig(DsdMode mode, int d2pSampleRate = -1) {
        mDsdMode = mode;
        mTargetD2pSampleRate = d2pSampleRate;
        LOGD("DsdConfig: mode = %d, d2pSampleRate = %d", mode, d2pSampleRate);
    }

    virtual void prepare() = 0;

    virtual void play() = 0;

    virtual void pause() = 0;

    virtual void resume() = 0;

    virtual void stop() = 0;

    virtual void seek(long ms) = 0;

    virtual void release() = 0;

    // --- 状态与属性查询 ---
    virtual long getDuration() const = 0;

    virtual long getCurrentPosition() const = 0;

    virtual int getSampleRate() const = 0;

    virtual int getChannelCount() const = 0;

    virtual int getBitPerSample() const = 0;

    virtual bool isDsd() const = 0;

    bool isPlaying() const {
        return mState == STATE_PLAYING;
    }

    PlayerState getState() const {
        return mState;
    }

protected:
    IPlayerCallback *mCallback = nullptr;

    std::atomic<PlayerState> mState{STATE_IDLE};
    std::mutex mStateMutex;

    // Seek 相关
    std::mutex mSeekMutex;
    long mSeekTargetMs = -1;

    long mCurrentPositionMs = 0;
    long mDurationMs = 0;
    int mSampleRate = 0;
    int mChannelCount = 0;
    int mBitPerSample = 0;
    bool mIsSourceDsd = false;

    std::atomic<bool> mIsExit{false};
    std::atomic<bool> mIsSeeking{false};

    // DSD 配置
    DsdMode mDsdMode = DSD_MODE_NATIVE;
    int mTargetD2pSampleRate = 192000;
};

#endif //QYPLAYER_BASEPLAYER_H
