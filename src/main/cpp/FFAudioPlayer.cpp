//
// Created by Administrator on 2025/1/6.
//

#include "FFAudioPlayer.h"
#include "Utils.h"

FFAudioPlayer::FFAudioPlayer() :
        sampleRate(44100), channels(2), duration(0), currentPosition(0), audioStreamIndex(-1),
        isPaused(false), isPlaying(false), isSeeking(false), isStopped(false), shouldStopped(false),
        errorBuffer() {
    LOGD("FFAudioPlayer create %p", this);
}

FFAudioPlayer::~FFAudioPlayer() {
    LOGD("FFAudioPlayer destroy %p", this);
    release();
}

bool FFAudioPlayer::init(const char *filePath) {
    avformat_network_init();
    if (!openAudioFile(filePath)) {
        return false;
    }
    for (int i = 0; i < formatContext->nb_streams; ++i) {
        if (formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = i;
            break;
        }
    }
    if (audioStreamIndex == -1) {
        LOGE("could not find audio stream");
        return false;
    }
    codec = avcodec_find_decoder(formatContext->streams[audioStreamIndex]->codecpar->codec_id);
    if (!codec) {
        LOGE("Failed to find audio codec");
        return false;
    }

    codecContext = avcodec_alloc_context3(codec);
    if (!codecContext) {
        LOGE("Failed to allocate audio codec context");
        return false;
    }
    result = avcodec_parameters_to_context(codecContext,
                                           formatContext->streams[audioStreamIndex]->codecpar);
    if (result < 0) {
        av_strerror(result, errorBuffer, sizeof(errorBuffer));
        LOGE("Failed to copy codec parameters %d (%s)", result, errorBuffer);
        return false;
    }

    result = avcodec_open2(codecContext, codec, nullptr);
    if (result < 0) {
        av_strerror(result, errorBuffer, sizeof(errorBuffer));
        LOGE("Failed to open codec %d (%s)", result, errorBuffer);
        return false;
    }

    swrContext = swr_alloc();
    if (!swrContext) {
        LOGE("swrContext alloc failed");
        return false;
    }

    sampleRate = codecContext->sample_rate;
    duration = formatContext->duration / AV_TIME_BASE * 1000;
    channels = codecContext->ch_layout.nb_channels;
    timeBase = &formatContext->streams[audioStreamIndex]->time_base;

    // 默认转化为双声道 AV_SAMPLE_FMT_S16 格式的
    const AVChannelLayout in_ch_layout = codecContext->ch_layout,
            out_ch_layout = AV_CHANNEL_LAYOUT_STEREO;
    AVSampleFormat in_sample_fmt = codecContext->sample_fmt,
            out_sample_fmt = AV_SAMPLE_FMT_S16;

    swr_alloc_set_opts2(
            &swrContext,
            &out_ch_layout, out_sample_fmt, sampleRate,
            &in_ch_layout, in_sample_fmt, sampleRate,
            0, nullptr
    );
    result = swr_init(swrContext);
    if (result < 0) {
        av_strerror(result, errorBuffer, sizeof(errorBuffer));
        LOGE("Failed to init swr %d (%s)", result, errorBuffer);
        return false;
    }
    return true;
}

void FFAudioPlayer::play() {
    if (isPlaying) {
        return;
    }
    // 如果解码线程已停止或没有启动，启动新的线程
    if (!decodeThread.joinable()) {
        decodeThread = std::thread(&FFAudioPlayer::decodeLoop, this);
        decodeThread.detach();
    } else {
        // 如果线程已经启动，但是被暂停，则继续
        decodeCv.notify_all();
    }
    isPlaying = true;
    isPaused = false;
    isSeeking = false;
    isStopped = false;
    shouldStopped.store(false);

}

void FFAudioPlayer::pause() {
    if (!isStopped && isPlaying && !isPaused) {
        isPaused = true;
        isPlaying = false;
        isSeeking = false;
        decodeCv.notify_all();
    }
}

void FFAudioPlayer::stop() {
    shouldStopped.store(true);
    std::lock_guard<std::mutex> lock(decodeMutex);
    isStopped = true;
    isPlaying = false;
    isSeeking = false;
    isPaused = false;
    decodeCv.notify_all();
}

void FFAudioPlayer::release() {
    stop();
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
    if (!isSeeking) {
        isSeeking = true;
        result = av_seek_frame(formatContext, audioStreamIndex, position / 1000 / av_q2d(*timeBase),
                               AVSEEK_FLAG_BACKWARD);
        if (result < 0) {
            av_strerror(result, errorBuffer, sizeof(errorBuffer));
            LOGD("seek to position %ld result %d (%s)", position, result, errorBuffer);
        } else {
            currentPosition = position;
        }
        isSeeking = false;
        decodeCv.notify_all();
    }
}

int FFAudioPlayer::getSampleRate() const {
    return sampleRate;
}

int FFAudioPlayer::getChannelNumber() const {
    return channels;
}

long FFAudioPlayer::getDuration() const {
    return duration;
}

PlayState FFAudioPlayer::getPlayState() const {
    // pause
    if (isPaused) {
        return PAUSED;
    }
    // stopped
    if (isStopped) {
        return STOPPED;
    }
    // playing
    if (isPlaying) {
        return PLAYING;
    }
    return IDLE;
}

long FFAudioPlayer::getCurrentPosition() const {
    return currentPosition;
}

bool FFAudioPlayer::openAudioFile(const char *filePath) {
    result = avformat_open_input(&formatContext, filePath, nullptr, nullptr);
    if (result < 0) {
        av_strerror(result, errorBuffer, sizeof(errorBuffer));
        LOGE("open input failed %d (%s)", result, errorBuffer);
        return false;
    }
    result = avformat_find_stream_info(formatContext, nullptr);
    if (result < 0) {
        av_strerror(result, errorBuffer, sizeof(errorBuffer));
        LOGE("find stream info failed %d (%s)", result, errorBuffer);
        return false;
    }
    return true;
}

bool FFAudioPlayer::decodePacket(AVPacket *packet, AVFrame *frame) {
    if (shouldStopped.load() || isStopped || formatContext == nullptr || packet == nullptr ||
        frame == nullptr) {
        return false;
    }
    result = av_read_frame(formatContext, packet);
    // EOF 处理
    if (result == AVERROR_EOF) {
        LOGD("End of file reached.");
        return false;  // 表示文件结束，退出解码循环
    }
    if (result < 0) {
        av_strerror(result, errorBuffer, sizeof(errorBuffer));
        LOGE("read packet frame failed %d (%s)", result, errorBuffer);
        return false;
    }

    if (packet->stream_index != audioStreamIndex) {
        LOGE("packet stream index no match");
        av_packet_unref(packet);
        return false;
    }

    result = avcodec_send_packet(codecContext, packet);
    if (result < 0) {
        av_strerror(result, errorBuffer, sizeof(errorBuffer));
        LOGE("avcodec send packet failed %d (%s)", result, errorBuffer);
        av_packet_unref(packet);
        return false;
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
            if (currentPosition != position) {
                currentPosition = position;
                // LOGD("current position is %ld", currentPosition);
            }
        } else {
            LOGE("update progress failed");
        }
    }
    return true;
}

void FFAudioPlayer::decodeLoop() {
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    std::unique_lock<std::mutex> lock(decodeMutex);
    while (!isStopped) {
        if (shouldStopped.load()) {
            LOGD("decodeLoop now is should stopped");
            break;
        }
        if (isPaused || isSeeking) {
            decodeCv.wait(lock);
            LOGD("decodeLoop now is paused");
            continue;
        }
        if (!decodePacket(packet, frame)) {
            break;
        }
    }
    if (!shouldStopped.load()) {
        onCompletion();
    }
    decodeMutex.unlock();
    av_packet_unref(packet);
    av_frame_free(&frame);
    stop();
}











