#ifndef AUDIO_PLAYER_AUDIOPROBE_H
#define AUDIO_PLAYER_AUDIOPROBE_H

#include <string>
#include <vector>
#include <map>
#include <jni.h> // 引入 JNI 头文件
#include "FFmpegNetworkStream.h"

// 保持结构体定义不变
struct InternalTrack {
    int trackId = 0;
    int discNumber = 1;
    std::string title;
    std::string artist;
    std::string album;
    std::string genre;
    std::string path;
    int64_t startMs = 0;
    int64_t endMs = 0;
    int64_t durationMs = 0;
    std::string format;
    int sampleRate = 0;
    int channels = 0;
    int bitDepth = 0;
    int64_t bitRate = 0;
};

struct InternalMetadata {
    std::string uri;
    std::string albumTitle;
    std::string albumArtist;
    std::string genre;
    std::string date;
    std::string description;
    std::string lyrics;
    std::string extraInfo;
    int totalTracks = 0;
    int totalDiscs = 1;
    std::vector<uint8_t> coverData;
    std::vector<InternalTrack> tracks;
    bool success = false;
};

class AudioProbe {
public:
    static InternalMetadata probe(
            JNIEnv *env,
            const std::string &source,
            const std::map<std::string, std::string> &headers,
            const std::string &filename,
            const std::string &audioUrl
    );
};

#endif // AUDIO_PLAYER_AUDIOPROBE_H