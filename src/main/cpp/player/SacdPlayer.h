#ifndef QYPLAYER_SACDPLAYER_H
#define QYPLAYER_SACDPLAYER_H

#include "BasePlayer.h"
#include <vector>
#include "FFmpegD2pDecoder.h"

extern "C" {
// sacd 头文件
#include "sacd_reader.h"
#include "scarletbook_read.h"
#include "scarletbook_output.h"
#include "utils.h"
}

class SacdPlayer : public BasePlayer {
public:
    explicit SacdPlayer(IPlayerCallback *callback);

    ~SacdPlayer() override;

    // 特有的数据源设置接口
    void setDataSource(const std::string &isoPath, int trackIndex);

    // 重写基类虚函数
    void prepare() override;

    void play() override;

    void pause() override;

    void resume() override;

    void stop() override;

    void seek(long ms) override;

    void release() override;

    // Getters 实现
    long getDuration() const override;

    long getCurrentPosition() const override;

    int getSampleRate() const override;

    int getChannelCount() const override;

    int getBitPerSample() const override;

    bool isDsd() const override;

    // Internal Callbacks (供 C 语言回调使用)
    int onDecodeData(uint8_t *data, size_t size, int track_index);

    void onDecodeProgress(int track, uint32_t current, uint32_t total, float progress);

private:
    void releaseInternal();

    bool openSacdHandle();

    void closeSacdHandle();

    long getTrackDurationMs(int track_index);

    void extractAudioInfo();

private:
    std::string isoPath;
    int trackIndex = 0;
    int area_idx = -1;
    FFmpegD2pDecoder *d2pDecoder = nullptr;

    // Buffers
    std::vector<uint8_t> outBuffer;

    // Native Handles
    sacd_reader_t *mReader = nullptr;
    scarletbook_handle_t *mHandle = nullptr;
    scarletbook_output_t *mOutput = nullptr;
};

#endif //QYPLAYER_SACDPLAYER_H