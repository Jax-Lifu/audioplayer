/**
 * SACD Ripper - https://github.com/sacd-ripper/
 *
 * Copyright (c) 2010-2015 by respective authors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */

#ifndef SCARLETBOOK_OUTPUT_H_INCLUDED
#define SCARLETBOOK_OUTPUT_H_INCLUDED

#ifdef __lv2ppu__
#include "dst_decoder_ps3.h"
#else

#include <dst_decoder.h>

#endif

#include "scarletbook.h"

#define BYTES_PER_SECOND 705600
// forward declaration
typedef struct scarletbook_output_format_t scarletbook_output_format_t;
typedef struct scarletbook_output_s scarletbook_output_t;

enum {
    OUTPUT_FLAG_RAW = 1 << 0,
    OUTPUT_FLAG_DSD = 1 << 1,
    OUTPUT_FLAG_DST = 1 << 2,
    OUTPUT_FLAG_EDIT_MASTER = 1 << 3
};

// Handler structure defined by each output format.
typedef struct scarletbook_format_handler_t {
    char const *description;
    char const *name;

    int (*startwrite)(scarletbook_output_format_t *ft);

    int (*write)(scarletbook_output_format_t *ft, const uint8_t *buf, size_t len);

    int (*stopwrite)(scarletbook_output_format_t *ft);

    int flags;
    size_t priv_size;
}
        scarletbook_format_handler_t;

typedef int (*fwprintf_callback_t)(FILE *stream, const wchar_t *format, ...);

/**
 * 播放音频数据回调
 * @param data 音频数据指针 (PCM or DSD)
 * @param size 数据长度
 * @param track_index 当前音轨
 * @return 0 继续, -1 停止/出错
 */
typedef int (*playback_audio_callback_t)(void *context, uint8_t *data, size_t size,
                                         int track_index);

/**
 * 播放进度回调
 * @param track_index 当前音轨
 * @param current_ms 当前播放毫秒数
 * @param total_ms 总毫秒数
 * @param progress 进度百分比 (0.0 - 1.0)
 */
typedef void (*playback_progress_callback_t)(void *context, int track_index, uint32_t current_ms,
                                             uint32_t total_ms, float progress);

struct scarletbook_output_format_t {
    int area;
    int track;
    uint32_t start_lsn;
    uint32_t length_lsn;
    uint32_t current_lsn;
    char *filename;

    int channel_count;

    FILE *fd;
    char *write_cache;
    uint64_t write_length;
    uint64_t write_offset;
    uint64_t total_millisecond;

    int dst_encoded_import;
    int dsd_encoded_export;

    scarletbook_format_handler_t handler;
    void *priv;

    int error_nr;
    char error_str[256];

    dst_decoder_t *dst_decoder;

    scarletbook_handle_t *sb_handle;
    fwprintf_callback_t cb_fwprintf;

    void *playback_context;
    playback_audio_callback_t playback_audio_cb;
    playback_progress_callback_t playback_progress_cb;

    struct list_head siblings;
};

typedef void (*stats_progress_callback_t)(uint32_t stats_total_sectors,
                                          uint32_t stats_total_sectors_processed,
                                          uint32_t stats_current_file_total_sectors,
                                          uint32_t stats_current_file_sectors_processed);

typedef void (*stats_track_callback_t)(char *filename, int current_track, int total_tracks);


// 直接传入音频回调和进度回调
scarletbook_output_t *
scarletbook_output_create_for_player(scarletbook_handle_t *handle,
                                     void *context,
                                     playback_audio_callback_t audio_cb,
                                     playback_progress_callback_t progress_cb);

scarletbook_output_t *
scarletbook_output_create(scarletbook_handle_t *, stats_track_callback_t, stats_progress_callback_t,
                          fwprintf_callback_t);

int scarletbook_output_destroy(scarletbook_output_t *);

int
scarletbook_output_enqueue_track(scarletbook_output_t *, int, int, const char *, const char *, int);

int scarletbook_output_enqueue_raw_sectors(scarletbook_output_t *, int, int, char *, char *);

int scarletbook_output_enqueue_concatenate_tracks(scarletbook_output_t *output, int area, int track,
                                                  char *file_path, char *fmt,
                                                  int dsd_encoded_export, int last_track);

int scarletbook_output_start(scarletbook_output_t *);

void scarletbook_output_interrupt(scarletbook_output_t *);

void scarletbook_output_pause(scarletbook_output_t *output);

void scarletbook_output_resume(scarletbook_output_t *output);

void scarletbook_output_seek(scarletbook_output_t *, uint64_t);

int scarletbook_output_is_busy(scarletbook_output_t *);

#endif /* SCARLETBOOK_OUTPUT_H_INCLUDED */
