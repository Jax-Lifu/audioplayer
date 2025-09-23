#include <jni.h>
#include <string>
#include <android/log.h>
#include <mutex>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
}

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FFmpegDST", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "FFmpegDST", __VA_ARGS__)

static AVCodecContext *codec_ctx = nullptr;
static SwrContext *swr_ctx = nullptr;
static AVFrame *frame = nullptr;
static AVPacket *pkt = nullptr;
static std::mutex decoder_mutex; // 保护 decoder 资源

static const int OUT_SAMPLE_RATE = 192000;
static const int OUT_CHANNELS = 2;
static const int64_t OUT_CHANNEL_LAYOUT = AV_CH_LAYOUT_STEREO;
static const AVSampleFormat OUT_SAMPLE_FMT = AV_SAMPLE_FMT_S16;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_qytech_audioplayer_player_FFmpegDstDecoder_initDstDecoder(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(decoder_mutex); // 加锁防止同时操作

    // 如果已经初始化，先释放
    if (codec_ctx || swr_ctx || frame || pkt) {
        LOGD("Decoder already initialized, releasing first");
        if (codec_ctx) {
            avcodec_free_context(&codec_ctx);
            codec_ctx = nullptr;
        }
        if (frame) {
            av_frame_free(&frame);
            frame = nullptr;
        }
        if (pkt) {
            av_packet_free(&pkt);
            pkt = nullptr;
        }
        if (swr_ctx) {
            swr_free(&swr_ctx);
            swr_ctx = nullptr;
        }
    }

    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_DST);
    if (!codec) {
        LOGE("DST codec not found");
        return JNI_FALSE;
    }

    codec_ctx = avcodec_alloc_context3(codec);
    if (!codec_ctx) {
        LOGE("Could not allocate codec context");
        return JNI_FALSE;
    }

    codec_ctx->ch_layout = AV_CHANNEL_LAYOUT_STEREO;
    codec_ctx->sample_rate = 2822400 / 8;
    codec_ctx->bits_per_coded_sample = 1;

    if (avcodec_open2(codec_ctx, codec, nullptr) < 0) {
        LOGE("Could not open codec");
        avcodec_free_context(&codec_ctx);
        return JNI_FALSE;
    }

    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (!frame || !pkt) {
        LOGE("Could not allocate frame or packet");
        avcodec_free_context(&codec_ctx);
        if (frame) av_frame_free(&frame);
        if (pkt) av_packet_free(&pkt);
        return JNI_FALSE;
    }
    enum AVSampleFormat in_sample_fmt = codec_ctx->sample_fmt;
    int in_sample_rate = codec_ctx->sample_rate;
    enum AVSampleFormat out_sample_fmt = OUT_SAMPLE_FMT;
    int out_sample_rate = OUT_SAMPLE_RATE;
    AVChannelLayout in_ch_layout = AV_CHANNEL_LAYOUT_STEREO;
    AVChannelLayout out_ch_layout = AV_CHANNEL_LAYOUT_STEREO;
    if (swr_alloc_set_opts2(&swr_ctx,
                            &out_ch_layout, out_sample_fmt, out_sample_rate,
                            &in_ch_layout, in_sample_fmt, in_sample_rate,
                            0, nullptr) < 0) {
        LOGE("swr_alloc_set_opts2 failed");
        swr_free(&swr_ctx);
        return JNI_FALSE;
    }

    if (!swr_ctx || swr_init(swr_ctx) < 0) {
        LOGE("Failed to initialize SwrContext");
        if (swr_ctx) swr_free(&swr_ctx);
        av_frame_free(&frame);
        av_packet_free(&pkt);
        avcodec_free_context(&codec_ctx);
        return JNI_FALSE;
    }

    LOGD("DST Decoder initialized successfully");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_qytech_audioplayer_player_FFmpegDstDecoder_decodeDstFrame(JNIEnv *env, jobject thiz,
                                                                   jbyteArray data) {
    std::lock_guard<std::mutex> lock(decoder_mutex);

    if (!codec_ctx || !swr_ctx || !frame || !pkt) {
        LOGE("Decoder not initialized");
        return nullptr;
    }

    jbyte *input_bytes = env->GetByteArrayElements(data, nullptr);
    int input_size = env->GetArrayLength(data);

    av_packet_unref(pkt);
    pkt->data = (uint8_t *) input_bytes;
    pkt->size = input_size;

    int ret = avcodec_send_packet(codec_ctx, pkt);
    if (ret < 0) {
        LOGE("Error sending packet: %d", ret);
        env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);
        return nullptr;
    }

    ret = avcodec_receive_frame(codec_ctx, frame);
    if (ret < 0) {
        // DST 解码器可能需要多帧才能输出
        LOGD("No frame received yet: %d", ret);
        env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);
        return nullptr;
    }

    int dst_nb_samples = av_rescale_rnd(
            swr_get_delay(swr_ctx, codec_ctx->sample_rate) + frame->nb_samples,
            OUT_SAMPLE_RATE,
            codec_ctx->sample_rate,
            AV_ROUND_UP);

    int out_buf_size = av_samples_get_buffer_size(
            nullptr, OUT_CHANNELS, dst_nb_samples, OUT_SAMPLE_FMT, 1);

    auto *out_buf = (uint8_t *) av_malloc(out_buf_size);
    if (!out_buf) {
        LOGE("Could not allocate output buffer");
        env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);
        return nullptr;
    }

    uint8_t *out_planes[OUT_CHANNELS] = {nullptr};
    av_samples_fill_arrays(out_planes, nullptr, out_buf, OUT_CHANNELS, dst_nb_samples,
                           OUT_SAMPLE_FMT, 1);

    int converted = swr_convert(swr_ctx, out_planes, dst_nb_samples, (const uint8_t **) frame->data,
                                frame->nb_samples);
    if (converted < 0) {
        LOGE("swr_convert failed: %d", converted);
        av_free(out_buf);
        env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);
        return nullptr;
    }

    int out_size = av_samples_get_buffer_size(nullptr, OUT_CHANNELS, converted, OUT_SAMPLE_FMT, 1);
    jbyteArray result = env->NewByteArray(out_size);
    env->SetByteArrayRegion(result, 0, out_size, (jbyte *) out_buf);

    av_free(out_buf);
    env->ReleaseByteArrayElements(data, input_bytes, JNI_ABORT);

    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFmpegDstDecoder_releaseDstDecoder(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(decoder_mutex);

    if (codec_ctx) {
        avcodec_free_context(&codec_ctx);
        codec_ctx = nullptr;
    }
    if (frame) {
        av_frame_free(&frame);
        frame = nullptr;
    }
    if (pkt) {
        av_packet_free(&pkt);
        pkt = nullptr;
    }
    if (swr_ctx) {
        swr_free(&swr_ctx);
        swr_ctx = nullptr;
    }

    LOGD("Decoder released");
}
