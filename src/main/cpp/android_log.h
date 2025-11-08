//
// Created by Administrator on 2025/6/4.
//
#include <android/log.h>

#ifndef QYLAUNCHER_ANDROID_LOG_H
#define QYLAUNCHER_ANDROID_LOG_H


#define TAG "player-jni"
#ifdef DEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, TAG, __VA_ARGS__)
#else
// 发布版本不输出任何日志
#define LOGD(...)
#define LOGI(...)
#define LOGW(...)
#define LOGE(...)
#define LOGF(...)
#endif

#endif //QYLAUNCHER_ANDROID_LOG_H
