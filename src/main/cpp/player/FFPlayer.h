#ifndef QYPLAYER_FFPLAYER_H
#define QYPLAYER_FFPLAYER_H

#include "BasePlayer.h"
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <queue>
#include <vector>
#include <string>
#include "SystemProperties.h"
#include "CpuAffinity.h"
#include "DsdUtils.h"
#include <chrono>
#include <algorithm>
#include <map>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/time.h>
#include "libavutil/opt.h"
}

// === 线程安全的 Packet 队列 ===
class PacketQueue {
public:
    PacketQueue() : nb_packets(0), size(0), abort_request(false) {}

    ~PacketQueue() { flush(); }

    void start() {
        std::lock_guard<std::mutex> lock(mutex);
        abort_request = false;
        cond.notify_all();
    }

    void abort() {
        std::lock_guard<std::mutex> lock(mutex);
        abort_request = true;
        cond.notify_all();
    }

    void flush() {
        std::lock_guard<std::mutex> lock(mutex);
        while (!queue.empty()) {
            AVPacket *pkt = queue.front();
            queue.pop();
            av_packet_free(&pkt);
        }
        nb_packets = 0;
        size = 0;
        cond.notify_all();
    }

    int put(AVPacket *pkt) {
        if (abort_request) return -1;
        std::lock_guard<std::mutex> lock(mutex);
        queue.push(pkt);
        nb_packets++;
        size += pkt->size;
        cond.notify_one();
        return 0;
    }

    // block: true 为阻塞获取，false 为立即返回
    // 返回: 1 获取成功, 0 队列空, -1 终止
    int get(AVPacket *pkt, bool block) {
        std::unique_lock<std::mutex> lock(mutex);
        for (;;) {
            if (abort_request) return -1;

            if (!queue.empty()) {
                AVPacket *src = queue.front();
                queue.pop();
                nb_packets--;
                size -= src->size;
                av_packet_move_ref(pkt, src); // 转移引用，不拷贝数据
                av_packet_free(&src); // 释放容器内存
                return 1;
            }

            if (!block) return 0;
            cond.wait(lock);
        }
    }

    int getPacketCount() {
        std::lock_guard<std::mutex> lock(mutex);
        return nb_packets;
    }

    int getSize() {
        std::lock_guard<std::mutex> lock(mutex);
        return size;
    }

private:
    std::queue<AVPacket *> queue;
    std::mutex mutex;
    std::condition_variable cond;
    bool abort_request;
    int nb_packets;
    int size;
};

// === FFPlayer ===
class FFPlayer : public BasePlayer {
public:
    explicit FFPlayer(IPlayerCallback *callback);

    ~FFPlayer() override;

    void setDataSource(const char *path, const std::map<std::string, std::string> &headers,
                       int64_t startPositon = 0,
                       int64_t endPosition = -1);

    void prepare() override;

    void play() override;

    void pause() override;

    void resume() override;

    void stop() override;

    void release() override;

    void seek(long ms) override;

    // Getters
    long getDuration() const override;

    long getCurrentPosition() const override;

    int getSampleRate() const override;

    int getChannelCount() const override;

    int getBitPerSample() const override;

    bool isDsd() const override;

    bool isExit() const;

private:
    void releaseInternal();

    void initFFmpeg();

    void releaseFFmpeg();

    int initSwrContext();

    void extractAudioInfo();

    // 核心循环
    void readLoop();     // 生产者
    void decodingLoop(); // 消费者

    void handlePcmAudioPacket(AVPacket *packet, AVFrame *frame);

    void handleDsdAudioPacket(AVPacket *packet, AVFrame *frame);

    void updateProgress();

    void ensureBufferCapacity(size_t requiredSize);

    static bool isDsdCodec(AVCodecID id);

    static bool isMsbfCodec(AVCodecID id);

    static AVSampleFormat getOutputSampleFormat(AVSampleFormat inputFormat);

    static int interrupt_cb(void *ctx);

private:
    std::string mUrl;
    std::map<std::string, std::string> mHeaders;
    int64_t mStartTimeMs = 0;
    int64_t mEndTimeMs = -1;

    // FFmpeg context
    AVFormatContext *fmtCtx = nullptr;
    AVCodecContext *codecCtx = nullptr;
    SwrContext *swrCtx = nullptr;
    AVRational *timeBase = nullptr;
    enum AVSampleFormat outputSampleFormat = AV_SAMPLE_FMT_S16;

    // Audio Params
    int audioStreamIndex = -1;
    long mDurationMs = 0;
    long mCurrentPositionMs = 0;
    int mSampleRate = 0;
    int mChannelCount = 0;
    int mBitPerSample = 0;

    // DSD Params
    bool mIsSourceDsd = false;
    bool isMsbf = false;
    bool is4ChannelSupported = false;
    int mDsdMode = DSD_MODE_NATIVE;
    int mTargetD2pSampleRate = 44100;

    // Threads
    std::thread *readThread = nullptr;
    std::thread *decodeThread = nullptr;

    // Status
    std::atomic<bool> mIsExit{false};
    std::atomic<bool> mIsEOF{false}; // 新增：标记文件是否读完

    // Mutex & Condition
    std::mutex mStateMutex;
    std::condition_variable stateCond;

    // Seek Control
    std::mutex mSeekMutex;
    long mSeekTargetMs = -1;
    bool mIsSeeking = false;
    std::atomic<bool> mFlushCodec{false};

    // Queue & Buffer
    PacketQueue audioQueue;
    std::vector<uint8_t> outBuffer;

    // Buffering Control Parameters
    const int MAX_QUEUE_SIZE = 15 * 1024 * 1024; // 15MB 最大缓存
    const int MIN_BUFFER_PACKETS = 100;          // 恢复播放阈值：100个包 (之前是20，太小了)
    const int MIN_BUFFER_BYTES = 512 * 1024;     // 恢复播放阈值：512KB (DSD包大，数量可能少，双重保险)
};

#endif //QYPLAYER_FFPLAYER_H