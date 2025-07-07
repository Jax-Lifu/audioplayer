#include <jni.h>

#include <fstream>
#include "audio/ffmpeg_audio_reader.h"
#include "utils/scope_exit.h"
#include "chromaprint.h"
#include "android_log.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_com_qytech_audioplayer_ffprobe_FFprobe_getFingerprint(
        JNIEnv *env,
        jobject thiz,
        jstring source,
        jint durationSeconds
) {
    const char *filePath = env->GetStringUTFChars(source, nullptr);
    chromaprint::FFmpegAudioReader reader;
    if (!reader.Open(filePath)) {
        LOGE("Failed to open file: %s", filePath);
        env->ReleaseStringUTFChars(source, filePath);
        return nullptr;
    }
    ChromaprintContext *ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!ctx) {
        LOGE("Failed to create chromaprint context");
        env->ReleaseStringUTFChars(source, filePath);
        return nullptr;
    }
    reader.SetOutputChannels(chromaprint_get_num_channels(ctx));
    reader.SetOutputSampleRate(chromaprint_get_sample_rate(ctx));

    if (!chromaprint_start(ctx, reader.GetSampleRate(), reader.GetChannels())) {
        LOGE("Failed to start chromaprint");
        chromaprint_free(ctx);
        env->ReleaseStringUTFChars(source, filePath);
        return nullptr;
    }
    const int16_t *frame_data = nullptr;
    size_t frame_size = 0;
    // 单通道采样数
    const size_t max_samples = durationSeconds * reader.GetSampleRate();
    size_t samples_processed = 0;


    while (!reader.IsFinished() && samples_processed < max_samples) {
        if (!reader.Read(&frame_data, &frame_size)) {
            LOGE("Failed to read frame");
            chromaprint_free(ctx);
            env->ReleaseStringUTFChars(source, filePath);
            return nullptr; // 读取失败
        }

        if (frame_size == 0) continue;
        size_t samples_remaining = max_samples - samples_processed;
        size_t frame_samples = frame_size;
        if (frame_samples > samples_remaining) {
            frame_samples = samples_remaining;
        }
        if (!chromaprint_feed(ctx, frame_data, frame_size * reader.GetChannels())) {
            chromaprint_free(ctx);
            env->ReleaseStringUTFChars(source, filePath);
            return env->NewStringUTF(""); // feed 失败
        }
        samples_processed += frame_samples;
        if (frame_samples < frame_size) break;
    }

    if (!chromaprint_finish(ctx)) {
        chromaprint_free(ctx);
        env->ReleaseStringUTFChars(source, filePath);
        return env->NewStringUTF(""); // 结束失败
    }
    char *fp_str = nullptr;
    if (!chromaprint_get_fingerprint(ctx, &fp_str)) {
        chromaprint_free(ctx);
        env->ReleaseStringUTFChars(source, filePath);
        return env->NewStringUTF(""); // 获取 fingerprint 失败
    }
    jstring result = env->NewStringUTF(fp_str);

    chromaprint_dealloc(fp_str);
    chromaprint_free(ctx);
    env->ReleaseStringUTFChars(source, filePath);
    return result;
}