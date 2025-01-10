//
// Created by Administrator on 2025/1/6.
//

#ifndef QYLAUNCHER_UTILS_H
#define QYLAUNCHER_UTILS_H


#include <jni.h>
#include <android/log.h>

#define LOG_TAG "qy-ffmpeg"

#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))


class Utils {

public:
    static jstring charToJString(JNIEnv *env, const char *data);

    static const char *jStringToChar(JNIEnv *env, jstring data);

};


#endif //QYLAUNCHER_UTILS_H
