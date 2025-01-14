
#include "QYFFprobe.h"
#include "Utils.h"

#include <unicode/urename.h>
#include <iconv.h>

extern "C"
JNIEXPORT jobject JNICALL
Java_com_qytech_audioplayer_ffprobe_FFprobe_probeFile(
        JNIEnv *env,
        jobject thiz,
        jstring file_path
) {
    // 创建 FFMediaInfo 对象
    jobject ffMediaInfoObject = createFFMediaInfoObject(env);
    if (ffMediaInfoObject == nullptr) {
        return nullptr;
    }

    const char *path = env->GetStringUTFChars(file_path, nullptr);
    AVFormatContext *fmt_ctx = openMediaFile(path);
    env->ReleaseStringUTFChars(file_path, path);

    if (fmt_ctx == nullptr) {
        return nullptr;
    }

    // 设置基本信息
    setBasicMediaInfoFields(env, ffMediaInfoObject, fmt_ctx);

    // 设置媒体标签信息
    setMediaTags(env, ffMediaInfoObject, fmt_ctx);

    // 设置音频/视频流信息
    setStreamInfo(env, ffMediaInfoObject, fmt_ctx);

    // 关闭文件
    avformat_close_input(&fmt_ctx);

    return ffMediaInfoObject;
}

// 创建 FFMediaInfo 对象
jobject createFFMediaInfoObject(JNIEnv *env) {
    jclass ffMediaInfoClass = env->FindClass("com/qytech/audioplayer/ffprobe/FFMediaInfo");
    if (ffMediaInfoClass == nullptr) {
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(ffMediaInfoClass, "<init>", "()V");
    if (constructor == nullptr) {
        return nullptr;
    }

    return env->NewObject(ffMediaInfoClass, constructor);
}

// 打开媒体文件并返回 AVFormatContext
AVFormatContext *openMediaFile(const char *path) {
    AVFormatContext *fmt_ctx = nullptr;
    fmt_ctx = avformat_alloc_context();
    if (!fmt_ctx) {
        LOGE("avformat_alloc_context failed");
        return nullptr;
    }

    int error;
    if ((error = avformat_open_input(&fmt_ctx, path, nullptr, nullptr)) < 0) {
        char errBuff[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(error, errBuff, sizeof(errBuff));
        LOGE("avformat_open_input failed, error code: %d, message: %s", error, errBuff);
        return nullptr;
    }

    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        avformat_close_input(&fmt_ctx);
        LOGE("Could not find stream information");
        return nullptr;
    }

    return fmt_ctx;
}

// 设置基础的媒体信息字段
void setBasicMediaInfoFields(JNIEnv *env, jobject ffMediaInfoObject, AVFormatContext *fmt_ctx) {
    jclass ffMediaInfoClass = env->GetObjectClass(ffMediaInfoObject);

    jfieldID filenameField = env->GetFieldID(ffMediaInfoClass, "filename", "Ljava/lang/String;");
    jfieldID formatNameField = env->GetFieldID(ffMediaInfoClass, "formatName",
                                               "Ljava/lang/String;");
    jfieldID durationField = env->GetFieldID(ffMediaInfoClass, "duration", "J");
    jfieldID startField = env->GetFieldID(ffMediaInfoClass, "start", "J");
    jfieldID bitRateField = env->GetFieldID(ffMediaInfoClass, "bitRate", "J");

    env->SetObjectField(ffMediaInfoObject, filenameField, Utils::charToJString(env, fmt_ctx->url));
    env->SetLongField(ffMediaInfoObject, durationField, (long) fmt_ctx->duration);
    if (fmt_ctx->start_time > 0) {
        env->SetLongField(ffMediaInfoObject, startField, (long) fmt_ctx->start_time);
    }
    env->SetLongField(ffMediaInfoObject, bitRateField, (long) fmt_ctx->bit_rate);
    env->SetObjectField(ffMediaInfoObject, formatNameField,
                        Utils::charToJString(env, fmt_ctx->iformat->name));
}

// 设置媒体标签信息
void setMediaTags(JNIEnv *env, jobject ffMediaInfoObject, AVFormatContext *fmt_ctx) {
    jclass ffMediaInfoClass = env->GetObjectClass(ffMediaInfoObject);

    jfieldID titleField = env->GetFieldID(ffMediaInfoClass, "title", "Ljava/lang/String;");
    jfieldID artistField = env->GetFieldID(ffMediaInfoClass, "artist", "Ljava/lang/String;");
    jfieldID albumField = env->GetFieldID(ffMediaInfoClass, "album", "Ljava/lang/String;");
    jfieldID genreField = env->GetFieldID(ffMediaInfoClass, "genre", "Ljava/lang/String;");
    jfieldID dateField = env->GetFieldID(ffMediaInfoClass, "date", "Ljava/lang/String;");
    jfieldID commentField = env->GetFieldID(ffMediaInfoClass, "comment", "Ljava/lang/String;");

    AVDictionaryEntry *tag = nullptr;
    while ((tag = av_dict_get(fmt_ctx->metadata, "", tag, AV_DICT_IGNORE_SUFFIX))) {
        // LOGD("metadata key:%s,value: %s", tag->key, tag->value);
        if (strcmp(tag->key, "title") == 0) {
            env->SetObjectField(ffMediaInfoObject, titleField,
                                Utils::charToJString(env, tag->value));
        } else if (strcmp(tag->key, "artist") == 0) {
            env->SetObjectField(ffMediaInfoObject, artistField,
                                Utils::charToJString(env, tag->value));
        } else if (strcmp(tag->key, "album") == 0) {
            env->SetObjectField(ffMediaInfoObject, albumField,
                                Utils::charToJString(env, tag->value));
        } else if (strcmp(tag->key, "genre") == 0) {
            env->SetObjectField(ffMediaInfoObject, genreField,
                                Utils::charToJString(env, tag->value));
        } else if (strcmp(tag->key, "date") == 0) {
            env->SetObjectField(ffMediaInfoObject, dateField,
                                Utils::charToJString(env, tag->value));
        } else if (strcmp(tag->key, "comment") == 0) {
            env->SetObjectField(ffMediaInfoObject, commentField,
                                Utils::charToJString(env, tag->value));
        }
    }
}

// 设置音频/视频流信息
void setStreamInfo(JNIEnv *env, jobject ffMediaInfoObject, AVFormatContext *fmt_ctx) {
    jclass ffMediaInfoClass = env->GetObjectClass(ffMediaInfoObject);

    jfieldID codecTypeField = env->GetFieldID(ffMediaInfoClass, "codecType", "Ljava/lang/String;");
    jfieldID codecNameField = env->GetFieldID(ffMediaInfoClass, "codecName", "Ljava/lang/String;");
    jfieldID codecLongNameField = env->GetFieldID(ffMediaInfoClass, "codecLongName",
                                                  "Ljava/lang/String;");
    jfieldID channelsField = env->GetFieldID(ffMediaInfoClass, "channels", "I");
    jfieldID channelLayoutField = env->GetFieldID(ffMediaInfoClass, "channelLayout",
                                                  "Ljava/lang/String;");
    jfieldID sampleRateField = env->GetFieldID(ffMediaInfoClass, "sampleRate", "I");
    jfieldID bitPerSampleField = env->GetFieldID(ffMediaInfoClass, "bitPerSample", "I");
    jfieldID imageField = env->GetFieldID(ffMediaInfoClass, "image", "[B");

    for (int i = 0; i < fmt_ctx->nb_streams; i++) {
        AVStream *stream = fmt_ctx->streams[i];
        AVCodecParameters *parameters = stream->codecpar;

        if (parameters->codec_type == AVMEDIA_TYPE_AUDIO) {
            setAudioStreamInfo(env, ffMediaInfoObject, parameters, codecTypeField, codecNameField,
                               codecLongNameField, channelsField, channelLayoutField,
                               sampleRateField, bitPerSampleField);
        } else if (stream->disposition & AV_DISPOSITION_ATTACHED_PIC) {
            // LOGD("Load ATTACHED_PIC");
            AVPacket pkt = stream->attached_pic;
            jbyteArray imageArray = env->NewByteArray(pkt.size);
            env->SetByteArrayRegion(imageArray, 0, pkt.size,
                                    reinterpret_cast<const jbyte *>(pkt.data));
            env->SetObjectField(ffMediaInfoObject, imageField, imageArray);
            env->DeleteLocalRef(imageArray);
        }
    }
}

// 设置音频流信息
void setAudioStreamInfo(JNIEnv *env, jobject ffMediaInfoObject, AVCodecParameters *parameters,
                        jfieldID codecTypeField, jfieldID codecNameField,
                        jfieldID codecLongNameField,
                        jfieldID channelsField, jfieldID channelLayoutField,
                        jfieldID sampleRateField,
                        jfieldID bitPerSampleField) {
    jstring codecType = Utils::charToJString(env, av_get_media_type_string(parameters->codec_type));
    const AVCodecDescriptor *cd = avcodec_descriptor_get(parameters->codec_id);
    jstring codecName = Utils::charToJString(env, cd->name);
    jstring codecLongName = Utils::charToJString(env, cd->long_name);

    env->SetObjectField(ffMediaInfoObject, codecTypeField, codecType);
    env->SetObjectField(ffMediaInfoObject, codecNameField, codecName);
    env->SetObjectField(ffMediaInfoObject, codecLongNameField, codecLongName);
    env->SetIntField(ffMediaInfoObject, channelsField, parameters->ch_layout.nb_channels);

    if (parameters->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC) {
        char value_str[128];
        av_channel_layout_describe(&parameters->ch_layout, value_str, sizeof(value_str));
        env->SetObjectField(ffMediaInfoObject, channelLayoutField,
                            Utils::charToJString(env, value_str));
    }
    // LOGD("bits_per_coded_sample %d , bits_per_raw_sample %d", parameters->bits_per_coded_sample,
    //     parameters->bits_per_raw_sample);
    env->SetIntField(ffMediaInfoObject, sampleRateField, parameters->sample_rate);
    if (parameters->codec_id == AV_CODEC_ID_DSD_LSBF ||
        parameters->codec_id == AV_CODEC_ID_DSD_MSBF ||
        parameters->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
        parameters->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR) {
        env->SetIntField(ffMediaInfoObject, bitPerSampleField, 1);
    } else {
        env->SetIntField(ffMediaInfoObject, bitPerSampleField, parameters->bits_per_coded_sample);
    }
}

// 设置视频流信息，包括封面图
void
setVideoStreamInfo(JNIEnv *env, jobject ffMediaInfoObject, AVStream *stream, jfieldID imageField) {

}




