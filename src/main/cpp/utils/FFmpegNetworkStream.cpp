//
// Created by Administrator on 2025/12/2.
//

#include "FFmpegNetworkStream.h"

bool FFmpegNetworkStream::open(const std::string &url,
                               const std::map<std::string, std::string> &headers) {
    close();
    AVDictionary *opts = nullptr;

    // 构建 Headers
    std::string headerStr;
    for (const auto &pair: headers) {
        headerStr += pair.first + ": " + pair.second + "\r\n";
    }
    if (!headerStr.empty()) {
        av_dict_set(&opts, "headers", headerStr.c_str(), 0);
    }

    // 设置超时
    av_dict_set(&opts, "timeout", "30000000", 0);

    // 使用 avio_open2 支持 http/https 以及 file://
    int ret = avio_open2(&ctx, url.c_str(), AVIO_FLAG_READ, nullptr, &opts);
    av_dict_free(&opts);

    if (ret < 0) {
        // LOGE("ProbeNetworkStream: Failed to open %s, err=%d", url.c_str(), ret);
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
