#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "h26x_vui_rewriter.h"

typedef struct {
    uint8_t data[256];
    size_t bits;
} Bits;

static void bit(Bits *bits, unsigned int value) {
    if (value) bits->data[bits->bits / 8] |= 1U << (7 - bits->bits % 8);
    bits->bits++;
}

static void fixed(Bits *bits, uint32_t value, unsigned int count) {
    for (unsigned int i = 0; i < count; ++i)
        bit(bits, value >> (count - i - 1) & 1U);
}

static void ue(Bits *bits, uint32_t value) {
    uint32_t code = value + 1;
    unsigned int width = 0;
    for (uint32_t copy = code; copy; copy >>= 1) width++;
    for (unsigned int i = 1; i < width; ++i) bit(bits, 0);
    fixed(bits, code, width);
}

static void trailing(Bits *bits) {
    bit(bits, 1);
    while (bits->bits % 8) bit(bits, 0);
}

static size_t make_h264_sps(uint8_t *nal) {
    nal[0] = 0x67;
    Bits bits = {{0}, 0};
    fixed(&bits, 66, 8);
    fixed(&bits, 0, 8);
    fixed(&bits, 30, 8);
    ue(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    bit(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    bit(&bits, 1);
    bit(&bits, 1);
    bit(&bits, 0);
    bit(&bits, 0);
    trailing(&bits);
    size_t size = bits.bits / 8;
    for (size_t i = 0; i < size; ++i) nal[i + 1] = bits.data[i];
    return size + 1;
}

static size_t make_h265_sps(uint8_t *nal) {
    nal[0] = 0x42;
    nal[1] = 0x01;
    Bits bits = {{0}, 0};
    fixed(&bits, 0, 4);
    fixed(&bits, 0, 3);
    bit(&bits, 1);
    fixed(&bits, 0, 32);
    fixed(&bits, 0, 32);
    fixed(&bits, 0, 24);
    fixed(&bits, 30, 8);
    ue(&bits, 0);
    ue(&bits, 1);
    ue(&bits, 0);
    ue(&bits, 0);
    bit(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    bit(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    ue(&bits, 0);
    for (int i = 0; i < 6; ++i) ue(&bits, 0);
    bit(&bits, 0);
    bit(&bits, 0);
    bit(&bits, 0);
    bit(&bits, 0);
    ue(&bits, 0);
    bit(&bits, 0);
    bit(&bits, 0);
    bit(&bits, 0);
    bit(&bits, 0);
    bit(&bits, 0);
    trailing(&bits);
    size_t size = bits.bits / 8;
    for (size_t i = 0; i < size; ++i) nal[i + 2] = bits.data[i];
    return size + 2;
}

static void check_codec(int codec, const uint8_t *sps, size_t sps_size) {
    const H26xVuiColor request = {1, 9, 16, 9};
    uint8_t *rewritten = NULL;
    size_t rewritten_size = 0;
    assert(h26x_rewrite_vui(sps, sps_size, codec, 4, &request,
                            &rewritten, &rewritten_size) == 0);
    H26xVuiColor actual;
    assert(h26x_read_vui(rewritten, rewritten_size, codec, &actual) == 0);
    assert(actual.full_range == 1);
    assert(actual.colour_primaries == 9);
    assert(actual.transfer_characteristics == 16);
    assert(actual.matrix_coefficients == 9);

    const H26xVuiColor partial = {0, -1, 18, -1};
    uint8_t *twice = NULL;
    size_t twice_size = 0;
    assert(h26x_rewrite_vui(rewritten, rewritten_size, codec, 4, &partial,
                            &twice, &twice_size) == 0);
    assert(h26x_read_vui(twice, twice_size, codec, &actual) == 0);
    assert(actual.full_range == 0);
    assert(actual.colour_primaries == 9);
    assert(actual.transfer_characteristics == 18);
    assert(actual.matrix_coefficients == 9);

    uint8_t framed[512] = {0, 0, 0, 1};
    for (size_t i = 0; i < sps_size; ++i) framed[i + 4] = sps[i];
    uint8_t *annex_b = NULL;
    size_t annex_b_size = 0;
    assert(h26x_rewrite_vui(framed, sps_size + 4, codec, 4, &request,
                            &annex_b, &annex_b_size) == 0);
    assert(h26x_read_vui(annex_b + 4, annex_b_size - 4, codec, &actual) == 0);

    framed[0] = (uint8_t)(sps_size >> 24);
    framed[1] = (uint8_t)(sps_size >> 16);
    framed[2] = (uint8_t)(sps_size >> 8);
    framed[3] = (uint8_t)sps_size;
    uint8_t *length_prefixed = NULL;
    size_t length_prefixed_size = 0;
    assert(h26x_rewrite_vui(framed, sps_size + 4, codec, 4, &request,
                            &length_prefixed, &length_prefixed_size) == 0);
    size_t output_nal_size = ((size_t)length_prefixed[0] << 24) |
                             ((size_t)length_prefixed[1] << 16) |
                             ((size_t)length_prefixed[2] << 8) |
                             length_prefixed[3];
    assert(output_nal_size + 4 == length_prefixed_size);
    assert(h26x_read_vui(length_prefixed + 4, output_nal_size, codec, &actual) == 0);

    free(rewritten);
    free(twice);
    free(annex_b);
    free(length_prefixed);
}

static void check_configuration_records(const uint8_t *h264, size_t h264_size,
                                        const uint8_t *h265, size_t h265_size) {
    const H26xVuiColor request = {1, 1, 6, 1};
    uint8_t avcc[512] = {1, 66, 0, 30, 0xff, 0xe1};
    avcc[6] = (uint8_t)(h264_size >> 8);
    avcc[7] = (uint8_t)h264_size;
    for (size_t i = 0; i < h264_size; ++i) avcc[8 + i] = h264[i];
    avcc[8 + h264_size] = 0;
    uint8_t *output = NULL;
    size_t output_size = 0;
    assert(h26x_rewrite_vui(avcc, 9 + h264_size, H26X_CODEC_H264, 4,
                            &request, &output, &output_size) == 0);
    size_t rewritten_h264_size = ((size_t)output[6] << 8) | output[7];
    H26xVuiColor actual;
    assert(h26x_read_vui(output + 8, rewritten_h264_size,
                         H26X_CODEC_H264, &actual) == 0);
    assert(actual.transfer_characteristics == 6);
    free(output);

    uint8_t hvcc[512] = {0};
    hvcc[0] = 1;
    hvcc[21] = 3;
    hvcc[22] = 1;
    hvcc[23] = 0xa1;
    hvcc[24] = 0;
    hvcc[25] = 1;
    hvcc[26] = (uint8_t)(h265_size >> 8);
    hvcc[27] = (uint8_t)h265_size;
    for (size_t i = 0; i < h265_size; ++i) hvcc[28 + i] = h265[i];
    output = NULL;
    output_size = 0;
    assert(h26x_rewrite_vui(hvcc, 28 + h265_size, H26X_CODEC_H265, 4,
                            &request, &output, &output_size) == 0);
    size_t rewritten_h265_size = ((size_t)output[26] << 8) | output[27];
    assert(h26x_read_vui(output + 28, rewritten_h265_size,
                         H26X_CODEC_H265, &actual) == 0);
    assert(actual.transfer_characteristics == 6);
    free(output);
}

static int rewrite_file(const char *input_path, const char *output_path, int codec) {
    FILE *input = fopen(input_path, "rb");
    if (!input || fseek(input, 0, SEEK_END) != 0) return -1;
    long length = ftell(input);
    if (length <= 0 || fseek(input, 0, SEEK_SET) != 0) {
        fclose(input);
        return -1;
    }
    uint8_t *data = (uint8_t *)malloc((size_t)length);
    if (!data || fread(data, 1, (size_t)length, input) != (size_t)length) {
        free(data);
        fclose(input);
        return -1;
    }
    fclose(input);
    const H26xVuiColor request = {1, 9, 16, 9};
    uint8_t *output = NULL;
    size_t output_size = 0;
    int status = h26x_rewrite_vui(data, (size_t)length, codec, 4, &request,
                                  &output, &output_size);
    free(data);
    if (status < 0) return -1;
    FILE *file = fopen(output_path, "wb");
    if (!file || fwrite(output, 1, output_size, file) != output_size) status = -1;
    if (file) fclose(file);
    free(output);
    return status;
}

static void check_media_preserved(int codec, const uint8_t *sps, size_t sps_size) {
    const H26xVuiColor request = {0, 1, 1, 1};
    uint8_t vcl[256];
    memset(vcl, 0x55, sizeof(vcl));
    vcl[0] = codec == H26X_CODEC_H264 ? 0x65 : 0x26;
    if (codec == H26X_CODEC_H265) vcl[1] = 1;
    for (int length_size = 1; length_size <= 4; ++length_size) {
        uint8_t sample[1024] = {0};
        size_t vcl_size = length_size == 1 ? 128 : sizeof(vcl);
        size_t offset = 0;
        for (int i = length_size - 1; i >= 0; --i) sample[offset++] = (uint8_t)(sps_size >> (8 * i));
        memcpy(sample + offset, sps, sps_size); offset += sps_size;
        for (int i = length_size - 1; i >= 0; --i) sample[offset++] = (uint8_t)(vcl_size >> (8 * i));
        memcpy(sample + offset, vcl, vcl_size); offset += vcl_size;
        uint8_t *output = NULL;
        size_t output_size = 0;
        assert(h26x_rewrite_vui(sample, offset, codec, length_size, &request, &output, &output_size) == 0);
        size_t rewritten_size = 0;
        for (int i = 0; i < length_size; ++i) rewritten_size = (rewritten_size << 8) | output[i];
        H26xVuiColor actual;
        assert(h26x_read_vui(output + length_size, rewritten_size, codec, &actual) == 0);
        assert(actual.full_range == 0 && actual.colour_primaries == 1 &&
               actual.transfer_characteristics == 1 && actual.matrix_coefficients == 1);
        size_t vcl_offset = (size_t)length_size + rewritten_size + (size_t)length_size;
        assert(vcl_offset + vcl_size == output_size);
        assert(memcmp(output + vcl_offset, vcl, vcl_size) == 0);
        free(output);

        // A 4-byte length of 256 contains 00 00 01 but is not Annex B.
        size_t prefix = offset - vcl_size - (size_t)length_size;
        assert(h26x_rewrite_vui(sample + prefix, offset - prefix, codec, length_size,
                              &request, &output, &output_size) == 0);
        assert(output_size == offset - prefix);
        assert(memcmp(output, sample + prefix, output_size) == 0);
        free(output);
    }

    uint8_t annex_b[1024] = {0, 0, 0, 1};
    memcpy(annex_b + 4, sps, sps_size);
    size_t offset = sps_size + 4;
    const uint8_t start_code[] = {0, 0, 1};
    memcpy(annex_b + offset, start_code, sizeof(start_code)); offset += sizeof(start_code);
    memcpy(annex_b + offset, vcl, sizeof(vcl)); offset += sizeof(vcl);
    uint8_t *output = NULL;
    size_t output_size = 0;
    assert(h26x_rewrite_vui(annex_b, offset, codec, 4, &request, &output, &output_size) == 0);
    assert(memcmp(output + output_size - sizeof(vcl), vcl, sizeof(vcl)) == 0);
    free(output);
}

static void check_bad_input(void) {
    const H26xVuiColor request = {0, 1, 1, 1};
    uint8_t bad_sps[32] = {0x67};
    uint8_t *output = NULL;
    size_t output_size = 0;
    assert(h26x_rewrite_vui(bad_sps, sizeof(bad_sps), H26X_CODEC_H264, 4,
                          &request, &output, &output_size) < 0);
    assert(h26x_rewrite_vui(bad_sps, 1, H26X_CODEC_H264, 4,
                          &request, &output, &output_size) < 0);
    const H26xVuiColor invalid = {2, 1, 1, 1};
    assert(h26x_rewrite_vui(bad_sps, sizeof(bad_sps), H26X_CODEC_H264, 4,
                          &invalid, &output, &output_size) < 0);
}

int main(int argc, char **argv) {
    uint8_t h264[256] = {0};
    uint8_t h265[256] = {0};
    size_t h264_size = make_h264_sps(h264);
    size_t h265_size = make_h265_sps(h265);
    check_codec(H26X_CODEC_H264, h264, h264_size);
    check_codec(H26X_CODEC_H265, h265, h265_size);
    check_configuration_records(h264, h264_size, h265, h265_size);
    check_media_preserved(H26X_CODEC_H264, h264, h264_size);
    check_media_preserved(H26X_CODEC_H265, h265, h265_size);
    check_bad_input();
    if (argc == 5) {
        assert(rewrite_file(argv[1], argv[2], H26X_CODEC_H264) == 0);
        assert(rewrite_file(argv[3], argv[4], H26X_CODEC_H265) == 0);
    }
    puts("h26x_vui_rewriter_test: OK");
    return 0;
}
