#include "h26x_vui_rewriter.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    const uint8_t *data;
    size_t size;
    size_t bit;
    int error;
} BitReader;

typedef struct {
    uint8_t *data;
    size_t size;
    size_t capacity;
} Buffer;

typedef struct {
    size_t vui_flag;
    size_t vui_start;
    size_t color_start;
    size_t color_end;
    int vui_present;
    int video_format;
    H26xVuiColor color;
} VuiLocation;

static uint32_t read_bits(BitReader *reader, unsigned int count) {
    if (count > 32 || reader->bit > reader->size * 8 ||
        count > reader->size * 8 - reader->bit) {
        reader->error = 1;
        return 0;
    }
    uint32_t value = 0;
    for (unsigned int i = 0; i < count; ++i) {
        value = (value << 1) |
                ((reader->data[reader->bit / 8] >> (7 - reader->bit % 8)) & 1U);
        reader->bit++;
    }
    return value;
}

static uint32_t read_ue(BitReader *reader) {
    unsigned int zeros = 0;
    while (!reader->error && read_bits(reader, 1) == 0) {
        if (++zeros >= 32) {
            reader->error = 1;
            return 0;
        }
    }
    if (reader->error) return 0;
    uint32_t suffix = zeros ? read_bits(reader, zeros) : 0;
    return ((UINT32_C(1) << zeros) - 1U) + suffix;
}

static int32_t read_se(BitReader *reader) {
    uint32_t value = read_ue(reader);
    return (value & 1U) ? (int32_t)((value + 1U) / 2U) : -(int32_t)(value / 2U);
}

static int reserve(Buffer *buffer, size_t extra) {
    if (extra > SIZE_MAX - buffer->size) return -1;
    size_t needed = buffer->size + extra;
    if (needed <= buffer->capacity) return 0;
    size_t capacity = buffer->capacity ? buffer->capacity : 64;
    while (capacity < needed) {
        if (capacity > SIZE_MAX / 2) {
            capacity = needed;
            break;
        }
        capacity *= 2;
    }
    uint8_t *data = (uint8_t *)realloc(buffer->data, capacity);
    if (!data) return -1;
    buffer->data = data;
    buffer->capacity = capacity;
    return 0;
}

static int append(Buffer *buffer, const void *data, size_t size) {
    if (reserve(buffer, size) < 0) return -1;
    if (size) memcpy(buffer->data + buffer->size, data, size);
    buffer->size += size;
    return 0;
}

static int append_byte(Buffer *buffer, uint8_t value) {
    return append(buffer, &value, 1);
}

static int write_bit(Buffer *buffer, size_t *bit_size, unsigned int value) {
    if (*bit_size % 8 == 0 && append_byte(buffer, 0) < 0) return -1;
    if (value) buffer->data[*bit_size / 8] |= (uint8_t)(1U << (7 - *bit_size % 8));
    (*bit_size)++;
    return 0;
}

static int write_bits(Buffer *buffer, size_t *bit_size, uint32_t value,
                      unsigned int count) {
    for (unsigned int i = 0; i < count; ++i) {
        unsigned int shift = count - i - 1;
        if (write_bit(buffer, bit_size, (value >> shift) & 1U) < 0) return -1;
    }
    return 0;
}

static int copy_bits(Buffer *buffer, size_t *bit_size, const uint8_t *source,
                     size_t first, size_t end) {
    for (size_t bit = first; bit < end; ++bit) {
        unsigned int value = (source[bit / 8] >> (7 - bit % 8)) & 1U;
        if (write_bit(buffer, bit_size, value) < 0) return -1;
    }
    return 0;
}

static int skip_h264_scaling_list(BitReader *reader, int count) {
    int last = 8;
    int next = 8;
    for (int i = 0; i < count; ++i) {
        if (next != 0) {
            int delta = read_se(reader);
            next = (last + delta + 256) & 255;
        }
        if (next != 0) last = next;
    }
    return reader->error ? -1 : 0;
}

static int h264_extended_profile(unsigned int profile) {
    static const uint8_t profiles[] = {
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135
    };
    for (size_t i = 0; i < sizeof(profiles); ++i)
        if (profile == profiles[i]) return 1;
    return 0;
}

static int parse_vui_prefix(BitReader *reader, VuiLocation *location) {
    location->vui_start = reader->bit;
    if (read_bits(reader, 1)) {
        unsigned int aspect = read_bits(reader, 8);
        if (aspect == 255) (void)read_bits(reader, 32);
    }
    if (read_bits(reader, 1)) (void)read_bits(reader, 1);
    location->color_start = reader->bit;
    int signal_present = (int)read_bits(reader, 1);
    location->video_format = 5;
    location->color.full_range = 0;
    location->color.colour_primaries = 2;
    location->color.transfer_characteristics = 2;
    location->color.matrix_coefficients = 2;
    if (signal_present) {
        location->video_format = (int)read_bits(reader, 3);
        location->color.full_range = (int)read_bits(reader, 1);
        if (read_bits(reader, 1)) {
            location->color.colour_primaries = (int)read_bits(reader, 8);
            location->color.transfer_characteristics = (int)read_bits(reader, 8);
            location->color.matrix_coefficients = (int)read_bits(reader, 8);
        }
    }
    location->color_end = reader->bit;
    return reader->error ? -1 : 0;
}

static int locate_h264_vui(const uint8_t *rbsp, size_t size,
                           VuiLocation *location) {
    BitReader reader = {rbsp, size, 0, 0};
    unsigned int profile = read_bits(&reader, 8);
    (void)read_bits(&reader, 16);
    (void)read_ue(&reader);
    if (h264_extended_profile(profile)) {
        unsigned int chroma_format = read_ue(&reader);
        if (chroma_format == 3) (void)read_bits(&reader, 1);
        (void)read_ue(&reader);
        (void)read_ue(&reader);
        (void)read_bits(&reader, 1);
        if (read_bits(&reader, 1)) {
            int lists = chroma_format == 3 ? 12 : 8;
            for (int i = 0; i < lists; ++i) {
                if (read_bits(&reader, 1) &&
                    skip_h264_scaling_list(&reader, i < 6 ? 16 : 64) < 0)
                    return -1;
            }
        }
    }
    (void)read_ue(&reader);
    unsigned int poc_type = read_ue(&reader);
    if (poc_type == 0) {
        (void)read_ue(&reader);
    } else if (poc_type == 1) {
        (void)read_bits(&reader, 1);
        (void)read_se(&reader);
        (void)read_se(&reader);
        unsigned int count = read_ue(&reader);
        if (count > 256) return -1;
        for (unsigned int i = 0; i < count; ++i) (void)read_se(&reader);
    } else if (poc_type > 2) {
        return -1;
    }
    (void)read_ue(&reader);
    (void)read_bits(&reader, 1);
    (void)read_ue(&reader);
    (void)read_ue(&reader);
    int frame_mbs_only = (int)read_bits(&reader, 1);
    if (!frame_mbs_only) (void)read_bits(&reader, 1);
    (void)read_bits(&reader, 1);
    if (read_bits(&reader, 1)) {
        for (int i = 0; i < 4; ++i) (void)read_ue(&reader);
    }
    if (reader.error) return -1;
    location->vui_flag = reader.bit;
    location->vui_present = (int)read_bits(&reader, 1);
    if (!location->vui_present) {
        location->vui_start = reader.bit;
        location->color_start = reader.bit;
        location->color_end = reader.bit;
        location->video_format = 5;
        location->color = (H26xVuiColor){0, 2, 2, 2};
        return reader.error ? -1 : 0;
    }
    return parse_vui_prefix(&reader, location);
}

static int skip_profile_tier_level(BitReader *reader, unsigned int max_sub_layers) {
    (void)read_bits(reader, 32);
    (void)read_bits(reader, 32);
    (void)read_bits(reader, 24);
    (void)read_bits(reader, 8);
    unsigned int profile_present[8] = {0};
    unsigned int level_present[8] = {0};
    for (unsigned int i = 0; i < max_sub_layers; ++i) {
        profile_present[i] = read_bits(reader, 1);
        level_present[i] = read_bits(reader, 1);
    }
    if (max_sub_layers > 0) {
        for (unsigned int i = max_sub_layers; i < 8; ++i)
            (void)read_bits(reader, 2);
    }
    for (unsigned int i = 0; i < max_sub_layers; ++i) {
        if (profile_present[i]) {
            (void)read_bits(reader, 32);
            (void)read_bits(reader, 32);
            (void)read_bits(reader, 24);
        }
        if (level_present[i]) (void)read_bits(reader, 8);
    }
    return reader->error ? -1 : 0;
}

static int skip_h265_scaling_list(BitReader *reader) {
    for (int size_id = 0; size_id < 4; ++size_id) {
        int step = size_id == 3 ? 3 : 1;
        for (int matrix_id = 0; matrix_id < 6; matrix_id += step) {
            if (!read_bits(reader, 1)) {
                (void)read_ue(reader);
            } else {
                int coefficients = 1 << (4 + (size_id << 1));
                if (coefficients > 64) coefficients = 64;
                if (size_id > 1) (void)read_se(reader);
                for (int i = 0; i < coefficients; ++i) (void)read_se(reader);
            }
        }
    }
    return reader->error ? -1 : 0;
}

static int skip_h265_short_term_sets(BitReader *reader, unsigned int count) {
    if (count > 64) return -1;
    unsigned int delta_pocs[64] = {0};
    for (unsigned int set = 0; set < count; ++set) {
        int predicted = set > 0 ? (int)read_bits(reader, 1) : 0;
        if (predicted) {
            (void)read_bits(reader, 1);
            (void)read_ue(reader);
            unsigned int reference_count = delta_pocs[set - 1];
            unsigned int current_count = 0;
            for (unsigned int i = 0; i <= reference_count; ++i) {
                int used = (int)read_bits(reader, 1);
                int use_delta = used ? 1 : (int)read_bits(reader, 1);
                if (use_delta) current_count++;
            }
            delta_pocs[set] = current_count;
        } else {
            unsigned int negative = read_ue(reader);
            unsigned int positive = read_ue(reader);
            if (negative > 16 || positive > 16 || negative + positive > 16)
                return -1;
            delta_pocs[set] = negative + positive;
            for (unsigned int i = 0; i < negative + positive; ++i) {
                (void)read_ue(reader);
                (void)read_bits(reader, 1);
            }
        }
        if (reader->error) return -1;
    }
    return 0;
}

static int locate_h265_vui(const uint8_t *rbsp, size_t size,
                           VuiLocation *location) {
    BitReader reader = {rbsp, size, 0, 0};
    (void)read_bits(&reader, 4);
    unsigned int max_sub_layers = read_bits(&reader, 3);
    (void)read_bits(&reader, 1);
    if (skip_profile_tier_level(&reader, max_sub_layers) < 0) return -1;
    (void)read_ue(&reader);
    unsigned int chroma_format = read_ue(&reader);
    if (chroma_format > 3) return -1;
    if (chroma_format == 3) (void)read_bits(&reader, 1);
    (void)read_ue(&reader);
    (void)read_ue(&reader);
    if (read_bits(&reader, 1)) {
        for (int i = 0; i < 4; ++i) (void)read_ue(&reader);
    }
    (void)read_ue(&reader);
    (void)read_ue(&reader);
    unsigned int poc_bits_minus4 = read_ue(&reader);
    if (poc_bits_minus4 > 12) return -1;
    int ordering_all = (int)read_bits(&reader, 1);
    unsigned int ordering_count = ordering_all ? max_sub_layers + 1 : 1;
    for (unsigned int i = 0; i < ordering_count; ++i) {
        (void)read_ue(&reader);
        (void)read_ue(&reader);
        (void)read_ue(&reader);
    }
    for (int i = 0; i < 6; ++i) (void)read_ue(&reader);
    if (read_bits(&reader, 1) && read_bits(&reader, 1) &&
        skip_h265_scaling_list(&reader) < 0)
        return -1;
    (void)read_bits(&reader, 1);
    (void)read_bits(&reader, 1);
    if (read_bits(&reader, 1)) {
        (void)read_bits(&reader, 8);
        (void)read_ue(&reader);
        (void)read_ue(&reader);
        (void)read_bits(&reader, 1);
    }
    unsigned int short_term_sets = read_ue(&reader);
    if (skip_h265_short_term_sets(&reader, short_term_sets) < 0) return -1;
    if (read_bits(&reader, 1)) {
        unsigned int long_term = read_ue(&reader);
        if (long_term > 32) return -1;
        for (unsigned int i = 0; i < long_term; ++i) {
            (void)read_bits(&reader, poc_bits_minus4 + 4);
            (void)read_bits(&reader, 1);
        }
    }
    (void)read_bits(&reader, 1);
    (void)read_bits(&reader, 1);
    if (reader.error) return -1;
    location->vui_flag = reader.bit;
    location->vui_present = (int)read_bits(&reader, 1);
    if (!location->vui_present) {
        location->vui_start = reader.bit;
        location->color_start = reader.bit;
        location->color_end = reader.bit;
        location->video_format = 5;
        location->color = (H26xVuiColor){0, 2, 2, 2};
        return reader.error ? -1 : 0;
    }
    return parse_vui_prefix(&reader, location);
}

static int unescape_rbsp(const uint8_t *data, size_t size, Buffer *rbsp) {
    int zero_count = 0;
    for (size_t i = 0; i < size; ++i) {
        if (zero_count >= 2 && data[i] == 3 && i + 1 < size && data[i + 1] <= 3) {
            zero_count = 0;
            continue;
        }
        if (append_byte(rbsp, data[i]) < 0) return -1;
        zero_count = data[i] == 0 ? zero_count + 1 : 0;
    }
    return 0;
}

static int escape_rbsp(const uint8_t *data, size_t size, Buffer *output) {
    int zero_count = 0;
    for (size_t i = 0; i < size; ++i) {
        if (zero_count >= 2 && data[i] <= 3) {
            if (append_byte(output, 3) < 0) return -1;
            zero_count = 0;
        }
        if (append_byte(output, data[i]) < 0) return -1;
        zero_count = data[i] == 0 ? zero_count + 1 : 0;
    }
    return 0;
}

static int nal_type(const uint8_t *nal, size_t size, int codec) {
    if (!size) return -1;
    if (codec == H26X_CODEC_H264) return nal[0] & 0x1f;
    if (codec == H26X_CODEC_H265 && size >= 2) return (nal[0] >> 1) & 0x3f;
    return -1;
}

static int rbsp_payload_end(const uint8_t *rbsp, size_t size, size_t *end);

static int rewrite_sps_nal(const uint8_t *nal, size_t nal_size, int codec,
                           const H26xVuiColor *requested, Buffer *output) {
    int header_size = codec == H26X_CODEC_H264 ? 1 : 2;
    int sps_type = codec == H26X_CODEC_H264 ? 7 : 33;
    if (nal_type(nal, nal_size, codec) != sps_type)
        return append(output, nal, nal_size);
    if (nal_size <= (size_t)header_size) return -1;

    Buffer rbsp = {0};
    Buffer rewritten = {0};
    size_t rewritten_bits = 0;
    size_t payload_end = 0;
    VuiLocation location;
    int result = -1;
    if (unescape_rbsp(nal + header_size, nal_size - (size_t)header_size, &rbsp) < 0)
        goto done;
    if ((codec == H26X_CODEC_H264
             ? locate_h264_vui(rbsp.data, rbsp.size, &location)
             : locate_h265_vui(rbsp.data, rbsp.size, &location)) < 0)
        goto done;
    if (rbsp_payload_end(rbsp.data, rbsp.size, &payload_end) < 0 ||
        payload_end < location.color_end)
        goto done;
    H26xVuiColor color = location.color;
    if (requested->full_range >= 0) color.full_range = requested->full_range;
    if (requested->colour_primaries >= 0)
        color.colour_primaries = requested->colour_primaries;
    if (requested->transfer_characteristics >= 0)
        color.transfer_characteristics = requested->transfer_characteristics;
    if (requested->matrix_coefficients >= 0)
        color.matrix_coefficients = requested->matrix_coefficients;

    if (copy_bits(&rewritten, &rewritten_bits, rbsp.data, 0, location.vui_flag) < 0 ||
        write_bit(&rewritten, &rewritten_bits, 1) < 0 ||
        copy_bits(&rewritten, &rewritten_bits, rbsp.data, location.vui_start,
                  location.color_start) < 0)
        goto done;
    if (!location.vui_present &&
        write_bits(&rewritten, &rewritten_bits, 0, 2) < 0)
        goto done;
    if (
        write_bit(&rewritten, &rewritten_bits, 1) < 0 ||
        write_bits(&rewritten, &rewritten_bits, (uint32_t)location.video_format, 3) < 0 ||
        write_bit(&rewritten, &rewritten_bits, (unsigned int)color.full_range) < 0 ||
        write_bit(&rewritten, &rewritten_bits, 1) < 0 ||
        write_bits(&rewritten, &rewritten_bits, (uint32_t)color.colour_primaries, 8) < 0 ||
        write_bits(&rewritten, &rewritten_bits, (uint32_t)color.transfer_characteristics, 8) < 0 ||
        write_bits(&rewritten, &rewritten_bits, (uint32_t)color.matrix_coefficients, 8) < 0)
        goto done;
    if (!location.vui_present) {
        int default_suffix_bits = codec == H26X_CODEC_H264 ? 6 : 7;
        if (write_bits(&rewritten, &rewritten_bits, 0, (unsigned int)default_suffix_bits) < 0)
            goto done;
    }
    if (copy_bits(&rewritten, &rewritten_bits, rbsp.data, location.color_end,
                  payload_end) < 0 ||
        write_bit(&rewritten, &rewritten_bits, 1) < 0)
        goto done;
    while (rewritten_bits % 8 != 0) {
        if (write_bit(&rewritten, &rewritten_bits, 0) < 0) goto done;
    }
    if (
        rewritten_bits % 8 != 0 ||
        append(output, nal, (size_t)header_size) < 0 ||
        escape_rbsp(rewritten.data, rewritten.size, output) < 0)
        goto done;
    result = 0;
done:
    free(rbsp.data);
    free(rewritten.data);
    return result;
}

static size_t find_start_code(const uint8_t *data, size_t size, size_t offset,
                              size_t *code_size) {
    for (size_t i = offset; i + 3 <= size; ++i) {
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            *code_size = 3;
            return i;
        }
        if (i + 4 <= size && data[i] == 0 && data[i + 1] == 0 &&
            data[i + 2] == 0 && data[i + 3] == 1) {
            *code_size = 4;
            return i;
        }
    }
    return size;
}

static int rewrite_annex_b(const uint8_t *data, size_t size, int codec,
                           const H26xVuiColor *color, Buffer *output) {
    size_t code_size = 0;
    size_t start = find_start_code(data, size, 0, &code_size);
    if (start == size) return -1;
    if (append(output, data, start) < 0) return -1;
    while (start < size) {
        size_t nal_start = start + code_size;
        size_t next_code_size = 0;
        size_t next = find_start_code(data, size, nal_start, &next_code_size);
        if (append(output, data + start, code_size) < 0 ||
            rewrite_sps_nal(data + nal_start, next - nal_start, codec, color, output) < 0)
            return -1;
        start = next;
        code_size = next_code_size;
    }
    return 0;
}

static int append_length(Buffer *output, size_t value, int length_size) {
    size_t maximum = length_size == 4 ? UINT32_MAX :
                     ((size_t)1 << (length_size * 8)) - 1;
    if (value > maximum) return -1;
    for (int i = length_size - 1; i >= 0; --i) {
        if (append_byte(output, (uint8_t)(value >> (i * 8))) < 0) return -1;
    }
    return 0;
}

static int rewrite_length_prefixed(const uint8_t *data, size_t size, int codec,
                                   int length_size, const H26xVuiColor *color,
                                   Buffer *output) {
    if (length_size < 1 || length_size > 4) return -1;
    size_t offset = 0;
    while (offset < size) {
        if (size - offset < (size_t)length_size) return -1;
        size_t nal_size = 0;
        for (int i = 0; i < length_size; ++i)
            nal_size = (nal_size << 8) | data[offset++];
        if (!nal_size || nal_size > size - offset) return -1;
        Buffer nal = {0};
        if (rewrite_sps_nal(data + offset, nal_size, codec, color, &nal) < 0 ||
            append_length(output, nal.size, length_size) < 0 ||
            append(output, nal.data, nal.size) < 0) {
            free(nal.data);
            return -1;
        }
        free(nal.data);
        offset += nal_size;
    }
    return 0;
}

static int is_length_prefixed(const uint8_t *data, size_t size, int length_size) {
    if (length_size < 1 || length_size > 4) return 0;
    size_t offset = 0;
    while (offset < size) {
        if (size - offset < (size_t)length_size) return 0;
        size_t nal_size = 0;
        for (int i = 0; i < length_size; ++i)
            nal_size = (nal_size << 8) | data[offset++];
        if (!nal_size || nal_size > size - offset) return 0;
        offset += nal_size;
    }
    return offset == size;
}

static int rewrite_avcc(const uint8_t *data, size_t size,
                        const H26xVuiColor *color, Buffer *output) {
    if (size < 7 || data[0] != 1 || append(output, data, 6) < 0) return -1;
    size_t offset = 6;
    int groups[2] = {data[5] & 0x1f, -1};
    for (int group = 0; group < 2; ++group) {
        if (group == 1) {
            if (offset >= size) return -1;
            groups[1] = data[offset];
            if (append_byte(output, data[offset++]) < 0) return -1;
        }
        for (int i = 0; i < groups[group]; ++i) {
            if (offset + 2 > size) return -1;
            size_t nal_size = ((size_t)data[offset] << 8) | data[offset + 1];
            offset += 2;
            if (!nal_size || nal_size > size - offset) return -1;
            Buffer nal = {0};
            int status = group == 0
                ? rewrite_sps_nal(data + offset, nal_size, H26X_CODEC_H264, color, &nal)
                : append(&nal, data + offset, nal_size);
            if (status < 0 || nal.size > UINT16_MAX ||
                append_length(output, nal.size, 2) < 0 ||
                append(output, nal.data, nal.size) < 0) {
                free(nal.data);
                return -1;
            }
            free(nal.data);
            offset += nal_size;
        }
    }
    return append(output, data + offset, size - offset);
}

static int rewrite_hvcc(const uint8_t *data, size_t size,
                        const H26xVuiColor *color, Buffer *output) {
    if (size < 23 || data[0] != 1 || append(output, data, 23) < 0) return -1;
    size_t offset = 23;
    int arrays = data[22];
    for (int array = 0; array < arrays; ++array) {
        if (offset + 3 > size || append(output, data + offset, 3) < 0) return -1;
        int type = data[offset] & 0x3f;
        int count = ((int)data[offset + 1] << 8) | data[offset + 2];
        offset += 3;
        for (int i = 0; i < count; ++i) {
            if (offset + 2 > size) return -1;
            size_t nal_size = ((size_t)data[offset] << 8) | data[offset + 1];
            offset += 2;
            if (!nal_size || nal_size > size - offset) return -1;
            Buffer nal = {0};
            int status = type == 33
                ? rewrite_sps_nal(data + offset, nal_size, H26X_CODEC_H265, color, &nal)
                : append(&nal, data + offset, nal_size);
            if (status < 0 || nal.size > UINT16_MAX ||
                append_length(output, nal.size, 2) < 0 ||
                append(output, nal.data, nal.size) < 0) {
                free(nal.data);
                return -1;
            }
            free(nal.data);
            offset += nal_size;
        }
    }
    return append(output, data + offset, size - offset);
}

static int valid_color(const H26xVuiColor *color) {
    return color && color->full_range >= -1 && color->full_range <= 1 &&
           color->colour_primaries >= -1 && color->colour_primaries <= 255 &&
           color->transfer_characteristics >= -1 &&
           color->transfer_characteristics <= 255 &&
           color->matrix_coefficients >= -1 && color->matrix_coefficients <= 255;
}

static int rbsp_payload_end(const uint8_t *rbsp, size_t size, size_t *end) {
    for (size_t byte = size; byte > 0; --byte) {
        uint8_t value = rbsp[byte - 1];
        if (!value) continue;
        unsigned int trailing_zeros = 0;
        while ((value & 1U) == 0) {
            value >>= 1;
            trailing_zeros++;
        }
        *end = (byte - 1) * 8 + (7 - trailing_zeros);
        return 0;
    }
    return -1;
}

int h26x_rewrite_vui(const uint8_t *input, size_t input_size, int codec,
                     int nal_length_size, const H26xVuiColor *color,
                     uint8_t **output, size_t *output_size) {
    if (!input || !input_size || !output || !output_size || !valid_color(color) ||
        (codec != H26X_CODEC_H264 && codec != H26X_CODEC_H265))
        return -1;
    Buffer rewritten = {0};
    int result;
    size_t ignored = 0;
    /* Configuration records and complete length-prefixed samples can contain
     * 00 00 01 in a NAL length; do not mistake that for an Annex B delimiter. */
    if (is_length_prefixed(input, input_size, nal_length_size)) {
        result = rewrite_length_prefixed(input, input_size, codec,
                                         nal_length_size, color, &rewritten);
    } else if (codec == H26X_CODEC_H264 && input_size >= 7 && input[0] == 1 &&
        (input[4] & 0xfc) == 0xfc && (input[5] & 0xe0) == 0xe0) {
        result = rewrite_avcc(input, input_size, color, &rewritten);
    } else if (codec == H26X_CODEC_H265 && input_size >= 23 && input[0] == 1) {
        result = rewrite_hvcc(input, input_size, color, &rewritten);
    } else if (find_start_code(input, input_size, 0, &ignored) < input_size) {
        result = rewrite_annex_b(input, input_size, codec, color, &rewritten);
    } else {
        int type = nal_type(input, input_size, codec);
        int plausible = codec == H26X_CODEC_H264
            ? type > 0 && type < 24
            : type >= 0 && type < 48;
        result = plausible
            ? rewrite_sps_nal(input, input_size, codec, color, &rewritten)
            : -1;
    }
    if (result < 0) {
        free(rewritten.data);
        return -1;
    }
    *output = rewritten.data;
    *output_size = rewritten.size;
    return 0;
}

int h26x_read_vui(const uint8_t *nal, size_t nal_size, int codec,
                  H26xVuiColor *color) {
    int header_size = codec == H26X_CODEC_H264 ? 1 : 2;
    int expected_type = codec == H26X_CODEC_H264 ? 7 : 33;
    if (!nal || !color || nal_size <= (size_t)header_size ||
        nal_type(nal, nal_size, codec) != expected_type)
        return -1;
    Buffer rbsp = {0};
    VuiLocation location;
    int result = -1;
    if (unescape_rbsp(nal + header_size, nal_size - (size_t)header_size, &rbsp) == 0 &&
        (codec == H26X_CODEC_H264
             ? locate_h264_vui(rbsp.data, rbsp.size, &location)
             : locate_h265_vui(rbsp.data, rbsp.size, &location)) == 0 &&
        location.vui_present) {
        *color = location.color;
        result = 0;
    }
    free(rbsp.data);
    return result;
}
