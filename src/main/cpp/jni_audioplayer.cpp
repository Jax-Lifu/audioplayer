#include <jni.h>
#include <string>
#include <vector>
#include "player/FFPlayer.h"
#include "player/SacdPlayer.h"
#include "player/PlayerDefines.h"
#include "jni_audioplayer.h"


// ============================================================================
// JNI 回调实现 (C++ -> Kotlin)
// ============================================================================
class JniCallback : public IPlayerCallback {
private:
    static JavaVM *getJVM(JavaVM *newValue = nullptr) {
        // C++11 保证了 static 局部变量的初始化是线程安全的，且全局唯一
        static JavaVM *g_stored_jvm = nullptr;
        if (newValue != nullptr) {
            g_stored_jvm = newValue;
        }
        return g_stored_jvm;
    }

public:
    static void setJavaVM(JavaVM *vm) {
        // 保存到静态局部变量中
        getJVM(vm);
        LOGD("ProbeUtils: setJavaVM called. JVM Saved.");
    }

    JniCallback(JNIEnv *env, jobject callbackObj) {
        javaCallbackObj = env->NewGlobalRef(callbackObj);

        // 2. 获取 Callback 的类 (不管是匿名内部类还是接口)
        jclass clazz = env->GetObjectClass(callbackObj);

        // 3. 获取方法 ID (签名保持不变)
        jmid_onPrepared = env->GetMethodID(clazz, "onPrepared", "()V");
        jmid_onProgress = env->GetMethodID(clazz, "onProgress", "(IJJF)V");
        jmid_onError = env->GetMethodID(clazz, "onError", "(ILjava/lang/String;)V");
        jmid_onComplete = env->GetMethodID(clazz, "onComplete", "()V");
        jmid_onAudioData = env->GetMethodID(clazz, "onAudioData", "([BI)V");

        // 记得删除局部引用
        env->DeleteLocalRef(clazz);
    }

    ~JniCallback() override {
        JNIEnv *env = getEnv();
        if (env && javaCallbackObj) {
            env->DeleteGlobalRef(javaCallbackObj);
            javaCallbackObj = nullptr;
        }
    }

    bool isValid() {
        return javaCallbackObj != nullptr;
    }

    void onPrepared() override {
        callVoidMethod(jmid_onPrepared);
    }

    void onProgress(int trackIndex, long currentMs, long totalMs, float progress) override {
        JNIEnv *env = getEnv();
        if (env && isValid()) {
            env->CallVoidMethod(javaCallbackObj, jmid_onProgress, trackIndex, (jlong) currentMs,
                                (jlong) totalMs, progress);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    void onError(int code, const char *msg) override {
        JNIEnv *env = getEnv();
        if (env && isValid()) {
            jstring jMsg = safeNewStringUTF(env, msg);
            env->CallVoidMethod(javaCallbackObj, jmid_onError, (jint) code, jMsg);
            env->DeleteLocalRef(jMsg);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    void onComplete() override {
        callVoidMethod(jmid_onComplete);
    }

    void onAudioData(uint8_t *data, int size) override {
        JNIEnv *env = getEnv();
        if (env && isValid()) {
            jbyteArray jData = env->NewByteArray(size);
            env->SetByteArrayRegion(jData, 0, size, (jbyte *) data);

            env->CallVoidMethod(javaCallbackObj, jmid_onAudioData, jData, (jint) size);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            env->DeleteLocalRef(jData);
        }
    }

private:
    jobject javaCallbackObj;
    jmethodID jmid_onPrepared;
    jmethodID jmid_onProgress;
    jmethodID jmid_onError;
    jmethodID jmid_onComplete;
    jmethodID jmid_onAudioData;

    static JNIEnv *getEnv() {
        JNIEnv *env;
        int res = getJVM()->GetEnv((void **) &env, JNI_VERSION_1_6);
        if (res != JNI_OK) {
            // 如果当前线程没有 Attach 到 JVM (例如解码线程)，则 Attach
            if (res == JNI_EDETACHED) {
                if (getJVM()->AttachCurrentThread(&env, nullptr) != 0) {
                    return nullptr;
                }
            } else {
                return nullptr;
            }
        }
        return env;
    }

    void callVoidMethod(jmethodID mid) {
        JNIEnv *env = getEnv();
        if (env && isValid()) env->CallVoidMethod(javaCallbackObj, mid);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
};

// ============================================================================
// 上下文管理
// ============================================================================
enum PlayerType {
    TYPE_FFMPEG = 0,
    TYPE_SACD = 1
};

struct PlayerContext {
    PlayerType type;
    void *playerInstance;
    JniCallback *callback;
    std::mutex ctxMutex;
    bool isReleased = false;


    PlayerContext(PlayerType t, void *p, JniCallback *cb) : type(t), playerInstance(p),
                                                            callback(cb) {}

    ~PlayerContext() {
        if (type == TYPE_FFMPEG) {
            delete (FFPlayer *) playerInstance;
        } else {
            delete (SacdPlayer *) playerInstance;
        }
        delete callback;
    }
};

// ============================================================================
// Native 实现函数
// ============================================================================

// 获取 Context
static PlayerContext *getContext(jlong handle) {
    return reinterpret_cast<PlayerContext *>(handle);
}

#define LOCK_CONTEXT(ctx) \
    if (!ctx) return; \
    std::lock_guard<std::mutex> lock(ctx->ctxMutex); \
    if (ctx->isReleased) return;

// 1. Init
static jlong native_init(JNIEnv *env, jobject thiz, jint type, jobject jCallback) {
    auto *callback = new JniCallback(env, jCallback);
    void *player = nullptr;

    if (type == TYPE_FFMPEG) {
        player = new FFPlayer(callback);
    } else {
        player = new SacdPlayer(callback);
    }

    auto *ctx = new PlayerContext((PlayerType) type, player, callback);
    return reinterpret_cast<jlong>(ctx);
}

// 2. Set Source
static void native_setSource(JNIEnv *env, jobject thiz, jlong handle, jstring path, jstring headers,
                             jint trackIndex, jlong startPos, jlong endPos) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护

    const char *cPath = env->GetStringUTFChars(path, nullptr);
    const char *cHeaders = headers ? env->GetStringUTFChars(headers, nullptr) : "";

    if (ctx->type == TYPE_FFMPEG) {
        ((FFPlayer *) ctx->playerInstance)->setDataSource(cPath, cHeaders, startPos, endPos);
    } else {
        ((SacdPlayer *) ctx->playerInstance)->setDataSource(cPath, trackIndex);
    }

    env->ReleaseStringUTFChars(path, cPath);
    if (headers) env->ReleaseStringUTFChars(headers, cHeaders);
}

// 3. Prepare
static void native_prepare(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护
    if (ctx->type == TYPE_FFMPEG) ((FFPlayer *) ctx->playerInstance)->prepare();
    else ((SacdPlayer *) ctx->playerInstance)->prepare();
}

// 4. Play
static void native_play(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护
    if (ctx->type == TYPE_FFMPEG) ((FFPlayer *) ctx->playerInstance)->play();
    else ((SacdPlayer *) ctx->playerInstance)->play();
}

// 5. Pause
static void native_pause(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护
    if (ctx->type == TYPE_FFMPEG) ((FFPlayer *) ctx->playerInstance)->pause();
    else ((SacdPlayer *) ctx->playerInstance)->pause();
}

static void native_resume(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护
    if (ctx->type == TYPE_FFMPEG) ((FFPlayer *) ctx->playerInstance)->resume();
    else ((SacdPlayer *) ctx->playerInstance)->resume();
}

// 6. Stop
static void native_stop(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护
    if (ctx->type == TYPE_FFMPEG) ((FFPlayer *) ctx->playerInstance)->stop();
    else ((SacdPlayer *) ctx->playerInstance)->stop();
}

// 7. Seek
static void native_seek(JNIEnv *env, jobject thiz, jlong handle, jlong ms) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护
    if (ctx->type == TYPE_FFMPEG) ((FFPlayer *) ctx->playerInstance)->seek(ms);
    else ((SacdPlayer *) ctx->playerInstance)->seek(ms);
}

// 8. Release
static void native_release(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (!ctx) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(ctx->ctxMutex);
        if (ctx->isReleased) return; // 防止重复 release
        ctx->isReleased = true;
    }
    delete ctx; // 会触发析构，释放 player 和 callback
}

// 9. Config DSD
static void
native_setDsdConfig(JNIEnv *env, jobject thiz, jlong handle, jint mode, jint sampleRate) {
    auto *ctx = getContext(handle);
    LOCK_CONTEXT(ctx); // 加锁保护
    if (ctx->type == TYPE_FFMPEG) {
        ((FFPlayer *) ctx->playerInstance)->setDsdConfig((DsdMode) mode, sampleRate);
    } else {
        ((SacdPlayer *) ctx->playerInstance)->setDsdConfig((DsdMode) mode, sampleRate);
    }
}

// 10. Getters (GetSampleRate etc)
static jint native_getSampleRate(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (ctx->type == TYPE_FFMPEG) return ((FFPlayer *) ctx->playerInstance)->getSampleRate();
    else return ((SacdPlayer *) ctx->playerInstance)->getSampleRate();
}

static jint native_getChannelCount(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (ctx->type == TYPE_FFMPEG) return ((FFPlayer *) ctx->playerInstance)->getChannelCount();
    else return ((SacdPlayer *) ctx->playerInstance)->getChannelCount();
}

static jint native_getBitPerSample(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (ctx->type == TYPE_FFMPEG) return ((FFPlayer *) ctx->playerInstance)->getBitPerSample();
    else return ((SacdPlayer *) ctx->playerInstance)->getBitPerSample();
}

static jlong native_getDuration(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (ctx->type == TYPE_FFMPEG) return ((FFPlayer *) ctx->playerInstance)->getDuration();
    else return ((SacdPlayer *) ctx->playerInstance)->getDuration();
}

static jlong native_getCurrentPosition(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (ctx->type == TYPE_FFMPEG) return ((FFPlayer *) ctx->playerInstance)->getCurrentPosition();
    else return ((SacdPlayer *) ctx->playerInstance)->getCurrentPosition();
}

static jint native_getPlayerState(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (ctx->type == TYPE_FFMPEG) return ((FFPlayer *) ctx->playerInstance)->getState();
    else return ((SacdPlayer *) ctx->playerInstance)->getState();
}

static jboolean native_isDsd(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = getContext(handle);
    if (ctx->type == TYPE_FFMPEG) return ((FFPlayer *) ctx->playerInstance)->isDsd();
    else return ((SacdPlayer *) ctx->playerInstance)->isDsd();
}


// ============================================================================
// 动态注册表
// ============================================================================

static const JNINativeMethod gMethods[] = {
        {"native_init",               "(ILcom/qytech/audioplayer/player/EngineCallback;)J", (void *) native_init},
        {"native_setSource",          "(JLjava/lang/String;Ljava/lang/String;IJJ)V",        (void *) native_setSource},
        {"native_prepare",            "(J)V",                                               (void *) native_prepare},
        {"native_play",               "(J)V",                                               (void *) native_play},
        {"native_pause",              "(J)V",                                               (void *) native_pause},
        {"native_resume",             "(J)V",                                               (void *) native_resume},
        {"native_stop",               "(J)V",                                               (void *) native_stop},
        {"native_seek",               "(JJ)V",                                              (void *) native_seek},
        {"native_release",            "(J)V",                                               (void *) native_release},
        {"native_setDsdConfig",       "(JII)V",                                             (void *) native_setDsdConfig},
        {"native_getSampleRate",      "(J)I",                                               (void *) native_getSampleRate},
        {"native_getChannelCount",    "(J)I",                                               (void *) native_getChannelCount},
        {"native_getBitPerSample",    "(J)I",                                               (void *) native_getBitPerSample},
        {"native_getDuration",        "(J)J",                                               (void *) native_getDuration},
        {"native_getCurrentPosition", "(J)J",                                               (void *) native_getCurrentPosition},
        {"native_getPlayerState",     "(J)I",                                               (void *) native_getPlayerState},
        {"native_isDsd",              "(J)Z",                                               (void *) native_isDsd},
};

int register_audioplayer_methods(JavaVM *vm, JNIEnv *env) {

    static const char *className = "com/qytech/audioplayer/player/NativePlayerEngine";
    jclass clazz = env->FindClass(className);
    if (clazz == nullptr) {
        LOGE("JNI: Class %s not found", className);
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        return JNI_ERR;
    }
    JniCallback::setJavaVM(vm);
    return JNI_OK;
}