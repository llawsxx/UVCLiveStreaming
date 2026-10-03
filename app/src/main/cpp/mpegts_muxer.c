/*
 * Minimal MPEG-TS muxer derived from FFmpeg's libavformat/mpegtsenc.c.
 * Copyright (c) 2003 Fabrice Bellard
 * Copyright (c) 2026 UVCLiveStreaming contributors
 *
 * This file is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This extraction keeps the transport-stream mechanisms needed by
 * UVCLiveStreaming: PAT/PMT, H.264/H.265 and AAC stream declarations, PES headers,
 * PTS/PCR, continuity counters, random-access flags and 188-byte packetizing.
 */
#include "mpegts_muxer.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>

#define TS_PACKET_SIZE 188
#define PAT_PID 0x0000
#define PMT_PID 0x1000
#define VIDEO_PID 0x0100
#define AUDIO_PID 0x0101
#define TABLE_PERIOD_90K 45000

typedef struct ByteBuffer {
    uint8_t *data;
    size_t size;
    size_t capacity;
} ByteBuffer;

struct TsMuxer {
    int video_codec;
    int has_audio;
    int sample_rate;
    int channels;
    int aac_object_type;
    int nal_length_size;
    int tables_written;
    int64_t last_tables_pts;
    uint8_t continuity[4];
    ByteBuffer output;
    ByteBuffer video_config;
    ByteBuffer elementary;
    ByteBuffer pes;
};

static int reserve(ByteBuffer *buffer, size_t required) {
    if (required <= buffer->capacity)
        return 0;
    size_t capacity = buffer->capacity ? buffer->capacity : 1024;
    while (capacity < required) {
        if (capacity > SIZE_MAX / 2)
            return -1;
        capacity *= 2;
    }
    uint8_t *data = (uint8_t *)realloc(buffer->data, capacity);
    if (!data)
        return -1;
    buffer->data = data;
    buffer->capacity = capacity;
    return 0;
}

static int append(ByteBuffer *buffer, const void *data, size_t size) {
    if (size == 0)
        return 0;
    if (!data)
        return -1;
    if (size > SIZE_MAX - buffer->size || reserve(buffer, buffer->size + size) < 0)
        return -1;
    memcpy(buffer->data + buffer->size, data, size);
    buffer->size += size;
    return 0;
}

static uint32_t crc32_mpeg(const uint8_t *data, size_t size) {
    uint32_t crc = 0xffffffffU;
    for (size_t i = 0; i < size; ++i) {
        crc ^= (uint32_t)data[i] << 24;
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc << 1) ^ ((crc & 0x80000000U) ? 0x04c11db7U : 0);
    }
    return crc;
}

static int pid_index(int pid) {
    if (pid == PAT_PID) return 0;
    if (pid == PMT_PID) return 1;
    if (pid == VIDEO_PID) return 2;
    return 3;
}

static int write_section_packet(TsMuxer *muxer, int pid,
                                const uint8_t *section, size_t section_size) {
    if (section_size + 5 > TS_PACKET_SIZE)
        return -1;
    uint8_t packet[TS_PACKET_SIZE];
    memset(packet, 0xff, sizeof(packet));
    int index = pid_index(pid);
    packet[0] = 0x47;
    packet[1] = 0x40 | (uint8_t)(pid >> 8);
    packet[2] = (uint8_t)pid;
    packet[3] = 0x10 | muxer->continuity[index];
    muxer->continuity[index] = (muxer->continuity[index] + 1) & 0x0f;
    packet[4] = 0x00;
    memcpy(packet + 5, section, section_size);
    return append(&muxer->output, packet, sizeof(packet));
}

static int append_crc(ByteBuffer *section) {
    uint32_t crc = crc32_mpeg(section->data, section->size);
    uint8_t bytes[4] = {
        (uint8_t)(crc >> 24), (uint8_t)(crc >> 16),
        (uint8_t)(crc >> 8), (uint8_t)crc,
    };
    return append(section, bytes, sizeof(bytes));
}

static int write_tables(TsMuxer *muxer) {
    uint8_t pat_data[] = {
        0x00, 0xb0, 0x0d, 0x00, 0x01, 0xc1, 0x00, 0x00,
        0x00, 0x01, 0xf0 | (PMT_PID >> 8), PMT_PID & 0xff,
    };
    ByteBuffer section = {0};
    if (append(&section, pat_data, sizeof(pat_data)) < 0 ||
        append_crc(&section) < 0 ||
        write_section_packet(muxer, PAT_PID, section.data, section.size) < 0) {
        free(section.data);
        return -1;
    }
    section.size = 0;

    int stream_count = (muxer->video_codec != TS_VIDEO_NONE) + muxer->has_audio;
    int section_length = 13 + stream_count * 5;
    int pcr_pid = muxer->video_codec != TS_VIDEO_NONE ? VIDEO_PID : AUDIO_PID;
    uint8_t pmt_header[] = {
        0x02, (uint8_t)(0xb0 | (section_length >> 8)), (uint8_t)section_length,
        0x00, 0x01, 0xc1, 0x00, 0x00,
        (uint8_t)(0xe0 | (pcr_pid >> 8)), (uint8_t)pcr_pid,
        0xf0, 0x00,
    };
    if (append(&section, pmt_header, sizeof(pmt_header)) < 0)
        goto fail;
    if (muxer->video_codec != TS_VIDEO_NONE) {
        uint8_t stream[] = {
            muxer->video_codec == TS_VIDEO_H265 ? 0x24 : 0x1b,
            0xe0 | (VIDEO_PID >> 8), VIDEO_PID & 0xff, 0xf0, 0x00,
        };
        if (append(&section, stream, sizeof(stream)) < 0)
            goto fail;
    }
    if (muxer->has_audio) {
        uint8_t stream[] = {
            0x0f, 0xe0 | (AUDIO_PID >> 8), AUDIO_PID & 0xff, 0xf0, 0x00,
        };
        if (append(&section, stream, sizeof(stream)) < 0)
            goto fail;
    }
    if (append_crc(&section) < 0 ||
        write_section_packet(muxer, PMT_PID, section.data, section.size) < 0)
        goto fail;
    free(section.data);
    return 0;

fail:
    free(section.data);
    return -1;
}

static int64_t pts90_from_us(int64_t pts_us) {
    int64_t quotient = pts_us / 1000000;
    int64_t remainder = pts_us % 1000000;
    return quotient * 90000 + remainder * 90000 / 1000000;
}

static void write_pts(uint8_t *target, int64_t pts) {
    uint64_t value = (uint64_t)pts & ((1ULL << 33) - 1);
    target[0] = (uint8_t)(0x20 | (((value >> 30) & 7) << 1) | 1);
    target[1] = (uint8_t)(value >> 22);
    target[2] = (uint8_t)((((value >> 15) & 0x7f) << 1) | 1);
    target[3] = (uint8_t)(value >> 7);
    target[4] = (uint8_t)(((value & 0x7f) << 1) | 1);
}

static void write_pcr(uint8_t *target, int64_t pts90) {
    uint64_t base = (uint64_t)pts90 & ((1ULL << 33) - 1);
    target[0] = (uint8_t)(base >> 25);
    target[1] = (uint8_t)(base >> 17);
    target[2] = (uint8_t)(base >> 9);
    target[3] = (uint8_t)(base >> 1);
    target[4] = (uint8_t)(((base & 1) << 7) | 0x7e);
    target[5] = 0x00;
}

static int packetize_pes(TsMuxer *muxer, int pid, int stream_id,
                         const uint8_t *payload, size_t payload_size,
                         int64_t pts90, int key_frame, int include_pcr) {
    ByteBuffer *pes = &muxer->pes;
    pes->size = 0;
    if (reserve(pes, payload_size + 14) < 0)
        return -1;
    uint32_t pes_length = stream_id == 0xe0 || payload_size + 8 > 0xffff
        ? 0 : (uint32_t)payload_size + 8;
    uint8_t header[14] = {
        0x00, 0x00, 0x01, (uint8_t)stream_id,
        (uint8_t)(pes_length >> 8), (uint8_t)pes_length,
        0x80, 0x80, 0x05, 0, 0, 0, 0, 0,
    };
    write_pts(header + 9, pts90);
    if (append(pes, header, sizeof(header)) < 0 ||
        append(pes, payload, payload_size) < 0)
        return -1;

    size_t offset = 0;
    int first = 1;
    int index = pid_index(pid);
    while (offset < pes->size) {
        uint8_t packet[TS_PACKET_SIZE];
        memset(packet, 0xff, sizeof(packet));
        int adaptation_flags = 0;
        int minimum_adaptation = 0;
        if (first && include_pcr) {
            adaptation_flags |= 0x10;
            minimum_adaptation = 8;
        }
        if (first && key_frame) {
            adaptation_flags |= 0x40;
            if (minimum_adaptation == 0)
                minimum_adaptation = 2;
        }
        size_t remaining = pes->size - offset;
        size_t capacity = 184U - (size_t)minimum_adaptation;
        size_t take = remaining < capacity ? remaining : capacity;
        int adaptation_size = minimum_adaptation;
        if (take < capacity)
            adaptation_size = 184 - (int)take;

        packet[0] = 0x47;
        packet[1] = (uint8_t)(pid >> 8) | (first ? 0x40 : 0);
        packet[2] = (uint8_t)pid;
        packet[3] = (adaptation_size ? 0x30 : 0x10) | muxer->continuity[index];
        muxer->continuity[index] = (muxer->continuity[index] + 1) & 0x0f;
        int payload_offset = 4;
        if (adaptation_size) {
            packet[4] = (uint8_t)(adaptation_size - 1);
            payload_offset += adaptation_size;
            if (adaptation_size >= 2) {
                packet[5] = (uint8_t)adaptation_flags;
                int cursor = 6;
                if (adaptation_flags & 0x10) {
                    write_pcr(packet + cursor, pts90);
                    cursor += 6;
                }
                while (cursor < payload_offset)
                    packet[cursor++] = 0xff;
            }
        }
        memcpy(packet + payload_offset, pes->data + offset, take);
        if (append(&muxer->output, packet, sizeof(packet)) < 0)
            return -1;
        offset += take;
        first = 0;
    }
    return 0;
}

static int has_start_code(const uint8_t *data, size_t size) {
    return size >= 3 && data[0] == 0 && data[1] == 0 &&
           (data[2] == 1 || (size >= 4 && data[2] == 0 && data[3] == 1));
}

static int append_annex_b_nal(ByteBuffer *buffer, const uint8_t *data, size_t size) {
    static const uint8_t start_code[] = {0, 0, 0, 1};
    return append(buffer, start_code, sizeof(start_code)) < 0 ||
           append(buffer, data, size) < 0 ? -1 : 0;
}

static int parse_avcc(TsMuxer *muxer, const uint8_t *data, size_t size) {
    if (size < 7 || data[0] != 1)
        return -1;
    muxer->nal_length_size = (data[4] & 3) + 1;
    size_t offset = 6;
    int sps_count = data[5] & 0x1f;
    for (int group = 0; group < 2; ++group) {
        int count = group == 0 ? sps_count : (offset < size ? data[offset++] : -1);
        if (count < 0) return -1;
        for (int i = 0; i < count; ++i) {
            if (offset + 2 > size) return -1;
            size_t nal_size = ((size_t)data[offset] << 8) | data[offset + 1];
            offset += 2;
            if (offset + nal_size > size ||
                append_annex_b_nal(&muxer->video_config, data + offset, nal_size) < 0)
                return -1;
            offset += nal_size;
        }
    }
    return 0;
}

static int parse_hvcc(TsMuxer *muxer, const uint8_t *data, size_t size) {
    if (size < 23 || data[0] != 1)
        return -1;
    muxer->nal_length_size = (data[21] & 3) + 1;
    size_t offset = 23;
    int arrays = data[22];
    for (int array = 0; array < arrays; ++array) {
        if (offset + 3 > size) return -1;
        offset++;
        int count = ((int)data[offset] << 8) | data[offset + 1];
        offset += 2;
        for (int i = 0; i < count; ++i) {
            if (offset + 2 > size) return -1;
            size_t nal_size = ((size_t)data[offset] << 8) | data[offset + 1];
            offset += 2;
            if (offset + nal_size > size ||
                append_annex_b_nal(&muxer->video_config, data + offset, nal_size) < 0)
                return -1;
            offset += nal_size;
        }
    }
    return 0;
}

static int append_csd(TsMuxer *muxer, const uint8_t *data, size_t size) {
    if (!data || !size)
        return 0;
    if (has_start_code(data, size))
        return append(&muxer->video_config, data, size);
    if (muxer->video_codec == TS_VIDEO_H264 && data[0] == 1)
        return parse_avcc(muxer, data, size);
    if (muxer->video_codec == TS_VIDEO_H265 && data[0] == 1)
        return parse_hvcc(muxer, data, size);
    return append_annex_b_nal(&muxer->video_config, data, size);
}

static int convert_sample_to_annex_b(TsMuxer *muxer,
                                     const uint8_t *data, size_t size) {
    ByteBuffer *elementary = &muxer->elementary;
    if (has_start_code(data, size))
        return append(elementary, data, size);
    int length_size = muxer->nal_length_size;
    if (length_size < 1 || length_size > 4)
        length_size = 4;
    size_t offset = 0;
    while (offset + (size_t)length_size <= size) {
        size_t nal_size = 0;
        for (int i = 0; i < length_size; ++i)
            nal_size = (nal_size << 8) | data[offset + (size_t)i];
        offset += (size_t)length_size;
        if (!nal_size || offset + nal_size > size)
            return -1;
        if (append_annex_b_nal(elementary, data + offset, nal_size) < 0)
            return -1;
        offset += nal_size;
    }
    return offset == size ? 0 : -1;
}

TsMuxer *ts_muxer_create(int video_codec, int has_audio, int sample_rate,
                         int channels, int aac_object_type) {
    if (video_codec < TS_VIDEO_NONE || video_codec > TS_VIDEO_H265 ||
        (!video_codec && !has_audio) || sample_rate < 0 || channels < 0)
        return NULL;
    TsMuxer *muxer = (TsMuxer *)calloc(1, sizeof(*muxer));
    if (!muxer) return NULL;
    muxer->video_codec = video_codec;
    muxer->has_audio = !!has_audio;
    muxer->sample_rate = sample_rate;
    muxer->channels = channels;
    muxer->aac_object_type = aac_object_type > 0 ? aac_object_type : 2;
    muxer->nal_length_size = 4;
    muxer->last_tables_pts = INT64_MIN;
    return muxer;
}

void ts_muxer_destroy(TsMuxer *muxer) {
    if (!muxer) return;
    free(muxer->output.data);
    free(muxer->video_config.data);
    free(muxer->elementary.data);
    free(muxer->pes.data);
    free(muxer);
}

int ts_muxer_set_video_config(TsMuxer *muxer,
                              const uint8_t *csd0, size_t csd0_size,
                              const uint8_t *csd1, size_t csd1_size,
                              const uint8_t *csd2, size_t csd2_size) {
    if (!muxer || muxer->video_codec == TS_VIDEO_NONE)
        return -1;
    muxer->video_config.size = 0;
    if (append_csd(muxer, csd0, csd0_size) < 0 ||
        append_csd(muxer, csd1, csd1_size) < 0 ||
        append_csd(muxer, csd2, csd2_size) < 0)
        return -1;
    return 0;
}

static int begin_access_unit(TsMuxer *muxer, int64_t pts90, int force_tables) {
    muxer->output.size = 0;
    if (!muxer->tables_written || force_tables ||
        pts90 - muxer->last_tables_pts >= TABLE_PERIOD_90K ||
        pts90 < muxer->last_tables_pts) {
        if (write_tables(muxer) < 0)
            return -1;
        muxer->tables_written = 1;
        muxer->last_tables_pts = pts90;
    }
    return 0;
}

int ts_muxer_write_video(TsMuxer *muxer, const uint8_t *data, size_t size,
                         int64_t pts_us, int key_frame,
                         const uint8_t **output, size_t *output_size) {
    if (!muxer || muxer->video_codec == TS_VIDEO_NONE || !data || !size ||
        !output || !output_size)
        return -1;
    int64_t pts90 = pts90_from_us(pts_us);
    if (begin_access_unit(muxer, pts90, key_frame) < 0)
        return -1;
    muxer->elementary.size = 0;
    if (key_frame && append(&muxer->elementary, muxer->video_config.data,
                            muxer->video_config.size) < 0)
        return -1;
    if (convert_sample_to_annex_b(muxer, data, size) < 0)
        return -1;
    if (packetize_pes(muxer, VIDEO_PID, 0xe0, muxer->elementary.data,
                      muxer->elementary.size, pts90, key_frame, 1) < 0)
        return -1;
    *output = muxer->output.data;
    *output_size = muxer->output.size;
    return 0;
}

static int aac_frequency_index(int sample_rate) {
    static const int rates[] = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350,
    };
    for (int i = 0; i < (int)(sizeof(rates) / sizeof(rates[0])); ++i)
        if (rates[i] == sample_rate) return i;
    return -1;
}

int ts_muxer_write_audio(TsMuxer *muxer, const uint8_t *data, size_t size,
                         int64_t pts_us, const uint8_t **output,
                         size_t *output_size) {
    if (!muxer || !muxer->has_audio || !data || !size || !output || !output_size ||
        size > 0x1fff - 7)
        return -1;
    int frequency_index = aac_frequency_index(muxer->sample_rate);
    if (frequency_index < 0 || muxer->channels < 1 || muxer->channels > 7)
        return -1;
    int64_t pts90 = pts90_from_us(pts_us);
    if (begin_access_unit(muxer, pts90, 0) < 0)
        return -1;
    muxer->elementary.size = 0;
    size_t frame_length = size + 7;
    int profile = muxer->aac_object_type - 1;
    uint8_t adts[7] = {
        0xff, 0xf1,
        (uint8_t)((profile << 6) | (frequency_index << 2) | (muxer->channels >> 2)),
        (uint8_t)(((muxer->channels & 3) << 6) | (frame_length >> 11)),
        (uint8_t)(frame_length >> 3),
        (uint8_t)(((frame_length & 7) << 5) | 0x1f),
        0xfc,
    };
    if (append(&muxer->elementary, adts, sizeof(adts)) < 0 ||
        append(&muxer->elementary, data, size) < 0)
        return -1;
    int include_pcr = muxer->video_codec == TS_VIDEO_NONE;
    if (packetize_pes(muxer, AUDIO_PID, 0xc0, muxer->elementary.data,
                      muxer->elementary.size, pts90, 0, include_pcr) < 0)
        return -1;
    *output = muxer->output.data;
    *output_size = muxer->output.size;
    return 0;
}
