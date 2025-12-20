#include "FFmpegD2pDecoder.h"

#define MAX_OUTPUT_BUFFER_SIZE 1024 * 1024
#define LOG_TAG "FFmpegD2pDecoder"

FFmpegD2pDecoder::FFmpegD2pDecoder() : codecCtx(nullptr), swrCtx(nullptr), frame(nullptr),
                                       packet(nullptr),
                                       outFmt(AV_SAMPLE_FMT_NONE), bytesPerSample(0),
                                       targetRate(0) {}

FFmpegD2pDecoder::~FFmpegD2pDecoder() {
    release();
}

bool FFmpegD2pDecoder::init(int dsdRate, int targetPcmRate, int targetBitDepth) {
    release();

    targetRate = targetPcmRate;

    // 1. 确定输出格式
    if (targetBitDepth == 32) {
        outFmt = AV_SAMPLE_FMT_S32;
        bytesPerSample = 4;
    } else {
        outFmt = AV_SAMPLE_FMT_S16;
        bytesPerSample = 2;
    }

    // 2. 分配 Packet & Frame
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!packet || !frame) {
        LOGE("Failed to allocate packet or frame");
        return false;
    }

    // 3. 初始化 DSD 解码器
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_DSD_MSBF);
    if (!codec) {
        codec = avcodec_find_decoder(AV_CODEC_ID_DSD_LSBF); // Try LSBF
        if (!codec) {
            LOGE("DSD Decoder not found");
            return false;
        }
    }

    codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx) {
        LOGE("Failed to allocate codec context");
        return false;
    }

    codecCtx->sample_rate = dsdRate / 8; // DSD Rate -> Byte Rate
    codecCtx->ch_layout = AV_CHANNEL_LAYOUT_STEREO;
    codecCtx->bits_per_coded_sample = 1;

    if (avcodec_open2(codecCtx, codec, nullptr) < 0) {
        LOGE("Failed to open codec");
        return false;
    }

    // 4. 初始化重采样器
    swrCtx = swr_alloc();
    if (!swrCtx) {
        LOGE("Failed to allocate swr context");
        return false;
    }

    // Input: From Decoder
    av_opt_set_chlayout(swrCtx, "in_chlayout", &codecCtx->ch_layout, 0);
    av_opt_set_int(swrCtx, "in_sample_rate", codecCtx->sample_rate, 0);
    av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", codecCtx->sample_fmt, 0);

    // Output: To PCM
    const AVChannelLayout out_layout = AV_CHANNEL_LAYOUT_STEREO;
    av_opt_set_chlayout(swrCtx, "out_chlayout", &out_layout, 0);
    av_opt_set_int(swrCtx, "out_sample_rate", targetPcmRate, 0);
    av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", outFmt, 0);

    if (swr_init(swrCtx) < 0) {
        LOGE("Failed to init swr");
        return false;
    }

    LOGD("Decoder Inited: DSD %d -> PCM %d (%dbit)", dsdRate, targetPcmRate, targetBitDepth);
    return true;
}

int FFmpegD2pDecoder::process(uint8_t *inData, int inSize, uint8_t *outData) {
    if (!codecCtx || !swrCtx) {
        LOGE("Codec or swr context not initialized");
        return -1;
    }

    packet->data = inData;
    packet->size = inSize;

    int ret = avcodec_send_packet(codecCtx, packet);
    if (ret < 0) {
        LOGE("Failed to send packet: %s", av_err2str(ret));
        av_packet_unref(packet);
        return -1;
    }

    int totalBytesWritten = 0;

    while (true) {
        ret = avcodec_receive_frame(codecCtx, frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
        } else if (ret < 0) {
            LOGE("Failed to receive frame: %s", av_err2str(ret));
            av_packet_unref(packet);
            return -1;
        }

        int64_t delay = swr_get_delay(swrCtx, codecCtx->sample_rate);
        int dsd_nb_samples = (int) av_rescale_rnd(
                delay + frame->nb_samples, targetRate, codecCtx->sample_rate, AV_ROUND_UP);

        // 确保输出缓冲区有足够的空间
        int out_buffer_size = dsd_nb_samples * 2 * bytesPerSample; // 2 channels
        if (totalBytesWritten + out_buffer_size > MAX_OUTPUT_BUFFER_SIZE) {
            LOGE("Output buffer overflow: totalBytesWritten=%d, out_buffer_size=%d",
                 totalBytesWritten, out_buffer_size);
            av_frame_unref(frame);
            av_packet_unref(packet);
            return -1;
        }

        uint8_t *out_buffers[2] = {outData + totalBytesWritten, nullptr};

        int samples = swr_convert(swrCtx, out_buffers, dsd_nb_samples,
                                  (const uint8_t **) frame->data, frame->nb_samples);

        if (samples > 0) {
            totalBytesWritten += samples * 2 * bytesPerSample;
        } else if (samples < 0) {
            LOGE("swr_convert failed: %s", av_err2str(samples));
            av_frame_unref(frame);
            av_packet_unref(packet);
            return -1;
        }
        av_frame_unref(frame);
    }

    av_packet_unref(packet);
    return totalBytesWritten;
}

void FFmpegD2pDecoder::release() {
    if (codecCtx) {
        avcodec_free_context(&codecCtx);
        codecCtx = nullptr;
    }
    if (swrCtx) {
        swr_free(&swrCtx);
        swrCtx = nullptr;
    }
    if (frame) {
        av_frame_free(&frame);
        frame = nullptr;
    }
    if (packet) {
        av_packet_free(&packet);
        packet = nullptr;
    }
}