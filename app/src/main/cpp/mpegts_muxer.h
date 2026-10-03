/*
 * Minimal MPEG-TS muxer derived from FFmpeg's libavformat/mpegtsenc.c.
 * Copyright (c) 2003 Fabrice Bellard
 * Copyright (c) 2026 UVCLiveStreaming contributors
 *
 * This file is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 */
#ifndef UVCLIVESTREAMING_MPEGTS_MUXER_H
#define UVCLIVESTREAMING_MPEGTS_MUXER_H

#include <stddef.h>
#include <stdint.h>

enum TsVideoCodec {
    TS_VIDEO_NONE = 0,
    TS_VIDEO_H264 = 1,
    TS_VIDEO_H265 = 2,
};

typedef struct TsMuxer TsMuxer;

TsMuxer *ts_muxer_create(int video_codec, int has_audio, int sample_rate,
                         int channels, int aac_object_type);
void ts_muxer_destroy(TsMuxer *muxer);
int ts_muxer_set_video_config(TsMuxer *muxer,
                              const uint8_t *csd0, size_t csd0_size,
                              const uint8_t *csd1, size_t csd1_size,
                              const uint8_t *csd2, size_t csd2_size);
int ts_muxer_write_video(TsMuxer *muxer, const uint8_t *data, size_t size,
                         int64_t pts_us, int key_frame,
                         const uint8_t **output, size_t *output_size);
int ts_muxer_write_audio(TsMuxer *muxer, const uint8_t *data, size_t size,
                         int64_t pts_us, const uint8_t **output,
                         size_t *output_size);

#endif
