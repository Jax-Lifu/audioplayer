//
// Created by Administrator on 2025/1/6.
//

#ifndef QYLAUNCHER_UTILS_H
#define QYLAUNCHER_UTILS_H

#include <string>
#include <jni.h>
#include "android_log.h"

class Utils {

public:
    static jstring charToJString(JNIEnv *env, const char *data);

    static const char *jStringToChar(JNIEnv *env, jstring data);

    static std::string buildHeaderStringFromMap(JNIEnv *env, jobject jMap);
};


#endif //QYLAUNCHER_UTILS_H
