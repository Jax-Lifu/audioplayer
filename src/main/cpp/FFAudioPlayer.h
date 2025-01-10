//
// Created by Administrator on 2025/1/6.
//

#ifndef QYLAUNCHER_FFAUDIOPLAYER_H
#define QYLAUNCHER_FFAUDIOPLAYER_H

#include <android/log.h>
#include <jni.h>
#include <thread>
#include <unistd.h>
#include <cstring>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/opt.h>
#include <libavutil/imgutils.h>
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}
enum PlayState {
    IDLE = 0,     // 空闲状态
    PAUSED = 1,   // 暂停状态
    STOPPED = 2,   // 停止状态
    PLAYING = 3   // 播放状态
};


class FFAudioPlayer {
public:
    FFAudioPlayer();

    ~FFAudioPlayer();

    // 初始化音频播放器
    bool init(const char *filePath);

    // 播放音频
    void play();

    // 暂停音频
    void pause();

    // 停止音频
    void stop();

    // 释放资源
    void release();

    // 跳转到指定时间位置
    void seek(long position);

    [[nodiscard]] PlayState getPlayState() const;

    // 获取音频采样率
    [[nodiscard]] int getSampleRate() const;

    // 获取音频通道数
    [[nodiscard]] int getChannelNumber() const;

    // 获取音频时长
    [[nodiscard]] long getDuration() const;

    [[nodiscard]] long getCurrentPosition() const;

private:
    // FFmpeg components
    const AVCodec *codec = nullptr;
    AVFormatContext *formatContext = nullptr;
    SwrContext *swrContext = nullptr;
    AVRational *timeBase = nullptr;
    AVCodecContext *codecContext = nullptr;
    int sampleRate = 0;
    int channels = 0;
    long duration = 0;
    bool isPlaying = false;
    bool isPaused = false;
    bool isStopped = false;
    bool isSeeking = false;
    std::atomic<bool> shouldStopped;
    long currentPosition = 0;
    int audioStreamIndex = -1;
    int result = -1;
    char errorBuffer[AV_ERROR_MAX_STRING_SIZE];

    std::thread decodeThread;
    std::condition_variable decodeCv;
    std::mutex decodeMutex;


    // 打开音频文件
    bool openAudioFile(const char *filePath);

    bool decodePacket(AVPacket *packet, AVFrame *frame);

    // 解码音频数据包
    void decodeLoop();


};

void playAudio(const char *data, int size);

void onCompletion();

#endif //QYLAUNCHER_FFAUDIOPLAYER_H
