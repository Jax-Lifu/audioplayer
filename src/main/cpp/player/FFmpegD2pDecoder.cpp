#include "FFmpegD2pDecoder.h"

FFmpegD2pDecoder::FFmpegD2pDecoder() {
    // 构造时初始化为空，不做耗时操作
}

FFmpegD2pDecoder::~FFmpegD2pDecoder() {
    releaseFFmpegD2pDecoder();
}

bool FFmpegD2pDecoder::initFFmpegD2pDecoder(int sampleRate) {
    // 先清理可能存在的旧资源
    releaseFFmpegD2pDecoder();

    this->d2pSampleRate = sampleRate;
    LOGD("initFFmpegD2pDecoder: targetSampleRate=%d", d2pSampleRate);

    // 1. 初始化 Packet 和 Frame (只分配一次)
    mPacket = av_packet_alloc();
    mFrame = av_frame_alloc();
    if (!mPacket || !mFrame) {
        LOGE("Failed to allocate AVPacket or AVFrame");
        return false;
    }

    // 2. 查找并初始化 DSD 解码器
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_DSD_MSBF);
    if (!codec) {
        LOGE("avcodec_find_decoder AV_CODEC_ID_DSD_MSBF failed");
        return false;
    }

    codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx) {
        LOGE("avcodec_alloc_context3 failed");
        return false;
    }

    // 配置解码参数
    // FFmpeg DSD 解码器通常将 1 byte (8 bits) 视为一个采样单位
    // 2822400 / 8 = 352800 Hz。这是 FFmpeg 内部处理 DSD 的标准做法。
    codecCtx->sample_rate = 352800;
    codecCtx->bits_per_coded_sample = 1;
    // 设置为立体声
    codecCtx->ch_layout = AV_CHANNEL_LAYOUT_STEREO;

    if (avcodec_open2(codecCtx, codec, nullptr) < 0) {
        LOGE("avcodec_open2 failed");
        return false;
    }

    // 3. 初始化重采样器 (SwrContext)
    swrCtx = swr_alloc();
    if (!swrCtx) {
        LOGE("swr_alloc failed");
        return false;
    }

    // 设置输入参数 (从 codecCtx 获取)
    av_opt_set_chlayout(swrCtx, "in_chlayout", &codecCtx->ch_layout, 0);
    av_opt_set_int(swrCtx, "in_sample_rate", codecCtx->sample_rate, 0);
    av_opt_set_sample_fmt(swrCtx, "in_sample_fmt", codecCtx->sample_fmt, 0);

    // 设置输出参数 (目标 PCM)
    av_opt_set_chlayout(swrCtx, "out_chlayout", &outChannelLayout, 0);
    av_opt_set_int(swrCtx, "out_sample_rate", d2pSampleRate, 0);
    av_opt_set_sample_fmt(swrCtx, "out_sample_fmt", outSampleFormat, 0);

    if (swr_init(swrCtx) < 0) {
        LOGE("swr_init failed");
        return false;
    }

    LOGD("initFFmpegD2pDecoder success");
    return true;
}

void FFmpegD2pDecoder::releaseFFmpegD2pDecoder() {
    if (codecCtx) {
        avcodec_free_context(&codecCtx);
        codecCtx = nullptr;
    }
    if (swrCtx) {
        swr_free(&swrCtx);
        swrCtx = nullptr;
    }
    if (mFrame) {
        av_frame_free(&mFrame);
        mFrame = nullptr;
    }
    if (mPacket) {
        av_packet_free(&mPacket);
        mPacket = nullptr;
    }
    LOGD("releaseFFmpegD2pDecoder finished");
}

int FFmpegD2pDecoder::decodeD2pData(uint8_t *sourceData, size_t size, uint8_t *outData) {
    if (!codecCtx || !swrCtx || !mFrame || !mPacket) {
        LOGE("Decoder components not initialized");
        return -1;
    }

    // 1. 填充 Packet
    // 直接引用外部数据，不进行内存拷贝
    mPacket->data = sourceData;
    mPacket->size = (int)size;

    // 2. 发送 Packet 到解码器
    int ret = avcodec_send_packet(codecCtx, mPacket);
    if (ret < 0) {
        LOGE("avcodec_send_packet error: %d", ret);
        // 复位 packet 引用，防止野指针
        mPacket->data = nullptr;
        mPacket->size = 0;
        return -1;
    }

    int totalBytesWritten = 0;
    int outBytesPerSample = av_get_bytes_per_sample(outSampleFormat);
    int outChannels = 2; // Stereo

    // 3. 循环接收 Frame (标准流程)
    while (true) {
        ret = avcodec_receive_frame(codecCtx, mFrame);

        // 接收完成或需要更多数据
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
        }
        if (ret < 0) {
            LOGE("avcodec_receive_frame error: %d", ret);
            break; // 出错也要跳出
        }

        // 4. 计算重采样后的采样数
        // swr_get_delay 用于处理重采样器内部缓冲
        int64_t delay = swr_get_delay(swrCtx, codecCtx->sample_rate);
        int expected_out_samples = (int)av_rescale_rnd(
                delay + mFrame->nb_samples,
                d2pSampleRate,
                codecCtx->sample_rate,
                AV_ROUND_UP);

        // 准备输出 buffer 指针 (处理多次 receive 的情况，偏移指针)
        uint8_t *output_buffer_array[2] = {outData + totalBytesWritten, nullptr};

        // 5. 执行转换
        int convertedSamples = swr_convert(swrCtx,
                                           output_buffer_array,
                                           expected_out_samples,
                                           (const uint8_t **) mFrame->data,
                                           mFrame->nb_samples);

        if (convertedSamples > 0) {
            // 计算本次写入的字节数
            int currentSize = convertedSamples * outBytesPerSample * outChannels;
            totalBytesWritten += currentSize;
        }
        av_frame_unref(mFrame);
    }

    // 复位 Packet 数据指针，避免下次误用
    mPacket->data = nullptr;
    mPacket->size = 0;

    // 返回实际解码并写入缓冲区的总字节数
    return totalBytesWritten;
}