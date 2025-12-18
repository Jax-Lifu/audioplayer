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
#include <unistd.h>
#include <sstream>
#include <algorithm>
#include <mutex>

// ==========================================
// 辅助工具与锁
// ==========================================

static std::once_flag ffmpeg_init_flag;
static std::mutex sacd_mutex;


static bool fileExists(const std::string &path) {
    // 如果是网络流 (http/rtmp 等)，access 无法检测，暂且认为它"存在"交给 FFmpeg 处理
    if (path.find("://") != std::string::npos) {
        return true;
    }
    // F_OK 只判断是否存在
    return access(path.c_str(), F_OK) == 0;
}

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
probeStandard(const std::string &path, const std::map<std::string, std::string> &headers, const std::string &filenameFallback) {
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

    // 只有在 headers 里没有 UA 时，才设置默认 UA
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

    // 如果没有 Tag 标题，优先使用 filenameFallback
    if (track.title.empty()) {
        if (!filenameFallback.empty()) {
            track.title = filenameFallback;
        } else {
            // 如果连 filenameFallback 也没有，为了美观，先去除 URL 参数再截取文件名
            std::string cleanPath = getPathNoQuery(path);
            track.title = cleanPath.substr(cleanPath.find_last_of("/\\") + 1);
        }
    }

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

        if (cmd == "TITLE") { if (curNum == 0) meta.albumTitle = val; else curTitle = val; }
        else if (cmd == "PERFORMER") {
            if (curNum == 0) meta.albumArtist = val; else curPerf = val;
        } else if (cmd == "REM") {
            if (val.find("GENRE") == 0 && val.length() >= 6) meta.genre = val.substr(6);
            else if (val.find("DATE") == 0 && val.length() >= 5) meta.date = val.substr(5);
            else if (val.find("COMMENT") == 0 && val.length() >= 8) meta.description = val.substr(8);
        } else if (cmd == "FILE") {
            std::string tempFile = val;
            size_t lastSpace = val.find_last_of(' ');
            if (lastSpace != std::string::npos) {
                std::string possibleType = val.substr(lastSpace + 1);
                // 简单的类型检测
                if (possibleType == "WAVE" || possibleType == "MP3" || possibleType == "BINARY" ||
                    possibleType == "FLAC" || possibleType == "APE" || possibleType == "DSD") {
                    tempFile = val.substr(0, lastSpace);
                }
                curFile = unquote(tempFile);
            } else {
                curFile = unquote(val);
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

        // 定义用于探测元数据的实际路径 (本地路径 或 网络URL)
        std::string probeTarget;

        if (isNetworkOverride) {
            // --- 网络/网盘模式 ---
            // 直接使用传入的源 Audio URL 作为播放路径和探测路径
            it.path = audioUrl;
            probeTarget = audioUrl;
        } else {
            // --- 本地文件模式 ---
            // 1. 获取基础文件名
            std::string rawFile = temps[i].file;
            std::string rawBaseName = rawFile.substr(rawFile.find_last_of("/\\") + 1);

            // 2. 拼接标准路径
            std::string resolvedPath = ProbeUtils::resolvePath(path, rawBaseName);

            // 3. 本地文件存在性检查与自动纠错 (Fallback logic)
            bool originalExists = fileExists(resolvedPath);

            if (!originalExists) {
                LOGW("CUE entry file not found: %s. Trying heuristic...", resolvedPath.c_str());

                std::string cuePathNoExt = path;
                size_t cueDot = cuePathNoExt.find_last_of('.');
                if (cueDot != std::string::npos) cuePathNoExt = cuePathNoExt.substr(0, cueDot);

                std::string targetExt = "";
                size_t fileDot = rawBaseName.find_last_of('.');
                if (fileDot != std::string::npos) targetExt = rawBaseName.substr(fileDot);

                std::string fallbackPath = cuePathNoExt + targetExt;

                if (fileExists(fallbackPath)) {
                    LOGD("Fallback file found: %s", fallbackPath.c_str());
                    resolvedPath = fallbackPath;
                } else {
                    LOGE("Neither original nor fallback file exists.");
                }
            }

            it.path = resolvedPath;
            probeTarget = resolvedPath;
        }

        // --- 统一探测逻辑 ---
        // 只有当 probeTarget 非空时才进行探测
        if (!probeTarget.empty()) {
            if (fileCache.find(probeTarget) == fileCache.end()) {
                // 这里传入 headers 是为了让 probeStandard 能处理这就网络 URL 需要鉴权的情况
                fileCache[probeTarget] = probeStandard(probeTarget, headers, "");
            }

            // 从缓存中读取元数据
            const auto &fm = fileCache[probeTarget];
            if (fm.success && !fm.tracks.empty()) {
                const auto &ft = fm.tracks[0];
                it.format = ft.format;
                it.sampleRate = ft.sampleRate;
                it.channels = ft.channels;
                it.bitDepth = ft.bitDepth;
                it.bitRate = ft.bitRate;
            }

            // 计算结束时间和持续时间
            int64_t endPos = -1;
            // 如果还有下一轨，且下一轨属于同一个文件，则当前轨结束于下一轨开始
            if (i < temps.size() - 1 && temps[i + 1].file == temps[i].file) {
                endPos = temps[i + 1].start;
            }
                // 否则（这是最后一轨，或者下一轨换文件了），使用探测到的总时长
            else if (fm.success && !fm.tracks.empty() && fm.tracks[0].durationMs > 0) {
                endPos = fm.tracks[0].durationMs;
            }

            it.endMs = endPos;
            it.durationMs = (endPos > it.startMs) ? (endPos - it.startMs) : 0;
        } else {
            // 如果路径为空（异常情况），给个默认值
            it.durationMs = 0;
        }

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
// 4. 入口分发
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
        meta = probeStandard(source, headers, filename);
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