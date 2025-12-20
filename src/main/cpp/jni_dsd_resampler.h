#ifndef QYLAUNCHER_JNI_DSD_RESAMPLER_H
#define QYLAUNCHER_JNI_DSD_RESAMPLER_H

#include <jni.h>
#include <vector>
#include "Logger.h"
#include "FFmpegD2pDecoder.h"
#include "DsdUtils.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/time.h>
#include <libavutil/opt.h>
}

int register_dsd_resampler_methods(JavaVM *vm, JNIEnv *env);

#endif //QYLAUNCHER_JNI_DSD_RESAMPLER_H
