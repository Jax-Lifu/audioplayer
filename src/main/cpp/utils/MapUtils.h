//
// Created by Administrator on 2025/12/11.
//

#ifndef QYLAUNCHER_MAPUTILS_H
#define QYLAUNCHER_MAPUTILS_H

#include <map>
#include "jni.h"

// 辅助：Java Map -> std::map
static std::map <std::string, std::string> jmapToStdMap(JNIEnv *env, jobject jmap) {
    std::map <std::string, std::string> cppMap;
    if (!jmap) return cppMap;

    jclass mapClass = env->GetObjectClass(jmap);
    jobject entrySet = env->CallObjectMethod(jmap, env->GetMethodID(mapClass, "entrySet",
                                                                    "()Ljava/util/Set;"));
    jobject iterator = env->CallObjectMethod(entrySet,
                                             env->GetMethodID(env->FindClass("java/util/Set"),
                                                              "iterator",
                                                              "()Ljava/util/Iterator;"));
    jmethodID hasNext = env->GetMethodID(env->FindClass("java/util/Iterator"), "hasNext", "()Z");
    jmethodID next = env->GetMethodID(env->FindClass("java/util/Iterator"), "next",
                                      "()Ljava/lang/Object;");
    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID getKey = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
    jmethodID getValue = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");

    while (env->CallBooleanMethod(iterator, hasNext)) {
        // 核心修复：防止 Map 遍历时局部引用溢出
        env->PushLocalFrame(16);
        jobject entry = env->CallObjectMethod(iterator, next);
        jstring k = (jstring) env->CallObjectMethod(entry, getKey);
        jstring v = (jstring) env->CallObjectMethod(entry, getValue);
        if (k && v) {
            const char *ck = env->GetStringUTFChars(k, nullptr);
            const char *cv = env->GetStringUTFChars(v, nullptr);
            cppMap[ck] = cv;
            env->ReleaseStringUTFChars(k, ck);
            env->ReleaseStringUTFChars(v, cv);
        }
        env->PopLocalFrame(nullptr);
    }
    return cppMap;
}

#endif //QYLAUNCHER_MAPUTILS_H
