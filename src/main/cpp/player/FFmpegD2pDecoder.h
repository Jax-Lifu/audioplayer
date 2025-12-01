#ifndef QYPLAYER_FFMPEGD2PDECODER_H
#define QYPLAYER_FFMPEGD2PDECODER_H

#include "Logger.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/time.h>
#include <libavutil/opt.h>
}

class FFmpegD2pDecoder {
public:
    FFmpegD2pDecoder();

    ~FFmpegD2pDecoder();

    /**
     * 初始化解码器和重采样器
     * @param targetSampleRate 目标 PCM 采样率 (例如 88200, 176400 等)
     */
    bool initFFmpegD2pDecoder(int targetSampleRate);

    /**
     * 释放所有 FFmpeg 资源
     */
    void releaseFFmpegD2pDecoder();

    /**
     * 解码 DSD 数据块
     * @param sourceData DSD 原始数据
     * @param size 数据长度
     * @param outData 输出 PCM 的缓冲区 (调用者需保证足够大)
     * @return 实际写入 outData 的字节数，失败返回 -1
     */
    int decodeD2pData(uint8_t *sourceData, size_t size, uint8_t *outData);

private:
    int d2pSampleRate = 192000; // 默认值
    enum AVSampleFormat outSampleFormat = AV_SAMPLE_FMT_S16;
    AVChannelLayout outChannelLayout = AV_CHANNEL_LAYOUT_STEREO;

    // FFmpeg 上下文
    AVCodecContext *codecCtx = nullptr;
    SwrContext *swrCtx = nullptr;

    // 复用对象，避免频繁分配内存
    AVFrame *mFrame = nullptr;
    AVPacket *mPacket = nullptr;
};

#endif //QYPLAYER_FFMPEGD2PDECODER_H