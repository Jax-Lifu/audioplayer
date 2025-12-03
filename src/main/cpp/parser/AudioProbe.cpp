#include "AudioProbe.h"
#include "ProbeUtils.h"
#include "Logger.h"

extern "C" {
#include "sacd_reader.h"
#include "scarletbook_read.h"
#include <libavformat/avformat.h>
#include <libavcodec/codec_desc.h>
#include <libavutil/avutil.h>
}

#include <sstream>
#include <algorithm>
#include <mutex> // 必须引入，用于线程锁

// ==========================================
// 辅助工具与锁
// ==========================================

// 用于保护 FFmpeg 网络模块初始化 (只执行一次)
static std::once_flag ffmpeg_init_flag;
// 用于保护 SACD 库 (非线程安全库必须加锁)
static std::mutex sacd_mutex;

static std::string get_tag(AVDictionary *m, const char *key) {
    AVDictionaryEntry *t = av_dict_get(m, key, nullptr, AV_DICT_IGNORE_SUFFIX);
    return t ? std::string(t->value) : "";
}

static int parse_int(const std::string &s) {
    try { return std::stoi(s); } catch (...) { return 0; }
}

static bool endsWith(const std::string &str, const std::string &suffix) {
    if (str.length() < suffix.length()) {
        return false;
    }
    // 从字符串末尾比较
    return str.compare(str.length() - suffix.length(), suffix.length(), suffix) == 0;
}

// ==========================================
// 1. FFProbe (Standard)
// ==========================================
static InternalMetadata
probeStandard(const std::string &path, const std::map<std::string, std::string> &headers) {
    InternalMetadata meta;
    meta.uri = path;

    // 核心修复：线程安全的全局初始化
    std::call_once(ffmpeg_init_flag, []() {
        av_log_set_level(AV_LOG_QUIET);
        avformat_network_init();
    });

    AVFormatContext *fmt_ctx = nullptr;
    AVDictionary *opts = nullptr;

    std::string customHeaders;
    for (const auto &pair: headers) {
        if (strcasecmp(pair.first.c_str(), "User-Agent") == 0) {
            av_dict_set(&opts, "user_agent", pair.second.c_str(), 0);
        } else {
            customHeaders += pair.first + ": " + pair.second + "\r\n";
        }
    }
    if (!customHeaders.empty()) av_dict_set(&opts, "headers", customHeaders.c_str(), 0);

    // 增加超时设置
    av_dict_set(&opts, "timeout", "10000000", 0);

    if (avformat_open_input(&fmt_ctx, path.c_str(), nullptr, &opts) < 0) {
        av_dict_free(&opts);
        return meta;
    }
    av_dict_free(&opts);

    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        avformat_close_input(&fmt_ctx);
        return meta;
    }

    meta.albumTitle = get_tag(fmt_ctx->metadata, "album");
    meta.albumArtist = get_tag(fmt_ctx->metadata, "album_artist");
    if (meta.albumArtist.empty()) meta.albumArtist = get_tag(fmt_ctx->metadata, "artist");
    meta.genre = get_tag(fmt_ctx->metadata, "genre");
    meta.date = get_tag(fmt_ctx->metadata, "date");
    meta.description = get_tag(fmt_ctx->metadata, "comment");
    meta.lyrics = get_tag(fmt_ctx->metadata, "lyrics");
    if (meta.lyrics.empty()) meta.lyrics = get_tag(fmt_ctx->metadata, "unsyncedlyrics");

    std::string discStr = get_tag(fmt_ctx->metadata, "disc");
    std::string trackStr = get_tag(fmt_ctx->metadata, "track");
    int curDisc = 1;
    int curTrack = 1;
    if (discStr.find('/') != std::string::npos)
        sscanf(discStr.c_str(), "%d/%d", &curDisc, &meta.totalDiscs);
    else if (!discStr.empty()) curDisc = parse_int(discStr);

    if (trackStr.find('/') != std::string::npos)
        sscanf(trackStr.c_str(), "%d/%d", &curTrack, &meta.totalTracks);
    else if (!trackStr.empty()) curTrack = parse_int(trackStr);

    int audioIdx = -1;
    for (int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->disposition & AV_DISPOSITION_ATTACHED_PIC) {
            AVPacket pkt = fmt_ctx->streams[i]->attached_pic;
            if (pkt.data && pkt.size > 0) meta.coverData.assign(pkt.data, pkt.data + pkt.size);
        }
        if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && audioIdx < 0) {
            audioIdx = i;
        }
    }

    InternalTrack track;
    track.trackId = curTrack;
    track.discNumber = curDisc;
    track.path = path;
    track.title = get_tag(fmt_ctx->metadata, "title");
    if (track.title.empty()) track.title = path.substr(path.find_last_of("/\\") + 1);
    track.artist = get_tag(fmt_ctx->metadata, "artist");
    track.album = meta.albumTitle;
    track.genre = meta.genre;

    if (fmt_ctx->duration != AV_NOPTS_VALUE) {
        track.durationMs = fmt_ctx->duration / 1000;
        track.endMs = track.durationMs;
    }
    track.bitRate = fmt_ctx->bit_rate;

    if (audioIdx >= 0) {
        AVCodecParameters *p = fmt_ctx->streams[audioIdx]->codecpar;
        track.sampleRate = p->sample_rate;
        track.channels = p->ch_layout.nb_channels;
        track.bitDepth = (p->bits_per_raw_sample > 0) ? p->bits_per_raw_sample : 16;
        const AVCodecDescriptor *desc = avcodec_descriptor_get(p->codec_id);
        track.format = desc ? desc->name : "unknown";
        if (track.format.find("dsd") != std::string::npos) track.bitDepth = 1;
    }

    meta.tracks.push_back(track);
    meta.success = true;
    if (meta.totalTracks == 0) meta.totalTracks = 1;

    avformat_close_input(&fmt_ctx);
    return meta;
}

// ==========================================
// 2. CueProbe (CUE 分轨)
// ==========================================
static InternalMetadata
probeCue(JNIEnv *env, const std::string &path, const std::map<std::string, std::string> &headers) {
    InternalMetadata meta;
    meta.uri = path;

    // 核心修复：传入 env
    std::string content = ProbeUtils::readContent(env, path, headers);
    if (content.empty()) return meta;

    std::stringstream ss(content);
    std::string line;

    struct TempTrack {
        int num;
        std::string title;
        std::string performer;
        std::string file;
        int64_t start;
    };
    std::vector<TempTrack> temps;
    std::string curFile, curTitle, curPerf;
    int curNum = 0;
    int64_t curStart = 0;

    auto unquote = [](std::string s) {
        size_t f = s.find_first_not_of(" \t\r\n");
        if (f == std::string::npos) return std::string("");
        s = s.substr(f);
        size_t l = s.find_last_not_of(" \t\r\n");
        if (l != std::string::npos) s = s.substr(0, l + 1);
        if (s.size() >= 2 && s.front() == '"' && s.back() == '"') return s.substr(1, s.size() - 2);
        return s;
    };

    auto parseMs = [](const std::string &s) -> int64_t {
        int m, sec, f;
        if (sscanf(s.c_str(), "%d:%d:%d", &m, &sec, &f) == 3)
            return (int64_t) m * 60000 + sec * 1000 + f * 1000 / 75;
        return 0;
    };

    while (std::getline(ss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::stringstream ls(line);
        std::string cmd, val;
        ls >> cmd;
        std::getline(ls, val);
        val = unquote(val);

        if (cmd == "TITLE") { if (curNum == 0) meta.albumTitle = val; else curTitle = val; }
        else if (cmd == "PERFORMER") {
            if (curNum == 0)
                meta.albumArtist = val;
            else curPerf = val;
        } else if (cmd == "REM" && val.find("GENRE") == 0) meta.genre = val.substr(6);
        else if (cmd == "REM" && val.find("DATE") == 0) meta.date = val.substr(5);
        else if (cmd == "REM" && val.find("COMMENT") == 0) meta.description = val.substr(8);
        else if (cmd == "FILE") {
            size_t sp = val.find_last_of(' ');
            if (sp != std::string::npos) {
                std::string type = val.substr(sp + 1);
                if (type == "WAVE" || type == "MP3" || type == "BINARY" || type == "AIFF")
                    val = val.substr(0, sp);
            }
            curFile = unquote(val);
        } else if (cmd == "TRACK") {
            if (curNum > 0) temps.push_back({curNum, curTitle, curPerf, curFile, curStart});
            sscanf(val.c_str(), "%d", &curNum);
            curTitle = "Track " + std::to_string(curNum);
            curPerf = meta.albumArtist;
            curStart = 0;
        } else if (cmd == "INDEX") {
            int id;
            char buf[32];
            if (sscanf(val.c_str(), "%d %s", &id, buf) == 2 && id == 1) curStart = parseMs(buf);
        }
    }
    if (curNum > 0) temps.push_back({curNum, curTitle, curPerf, curFile, curStart});

    if (temps.empty()) return meta;
    meta.success = true;
    meta.totalTracks = (int) temps.size();

    std::map<std::string, InternalMetadata> fileCache;

    for (size_t i = 0; i < temps.size(); ++i) {
        std::string absPath = ProbeUtils::resolvePath(path, temps[i].file);

        if (fileCache.find(absPath) == fileCache.end()) {
            fileCache[absPath] = probeStandard(absPath, headers);
        }

        const auto &fm = fileCache[absPath];
        std::ostringstream titleStream;
        titleStream << std::setw(2) << std::setfill('0') << temps[i].num << ". " << temps[i].title;

        InternalTrack it;
        it.trackId = temps[i].num;
        it.discNumber = 1;
        it.title = titleStream.str();
        it.artist = temps[i].performer;
        it.album = meta.albumTitle;
        it.genre = meta.genre;
        it.path = absPath;
        it.startMs = temps[i].start;

        int64_t endPos = -1;
        if (i < temps.size() - 1 && temps[i + 1].file == temps[i].file) {
            endPos = temps[i + 1].start;
        } else if (fm.success && !fm.tracks.empty() && fm.tracks[0].durationMs > 0) {
            endPos = fm.tracks[0].durationMs;
        }

        it.endMs = endPos;
        it.durationMs = (endPos > it.startMs) ? (endPos - it.startMs) : 0;

        if (fm.success && !fm.tracks.empty()) {
            const auto &ft = fm.tracks[0];
            it.format = ft.format;
            it.sampleRate = ft.sampleRate;
            it.channels = ft.channels;
            it.bitDepth = ft.bitDepth;
            it.bitRate = ft.bitRate;
        }
        meta.tracks.push_back(it);
    }
    return meta;
}

// ==========================================
// 3. SacdProbe (ISO) - 本地
// ==========================================
static const char *genreStr(int g) {
    static const char *gs[] = {"Not Used", "Not Defined", "Adult Contemporary", "Alternative Rock",
                               "Children's Music", "Classical", "Contemporary Christian", "Country",
                               "Dance", "Easy Listening", "Erotic", "Folk", "Gospel", "Hip Hop",
                               "Jazz", "Latin", "Musical", "New Age", "Opera", "Operetta",
                               "Pop Music", "Rap", "Reggae", "Rock Music", "Rhythm and Blues",
                               "Sound Effects", "Sound Track", "Spoken Word", "World Music",
                               "Blues"};
    if (g >= 0 && g <= 29) return gs[g];
    return "Unknown";
}

static InternalMetadata
probeSacd(const std::string &path, const std::map<std::string, std::string> &headers) {
    InternalMetadata meta;
    meta.uri = path;
    std::lock_guard<std::mutex> lock(sacd_mutex);

    sacd_reader_t *reader = nullptr;
    FFmpegNetworkStream *netStream = nullptr;
    bool isNetwork = (path.find("http") == 0 || path.find("https") == 0);

    if (isNetwork) {
        netStream = new FFmpegNetworkStream();
        if (!netStream->open(path, headers)) {
            delete netStream;
            return meta;
        }
        sacd_io_callbacks_t cb;
        cb.context = netStream;
        cb.read = FFmpegNetworkStream::read_cb;
        cb.seek = FFmpegNetworkStream::seek_cb;
        cb.tell = FFmpegNetworkStream::tell_cb;
        cb.get_size = FFmpegNetworkStream::get_size_cb;
        reader = sacd_open_callbacks(&cb);
    } else {
        reader = sacd_open(path.c_str());
    }

    if (!reader) {
        if (netStream) {
            delete netStream;
        }
        return meta;
    }
    scarletbook_handle_t *handle = scarletbook_open(reader);
    if (!handle) {
        sacd_close(reader);
        return meta;
    }

    master_toc_t *mtoc = handle->master_toc;
    master_text_t *mtext = &handle->master_text;

    meta.success = true;
    if (mtext->disc_title) meta.albumTitle = mtext->disc_title;
    if (mtext->disc_artist) meta.albumArtist = mtext->disc_artist;
    if (mtoc->disc_genre) meta.genre = genreStr(mtoc->disc_genre->genre);

    char d[32];
    snprintf(d, 32, "%04d-%02d-%02d", mtoc->disc_date_year, mtoc->disc_date_month,
             mtoc->disc_date_day);
    meta.date = d;
    meta.totalDiscs = mtoc->album_set_size;
    meta.description =
            std::to_string(mtoc->version.major) + "." + std::to_string(mtoc->version.minor);
    if (mtoc->disc_catalog_number) meta.extraInfo = mtoc->disc_catalog_number;

    scarletbook_area_t *target = nullptr;
    for (int i = 0; i < handle->area_count; i++)
        if (handle->area[i].area_toc->channel_count == 2) {
            target = &handle->area[i];
            break;
        }
    if (!target && handle->area_count > 0) target = &handle->area[0];

    if (target) {
        meta.totalTracks = target->area_toc->track_count;
        for (int i = 0; i < target->area_toc->track_count; i++) {
            InternalTrack t;
            t.trackId = i + 1;
            t.discNumber = mtoc->album_sequence_number;
            t.path = path;
            t.album = meta.albumTitle;
            t.genre = meta.genre;

            area_track_text_t *txt = &target->area_track_text[i];
            t.title = (txt->track_type_title) ? txt->track_type_title : "Track " +
                                                                        std::to_string(i + 1);
            t.artist = (txt->track_type_performer) ? txt->track_type_performer : meta.albumArtist;

            auto toMs = [](const area_tracklist_time_t &tm) {
                return (int64_t) tm.minutes * 60000 + tm.seconds * 1000 + tm.frames * 1000 / 75;
            };
            t.startMs = toMs(target->area_tracklist_time->start[i]);
            t.durationMs = toMs(target->area_tracklist_time->duration[i]);
            t.endMs = t.startMs + t.durationMs;

            t.format = (target->area_toc->frame_format == FRAME_FORMAT_DST) ? "DST64" : "DSD64";
            t.sampleRate = 2822400;
            t.channels = target->area_toc->channel_count;
            t.bitDepth = 1;
            meta.tracks.push_back(t);
        }
    }
    scarletbook_close(handle);
    sacd_close(reader);
    return meta;
}

static bool isIsoFormat(const std::string &path, const std::map<std::string, std::string> &headers) {
    // 1. 如果有明确后缀，直接返回 true (性能优化)
    std::string lower = path;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    // 移除 URL 参数部分再判断后缀 (针对 ...file?token=xxx 这种情况)
    size_t qPos = lower.find('?');
    std::string pathNoQuery = (qPos != std::string::npos) ? lower.substr(0, qPos) : lower;
    if (endsWith(pathNoQuery, ".iso")) return true;

    // 2. 如果没有后缀，通过网络读取头部特征码 (Magic Number)
    // ISO 9660 标准：在 0x8000 (32768) 偏移处有 "CD001"
    FFmpegNetworkStream stream;
    if (!stream.open(path, headers)) return false;

    uint8_t magic[5] = {0};
    // Seek 到 32768
    if (stream.readAt(32768, magic, 5) == 5) {
        // 检查 CD001
        if (memcmp(magic, "CD001", 5) == 0) {
            LOGD("Detected ISO format by Magic Number at %s", path.c_str());
            return true;
        }
    }

    return false;
}

// ==========================================
// 4. 入口分发
// ==========================================
InternalMetadata
AudioProbe::probe(JNIEnv *env, const std::string &path,
                  const std::map<std::string, std::string> &headers) {
    std::string lower = path;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    size_t qPos = lower.find('?');
    std::string pathNoQuery = (qPos != std::string::npos) ? lower.substr(0, qPos) : lower;

    if (endsWith(pathNoQuery, ".cue")) {
        return probeCue(env, path, headers);
    }

    // 2. SACD ISO 处理 (包含 后缀判断 + 魔法数字嗅探)
    // 对于长链接，这里会发起一个轻量级的 HEAD/Range 请求去检查 0x8000 位置
    if (isIsoFormat(path, headers)) {
        return probeSacd(path, headers);
    }

    // 3. 其他情况交给 FFmpeg 标准探测
    return probeStandard(path, headers);
}


