#ifndef AUDIO_PLAYER_PROBEUTILS_H
#define AUDIO_PLAYER_PROBEUTILS_H

#include <string>
#include <vector>
#include <map>
#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sstream>
#include <iomanip>
#include "Logger.h"

extern "C" {
#include <libavutil/md5.h>
#include <libavformat/avformat.h>
#include <libavutil/dict.h>
}


static const std::string COVER_SAVE_DIR = "/sdcard/Music/.covers/";

class ProbeUtils {
private:
    static std::string computeMD5(const std::string &str) {
        uint8_t digest[16];
        av_md5_sum(digest, (const uint8_t *) str.c_str(), str.length());
        std::stringstream ss;
        for (auto b: digest) ss << std::hex << std::setw(2) << std::setfill('0') << (int) b;
        return ss.str();
    }

    static bool ensureDirectoryExists(const std::string &path) {
        if (access(path.c_str(), F_OK) == 0) return true;
        std::string cur;
        for (char c: path) {
            cur += c;
            if (c == '/' && access(cur.c_str(), F_OK) != 0) mkdir(cur.c_str(), 0777);
        }
        if (access(path.c_str(), F_OK) != 0) mkdir(path.c_str(), 0777);
        return true;
    }

    static std::vector<uint8_t>
    readRawBytes(const std::string &path, const std::map<std::string, std::string> &headers) {
        std::vector<uint8_t> result;
        AVDictionary *opts = nullptr;

        if (!headers.empty()) {
            std::string h;
            for (auto &p: headers) h += p.first + ": " + p.second + "\r\n";
            av_dict_set(&opts, "headers", h.c_str(), 0);
        }

        // 设置超时防止网络卡死 (10秒)
        av_dict_set(&opts, "timeout", "10000000", 0);

        AVIOContext *ctx = nullptr;
        if (avio_open2(&ctx, path.c_str(), AVIO_FLAG_READ, nullptr, &opts) >= 0) {
            int bufSize = 4096;
            std::vector<uint8_t> buffer(bufSize);
            int bytesRead;
            while ((bytesRead = avio_read(ctx, buffer.data(), bufSize)) > 0) {
                result.insert(result.end(), buffer.begin(), buffer.begin() + bytesRead);
            }
            avio_close(ctx);
        } else {
            LOGE("ProbeUtils: Failed to open IO: %s", path.c_str());
        }
        av_dict_free(&opts);
        return result;
    }

    // 核心修改：使用传入的 env，而不是全局获取
    static std::string decodeViaJava(JNIEnv *env, const std::vector<uint8_t> &data) {
        if (!env || data.empty()) return "";

        std::string result = "";

        // 1. 找到 Kotlin 辅助类
        jclass clazz = env->FindClass("com/qytech/audioplayer/utils/CharsetNativeHelper");
        if (clazz) {
            jmethodID mid = env->GetStaticMethodID(clazz, "decodeRawDataStrict",
                                                   "([B)Ljava/lang/String;");
            if (mid) {
                jbyteArray jData = env->NewByteArray(data.size());
                env->SetByteArrayRegion(jData, 0, data.size(), (const jbyte *) data.data());

                jstring jStr = (jstring) env->CallStaticObjectMethod(clazz, mid, jData);

                if (jStr) {
                    const char *chars = env->GetStringUTFChars(jStr, nullptr);
                    if (chars) {
                        result = chars;
                        env->ReleaseStringUTFChars(jStr, chars);
                    }
                    env->DeleteLocalRef(jStr);
                }
                env->DeleteLocalRef(jData);
            }
            env->DeleteLocalRef(clazz);
        }
        return result;
    }

public:
    static std::string saveCoverAuto(const std::string &uri, const std::vector<uint8_t> &data) {
        if (data.empty() || !ensureDirectoryExists(COVER_SAVE_DIR)) return "";
        std::string fullPath = COVER_SAVE_DIR + computeMD5(uri) + ".jpg";
        if (access(fullPath.c_str(), F_OK) == 0) return fullPath;

        FILE *fp = fopen(fullPath.c_str(), "wb");
        if (fp) {
            fwrite(data.data(), 1, data.size(), fp);
            fclose(fp);
            return fullPath;
        }
        return "";
    }

    // 核心修改：接收 JNIEnv* 参数
    static std::string
    readContent(JNIEnv *env, const std::string &path,
                const std::map<std::string, std::string> &headers = {}) {
        // 1. C++ 负责 IO
        std::vector<uint8_t> rawData = readRawBytes(path, headers);
        if (rawData.empty()) {
            return "";
        }
        // 2. Java 负责解码 (传入 env)
        return decodeViaJava(env, rawData);
    }

    static std::string resolvePath(const std::string &base, const std::string &rel) {
        if (rel.find("http") == 0 || (!rel.empty() && rel[0] == '/')) return rel;
        size_t p = base.find_last_of("/\\");
        return (p != std::string::npos) ? base.substr(0, p + 1) + rel : rel;
    }
};

#endif // AUDIO_PLAYER_PROBEUTILS_H