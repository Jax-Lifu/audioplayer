#include "jni_audioprobe.h"
#include "parser/AudioProbe.h"
#include "parser/ProbeUtils.h"
#include <jni.h>
#include <string>
#include <map>

// 辅助：Java Map -> std::map
std::map<std::string, std::string> jmapToStdMap(JNIEnv *env, jobject jmap) {
    std::map<std::string, std::string> cppMap;
    if (!jmap) return cppMap;

    jclass mapClass = env->GetObjectClass(jmap);
    jobject entrySet = env->CallObjectMethod(jmap, env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;"));
    jobject iterator = env->CallObjectMethod(entrySet, env->GetMethodID(env->FindClass("java/util/Set"), "iterator", "()Ljava/util/Iterator;"));
    jmethodID hasNext = env->GetMethodID(env->FindClass("java/util/Iterator"), "hasNext", "()Z");
    jmethodID next = env->GetMethodID(env->FindClass("java/util/Iterator"), "next", "()Ljava/lang/Object;");
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

static jobject nativeProbe(JNIEnv *env, jobject thiz, jstring jPath, jobject jHeaders) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    auto headers = jmapToStdMap(env, jHeaders);

    // 核心修改：传递当前 env
    InternalMetadata meta = AudioProbe::probe(env, path, headers);

    jstring jCoverPath = nullptr;
    if (!meta.coverData.empty()) {
        std::string savedPath = ProbeUtils::saveCoverAuto(meta.uri, meta.coverData);
        if (!savedPath.empty()) {
            jCoverPath = env->NewStringUTF(savedPath.c_str());
        }
    }

    env->ReleaseStringUTFChars(jPath, path);
    if (!meta.success) return nullptr;

    jclass clsMeta = env->FindClass("com/qytech/audioplayer/parser/model/AudioMetadata");
    jclass clsTrack = env->FindClass("com/qytech/audioplayer/parser/model/AudioTrackItem");
    jclass clsList = env->FindClass("java/util/ArrayList");

    jmethodID ctorMeta = env->GetMethodID(clsMeta, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/String;)V");
    jmethodID ctorTrack = env->GetMethodID(clsTrack, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJJLjava/lang/String;IIIJ)V");
    jmethodID ctorList = env->GetMethodID(clsList, "<init>", "()V");
    jmethodID listAdd = env->GetMethodID(clsList, "add", "(Ljava/lang/Object;)Z");

    jobject jTracks = env->NewObject(clsList, ctorList);

    for (const auto &t: meta.tracks) {
        // 核心修复：防止循环引用溢出
        env->PushLocalFrame(32);

        jstring ti = env->NewStringUTF(t.title.c_str());
        jstring ar = env->NewStringUTF(t.artist.c_str());
        jstring al = env->NewStringUTF(t.album.c_str());
        jstring ge = t.genre.empty() ? nullptr : env->NewStringUTF(t.genre.c_str());
        jstring pa = env->NewStringUTF(t.path.c_str());
        jstring fmt = t.format.empty() ? nullptr : env->NewStringUTF(t.format.c_str());

        jobject item = env->NewObject(clsTrack, ctorTrack,
                                      t.trackId, ti, ar, al, ge, pa,
                                      (long) t.startMs, (long) t.endMs, (long) t.durationMs,
                                      fmt, t.sampleRate, t.channels, t.bitDepth, (long) t.bitRate
        );

        env->CallBooleanMethod(jTracks, listAdd, item);
        env->PopLocalFrame(nullptr);
    }

    jstring jUri = env->NewStringUTF(meta.uri.c_str());
    jstring jAlb = env->NewStringUTF(meta.albumTitle.c_str());
    jstring jArt = env->NewStringUTF(meta.albumArtist.c_str());
    jstring jGen = meta.genre.empty() ? nullptr : env->NewStringUTF(meta.genre.c_str());
    jstring jDat = meta.date.empty() ? nullptr : env->NewStringUTF(meta.date.c_str());
    jstring jLyr = meta.lyrics.empty() ? nullptr : env->NewStringUTF(meta.lyrics.c_str());

    jobject result = env->NewObject(clsMeta, ctorMeta,
                                    jUri, jAlb, jArt, jGen, jDat, jCoverPath, jTracks, jLyr
    );

    if (jCoverPath) env->DeleteLocalRef(jCoverPath);
    return result;
}

static const JNINativeMethod gMethods[] = {
        {"native_probe", "(Ljava/lang/String;Ljava/util/Map;)Lcom/qytech/audioplayer/parser/model/AudioMetadata;", (void *) nativeProbe}
};

int register_audioprobe_methods(JavaVM *vm, JNIEnv *env) {
    jclass clazz = env->FindClass("com/qytech/audioplayer/parser/AudioProbe");
    if (!clazz) return JNI_ERR;
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        return JNI_ERR;
    }
    return JNI_OK;
}