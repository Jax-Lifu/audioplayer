//
// Created by Administrator on 2025/11/24.
//

#ifndef QYPLAYER_PLAYERDEFINES_H
#define QYPLAYER_PLAYERDEFINES_H

#include <stdint.h>
#include <string>

// DSD 播放模式
enum DsdMode {
    DSD_MODE_NATIVE = 0, // 源码透传 (需 USB DAC 支持 Native DSD)
    DSD_MODE_D2P = 1,    // 转 PCM (软解)
    DSD_MODE_DOP = 2     // DSD over PCM
};

// 播放器状态
enum PlayerState {
    STATE_IDLE = 0,         // 初始状态，无媒体
    STATE_PREPARING = 1,    // 正在准备
    STATE_PREPARED = 2,     // 准备完毕
    STATE_PLAYING = 3,      // 播放中
    STATE_PAUSED = 4,       // 已暂停
    STATE_STOPPED = 5,      // 已停止
    STATE_COMPLETED = 6,    // 播放完成
    STATE_ERROR = 7,        // 发生错误
    STATE_BUFFERING = 8,    // 缓冲中
};

// 回调接口
class IPlayerCallback {
public:
    virtual void onPrepared() = 0;

    virtual void onAudioData(uint8_t *data, int size) = 0;

    virtual void onProgress(int trackIndex, long currentMs, long totalMs, float progress) = 0;

    virtual void onComplete() = 0;

    virtual void onError(int code, const char *msg) = 0;

    virtual void onBuffering(bool buffering) = 0;

    virtual void onTrackTransition() = 0;

    virtual ~IPlayerCallback() {}
};

struct MediaInfo {
    std::string sourceId;
    int sampleRate = 0;
    int channels = 0;
    long bitrate = 0;     // bits per second
    int bitDepth = 0;
    std::string format;   // e.g., "flac", "dsd64", "pcm_s16le"
    std::string title;
    std::string album;
    std::string artist;
};

#endif //QYPLAYER_PLAYERDEFINES_H
