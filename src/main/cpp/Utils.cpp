//
// Created by Administrator on 2025/1/6.
//

#include <cstring>
#include "Utils.h"

const char *Utils::jStringToChar(JNIEnv *env, jstring data) {
    if (!data) {
        return nullptr;
    }
    const char *result = env->GetStringUTFChars(data, nullptr);
    return result;
}

jstring Utils::charToJString(JNIEnv *env, const char *data) {
    if (!data) {
        return nullptr;
    }
    jclass cls_Charset = env->FindClass("java/nio/charset/Charset");
    jmethodID mid_Charset_forName = env->GetStaticMethodID(cls_Charset, "forName",
                                                           "(Ljava/lang/String;)Ljava/nio/charset/Charset;");
    jmethodID mid_Charset_decode = env->GetMethodID(cls_Charset, "decode",
                                                    "(Ljava/nio/ByteBuffer;)Ljava/nio/CharBuffer;");
    jclass cls_CharBuffer = env->FindClass("java/nio/CharBuffer");
    jmethodID mid_CharBuffer_toString = env->GetMethodID(cls_CharBuffer, "toString",
                                                         "()Ljava/lang/String;");
    jobject bb = env->NewDirectByteBuffer((void *) data, (int) strlen(data));
    jobject charset = env->CallStaticObjectMethod(cls_Charset, mid_Charset_forName,
                                                  env->NewStringUTF("UTF-8"));

    jobject cb = env->CallObjectMethod(charset, mid_Charset_decode, bb);
    auto result = (jstring) env->CallObjectMethod(cb, mid_CharBuffer_toString);

    env->DeleteLocalRef(bb);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cb);
    return result;
}

// 辅助：从 Java Map<String, String> 构建 header 字符串
std::string Utils::buildHeaderStringFromMap(JNIEnv *env, jobject jMap) {
    std::string header;

    // 1. 获取 Map 类和方法
    jclass mapClass = env->GetObjectClass(jMap);
    jmethodID entrySetMethod = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
    jobject entrySet = env->CallObjectMethod(jMap, entrySetMethod);

    // 2. 获取 Set.iterator()
    jclass setClass = env->GetObjectClass(entrySet);
    jmethodID iteratorMethod = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);

    // 3. 准备 Entry 类
    jclass iteratorClass = env->GetObjectClass(iterator);
    jmethodID hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

    jclass entryClass = nullptr;
    jmethodID getKeyMethod = nullptr;
    jmethodID getValueMethod = nullptr;

    // 4. 遍历 Map
    while (env->CallBooleanMethod(iterator, hasNextMethod)) {
        jobject entry = env->CallObjectMethod(iterator, nextMethod);
        if (entryClass == nullptr) {
            entryClass = env->GetObjectClass(entry);
            getKeyMethod = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
            getValueMethod = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");
        }

        auto jKey = (jstring) env->CallObjectMethod(entry, getKeyMethod);
        auto jValue = (jstring) env->CallObjectMethod(entry, getValueMethod);

        const char *key = env->GetStringUTFChars(jKey, nullptr);
        const char *value = env->GetStringUTFChars(jValue, nullptr);

        // 拼接成 "key: value\r\n"
        header += key;
        header += ": ";
        header += value;
        header += "\r\n";

        env->ReleaseStringUTFChars(jKey, key);
        env->ReleaseStringUTFChars(jValue, value);
        env->DeleteLocalRef(jKey);
        env->DeleteLocalRef(jValue);
        env->DeleteLocalRef(entry);
    }

    return header;
}