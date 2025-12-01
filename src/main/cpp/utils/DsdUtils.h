#ifndef QYPLAYER_DSDUTILS_H
#define QYPLAYER_DSDUTILS_H

#include <stdint.h>
#include <mutex> // 必须引入这个

class DsdUtils {
private:
    // 静态数组声明
    static uint8_t bit_reverse_table[256];

    // 用于保证只初始化一次的标志位
    static std::once_flag init_flag;

    // 初始化函数的具体实现（静态）
    static void initBitReverseTable();

public:
    static int packDoP(bool msbf, const uint8_t *sourceBuffer, int size, uint8_t *outBuffer);

    static int packNative(bool msbf, const uint8_t *sourceBuffer, int size, uint8_t *outBuffer);

    static int pack4ChannelNative(bool msbf, const uint8_t *sourceBuffer, int size, uint8_t *outBuffer);
};

#endif //QYPLAYER_DSDUTILS_H