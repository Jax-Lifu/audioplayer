//
// Created by Administrator on 2025/12/2.
//

#ifndef QYLAUNCHER_FFMPEGNETWORKSTREAM_H
#define QYLAUNCHER_FFMPEGNETWORKSTREAM_H

extern "C" {
#include "libavformat/avformat.h"
}

#include <string>
#include <map>
#include <mutex>

class FFmpegNetworkStream {
public:
    FFmpegNetworkStream() {
        static std::once_flag flag;
        std::call_once(flag, []() {
            av_log_set_level(AV_LOG_QUIET);
            avformat_network_init();
        });
    }

    ~FFmpegNetworkStream() { close(); }

    bool open(const std::string &url, const std::map<std::string, std::string> &headers);

    void close();

    int readAt(int64_t offset, uint8_t *buf, int size);

    static long read_cb(void *opaque, void *buf, long size);

    static long seek_cb(void *opaque, long offset, int origin);

    static long tell_cb(void *opaque);

    static long get_size_cb(void *opaque);

private:
    AVIOContext *ctx = nullptr;
    int64_t fileSize = 0;
};


#endif //QYLAUNCHER_FFMPEGNETWORKSTREAM_H
