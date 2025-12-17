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
#include <map>
#include "SystemProperties.h" // 假设你有这个
#include "DsdUtils.h"         // 假设你有这个

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/time.h>
#include <libavutil/opt.h>
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
        std::lock_guard<std::mutex> lock(mutex);
        if (abort_request) return -1;
        queue.push(pkt);
        nb_packets++;
        size += pkt->size;
        cond.notify_one();
        return 0;
    }

    // block: true 为阻塞获取，false 为立即返回
    // 返回: 1 获取成功, 0 队列空, -1 终止/Abort
    int get(AVPacket *pkt, bool block) {
        std::unique_lock<std::mutex> lock(mutex);
        for (;;) {
            if (abort_request) return -1;

            if (!queue.empty()) {
                AVPacket *src = queue.front();
                queue.pop();
                nb_packets--;
                size -= src->size;
                av_packet_move_ref(pkt, src); // 转移引用
                av_packet_free(&src);
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
                       int64_t startPositon = 0, int64_t endPosition = -1);
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
    void readLoop();     // 生产者 (负责下载)
    void decodingLoop(); // 消费者 (负责解码播放)

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
    std::atomic<long> mCurrentPositionMs{0};
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
    std::atomic<bool> mIsEOF{false};

    // Mutex & Condition
    std::mutex mStateMutex;
    std::condition_variable stateCond;

    // Seek Control
    std::mutex mSeekMutex;
    long mSeekTargetMs = -1;
    std::atomic<bool> mIsSeeking{false};
    std::atomic<bool> mFlushCodec{false};

    // Queue & Buffer
    PacketQueue audioQueue;
    std::vector<uint8_t> outBuffer;

    // --- 缓存控制核心参数 ---
    // 允许缓存 50MB (约一首无损歌曲)，实现“暂停时继续下载”
    const int MAX_QUEUE_SIZE = 50 * 1024 * 1024;

    // 起播阈值 (默认256KB，最后3s左右)。
    // 当 Seek 或缓冲耗尽后，必须积攒这么多数据才开始播放，防止频繁卡顿。
    int minStartThresholdBytes = 256 * 1024;
};

#endif //QYPLAYER_FFPLAYER_H