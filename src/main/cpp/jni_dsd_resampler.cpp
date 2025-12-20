#include "jni_dsd_resampler.h"
#include "DsdUtils.h"

// ----------------------------------------------------------------------------
// JNI 实现 - 实例方法 (FFmpeg D2P 转码)
// ----------------------------------------------------------------------------

static jlong DsdResampler_nativeInit(JNIEnv *env, jobject thiz,
                                     jint dsdRate, jint targetPcmRate, jint targetBitDepth) {
    auto *decoder = new FFmpegD2pDecoder();
    if (!decoder->init(dsdRate, targetPcmRate, targetBitDepth)) {
        delete decoder;
        return 0;
    }
    return (jlong) decoder;
}

// ByteArray 版本 D2P
static jint DsdResampler_nativePackD2p(JNIEnv *env, jobject thiz, jlong ctxPtr,
                                       jbyteArray dsdData, jint size, jbyteArray pcmOut) {
    auto *decoder = (FFmpegD2pDecoder *) ctxPtr;
    if (!decoder) return 0;

    jbyte *in_bytes = env->GetByteArrayElements(dsdData, nullptr);
    jbyte *out_bytes = env->GetByteArrayElements(pcmOut, nullptr);

    int bytesWritten = decoder->process((uint8_t *) in_bytes, size, (uint8_t *) out_bytes);

    env->ReleaseByteArrayElements(dsdData, in_bytes, JNI_ABORT);
    env->ReleaseByteArrayElements(pcmOut, out_bytes, 0);

    return (bytesWritten < 0) ? 0 : bytesWritten;
}

// DirectBuffer 版本 D2P (零拷贝)
static jint DsdResampler_nativePackD2pDirect(JNIEnv *env, jobject thiz, jlong ctxPtr,
                                             jobject srcDirectBuf, jint size, jobject outDirectBuf) {
    auto *decoder = (FFmpegD2pDecoder *) ctxPtr;
    if (!decoder || srcDirectBuf == nullptr || outDirectBuf == nullptr) return 0;

    auto *srcPtr = (uint8_t *) env->GetDirectBufferAddress(srcDirectBuf);
    auto *outPtr = (uint8_t *) env->GetDirectBufferAddress(outDirectBuf);

    if (srcPtr == nullptr || outPtr == nullptr) return 0;

    int bytesWritten = decoder->process(srcPtr, size, outPtr);
    return (bytesWritten < 0) ? 0 : bytesWritten;
}

static void DsdResampler_nativeRelease(JNIEnv *env, jobject thiz, jlong ctxPtr) {
    auto *decoder = (FFmpegD2pDecoder *) ctxPtr;
    if (decoder) {
        delete decoder;
    }
}

// ----------------------------------------------------------------------------
// JNI 实现 - 静态工具方法 (ByteArray 版本)
// ----------------------------------------------------------------------------

static jint DsdResampler_nativePackDoP(JNIEnv *env, jclass clazz,
                                       jboolean msbf, jbyteArray src, jint size, jbyteArray out) {
    if (src == nullptr || out == nullptr) return 0;
    jbyte *srcPtr = env->GetByteArrayElements(src, nullptr);
    jbyte *outPtr = env->GetByteArrayElements(out, nullptr);
    int ret = DsdUtils::packDoP(msbf, (const uint8_t *) srcPtr, size, (uint8_t *) outPtr);
    env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    return ret;
}

static jint DsdResampler_nativePackNative(JNIEnv *env, jclass clazz,
                                          jboolean msbf, jbyteArray src, jint size, jbyteArray out) {
    if (src == nullptr || out == nullptr) return 0;
    jbyte *srcPtr = env->GetByteArrayElements(src, nullptr);
    jbyte *outPtr = env->GetByteArrayElements(out, nullptr);
    int ret = DsdUtils::packNative(msbf, (const uint8_t *) srcPtr, size, (uint8_t *) outPtr);
    env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    return ret;
}

static jint DsdResampler_nativePack4ChannelNative(JNIEnv *env, jclass clazz,
                                                  jboolean msbf, jbyteArray src, jint size, jbyteArray out) {
    if (src == nullptr || out == nullptr) return 0;
    jbyte *srcPtr = env->GetByteArrayElements(src, nullptr);
    jbyte *outPtr = env->GetByteArrayElements(out, nullptr);
    int ret = DsdUtils::pack4ChannelNative(msbf, (const uint8_t *) srcPtr, size, (uint8_t *) outPtr);
    env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    return ret;
}

// ----------------------------------------------------------------------------
// JNI 实现 - DirectBuffer 版本 (零拷贝，高性能推荐)
// ----------------------------------------------------------------------------

// Native DSD (Stereo)
static jint DsdResampler_nativePackNativeDirect(JNIEnv *env, jclass clazz,
                                                jboolean msbf, jobject srcDirectBuf, jint size, jobject outDirectBuf) {
    if (srcDirectBuf == nullptr || outDirectBuf == nullptr) return 0;
    auto *srcPtr = (uint8_t *) env->GetDirectBufferAddress(srcDirectBuf);
    auto *outPtr = (uint8_t *) env->GetDirectBufferAddress(outDirectBuf);
    if (srcPtr == nullptr || outPtr == nullptr) return 0;

    return DsdUtils::packNative(msbf, srcPtr, size, outPtr);
}

// DoP (Stereo)
static jint DsdResampler_nativePackDoPDirect(JNIEnv *env, jclass clazz,
                                             jboolean msbf, jobject srcDirectBuf, jint size, jobject outDirectBuf) {
    if (srcDirectBuf == nullptr || outDirectBuf == nullptr) return 0;
    auto *srcPtr = (uint8_t *) env->GetDirectBufferAddress(srcDirectBuf);
    auto *outPtr = (uint8_t *) env->GetDirectBufferAddress(outDirectBuf);
    if (srcPtr == nullptr || outPtr == nullptr) return 0;

    return DsdUtils::packDoP(msbf, srcPtr, size, outPtr);
}

// Native DSD (4 Channel)
static jint DsdResampler_nativePack4ChannelNativeDirect(JNIEnv *env, jclass clazz,
                                                        jboolean msbf, jobject srcDirectBuf, jint size, jobject outDirectBuf) {
    if (srcDirectBuf == nullptr || outDirectBuf == nullptr) return 0;
    auto *srcPtr = (uint8_t *) env->GetDirectBufferAddress(srcDirectBuf);
    auto *outPtr = (uint8_t *) env->GetDirectBufferAddress(outDirectBuf);
    if (srcPtr == nullptr || outPtr == nullptr) return 0;

    return DsdUtils::pack4ChannelNative(msbf, srcPtr, size, outPtr);
}

// ----------------------------------------------------------------------------
// 动态注册
// ----------------------------------------------------------------------------

static const char *CLASS_NAME = "com/qytech/audioplayer/audioframe/DsdResampler";

static const JNINativeMethod gMethods[] = {
        // --- 实例方法 (ByteArray) ---
        {"nativeInit",               "(III)J",    (void *) DsdResampler_nativeInit},
        {"nativePackD2p",            "(J[BI[B)I", (void *) DsdResampler_nativePackD2p},
        {"nativeRelease",            "(J)V",      (void *) DsdResampler_nativeRelease},

        // --- 实例方法 (DirectBuffer) ----
        {"nativePackD2pDirect",      "(JLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;)I", (void *) DsdResampler_nativePackD2pDirect},

        // --- 静态方法 (ByteArray) ---
        {"nativePackDoP",            "(Z[BI[B)I", (void *) DsdResampler_nativePackDoP},
        {"nativePackNative",         "(Z[BI[B)I", (void *) DsdResampler_nativePackNative},
        {"nativePack4ChannelNative", "(Z[BI[B)I", (void *) DsdResampler_nativePack4ChannelNative},

        // --- 静态方法 (DirectBuffer) ----
        {"nativePackNativeDirect",        "(ZLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;)I", (void *) DsdResampler_nativePackNativeDirect},
        {"nativePackDoPDirect",           "(ZLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;)I", (void *) DsdResampler_nativePackDoPDirect},
        {"nativePack4ChannelNativeDirect","(ZLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;)I", (void *) DsdResampler_nativePack4ChannelNativeDirect}
};

int register_dsd_resampler_methods(JavaVM *vm, JNIEnv *env) {
    jclass clazz = env->FindClass(CLASS_NAME);
    if (clazz == nullptr) return JNI_ERR;
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0)
        return JNI_ERR;
    return JNI_OK;
}