//
// Created by Administrator on 2025/12/2.
//

#ifndef QYLAUNCHER_STRINGUTILS_H
#define QYLAUNCHER_STRINGUTILS_H

#include "jni.h"
#include "string"

// 检查是否为合法的 UTF-8
static bool isValidUtf8(const char *str) {
    if (!str) return true;
    const unsigned char *bytes = (const unsigned char *) str;
    while (*bytes) {
        if ((*bytes & 0x80) == 0) { // ASCII
            bytes++;
        } else if ((*bytes & 0xE0) == 0xC0) { // 2-byte sequence
            if ((bytes[1] & 0xC0) != 0x80) return false;
            bytes += 2;
        } else if ((*bytes & 0xF0) == 0xE0) { // 3-byte sequence
            if ((bytes[1] & 0xC0) != 0x80 || (bytes[2] & 0xC0) != 0x80) return false;
            bytes += 3;
        } else if ((*bytes & 0xF8) == 0xF0) { // 4-byte sequence
            if ((bytes[1] & 0xC0) != 0x80 || (bytes[2] & 0xC0) != 0x80 ||
                (bytes[3] & 0xC0) != 0x80)
                return false;
            bytes += 4;
        } else {
            return false;
        }
    }
    return true;
}

// 安全的字符串转换函数
static jstring safeNewStringUTF(JNIEnv *env, const std::string &str) {
    if (str.empty()) {
        return env->NewStringUTF("");
    }

    const char *c_str = str.c_str();

    // 1. 如果是合法的 UTF-8，直接使用高效的 NewStringUTF
    if (isValidUtf8(c_str)) {
        return env->NewStringUTF(c_str);
    }

    // 2. 如果不是 UTF-8，大概率是 GBK (中文环境常见的 CUE/ISO 问题)
    // 我们使用 Java 的 new String(bytes, "GBK") 来构建
    int len = str.length();
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");

    jbyteArray bytes = env->NewByteArray(len);
    env->SetByteArrayRegion(bytes, 0, len, (jbyte *) c_str);

    // 尝试用 GBK 解码 (涵盖 GB2312)
    jstring encoding = env->NewStringUTF("GBK");

    jstring result = (jstring) env->NewObject(strClass, ctor, bytes, encoding);

    // 清理局部引用
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(encoding);
    env->DeleteLocalRef(strClass); // FindClass 返回的是局部引用

    return result;
}

#endif //QYLAUNCHER_STRINGUTILS_H
