package com.qytech.audioplayer.utils

object DopToPcm {

    /**
     * 将 DoP 数据解封装成 PCM
     * @param dopData 输入 DoP 封装的字节流 (24bit/3bytes per sample)
     * @param sampleRate PCM 采样率，例如 176400, 352800
     * @return 转换后的 PCM16 数据
     */
    fun convert(dopData: ByteArray, sampleRate: Int): ByteArray {
        val dsdBytes = extractDsd(dopData)

        // 将 DSD (1bit stream) 转换成 PCM
        return DsdInterleavedToPcm.convert(dsdBytes, sampleRate)
    }

    /**
     * 从 DoP 中提取原始 DSD bitstream
     * DoP 每帧 3 字节: [DSD byte1][DSD byte2][marker(0x05 or 0xFA)]
     */
    private fun extractDsd(dopData: ByteArray): ByteArray {
        val dsdList = ArrayList<Byte>()

        var i = 0
        while (i + 2 < dopData.size) {
            val dsd1 = dopData[i]
            val dsd2 = dopData[i + 1]
            val marker = dopData[i + 2]

            // 检查 DoP marker 是否正确
            if (marker.toInt() != 0x05 && marker.toInt() != 0xFA) {
                // 非法帧，直接跳过
                i += 3
                continue
            }

            // 添加 DSD 数据
            dsdList.add(dsd1)
            dsdList.add(dsd2)

            i += 3
        }

        return dsdList.toByteArray()
    }
}