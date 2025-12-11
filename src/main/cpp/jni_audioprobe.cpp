#include "jni_audioprobe.h"
#include "parser/AudioProbe.h"
#include "parser/ProbeUtils.h"
#include "MapUtils.h"
#include <jni.h>
#include <string>
#include <map>


static jobject
nativeProbe(JNIEnv *env, jobject thiz, jstring jPath, jobject jHeaders, jstring jFilename,
            jstring jAudioSourceUrl) {
    const char *path = nullptr;
    if (jPath) {
        path = env->GetStringUTFChars(jPath, nullptr);
    } else {
        return nullptr;
    }

    auto headers = jmapToStdMap(env, jHeaders);

    // 处理原始文件名
    const char *filename = nullptr;
    std::string strFilename = "";
    if (jFilename != nullptr) {
        filename = env->GetStringUTFChars(jFilename, nullptr);
        if (filename != nullptr) {
            strFilename = filename;
        }
    }
    // 处理原始音频 URL (用于网盘 CUE)
    const char *audioUrl = nullptr;
    std::string strAudioUrl = "";
    if (jAudioSourceUrl != nullptr) {
        audioUrl = env->GetStringUTFChars(jAudioSourceUrl, nullptr);
        if (audioUrl != nullptr) {
            strAudioUrl = audioUrl;
        }
    }

    // 解析并返回元数据
    InternalMetadata meta = AudioProbe::probe(env, path, headers, strFilename, strAudioUrl);

    // === 释放字符串资源 (修复部分) ===

    // 释放 path
    if (jPath && path) {
        env->ReleaseStringUTFChars(jPath, path);
    }

    // 释放 filename
    if (jFilename != nullptr && filename != nullptr) {
        env->ReleaseStringUTFChars(jFilename, filename);
    }

    // 释放 audioUrl
    if (jAudioSourceUrl != nullptr && audioUrl != nullptr) {
        env->ReleaseStringUTFChars(jAudioSourceUrl, audioUrl);
    }

    // === 结束释放 ===

    // 解析结果处理
    jstring jCoverPath = nullptr;
    if (!meta.coverData.empty()) {
        std::string savedPath = ProbeUtils::saveCoverAuto(meta.uri, meta.coverData);
        if (!savedPath.empty()) {
            jCoverPath = safeNewStringUTF(env, savedPath.c_str());
        }
    }

    if (!meta.success) return nullptr;

    jclass clsMeta = env->FindClass("com/qytech/audioplayer/parser/model/AudioMetadata");
    jclass clsTrack = env->FindClass("com/qytech/audioplayer/parser/model/AudioTrackItem");
    jclass clsList = env->FindClass("java/util/ArrayList");

    jmethodID ctorMeta = env->GetMethodID(clsMeta, "<init>",
                                          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/String;)V");
    jmethodID ctorTrack = env->GetMethodID(clsTrack, "<init>",
                                           "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJJLjava/lang/String;IIIJ)V");
    jmethodID ctorList = env->GetMethodID(clsList, "<init>", "()V");
    jmethodID listAdd = env->GetMethodID(clsList, "add", "(Ljava/lang/Object;)Z");

    jobject jTracks = env->NewObject(clsList, ctorList);

    for (const auto &t: meta.tracks) {
        env->PushLocalFrame(32);

        jstring ti = safeNewStringUTF(env, t.title.c_str());
        jstring ar = safeNewStringUTF(env, t.artist.c_str());
        jstring al = safeNewStringUTF(env, t.album.c_str());
        jstring ge = t.genre.empty() ? nullptr : safeNewStringUTF(env, t.genre.c_str());
        jstring pa = safeNewStringUTF(env, t.path.c_str());
        jstring fmt = t.format.empty() ? nullptr : safeNewStringUTF(env, t.format.c_str());

        jobject item = env->NewObject(clsTrack, ctorTrack,
                                      t.trackId, ti, ar, al, ge, pa,
                                      (long) t.startMs, (long) t.endMs, (long) t.durationMs,
                                      fmt, t.sampleRate, t.channels, t.bitDepth, (long) t.bitRate
        );

        env->CallBooleanMethod(jTracks, listAdd, item);
        env->PopLocalFrame(nullptr);
    }

    jstring jUri = safeNewStringUTF(env, meta.uri.c_str());
    jstring jAlb = safeNewStringUTF(env, meta.albumTitle.c_str());
    jstring jArt = safeNewStringUTF(env, meta.albumArtist.c_str());
    jstring jGen = meta.genre.empty() ? nullptr : safeNewStringUTF(env, meta.genre.c_str());
    jstring jDat = meta.date.empty() ? nullptr : safeNewStringUTF(env, meta.date.c_str());
    jstring jLyr = meta.lyrics.empty() ? nullptr : safeNewStringUTF(env, meta.lyrics.c_str());

    jobject result = env->NewObject(clsMeta, ctorMeta,
                                    jUri, jAlb, jArt, jGen, jDat, jCoverPath, jTracks, jLyr
    );

    if (jCoverPath) env->DeleteLocalRef(jCoverPath);

    return result;
}

static const JNINativeMethod gMethods[] = {
        {"nativeProbe",
         "(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;)Lcom/qytech/audioplayer/parser/model/AudioMetadata;",
         (void *) nativeProbe}
};

int register_audioprobe_methods(JavaVM *vm, JNIEnv *env) {
    jclass clazz = env->FindClass("com/qytech/audioplayer/parser/AudioProbe");
    if (!clazz) return JNI_ERR;
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        return JNI_ERR;
    }
    return JNI_OK;
}