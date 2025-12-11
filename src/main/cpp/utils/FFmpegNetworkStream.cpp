//
// Created by Administrator on 2025/12/2.
//

#include "FFmpegNetworkStream.h"
#include "Logger.h"

bool FFmpegNetworkStream::open(const std::string &url,
                               const std::map<std::string, std::string> &headers) {
    close();
    AVDictionary *options = nullptr;
    std::string customHeaders;
    bool hasUserAgent = false;
    // 1. 处理传入的 Headers
    for (const auto &pair: headers) {
        if (strcasecmp(pair.first.c_str(), "User-Agent") == 0) {
            av_dict_set(&options, "user_agent", pair.second.c_str(), 0);
            hasUserAgent = true;
            LOGD("Using provided User-Agent: %s", pair.second.c_str());
        } else {
            customHeaders += pair.first + ": " + pair.second + "\r\n";
        }
    }
    if (!customHeaders.empty()) {
        av_dict_set(&options, "headers", customHeaders.c_str(), 0);
    }

    // 2. 网络优化参数
    av_dict_set(&options, "timeout", "10000000", 0); // 10s
    av_dict_set(&options, "rw_timeout", "10000000", 0); // 10s
    av_dict_set(&options, "reconnect", "1", 0);
    av_dict_set(&options, "reconnect_at_eof", "1", 0);
    av_dict_set(&options, "reconnect_streamed", "1", 0);
    av_dict_set(&options, "reconnect_delay_max", "5", 0);
    if (!hasUserAgent) {
        av_dict_set(&options, "user_agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", 0);
    }
    av_dict_set(&options, "buffer_size", "4194304", 0);
    av_dict_set(&options, "probesize", "1024000", 0);
    av_dict_set(&options, "analyzeduration", "2000000", 0);

    // 使用 avio_open2 支持 http/https 以及 file://
    int ret = avio_open2(&ctx, url.c_str(), AVIO_FLAG_READ, nullptr, &options);
    av_dict_free(&options);

    if (ret < 0) {
        LOGE("ProbeNetworkStream: Failed to open %s, err=%d", url.c_str(), ret);
        return false;
    }

    fileSize = avio_size(ctx);
    return true;
}

void FFmpegNetworkStream::close() {
    if (ctx) {
        avio_closep(&ctx);
        ctx = nullptr;
    }
}

int FFmpegNetworkStream::readAt(int64_t offset, uint8_t *buf, int size) {
    if (!ctx) return -1;
    if (avio_seek(ctx, offset, SEEK_SET) < 0) return -1;
    return avio_read(ctx, buf, size);
}

long FFmpegNetworkStream::read_cb(void *opaque, void *buf, long size) {
    auto *s = (FFmpegNetworkStream *) opaque;
    if (!s->ctx) return -1;
    int ret = avio_read(s->ctx, (unsigned char *) buf, size);
    return (ret < 0) ? -1 : ret;
}

long FFmpegNetworkStream::seek_cb(void *opaque, long offset, int origin) {
    auto *s = (FFmpegNetworkStream *) opaque;
    if (!s->ctx) return -1;
    int whence = SEEK_SET;
    if (origin == SEEK_CUR) whence = SEEK_CUR;
    else if (origin == SEEK_END) whence = SEEK_END;
    int64_t ret = avio_seek(s->ctx, offset, whence);
    return (ret < 0) ? -1 : 0;
}

long FFmpegNetworkStream::tell_cb(void *opaque) {
    auto *s = (FFmpegNetworkStream *) opaque;
    if (!s->ctx) return -1;
    return (long) avio_tell(s->ctx);
}

long FFmpegNetworkStream::get_size_cb(void *opaque) {
    auto *s = (FFmpegNetworkStream *) opaque;
    return (long) s->fileSize;
}
