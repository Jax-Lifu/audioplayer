//
// Created by Administrator on 2025/6/4.
//
#include <android/log.h>

#ifndef QYLAUNCHER_ANDROID_LOG_H
#define QYLAUNCHER_ANDROID_LOG_H


#define LOG_TAG "player-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#endif //QYLAUNCHER_ANDROID_LOG_H
