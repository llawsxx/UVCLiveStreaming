#ifndef UVCLIVESTREAMING_H26X_VUI_REWRITER_H
#define UVCLIVESTREAMING_H26X_VUI_REWRITER_H

#include <stddef.h>
#include <stdint.h>

enum {
    H26X_CODEC_H264 = 1,
    H26X_CODEC_H265 = 2,
};

typedef struct {
    int full_range; /* -1 keeps the SPS value. */
    int colour_primaries;
    int transfer_characteristics;
    int matrix_coefficients;
} H26xVuiColor;

/*
 * Rewrites every SPS found in Annex B, length-prefixed, avcC or hvcC data.
 * The caller owns *output and must release it with free().
 */
int h26x_rewrite_vui(const uint8_t *input, size_t input_size, int codec,
                     int nal_length_size, const H26xVuiColor *color,
                     uint8_t **output, size_t *output_size);

/* Reads the effective colour values from one raw SPS NAL (without framing). */
int h26x_read_vui(const uint8_t *nal, size_t nal_size, int codec,
                  H26xVuiColor *color);

#endif
