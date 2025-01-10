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

