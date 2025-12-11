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
#include <mutex>

// ==========================================
// 辅助工具与锁
// ==========================================

static std::once_flag ffmpeg_init_flag;
static std::mutex sacd_mutex;

static std::string get_tag(AVDictionary *m, const char *key) {
    AVDictionaryEntry *t = av_dict_get(m, key, nullptr, AV_DICT_IGNORE_SUFFIX);
    return t ? std::string(t->value) : "";
}

static int parse_int(const std::string &s) {
    try { return std::stoi(s); } catch (...) { return 0; }
}

static bool endsWith(const std::string &str, const std::string &suffix) {
    if (str.length() < suffix.length()) return false;
    std::string sLower = str;
    std::string sufLower = suffix;
    // 简单转小写比较
    std::transform(sLower.begin(), sLower.end(), sLower.begin(), ::tolower);
    std::transform(sufLower.begin(), sufLower.end(), sufLower.begin(), ::tolower);
    return sLower.compare(sLower.length() - sufLower.length(), sufLower.length(), sufLower) == 0;
}

// 提取纯路径（去除 URL 参数），用于后缀判断
static std::string getPathNoQuery(const std::string &path) {
    std::string lower = path;
    size_t qPos = lower.find('?');
    return (qPos != std::string::npos) ? lower.substr(0, qPos) : lower;
}

// ==========================================
// 1. FFProbe (Standard)
// ==========================================
static InternalMetadata
probeStandard(const std::string &path, const std::map<std::string, std::string> &headers) {
    InternalMetadata meta;
    meta.uri = path;
    meta.success = false; // 默认 false

    std::call_once(ffmpeg_init_flag, []() {
        av_log_set_level(AV_LOG_QUIET);
        avformat_network_init();
    });

    AVFormatContext *fmt_ctx = nullptr;
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

    // 2. 设置通用网络参数
    av_dict_set(&options, "timeout", "10000000", 0); // 10s
    av_dict_set(&options, "rw_timeout", "10000000", 0);
    av_dict_set(&options, "reconnect", "1", 0);

    // 【关键修复】只有在 headers 里没有 UA 时，才设置默认 UA
    // 否则会覆盖掉 pan.baidu.com，导致 403 Forbidden
    if (!hasUserAgent) {
        av_dict_set(&options, "user_agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", 0);
    }

    av_dict_set(&options, "buffer_size", "4194304", 0);
    av_dict_set(&options, "probesize", "512000", 0); // 减小探测大小，加速失败反馈
    av_dict_set(&options, "analyzeduration", "1000000", 0);

    // 尝试打开
    int ret = avformat_open_input(&fmt_ctx, path.c_str(), nullptr, &options);
    if (ret < 0) {
        LOGE("probeStandard: avformat_open_input failed: %d, path: %s", ret, path.c_str());
        av_dict_free(&options);
        return meta; // 失败直接返回，交给后续 fallback
    }
    av_dict_free(&options);

    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        LOGE("probeStandard: avformat_find_stream_info failed");
        avformat_close_input(&fmt_ctx);
        return meta;
    }

    // --- 解析元数据 ---
    meta.albumTitle = get_tag(fmt_ctx->metadata, "album");
    meta.albumArtist = get_tag(fmt_ctx->metadata, "album_artist");
    if (meta.albumArtist.empty()) meta.albumArtist = get_tag(fmt_ctx->metadata, "artist");
    meta.genre = get_tag(fmt_ctx->metadata, "genre");
    meta.date = get_tag(fmt_ctx->metadata, "date");
    meta.description = get_tag(fmt_ctx->metadata, "comment");

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

    // 如果找不到音频流，说明可能不是常规音频文件（可能是 ISO）
    if (audioIdx < 0) {
        LOGW("probeStandard: No audio stream found. Might be ISO/SACD.");
        avformat_close_input(&fmt_ctx);
        return meta; // success 依然是 false
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

    AVCodecParameters *p = fmt_ctx->streams[audioIdx]->codecpar;
    track.sampleRate = p->sample_rate;
    track.channels = p->ch_layout.nb_channels;
    track.bitDepth = (p->bits_per_raw_sample > 0) ? p->bits_per_raw_sample : 16;
    const AVCodecDescriptor *desc = avcodec_descriptor_get(p->codec_id);
    track.format = desc ? desc->name : "unknown";

    if (track.format.find("pcm") != std::string::npos) {
        track.format = "pcm";
    } else if (track.format.find("dsd") != std::string::npos) {
        track.format = "DSD" + std::to_string(p->sample_rate * 8 / 44100);
        track.bitDepth = 1;
        track.sampleRate = p->sample_rate * 8;
    }

    meta.tracks.push_back(track);
    meta.success = true;
    if (meta.totalTracks == 0) meta.totalTracks = 1;

    avformat_close_input(&fmt_ctx);
    return meta;
}

// ==========================================
// 2. CueProbe
// ==========================================
static InternalMetadata
probeCue(JNIEnv *env, const std::string &path,
         const std::map<std::string, std::string> &headers,
         const std::string &audioUrl) {
    InternalMetadata meta;
    meta.uri = path;

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
        LOGD("line %s, cmd %s, val %s", line.c_str(), cmd.c_str(), val.c_str());
        if (cmd == "TITLE") { if (curNum == 0) meta.albumTitle = val; else curTitle = val; }
        else if (cmd == "PERFORMER") {
            if (curNum == 0)
                meta.albumArtist = val;
            else curPerf = val;
        } else if (cmd == "REM") {
            if (val.find("GENRE") == 0 && val.length() >= 6) {
                meta.genre = val.substr(6);
            } else if (val.find("DATE") == 0 && val.length() >= 5) {
                meta.date = val.substr(5);
            } else if (val.find("COMMENT") == 0 && val.length() >= 8) {
                meta.description = val.substr(8);
            }
        } else if (cmd == "FILE") {
            std::string tempFile = val;
            LOGD("FILE value %s", val.c_str());
            size_t lastSpace = val.find_last_of(' ');
            if (lastSpace != std::string::npos) {
                std::string possibleType = val.substr(lastSpace + 1);
                bool isType = false;
                if (possibleType == "WAVE" || possibleType == "MP3" || possibleType == "BINARY" ||
                    possibleType == "AIFF" || possibleType == "FLAC" || possibleType == "APE" ||
                    possibleType == "DSD" || possibleType == "MOTOROLA") {
                    isType = true;
                }
                if (isType) {
                    tempFile = val.substr(0, lastSpace);
                    LOGD("tempFile %s", tempFile.c_str());
                }
                curFile = unquote(tempFile);
            }
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

    bool isNetworkOverride = !audioUrl.empty();
    std::map<std::string, InternalMetadata> fileCache;

    for (size_t i = 0; i < temps.size(); ++i) {
        InternalTrack it;
        it.trackId = temps[i].num;
        it.discNumber = 1;

        std::ostringstream titleStream;
        titleStream << std::setw(2) << std::setfill('0') << temps[i].num << ". " << temps[i].title;
        it.title = titleStream.str();
        it.artist = temps[i].performer;
        it.album = meta.albumTitle;
        it.genre = meta.genre;
        it.startMs = temps[i].start;

        if (isNetworkOverride) {
            it.path = audioUrl;
        } else {
            it.path = ProbeUtils::resolvePath(path, temps[i].file);
        }

        if (fileCache.find(it.path) == fileCache.end()) {
            fileCache[it.path] = probeStandard(it.path, headers);
        }
        const auto &fm = fileCache[it.path];
        if (fm.success && !fm.tracks.empty()) {
            const auto &ft = fm.tracks[0];
            it.format = ft.format;
            it.sampleRate = ft.sampleRate;
            it.channels = ft.channels;
            it.bitDepth = ft.bitDepth;
            it.bitRate = ft.bitRate;
        }

        int64_t endPos = -1;
        if (i < temps.size() - 1 && temps[i + 1].file == temps[i].file) {
            endPos = temps[i + 1].start;
        } else if (fm.success && !fm.tracks.empty() && fm.tracks[0].durationMs > 0) {
            endPos = fm.tracks[0].durationMs;
        }

        it.endMs = endPos;
        it.durationMs = (endPos > it.startMs) ? (endPos - it.startMs) : 0;
        meta.tracks.push_back(it);
    }
    return meta;
}

// ==========================================
// 3. SacdProbe (ISO) - 本地 & 网络
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
    LOGD("probeSacd: Starting for %s", path.c_str());
    InternalMetadata meta;
    meta.uri = path;
    std::lock_guard<std::mutex> lock(sacd_mutex);

    sacd_reader_t *reader = nullptr;
    FFmpegNetworkStream *netStream = nullptr;
    bool isNetwork = (path.find("http") == 0 || path.find("https") == 0);

    if (isNetwork) {
        netStream = new FFmpegNetworkStream();
        // FFmpegNetworkStream 内部应该会处理 Headers，确保 UA 被传递
        if (!netStream->open(path, headers)) {
            LOGE("probeSacd: FFmpegNetworkStream open failed");
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
        LOGE("probeSacd: sacd_open failed");
        if (netStream) delete netStream;
        return meta;
    }

    scarletbook_handle_t *handle = scarletbook_open(reader);
    if (!handle) {
        LOGE("probeSacd: scarletbook_open failed (Not a valid SACD ISO)");
        sacd_close(reader);
        if (netStream) delete netStream;
        return meta;
    }

    // --- 解析成功，提取数据 ---
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
    // 优先找立体声区域
    for (int i = 0; i < handle->area_count; i++) {
        if (handle->area[i].area_toc->channel_count == 2) {
            target = &handle->area[i];
            break;
        }
    }
    // 找不到立体声就找多声道
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
    sacd_close(reader); // reader 会通过回调关闭 netStream?
    if (netStream) delete netStream;

    return meta;
}

// ==========================================
// 4. 入口分发 (核心修改)
// ==========================================
InternalMetadata
AudioProbe::probe(
        JNIEnv *env,
        const std::string &source,
        const std::map<std::string, std::string> &headers,
        const std::string &filename,
        const std::string &audioUrl
) {

    std::string nameToCheck = filename;
    if (nameToCheck.empty()) {
        nameToCheck = getPathNoQuery(source);
    }

    LOGD("AudioProbe::probe: source=[%s], originalName=[%s], checkName=[%s], audioUrl=[%s]",
         source.c_str(), filename.c_str(), nameToCheck.c_str(), audioUrl.c_str());

    InternalMetadata meta;
    meta.uri = source;
    meta.success = false;

    try {
        // 解析 CUE
        if (endsWith(nameToCheck, ".cue")) {
            LOGD("AudioProbe: Detected .cue extension, using probeCue");
            return probeCue(env, source, headers, audioUrl);
        }
        // 解析 Sacd
        if (endsWith(nameToCheck, ".iso")) {
            LOGD("AudioProbe: Detected .iso extension, skipping FFmpeg, using probeSacd");
            return probeSacd(source, headers);
        }

        LOGD("AudioProbe: Attempting standard FFmpeg probe");
        meta = probeStandard(source, headers);
        return meta;
    } catch (const std::exception &e) {
        LOGE("Native crash prevented in probe: %s", e.what());
        meta.success = false;
        return meta;
    } catch (...) {
        LOGE("Native crash prevented in probe: unknown exception");
        meta.success = false;
        return meta;
    }
}