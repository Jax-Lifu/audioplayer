#include "DsdUtils.h"

uint8_t DsdUtils::bit_reverse_table[256];
std::once_flag DsdUtils::init_flag;

void DsdUtils::initBitReverseTable() {
    for (int i = 0; i < 256; ++i) {
        uint8_t b = i;
        b = (b & 0xF0) >> 4 | (b & 0x0F) << 4;
        b = (b & 0xCC) >> 2 | (b & 0x33) << 2;
        b = (b & 0xAA) >> 1 | (b & 0x55) << 1;
        bit_reverse_table[i] = b;
    }
}

/**
 * 将 DSD 数据打包为 DoP (DSD over PCM) 格式
 * 输出格式：32-bit PCM (Marker 8bit | DSD 16bit | Padding 8bit)
 *
 * @param msbf true=DFF(交错, MSB优先), false=DSF(平面, LSB优先)
 * @param sourceBuffer 输入 DSD 原始数据
 * @param size 输入数据字节大小
 * @param outBuffer 输出缓冲区 (大小必须至少是 size * 2)
 * @return 写入输出缓冲区的字节数
 */
int DsdUtils::packDoP(bool msbf, const uint8_t *sourceBuffer, int size, uint8_t *outBuffer) {
    // 确保位反转表已初始化
    std::call_once(init_flag, initBitReverseTable);

    // 将输出缓冲区视为 32位 数组，方便操作
    auto *destData = reinterpret_cast<uint32_t *>(outBuffer);
    int destIndex = 0;

    // DoP Marker: 0x05 / 0xFA 交替
    uint8_t marker = 0x05;

    if (msbf) {
        // -------- DFF 格式 (交错输入: L0 R0 L1 R1 ...) --------
        // 每次处理 4 字节输入 (L0, R0, L1, R1) -> 生成 2 个 DoP 帧 (L, R)
        for (int i = 0; i + 3 < size; i += 4) {
            // 左声道 (取 src[i] 和 src[i+2])
            // DoP 结构: [Marker 8][Data0 8][Data1 8][Pad 8] 或 [Marker 8][Data0 8][Data1 8] (取决于大小端，通常是大端移位)
            // 原代码逻辑：(marker << 24) | (src << 16) | (src << 8)

            destData[destIndex++] = (marker << 24) |
                                    (sourceBuffer[i] << 16) |
                                    (sourceBuffer[i + 2] << 8);

            // 右声道 (取 src[i+1] 和 src[i+3])
            destData[destIndex++] = (marker << 24) |
                                    (sourceBuffer[i + 1] << 16) |
                                    (sourceBuffer[i + 3] << 8);

            marker ^= 0xFF; // 翻转 Marker
        }
    } else {
        // -------- DSF 格式 (平面输入: [L...][R...], 需位反转) --------
        size_t half = size / 2;
        // 每次处理 4 字节 (L0,L1, R0,R1) -> 生成 2 个 DoP 帧
        // 注意：sourceBuffer[i] 是左声道，sourceBuffer[half + i] 是右声道
        for (int i = 0; i + 1 < half; i += 2) {

            // 左声道
            destData[destIndex++] = (marker << 24) |
                                    (bit_reverse_table[sourceBuffer[i]] << 16) |
                                    (bit_reverse_table[sourceBuffer[i + 1]] << 8);

            // 右声道
            destData[destIndex++] = (marker << 24) |
                                    (bit_reverse_table[sourceBuffer[half + i]] << 16) |
                                    (bit_reverse_table[sourceBuffer[half + i + 1]] << 8);

            marker ^= 0xFF;
        }
    }

    // 返回总字节数 (int32个数 * 4)
    return destIndex * 4;
}

/**
 * 将 DSD 数据打包为 Native DSD (Interleaved 32-bit) 格式
 * 许多 USB DAC 要求 DSD_U32_BE 格式，即 4字节左声道紧接 4字节右声道
 *
 * @param msbf true=DFF, false=DSF
 * @param outBuffer 输出缓冲区 (大小等于输入 size)
 * @return 写入输出缓冲区的字节数
 */
int DsdUtils::packNative(bool msbf, const uint8_t *sourceBuffer, int size, uint8_t *outBuffer) {
    std::call_once(init_flag, initBitReverseTable);

    // 这里我们直接操作字节以精确控制内存布局，防止大小端转换问题
    // 目标结构: [L0 L1 L2 L3] [R0 R1 R2 R3] ...

    int destIndex = 0;

    if (msbf) {
        // -------- DFF 格式 (输入为 Byte 交错: L R L R ...) --------
        // 需要转换为 Block 交错 (4字节 L, 4字节 R)
        // 每次处理 8 字节输入: L0, R0, L1, R1, L2, R2, L3, R3

        for (int i = 0; i + 7 < size; i += 8) {
            // 提取 4 个左声道字节
            outBuffer[destIndex++] = sourceBuffer[i];     // L0
            outBuffer[destIndex++] = sourceBuffer[i + 2]; // L1
            outBuffer[destIndex++] = sourceBuffer[i + 4]; // L2
            outBuffer[destIndex++] = sourceBuffer[i + 6]; // L3

            // 提取 4 个右声道字节
            outBuffer[destIndex++] = sourceBuffer[i + 1]; // R0
            outBuffer[destIndex++] = sourceBuffer[i + 3]; // R1
            outBuffer[destIndex++] = sourceBuffer[i + 5]; // R2
            outBuffer[destIndex++] = sourceBuffer[i + 7]; // R3
        }
    } else {
        // -------- DSF 格式 (输入为 Planar: L... R..., 需位反转) --------
        size_t half = size / 2;

        // 每次处理 4 字节 L 和 4 字节 R
        for (int i = 0; i + 3 < half; i += 4) {
            // 左声道 4 字节 (位反转)
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[i]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[i + 1]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[i + 2]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[i + 3]];

            // 右声道 4 字节 (位反转)
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[half + i]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[half + i + 1]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[half + i + 2]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[half + i + 3]];
        }
    }

    return destIndex;
}

/**
 * 特殊 I2S/4声道 Native 打包逻辑
 * 对应原始代码中 isI2sAudio 的处理分支
 *
 * 行为特征：
 * 1. 输出按 16 字节 (128-bit) 为一个处理单元
 * 2. 格式重排：将输入重组为 [L_Word0][L_Word1][R_Word0][R_Word1] 的形式
 * 3. 字节逆序：每个 4字节 Word 内部进行倒序 (3,2,1,0)
 */
int DsdUtils::pack4ChannelNative(bool msbf, const uint8_t *sourceBuffer, int size, uint8_t *outBuffer) {
    // 确保位反转表初始化
    std::call_once(init_flag, initBitReverseTable);

    int destIndex = 0;

    if (msbf) {
        // -------- DFF 格式 (Interleaved: L R L R ...) --------
        // 原始逻辑：将每 16 字节的交错输入，重排并倒序
        // 输入：L0 R0 L1 R1 L2 R2 L3 R3 ... (L是偶数下标, R是奇数下标)

        for (int i = 0; i + 15 < size; i += 16) {
            // [0x00 - 0x03] 左声道 Word 1 (倒序: L3 L2 L1 L0)
            // 原代码 src下标: i+6, i+4, i+2, i+0
            outBuffer[destIndex++] = sourceBuffer[i + 6];
            outBuffer[destIndex++] = sourceBuffer[i + 4];
            outBuffer[destIndex++] = sourceBuffer[i + 2];
            outBuffer[destIndex++] = sourceBuffer[i + 0];

            // [0x04 - 0x07] 左声道 Word 2 (倒序: L7 L6 L5 L4)
            // 原代码 src下标: i+14, i+12, i+10, i+8
            outBuffer[destIndex++] = sourceBuffer[i + 14];
            outBuffer[destIndex++] = sourceBuffer[i + 12];
            outBuffer[destIndex++] = sourceBuffer[i + 10];
            outBuffer[destIndex++] = sourceBuffer[i + 8];

            // [0x08 - 0x0B] 右声道 Word 1 (倒序: R3 R2 R1 R0)
            // 原代码 src下标: i+7, i+5, i+3, i+1
            outBuffer[destIndex++] = sourceBuffer[i + 7];
            outBuffer[destIndex++] = sourceBuffer[i + 5];
            outBuffer[destIndex++] = sourceBuffer[i + 3];
            outBuffer[destIndex++] = sourceBuffer[i + 1];

            // [0x0C - 0x0F] 右声道 Word 2 (倒序: R7 R6 R5 R4)
            // 原代码 src下标: i+15, i+13, i+11, i+9
            outBuffer[destIndex++] = sourceBuffer[i + 15];
            outBuffer[destIndex++] = sourceBuffer[i + 13];
            outBuffer[destIndex++] = sourceBuffer[i + 11];
            outBuffer[destIndex++] = sourceBuffer[i + 9];
        }

    } else {
        // -------- DSF 格式 (Planar: L... R...) --------
        // 原始代码使用了 j + 4096，这是硬编码。
        // 通用化处理：Planar 格式下，右声道偏移量为 size / 2

        int half = size / 2;
        int srcL = 0;
        int srcR = half;

        // 每次循环处理 8 字节左声道 + 8 字节右声道 (共16字节输出)
        // 边界检查：确保左声道还有 8 字节可读
        while (srcL + 7 < half) {

            // [0x00 - 0x03] 左声道 Word 1 (位反转 + 字节倒序)
            // 原代码: reverse[src[j+3]], reverse[src[j+2]] ...
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 3]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 2]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 1]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 0]];

            // [0x04 - 0x07] 左声道 Word 2
            // 原代码: reverse[src[j+7]] ...
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 7]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 6]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 5]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcL + 4]];

            // [0x08 - 0x0B] 右声道 Word 1 (位反转 + 字节倒序)
            // 原代码: reverse[src[j + 4096 + 3]] -> 对应 srcR + 3
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 3]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 2]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 1]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 0]];

            // [0x0C - 0x0F] 右声道 Word 2
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 7]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 6]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 5]];
            outBuffer[destIndex++] = bit_reverse_table[sourceBuffer[srcR + 4]];

            // 步进：源数据处理了 8 字节
            srcL += 8;
            srcR += 8;
        }
    }

    return destIndex;
}
