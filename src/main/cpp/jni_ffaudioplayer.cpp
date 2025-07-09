//
// Created by Administrator on 2025/1/6.
//
#include <jni.h>
#include "Utils.h"
#include "FFAudioPlayer.h"

JavaVM *jvm = nullptr;

jobject ffAudioPlayerCallback = nullptr;
jmethodID onAudioDataReceivedMethod = nullptr;

jobject onCompletionListener = nullptr;
jmethodID onCompletionMethod = nullptr;

FFAudioPlayer *player = nullptr;
//FFAudioPlayer player;

void playAudio(const char *data, int size) {
    if (size <= 0) {
        return;
    }
    if (ffAudioPlayerCallback == nullptr) {
        return;
    }
    JNIEnv *env;
    if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return;
    }

    if (onAudioDataReceivedMethod == nullptr) {
        jclass ffAudioPlayerCallbackClass = env->GetObjectClass(ffAudioPlayerCallback);
        onAudioDataReceivedMethod = env->GetMethodID(ffAudioPlayerCallbackClass,
                                                     "onAudioDataReceived",
                                                     "([B)V");
    }

    if (onAudioDataReceivedMethod == nullptr) {
        return;
    }

    jbyteArray byteArray = env->NewByteArray(size);
    env->SetByteArrayRegion(byteArray, 0, size, (jbyte *) data);
    env->CallVoidMethod(ffAudioPlayerCallback, onAudioDataReceivedMethod, byteArray);
    env->DeleteLocalRef(byteArray);
}

void onCompletion() {
    if (onCompletionListener == nullptr) {
        return;
    }
    JNIEnv *env;
    if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return;
    }
    if (onCompletionMethod == nullptr) {
        jclass onCompletionListenerClass = env->GetObjectClass(onCompletionListener);
        onCompletionMethod = env->GetMethodID(onCompletionListenerClass,
                                              "onCompletion",
                                              "()V");
    }

    if (onCompletionMethod == nullptr) {
        return;
    }
    env->CallVoidMethod(onCompletionListener, onCompletionMethod);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, [[maybe_unused]] void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1init(JNIEnv *env, jobject thiz,
                                                              jstring file_path,
                                                              jobject headers) {
    if (player != nullptr) {
        player->stop();
        player->release();
        delete player;
        player = nullptr;
    }
    std::string header = Utils::buildHeaderStringFromMap(env, headers);
    if (!header.empty()) {
        LOGD("header: %s", header.c_str());
    }
    player = new FFAudioPlayer();
    player->init(Utils::jStringToChar(env, file_path), header.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1seek(JNIEnv *env, jobject thiz,
                                                              jlong position) {
    if (player == nullptr) {
        return;
    }
    player->seek(position);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1release(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return;
    }
    player->release();
}
extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1stop(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return;
    }
    player->stop();
}
extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1pause(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return;
    }
    player->pause();
}
extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1play(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return;
    }
    player->play();
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1getDuration(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return 0;
    }
    return player->getDuration();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1getChannels(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return 0;
    }
    return player->getChannelNumber();
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1getSampleRate(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return 0;
    }
    return player->getSampleRate();
}



extern "C"
JNIEXPORT jlong JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1getCurrentPosition(JNIEnv *env,
                                                                            jobject thiz) {
    if (player == nullptr) {
        return 0;
    }
    return player->getCurrentPosition();
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1getPlayState(JNIEnv *env, jobject thiz) {
    if (player == nullptr) {
        return 0;
    }
    return player->getPlayState();
}
extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1setCallback(JNIEnv *env, jobject thiz,
                                                                     jobject callback) {
    env->GetJavaVM(&jvm);
    if (ffAudioPlayerCallback != nullptr) {
        env->DeleteGlobalRef(ffAudioPlayerCallback);
    }
    ffAudioPlayerCallback = env->NewGlobalRef(callback);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_player_FFAudioPlayer_native_1setOnCompletionListener(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jobject listener) {
    env->GetJavaVM(&jvm);
    if (onCompletionListener != nullptr) {
        env->DeleteGlobalRef(onCompletionListener);
    }
    onCompletionListener = env->NewGlobalRef(listener);

}