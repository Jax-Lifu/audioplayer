#include "FFAudioPlayer.h"
#include "Utils.h"
#include <sys/system_properties.h>

#define  PROPERTY_VALUE_MAX 128
// MSBF → LSBF lookup table（提前生成）
static uint8_t bit_reverse_table[256];

// 读取系统属性
static std::string getSystemProperty(const char *key, const char *def = "") {
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get(key, value) > 0) {
        return {value};
    }
    return {def};
}

// 判断 persist.sys.audio.i2s 是否开启
static bool isI2sEnabled() {
    std::string prop = getSystemProperty("persist.sys.audio.i2s", "false");
    return (prop == "1" || prop == "true" || prop == "True");
}

static void init_bit_reverse_table() {
    for (int i = 0; i < 256; ++i) {
        uint8_t b = i;
        b = (b & 0xF0) >> 4 | (b & 0x0F) << 4;
        b = (b & 0xCC) >> 2 | (b & 0x33) << 2;
        b = (b & 0xAA) >> 1 | (b & 0x55) << 1;
        bit_reverse_table[i] = b;
    }
}

FFAudioPlayer::FFAudioPlayer() :
        sampleRate(44100), channels(2), duration(0), currentPosition(0), audioStreamIndex(-1),
        isPaused(false), isPlaying(false), isSeeking(false), isStopped(false), shouldStopped(false),
        isDecodeThreadRunning(false), swrContext(nullptr) {
    avformat_network_init();
    init_bit_reverse_table();
}

FFAudioPlayer::~FFAudioPlayer() {
    release();
}

bool
FFAudioPlayer::init(const char *filePath, const char *headers, int dsd_mode, int d2p_sample_rate) {
    if (!openAudioFile(filePath, headers)) {
        LOGE("Failed to open audio file: %s", filePath);
        return false;
    }
    isI2sAudio = isI2sEnabled();
    switch (dsd_mode) {
        case 0:
            this->dsdMode = NATIVE;
            break;
        case 1:
            this->dsdMode = D2P;
            break;
        case 2:
            this->dsdMode = DOP;
            break;
        default:
            this->dsdMode = NATIVE;
            break;
    }
    this->d2pSampleRate = d2p_sample_rate;
    LOGD("dsd mode %d", dsd_mode);
    for (int i = 0; i < formatContext->nb_streams; ++i) {
        if (formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = i;
            break;
        }
    }
    if (audioStreamIndex == -1) return false;

    codec = avcodec_find_decoder(formatContext->streams[audioStreamIndex]->codecpar->codec_id);
    if (!codec) return false;

    codecContext = avcodec_alloc_context3(codec);
    if (!codecContext) return false;

    AVCodecParameters *codecpar = formatContext->streams[audioStreamIndex]->codecpar;
    AVCodecID codecId = codecpar->codec_id;

    LOGD("codecId: %d , codec name %s, dsd mode %d", codecId,
         avcodec_descriptor_get(codecId)->name, dsd_mode);

    switch (codecId) {
        case AV_CODEC_ID_DSD_LSBF:
        case AV_CODEC_ID_DSD_MSBF:
        case AV_CODEC_ID_DSD_LSBF_PLANAR:
        case AV_CODEC_ID_DSD_MSBF_PLANAR:
            encodingType = AudioEncodingType::DSD;
            break;
        case AV_CODEC_ID_DST:
            encodingType = AudioEncodingType::DST;
            break;
        default:
            encodingType = AudioEncodingType::PCM;
            break;
    }
    isMSBF = codecId == AV_CODEC_ID_DSD_MSBF || codecId == AV_CODEC_ID_DSD_MSBF_PLANAR;

    result = avcodec_parameters_to_context(codecContext, codecpar);
    if (result < 0) return false;
    result = avcodec_open2(codecContext, codec, nullptr);
    if (result < 0) return false;

    sampleRate = codecContext->sample_rate;
    duration = formatContext->duration / AV_TIME_BASE * 1000;
    channels = codecContext->ch_layout.nb_channels;
    timeBase = &formatContext->streams[audioStreamIndex]->time_base;

    // 如果是 PCM 或 D2P DSD，创建 swrContext
    if (!isDsdAudio() || (isDsdAudio() && dsdMode == D2P)) {
        if (!swrContext) {
            swrContext = swr_alloc();
            if (!swrContext) return false;

            AVChannelLayout in_ch_layout = codecContext->ch_layout;
            AVSampleFormat in_sample_fmt = codecContext->sample_fmt;
            int in_sample_rate = codecContext->sample_rate;

            AVChannelLayout out_ch_layout = AV_CHANNEL_LAYOUT_STEREO;
            AVSampleFormat out_sample_fmt = AV_SAMPLE_FMT_S16;
            int out_sample_rate = isDsdAudio() ? d2pSampleRate : sampleRate;

            swr_alloc_set_opts2(&swrContext,
                                &out_ch_layout, out_sample_fmt, out_sample_rate,
                                &in_ch_layout, in_sample_fmt, in_sample_rate,
                                0, nullptr);
            if (swr_init(swrContext) < 0) return false;
            channels = 2;
        }
    }
    if (isDsdAudio()) {
        sampleRate = sampleRate * 8;
    }
    return true;
}

void FFAudioPlayer::play() {
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        if (isPlaying) return;

        shouldStopped = false;
        isStopped = false;
        isPaused = false;
        isSeeking = false;
        isPlaying = true;

        if (!isDecodeThreadRunning.load()) {
            isDecodeThreadRunning = true;
            decodeThread = std::thread(&FFAudioPlayer::decodeLoop, this);
        } else {
            decodeCv.notify_all();
        }
    }
}

void FFAudioPlayer::pause() {
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        if (!isStopped && isPlaying && !isPaused) {
            isPaused = true;
            isPlaying = false;
        }
    }
    decodeCv.notify_all();
}

void FFAudioPlayer::stop() {
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        shouldStopped = true;
        isStopped = true;
        isPaused = false;
        isPlaying = false;
        isSeeking = false;
    }
    decodeCv.notify_all();
    if (decodeThread.joinable()) {
        decodeThread.join();
        isDecodeThreadRunning = false;
    }
}

void FFAudioPlayer::release() {
    stop();
    std::lock_guard<std::mutex> lock(stateMutex);
    if (codecContext) {
        avcodec_flush_buffers(codecContext);
        avcodec_free_context(&codecContext);
        codecContext = nullptr;
    }
    if (formatContext) {
        avformat_close_input(&formatContext);
        avformat_free_context(formatContext);
        formatContext = nullptr;
    }
    if (swrContext) {
        swr_free(&swrContext);
        swrContext = nullptr;
    }
}

void FFAudioPlayer::seek(long position) {
    std::unique_lock<std::mutex> lock(stateMutex);
    if (isSeeking || !formatContext) return;

    isSeeking = true;
    long seekTarget = position / 1000 / av_q2d(*timeBase);
    result = av_seek_frame(formatContext, audioStreamIndex, seekTarget, AVSEEK_FLAG_BACKWARD);
    if (result >= 0) currentPosition = position;
    isSeeking = false;
    decodeCv.notify_all();
}

PlayState FFAudioPlayer::getPlayState() const {
    if (isPaused) return PAUSED;
    if (isStopped) return STOPPED;
    if (isPlaying) return PLAYING;
    return IDLE;
}

long FFAudioPlayer::getCurrentPosition() const { return currentPosition; }

int FFAudioPlayer::getSampleRate() const { return sampleRate; }

int FFAudioPlayer::getChannelNumber() const { return channels; }

long FFAudioPlayer::getDuration() const { return duration; }

bool FFAudioPlayer::openAudioFile(const char *filePath, const char *headers) {
    AVDictionary *options = nullptr;
    if (headers) av_dict_set(&options, "headers", headers, 0);

    result = avformat_open_input(&formatContext, filePath, nullptr, &options);
    if (result < 0) return false;
    result = avformat_find_stream_info(formatContext, nullptr);
    if (result < 0) return false;
    return true;
}

bool FFAudioPlayer::decodePacket(AVPacket *packet, AVFrame *frame) {
    if (shouldStopped || isStopped || !formatContext || !packet || !frame) return false;

    result = av_read_frame(formatContext, packet);
    if (result == AVERROR_EOF) return false;
    if (result < 0) return false;

    if (packet->stream_index != audioStreamIndex) {
        av_packet_unref(packet);
        return true;
    }

    if (isDsdAudio()) {
        // 更新播放进度
        if (packet->pts != AV_NOPTS_VALUE) {
            double timeSec = packet->pts * av_q2d(*timeBase);
            long positionMs = static_cast<long>(timeSec * 1000);
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                currentPosition = positionMs;
            }
        }
        switch (dsdMode) {
            case NATIVE:
                processDSDNative(packet->data, packet->size);
                break;
            case D2P:
                processDSDD2P(codecContext, packet);
                break;
            case DOP:
                processDSDOp(packet->data, packet->size);
                break;
        }
        av_packet_unref(packet);
        return true;
    }

    result = avcodec_send_packet(codecContext, packet);
    if (result < 0) {
        av_packet_unref(packet);
        return true;
    }

    while (avcodec_receive_frame(codecContext, frame) == 0) {
        int outSamples = swr_get_out_samples(swrContext, frame->nb_samples);
        int outSampleSize = av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
        int bufferOutSize = outSamples * outSampleSize * channels;

        uint8_t *outputBuffer = (uint8_t *) av_malloc(bufferOutSize);
        if (!outputBuffer) break;

        swr_convert(swrContext, &outputBuffer, outSamples, (const uint8_t **) frame->data,
                    frame->nb_samples);
        playAudio((char *) outputBuffer, bufferOutSize);
        av_free(outputBuffer);

        if (frame->pts != AV_NOPTS_VALUE) {
            long position = frame->pts * av_q2d(*timeBase) * 1000;
            std::lock_guard<std::mutex> lock(stateMutex);
            currentPosition = position;
        }
    }

    av_packet_unref(packet);
    return true;
}

void FFAudioPlayer::decodeLoop() {
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    std::unique_lock<std::mutex> lock(decodeMutex);

    while (true) {
        decodeCv.wait(lock, [this]() {
            std::lock_guard<std::mutex> stateLock(stateMutex);
            return shouldStopped || (!isPaused && !isSeeking);
        });

        {
            std::lock_guard<std::mutex> stateLock(stateMutex);
            if (shouldStopped || isStopped) break;
        }

        if (!decodePacket(packet, frame)) break;
    }

    {
        std::lock_guard<std::mutex> lock(stateMutex);
        isPlaying = false;
    }

    if (!shouldStopped) onCompletion();

    av_packet_unref(packet);
    av_packet_free(&packet);
    av_frame_free(&frame);
}

bool FFAudioPlayer::isDsdAudio() const {
    return encodingType == AudioEncodingType::DSD || encodingType == AudioEncodingType::DST;
}

// -------------------- D2P 处理 --------------------
void FFAudioPlayer::processDSDD2P(AVCodecContext *avctx, const AVPacket *avpkt) {
    if (!avctx || !avpkt) return;
    if (avcodec_send_packet(avctx, avpkt) < 0) return;

    AVFrame *frame = av_frame_alloc();
    if (!frame) return;

    while (avcodec_receive_frame(avctx, frame) == 0) {
        int outSamples = av_rescale_rnd(
                swr_get_delay(swrContext, frame->sample_rate) + frame->nb_samples,
                d2pSampleRate, frame->sample_rate, AV_ROUND_UP);
        int outSampleSize = av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
        int bufferOutSize = outSamples * outSampleSize * 2;

        uint8_t *outputBuffer = (uint8_t *) av_malloc(bufferOutSize);
        if (!outputBuffer) break;

        int convertedSamples = swr_convert(swrContext, &outputBuffer, outSamples,
                                           (const uint8_t **) frame->data, frame->nb_samples);
        if (convertedSamples > 0) {
            int data_size = convertedSamples * outSampleSize * 2;
            playAudio((char *) outputBuffer, data_size);
        }

        av_free(outputBuffer);

        if (frame->pts != AV_NOPTS_VALUE) {
            double timeSec = frame->pts * av_q2d(*timeBase);
            long positionMs = static_cast<long>(timeSec * 1000);
            std::lock_guard<std::mutex> lock(stateMutex);
            currentPosition = positionMs;
        }

        av_frame_unref(frame);
    }

    av_frame_free(&frame);
}


void FFAudioPlayer::processDSDNative(const uint8_t *src, size_t size) {
    std::vector<uint8_t> destData(size);
    if (isMSBF) {
        // 直接复制左右声道，交错 8 字节，但不 bit reverse
        if (!isI2sAudio) {
            for (int i = 0; i < size; i += 8) {
                // 左声道
                destData[i] = src[i];
                destData[i + 1] = src[i + 2];
                destData[i + 2] = src[i + 4];
                destData[i + 3] = src[i + 6];
                // 右声道
                destData[i + 4] = src[i + 1];
                destData[i + 5] = src[i + 3];
                destData[i + 6] = src[i + 5];
                destData[i + 7] = src[i + 7];
            }
        } else {
            for (int i = 0; i < size; i += 16) {
                destData[i + 0x00] = src[i + 6];
                destData[i + 0x01] = src[i + 4];
                destData[i + 0x02] = src[i + 2];
                destData[i + 0x03] = src[i + 0];

                destData[i + 0x04] = src[i + 14];
                destData[i + 0x05] = src[i + 12];
                destData[i + 0x06] = src[i + 10];
                destData[i + 0x07] = src[i + 8];

                destData[i + 0x08] = src[i + 7];
                destData[i + 0x09] = src[i + 5];
                destData[i + 0x0a] = src[i + 3];
                destData[i + 0x0b] = src[i + 1];

                destData[i + 0x0c] = src[i + 15];
                destData[i + 0x0d] = src[i + 13];
                destData[i + 0x0e] = src[i + 11];
                destData[i + 0x0f] = src[i + 9];
            }
        }
    } else {
        // 带 bit reverse 的交错处理
        // 每声道大小（用 packet->size / channels ）
        if (!isI2sAudio) {
            const int channelSize = size / channels;
            for (int j = 0, k = 0; j < channelSize; j += 4, k += 8) {
                // 左声道
                destData[k] = bit_reverse_table[src[j] & 0xFF];
                destData[k + 1] = bit_reverse_table[src[j + 1] & 0xFF];
                destData[k + 2] = bit_reverse_table[src[j + 2] & 0xFF];
                destData[k + 3] = bit_reverse_table[src[j + 3] & 0xFF];
                // 右声道
                destData[k + 4] = bit_reverse_table[src[j + channelSize] & 0xFF];
                destData[k + 5] = bit_reverse_table[src[j + channelSize + 1] & 0xFF];
                destData[k + 6] = bit_reverse_table[src[j + channelSize + 2] & 0xFF];
                destData[k + 7] = bit_reverse_table[src[j + channelSize + 3] & 0xFF];
            }
        } else {
            int j = 0;
            for (int i = 0; i < size; i += 16) {
                destData[i + 0x00] = bit_reverse_table[src[j + 0x03]];
                destData[i + 0x01] = bit_reverse_table[src[j + 0x02]];
                destData[i + 0x02] = bit_reverse_table[src[j + 0x01]];
                destData[i + 0x03] = bit_reverse_table[src[j + 0x00]];
                destData[i + 0x04] = bit_reverse_table[src[j + 0x07]];
                destData[i + 0x05] = bit_reverse_table[src[j + 0x06]];
                destData[i + 0x06] = bit_reverse_table[src[j + 0x05]];
                destData[i + 0x07] = bit_reverse_table[src[j + 0x04]];
                j += 8;
            }
            j = 0;
            for (int i = 0; i < size; i += 16) {
                destData[i + 0x08] = bit_reverse_table[src[j + 4096 + 0x03]];
                destData[i + 0x09] = bit_reverse_table[src[j + 4096 + 0x02]];
                destData[i + 0x0a] = bit_reverse_table[src[j + 4096 + 0x01]];
                destData[i + 0x0b] = bit_reverse_table[src[j + 4096 + 0x00]];
                destData[i + 0x0c] = bit_reverse_table[src[j + 4096 + 0x07]];
                destData[i + 0x0d] = bit_reverse_table[src[j + 4096 + 0x06]];
                destData[i + 0x0e] = bit_reverse_table[src[j + 4096 + 0x05]];
                destData[i + 0x0f] = bit_reverse_table[src[j + 4096 + 0x04]];
                j += 8;
            }
        }
    }
    playAudio(reinterpret_cast<const char *>(destData.data()), destData.size());
}

void FFAudioPlayer::processDSDOp(const uint8_t *src, size_t size) {
    constexpr size_t CHUNK_SIZE = 8192; // 每块读取大小
    size_t offset = 0;

    while (offset < size) {
        size_t chunkSize = (size - offset) > CHUNK_SIZE ? CHUNK_SIZE : (size - offset);

        int32_t destData[4096] = {0};
        uint8_t marker = 0x05;

        if (isMSBF) {
            // DFF 处理
            for (size_t i = 0, j = 0; j + 3 < chunkSize; i += 2, j += 4) {
                destData[i] =
                        (marker << 24) | (src[offset + j + 0] << 16) | (src[offset + j + 2] << 8) |
                        0x0;
                destData[i + 1] =
                        (marker << 24) | (src[offset + j + 1] << 16) | (src[offset + j + 3] << 8) |
                        0x0;
                marker ^= 0xFF;
            }
        } else {
            // DSF 处理（带 bit-reversal，左右声道平面交错 4096）
            for (size_t i = 0; 4096 + i + 3 < chunkSize; i += 4) {
                destData[i] = (marker << 24) | (bit_reverse_table[src[offset + i + 0]] << 16) |
                              (bit_reverse_table[src[offset + i + 1]] << 8) | 0x0;
                destData[i + 1] =
                        (marker << 24) | (bit_reverse_table[src[offset + 4096 + i + 0]] << 16) |
                        (bit_reverse_table[src[offset + 4096 + i + 1]] << 8) | 0x0;
                marker ^= 0xFF;
                destData[i + 2] = (marker << 24) | (bit_reverse_table[src[offset + i + 2]] << 16) |
                                  (bit_reverse_table[src[offset + i + 3]] << 8) | 0x0;
                destData[i + 3] =
                        (marker << 24) | (bit_reverse_table[src[offset + 4096 + i + 2]] << 16) |
                        (bit_reverse_table[src[offset + 4096 + i + 3]] << 8) | 0x0;
                marker ^= 0xFF;
            }
        }

        playAudio(reinterpret_cast<const char *>(destData), 4096 * sizeof(int32_t));
        offset += chunkSize;
    }
}


