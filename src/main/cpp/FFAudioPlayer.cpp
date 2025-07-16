#include "FFAudioPlayer.h"
#include "Utils.h"

// MSBF → LSBF lookup table（提前生成）
static uint8_t bit_reverse_table[256];

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
        isDecodeThreadRunning(false) {
    avformat_network_init();
    init_bit_reverse_table();
}

FFAudioPlayer::~FFAudioPlayer() {
    release();
}

bool FFAudioPlayer::init(const char *filePath, const char *headers) {
    if (!openAudioFile(filePath, headers)) {
        LOGE("Failed to open audio file: %s", filePath);
        return false;
    }

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
    LOGD("codecId: %d , %s", codecId,
         avcodec_descriptor_get(codecId)->name);
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
    result = avcodec_parameters_to_context(codecContext,
                                           codecpar);

    if (result < 0) return false;
    result = avcodec_open2(codecContext, codec, nullptr);
    if (result < 0) return false;
    if (!isNativeDSDAudio()) {
        swrContext = swr_alloc();
        if (!swrContext) return false;
        const AVChannelLayout in_ch_layout = codecContext->ch_layout;
        const AVChannelLayout out_ch_layout = AV_CHANNEL_LAYOUT_STEREO;
        AVSampleFormat in_sample_fmt = codecContext->sample_fmt;
        AVSampleFormat out_sample_fmt = AV_SAMPLE_FMT_S16;

        swr_alloc_set_opts2(&swrContext, &out_ch_layout, out_sample_fmt, sampleRate,
                            &in_ch_layout, in_sample_fmt, sampleRate, 0, nullptr);
        result = swr_init(swrContext);
        if (result < 0) return false;
    }

    sampleRate = codecContext->sample_rate;
    duration = formatContext->duration / AV_TIME_BASE * 1000;
    channels = codecContext->ch_layout.nb_channels;
    timeBase = &formatContext->streams[audioStreamIndex]->time_base;
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
        avcodec_free_context(&codecContext);
        codecContext = nullptr;
    }
    if (formatContext) {
        avformat_close_input(&formatContext);
        formatContext = nullptr;
    }
    if (swrContext) {
        swr_free(&swrContext);
        swrContext = nullptr;
    }
}

void FFAudioPlayer::seek(long position) {
    {
        std::unique_lock<std::mutex> lock(stateMutex);
        if (isSeeking || !formatContext) return;

        isSeeking = true;
        long seekTarget = position / 1000 / av_q2d(*timeBase);
        result = av_seek_frame(formatContext, audioStreamIndex, seekTarget, AVSEEK_FLAG_BACKWARD);
        if (result >= 0) {
            currentPosition = position;
        }
        isSeeking = false;
    }
    decodeCv.notify_all();
}

PlayState FFAudioPlayer::getPlayState() const {
    if (isPaused) return PAUSED;
    if (isStopped) return STOPPED;
    if (isPlaying) return PLAYING;
    return IDLE;
}

long FFAudioPlayer::getCurrentPosition() const {
    return currentPosition;
}

int FFAudioPlayer::getSampleRate() const { return sampleRate; }

int FFAudioPlayer::getChannelNumber() const { return channels; }

long FFAudioPlayer::getDuration() const { return duration; }

bool FFAudioPlayer::openAudioFile(const char *filePath, const char *headers) {
    AVDictionary *options = nullptr;
    LOGD("openAudioFile: %s headers %s", filePath, headers);
    if (headers) {
        av_dict_set(&options, "headers", headers, 0);
    }
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

    if (isNativeDSDAudio()) {
        // 更新播放进度
        if (packet->pts != AV_NOPTS_VALUE) {
            double timeSec = packet->pts * av_q2d(*timeBase);
            long positionMs = static_cast<long>(timeSec * 1000);
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                currentPosition = positionMs;
            }
        }
        std::vector<uint8_t> destData(packet->size);
        const uint8_t *src = packet->data;
        if (isMSBF) {
            // 直接复制左右声道，交错 8 字节，但不 bit reverse
            for (int i = 0; i < packet->size; i += 8) {
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
            // 带 bit reverse 的交错处理
            // 每声道大小（用 packet->size / channels ）
            const int channelSize = packet->size / channels;
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
        }

        playAudio(reinterpret_cast<const char *>(destData.data()), packet->size);
        av_packet_unref(packet);
        return true;
    }


    result = avcodec_send_packet(codecContext, packet);
    if (result < 0) {
        av_packet_unref(packet);
        return true;
    }

    uint8_t *outputBuffer = nullptr;
    int outputChannelNumber = 2;

    while (avcodec_receive_frame(codecContext, frame) == 0) {

        int outSampleSize = av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
        int outSamples = swr_get_out_samples(swrContext, frame->nb_samples);
        int bufferOutSize = outSampleSize * outSamples * outputChannelNumber;

        outputBuffer = (uint8_t *) av_malloc(bufferOutSize);
        swr_convert(swrContext, &outputBuffer, bufferOutSize,
                    (const uint8_t **) frame->data, frame->nb_samples);
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

    if (!shouldStopped) {
        onCompletion();
    }

    av_packet_unref(packet);
    av_packet_free(&packet);
    av_frame_free(&frame);
}

bool FFAudioPlayer::isNativeDSDAudio() const {
    return encodingType == AudioEncodingType::DSD || encodingType == AudioEncodingType::DST;
}
