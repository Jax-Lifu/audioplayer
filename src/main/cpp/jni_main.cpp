#include <jni.h>
#include "Logger.h"
#include "jni_audioprobe.h"
#include "jni_audioplayer.h"
#include "parser/ProbeUtils.h"
#include "jni_dsd_resampler.h"


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGD("JNI_OnLoad: Starting registration...");

    // 1. 注册 AudioPlayer
    if (register_audioplayer_methods(vm, env) != JNI_OK) {
        LOGE("JNI: Failed to register AudioPlayer methods");
        return JNI_ERR;
    }

    // 2. 注册 AudioProbe
    if (register_audioprobe_methods(vm, env) != JNI_OK) {
        LOGE("JNI: Failed to register AudioProbe methods");
        return JNI_ERR;
    }

    // 3. 注册 DsdResampler
    if (register_dsd_resampler_methods(vm, env) != JNI_OK) {
        LOGE("JNI: Failed to register DsdResampler methods");
        return JNI_ERR;
    }

    LOGD("JNI_OnLoad: Success");
    return JNI_VERSION_1_6;
}