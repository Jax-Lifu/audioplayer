
#include "QYFFprobe.h"

#include <unicode/urename.h>
#include <iconv.h>

#define LOG_TAG "qy_ffmpeg"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

// 判断是否是有效的UTF-8字符串
bool isValidUTF8(const char *str) {
    unsigned char c;
    while ((c = *str++)) {
        if (c < 0x80) {
            continue;  // ASCII字符
        } else if ((c & 0xE0) == 0xC0) {
            // 110xxxxx
            if ((*str & 0xC0) != 0x80) {
                return false;  // 错误的多字节字符
            }
            str++;
        } else if ((c & 0xF0) == 0xE0) {
            // 1110xxxx
            if ((*(str + 1) & 0xC0) != 0x80 || (*(str + 2) & 0xC0) != 0x80) {
                return false;
            }
            str += 2;
        } else if ((c & 0xF8) == 0xF0) {
            // 11110xxx
            if ((*(str + 1) & 0xC0) != 0x80 || (*(str + 2) & 0xC0) != 0x80 ||
                (*(str + 3) & 0xC0) != 0x80) {
                return false;
            }
            str += 3;
        } else {
            return false;  // 无效的UTF-8字节
        }
    }
    return true;
}


jstring charToJString(JNIEnv *env, const char *data) {
    try {
        jobject bb = env->NewDirectByteBuffer((void *) data, (int) strlen(data));

        jclass cls_Charset = env->FindClass("java/nio/charset/Charset");
        jmethodID mid_Charset_forName = env->GetStaticMethodID(cls_Charset, "forName",
                                                               "(Ljava/lang/String;)Ljava/nio/charset/Charset;");
        jobject charset = env->CallStaticObjectMethod(cls_Charset, mid_Charset_forName,
                                                      env->NewStringUTF("UTF-8"));

        jmethodID mid_Charset_decode = env->GetMethodID(cls_Charset, "decode",
                                                        "(Ljava/nio/ByteBuffer;)Ljava/nio/CharBuffer;");
        jobject cb = env->CallObjectMethod(charset, mid_Charset_decode, bb);
        env->DeleteLocalRef(bb);

        jclass cls_CharBuffer = env->FindClass("java/nio/CharBuffer");
        jmethodID mid_CharBuffer_toString = env->GetMethodID(cls_CharBuffer, "toString",
                                                             "()Ljava/lang/String;");
        return (jstring) env->CallObjectMethod(cb, mid_CharBuffer_toString);
    } catch (const std::exception &e) {
        env->ExceptionClear();  // 清除异常状态
        return nullptr;
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_qytech_audioplayer_ffprobe_FFprobe_codecs(
        JNIEnv *env,
        jobject thiz) {
    // 遍历所有编解码器
    void *i = nullptr;
    const AVCodec *c;

    while ((c = av_codec_iterate(&i))) {
        if (c->type == AVMEDIA_TYPE_AUDIO && av_codec_is_decoder(c)) {
            LOGD("c %s", c->name);
        }
    }
}

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

    env->SetObjectField(ffMediaInfoObject, filenameField, charToJString(env, fmt_ctx->url));
    env->SetLongField(ffMediaInfoObject, durationField, (long) fmt_ctx->duration);
    if (fmt_ctx->start_time > 0) {
        env->SetLongField(ffMediaInfoObject, startField, (long) fmt_ctx->start_time);
    }
    env->SetLongField(ffMediaInfoObject, bitRateField, (long) fmt_ctx->bit_rate);
    env->SetObjectField(ffMediaInfoObject, formatNameField,
                        charToJString(env, fmt_ctx->iformat->name));
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
            env->SetObjectField(ffMediaInfoObject, titleField, charToJString(env, tag->value));
        } else if (strcmp(tag->key, "artist") == 0) {
            env->SetObjectField(ffMediaInfoObject, artistField, charToJString(env, tag->value));
        } else if (strcmp(tag->key, "album") == 0) {
            env->SetObjectField(ffMediaInfoObject, albumField, charToJString(env, tag->value));
        } else if (strcmp(tag->key, "genre") == 0) {
            env->SetObjectField(ffMediaInfoObject, genreField, charToJString(env, tag->value));
        } else if (strcmp(tag->key, "date") == 0) {
            env->SetObjectField(ffMediaInfoObject, dateField, charToJString(env, tag->value));
        } else if (strcmp(tag->key, "comment") == 0) {
            env->SetObjectField(ffMediaInfoObject, commentField, charToJString(env, tag->value));
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
    jstring codecType = charToJString(env, av_get_media_type_string(parameters->codec_type));
    const AVCodecDescriptor *cd = avcodec_descriptor_get(parameters->codec_id);
    jstring codecName = charToJString(env, cd->name);
    jstring codecLongName = charToJString(env, cd->long_name);

    env->SetObjectField(ffMediaInfoObject, codecTypeField, codecType);
    env->SetObjectField(ffMediaInfoObject, codecNameField, codecName);
    env->SetObjectField(ffMediaInfoObject, codecLongNameField, codecLongName);
    env->SetIntField(ffMediaInfoObject, channelsField, parameters->ch_layout.nb_channels);

    if (parameters->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC) {
        char value_str[128];
        av_channel_layout_describe(&parameters->ch_layout, value_str, sizeof(value_str));
        env->SetObjectField(ffMediaInfoObject, channelLayoutField, charToJString(env, value_str));
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




