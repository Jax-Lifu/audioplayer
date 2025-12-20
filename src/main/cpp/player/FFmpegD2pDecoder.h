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
     * 初始化解码器
     * @param dsdRate DSD 源采样率 (e.g. 2822400)
     * @param targetPcmRate 目标 PCM 采样率 (e.g. 176400)
     * @param targetBitDepth 目标位深 (16 或 32)
     */
    bool init(int dsdRate, int targetPcmRate, int targetBitDepth);

    /**
     * 处理数据
     * @return 写入 outData 的字节数，失败返回 -1
     */
    int process(uint8_t *inData, int inSize, uint8_t *outData);

    void release();

private:
    AVCodecContext *codecCtx = nullptr;
    SwrContext *swrCtx = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;

    int targetRate = 0;
    AVSampleFormat outFmt = AV_SAMPLE_FMT_S16;
    int bytesPerSample = 2;
};

#endif //QYPLAYER_FFMPEGD2PDECODER_H