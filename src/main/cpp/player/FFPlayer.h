#ifndef QYPLAYER_FFPLAYER_H
#define QYPLAYER_FFPLAYER_H

#include "BasePlayer.h"
#include <thread>
#include <condition_variable>
#include "SystemProperties.h"
#include "CpuAffinity.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/time.h>
#include "libavutil/opt.h"
}

class FFPlayer : public BasePlayer {
public:
    explicit FFPlayer(IPlayerCallback *callback);

    ~FFPlayer() override;

    void
    setDataSource(const char *path, const char *headers,
                  int64_t startPositon = 0, int64_t endPosition = -1);

    void prepare() override;

    void play() override;

    void pause() override;

    void resume() override;

    void stop() override;

    void release() override;

    void seek(long ms) override;

    // Getters 实现
    long getDuration() const override;

    long getCurrentPosition() const override;

    int getSampleRate() const override;

    int getChannelCount() const override;

    int getBitPerSample() const override;

    bool isDsd() const override;

    bool isExit() const;

private:
    // 内部资源释放
    void releaseInternal();

    // 初始化 FFmpeg 网络模块
    void initFFmpeg();

    // 释放 FFmpeg 上下文
    void releaseFFmpeg();

    // 初始化重采样器
    int initSwrContext();

    // 提取音频信息
    void extractAudioInfo();

    // 解码线程函数
    void decodingLoop();

    // 处理 PCM 包
    void handlePcmAudioPacket(AVPacket *packet, AVFrame *frame);

    // 处理 DSD 包
    void handleDsdAudioPacket(AVPacket *packet, AVFrame *frame);

    // 更新进度
    void updateProgress();

    // 辅助函数
    static bool isDsdCodec(AVCodecID id);

    static bool isMsbfCodec(AVCodecID id);

    static AVSampleFormat getOutputSampleFormat(AVSampleFormat inputFormat);

    static int interrupt_cb(void *ctx);

    void ensureBufferCapacity(size_t requiredSize);


private:
    // 成员变量
    std::string mUrl;
    std::string mHeaders;
    int64_t mStartTimeMs = 0; // 毫秒
    int64_t mEndTimeMs = -1;  // 毫秒

    // FFmpeg 上下文
    AVFormatContext *fmtCtx = nullptr;
    AVCodecContext *codecCtx = nullptr;
    SwrContext *swrCtx = nullptr;
    enum AVSampleFormat outputSampleFormat = AV_SAMPLE_FMT_S16;
    AVRational *timeBase = nullptr;

    int audioStreamIndex = -1;
    bool isMsbf = true;
    // 线程与同步 (FFPlayer 特有的)
    std::thread *decodeThread = nullptr;
    std::condition_variable stateCond;

    // 缓冲区
    std::vector<uint8_t> outBuffer;
};

#endif //QYPLAYER_FFPLAYER_H