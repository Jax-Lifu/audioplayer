#ifndef QYLAUNCHER_FFAUDIOPLAYER_H
#define QYLAUNCHER_FFAUDIOPLAYER_H

#include <android/log.h>
#include <jni.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>

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
#include <libavutil/error.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libswresample/swresample.h>
}

enum class AudioEncodingType {
    PCM, DSD, DST, MQA, Unknown
};

enum DSDMode {
    NATIVE = 0,
    D2P = 1,
    DOP = 2,
};

// 播放状态定义
enum PlayState {
    IDLE = 0,     // 初始状态，未开始播放
    PAUSED = 1,   // 暂停中
    STOPPED = 2,  // 已停止
    PLAYING = 3   // 正在播放
};

class FFAudioPlayer {
public:
    FFAudioPlayer();

    ~FFAudioPlayer();


    // 初始化播放器，传入音频文件路径
    bool init(const char *filePath, const char *headers, int dsd_mode, int d2p_sample_rate);

    // 控制方法
    void play();

    void pause();

    void stop();

    void release();

    void seek(long position);

    // 获取播放状态
    [[nodiscard]] PlayState getPlayState() const;

    [[nodiscard]] long getCurrentPosition() const;

    // 获取音频信息
    [[nodiscard]] int getSampleRate() const;

    [[nodiscard]] int getChannelNumber() const;

    [[nodiscard]] long getDuration() const;

private:
    // FFmpeg 组件
    const AVCodec *codec = nullptr;
    AVFormatContext *formatContext = nullptr;
    AVCodecContext *codecContext = nullptr;
    SwrContext *swrContext = nullptr;
    AVRational *timeBase = nullptr;
    AVFilterGraph *filterGraph = nullptr;
    AVFilterContext *srcFilter = nullptr;
    AVFilterContext *sinkFilter = nullptr;

    // 音频参数
    int sampleRate = 44100;
    int d2pSampleRate = 48000;
    int channels = 2;
    long duration = 0;
    long currentPosition = 0;
    int audioStreamIndex = -1;
    bool isMSBF = false;
    AudioEncodingType encodingType = AudioEncodingType::Unknown;

    // 播放状态
    bool isPlaying = false;
    bool isPaused = false;
    bool isStopped = false;
    bool isSeeking = false;
    std::atomic<bool> shouldStopped = false;
    std::atomic<bool> isDecodeThreadRunning = false;

    // 线程与同步
    std::thread decodeThread;
    std::mutex stateMutex;              // 控制状态的互斥锁
    std::mutex decodeMutex;             // 控制解码线程的互斥锁
    std::condition_variable decodeCv;   // 解码控制条件变量

    // 内部方法
    bool openAudioFile(const char *filePath, const char *headers);

    bool decodePacket(AVPacket *packet, AVFrame *frame);


    void decodeLoop();

    bool isDsdAudio() const;

    DSDMode dsdMode = DSDMode::NATIVE;

    bool isI2sAudio = false;

    AVSampleFormat outputFormat = AV_SAMPLE_FMT_S16;

    int result = -1;

    void processDSDNative(const uint8_t *src, size_t size);

    void processDSDOp(const uint8_t *src, size_t size);

    void processDSDD2P(AVCodecContext *avctx, const AVPacket *avpkt);
};


void playAudio(const char *data, int size);

void onCompletion();

#endif // QYLAUNCHER_FFAUDIOPLAYER_H
