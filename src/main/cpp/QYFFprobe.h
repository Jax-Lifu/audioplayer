//
// Created by Administrator on 2024/12/26.
//

#ifndef QYLAUNCHER_QYFFPROBE_H
#define QYLAUNCHER_QYFFPROBE_H

#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <locale>
#include <codecvt>

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
#include <libavutil/imgutils.h>
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

// 创建 FFMediaInfo 对象
jobject createFFMediaInfoObject(JNIEnv *env);

// 打开媒体文件并返回 AVFormatContext
AVFormatContext *openMediaFile(const char *path);

// 设置基础的媒体信息字段
void setBasicMediaInfoFields(JNIEnv *env, jobject ffMediaInfoObject, AVFormatContext *fmt_ctx);

// 设置媒体标签信息（如标题、艺术家、专辑等）
void setMediaTags(JNIEnv *env, jobject ffMediaInfoObject, AVFormatContext *fmt_ctx);

// 设置音频/视频流信息
void setStreamInfo(JNIEnv *env, jobject ffMediaInfoObject, AVFormatContext *fmt_ctx);

// 设置音频流信息
void setAudioStreamInfo(JNIEnv *env, jobject ffMediaInfoObject, AVCodecParameters *parameters,
                        jfieldID codecTypeField, jfieldID codecNameField,
                        jfieldID codecLongNameField,
                        jfieldID channelsField, jfieldID channelLayoutField,
                        jfieldID sampleRateField,
                        jfieldID bitPerSampleField);

// 设置视频流信息，包括封面图
void
setVideoStreamInfo(JNIEnv *env, jobject ffMediaInfoObject, AVStream *stream, jfieldID imageField);

#endif //QYLAUNCHER_QYFFPROBE_H
