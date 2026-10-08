#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jpeglib.h>
#include <libuvc/libuvc.h>
#include "mjpeg_repair.h"

extern uvc_error_t uvc_mjpeg2i420_diagnostic(uvc_frame_t *, uvc_frame_t *,
    long *, char *, size_t);

static unsigned char *make_jpeg(int vertical_sampling, unsigned long *size) {
    struct jpeg_compress_struct jpeg = {0};
    struct jpeg_error_mgr error;
    unsigned char *bytes = NULL;
    unsigned char row[64 * 3];
    jpeg.err = jpeg_std_error(&error);
    jpeg_create_compress(&jpeg);
    jpeg_mem_dest(&jpeg, &bytes, size);
    jpeg.image_width = 64;
    jpeg.image_height = 48;
    jpeg.input_components = 3;
    jpeg.in_color_space = JCS_RGB;
    jpeg_set_defaults(&jpeg);
    jpeg.comp_info[0].v_samp_factor = vertical_sampling;
    jpeg_set_quality(&jpeg, 90, TRUE);
    jpeg_start_compress(&jpeg, TRUE);
    while (jpeg.next_scanline < jpeg.image_height) {
        for (int x = 0; x < 64; ++x) {
            row[x * 3] = (x * 37 + jpeg.next_scanline * 13) & 255;
            row[x * 3 + 1] = (x * 11 + jpeg.next_scanline * 53) & 255;
            row[x * 3 + 2] = (x * 71 + jpeg.next_scanline * 23) & 255;
        }
        JSAMPROW rows[] = { row };
        jpeg_write_scanlines(&jpeg, rows, 1);
    }
    jpeg_finish_compress(&jpeg);
    jpeg_destroy_compress(&jpeg);
    return bytes;
}

extern uvc_error_t uvc_mjpeg2yuv_diagnostic(uvc_frame_t *, uvc_frame_t *, unsigned int, unsigned int,
    long *, char *, size_t);
#ifdef MJPEG_COMPARE_REFERENCE
extern uvc_error_t reference_mjpeg2yuv_diagnostic(uvc_frame_t *, uvc_frame_t *, unsigned int, unsigned int,
    long *, char *, size_t);
static void test_direct_output(int width, int height, int horizontal, int vertical, int grayscale) {
    struct jpeg_compress_struct jpeg = {0};
    struct jpeg_error_mgr error;
    unsigned char *encoded = NULL;
    unsigned long length = 0;
    jpeg.err = jpeg_std_error(&error);
    jpeg_create_compress(&jpeg);
    jpeg_mem_dest(&jpeg, &encoded, &length);
    jpeg.image_width = width; jpeg.image_height = height;
    jpeg.input_components = grayscale ? 1 : 3;
    jpeg.in_color_space = grayscale ? JCS_GRAYSCALE : JCS_RGB;
    jpeg_set_defaults(&jpeg);
    jpeg.comp_info[0].h_samp_factor = horizontal;
    jpeg.comp_info[0].v_samp_factor = vertical;
    jpeg_set_quality(&jpeg, 90, TRUE);
    jpeg_start_compress(&jpeg, TRUE);
    unsigned char *row = malloc((size_t)width * jpeg.input_components);
    assert(row);
    while (jpeg.next_scanline < jpeg.image_height) {
        for (int x = 0; x < width * jpeg.input_components; ++x)
            row[x] = (unsigned char)(x * 37 + jpeg.next_scanline * 13);
        JSAMPROW rows[] = {row};
        jpeg_write_scanlines(&jpeg, rows, 1);
    }
    jpeg_finish_compress(&jpeg); jpeg_destroy_compress(&jpeg); free(row);
    unsigned int cw = grayscale ? (width + 1) / 2 : (width + horizontal - 1) / horizontal;
    unsigned int ch = grayscale ? (height + 1) / 2 : (height + vertical - 1) / vertical;
    size_t size = (size_t)width * height + 2 * (size_t)cw * ch;
    unsigned char *guarded = malloc(size + 32);
    assert(guarded); memset(guarded, 0xa5, size + 32);
    uvc_frame_t input = {.data = encoded, .data_bytes = length, .width = width, .height = height};
    uvc_frame_t actual = {.data = guarded + 16, .data_bytes = size};
    uvc_frame_t *expected = uvc_allocate_frame(0);
    assert(expected);
    long warnings = -1;
    char detail[256];
    assert(reference_mjpeg2yuv_diagnostic(&input, expected, cw, ch, &warnings, detail, sizeof(detail)) == UVC_SUCCESS);
    assert(warnings == 0);
    assert(uvc_mjpeg2yuv_diagnostic(&input, &actual, cw, ch, &warnings, detail, sizeof(detail)) == UVC_SUCCESS);
    assert(warnings == 0);
    assert(actual.data == guarded + 16 && actual.data_bytes == size);
    assert(memcmp(actual.data, expected->data, size) == 0);
    for (int i = 0; i < 16; ++i) assert(guarded[i] == 0xa5 && guarded[size + 16 + i] == 0xa5);
    actual.data_bytes = size - 1;
    assert(uvc_mjpeg2yuv_diagnostic(&input, &actual, cw, ch, &warnings, detail, sizeof(detail)) != UVC_SUCCESS);
    for (int i = 0; i < 16; ++i) assert(guarded[i] == 0xa5 && guarded[size + 16 + i] == 0xa5);
    uvc_free_frame(expected); free(guarded); free(encoded);
}
#endif

int main(void) {
#ifdef MJPEG_COMPARE_REFERENCE
    const int sizes[][2] = {{64,48}, {64,49}, {63,47}, {1,1}, {1920,1080}, {3840,2160}};
    for (unsigned i = 0; i < sizeof(sizes) / sizeof(sizes[0]); ++i) {
        test_direct_output(sizes[i][0], sizes[i][1], 2, 2, 0);
        test_direct_output(sizes[i][0], sizes[i][1], 2, 1, 0);
        test_direct_output(sizes[i][0], sizes[i][1], 1, 1, 0);
        test_direct_output(sizes[i][0], sizes[i][1], 1, 1, 1);
    }
#endif
    uvc_frame_t *out = uvc_allocate_frame(0);
    assert(out);
    for (int sampling = 1; sampling <= 2; ++sampling) {
        unsigned long size = 0;
        unsigned char *bytes = make_jpeg(sampling, &size);
        uvc_frame_t in = {0};
        in.data = bytes;
        in.data_bytes = size;
        in.width = 64;
        in.height = 48;
        in.frame_format = UVC_FRAME_FORMAT_MJPEG;
        long warnings = -1;
        char message[256];
        assert(uvc_mjpeg2i420_diagnostic(&in, out, &warnings, message, sizeof(message)) == UVC_SUCCESS);
        assert(warnings == 0 && message[0] == '\0');
        assert(out->data_bytes == 64 * 48 * 3 / 2);

        unsigned char *reference = malloc(out->data_bytes);
        assert(reference);
        memcpy(reference, out->data, out->data_bytes);
        assert(mjpeg_missing_soi_header(bytes + 2, size - 2, 64, 48) == 0);
        assert(mjpeg_missing_soi_header(bytes + 2, size - 2, 128, 48) == SIZE_MAX);
        assert(mjpeg_missing_soi_header(bytes + 2, 20, 64, 48) == SIZE_MAX);
        unsigned char *repaired = malloc(size);
        assert(repaired);
        repaired[0] = 0xff; repaired[1] = 0xd8;
        memcpy(repaired + 2, bytes + 2, size - 2);
        in.data = repaired;
        assert(uvc_mjpeg2i420_diagnostic(&in, out, &warnings, message, sizeof(message)) == UVC_SUCCESS);
        assert(warnings == 0 && memcmp(reference, out->data, out->data_bytes) == 0);
        free(reference);
        free(repaired);
        in.data = bytes;

        // A missing EOI is tolerated by libjpeg, but must remain observable.
        in.data_bytes = size - 2;
        assert(uvc_mjpeg2i420_diagnostic(&in, out, &warnings, message, sizeof(message)) == UVC_SUCCESS);
        assert(warnings > 0 && message[0]);

        // Preserve the JPEG header, remove entropy data and add an EOI just
        // like the JNI repair path. Some corruption is recoverable; either
        // warnings or a fatal error must make the damaged input observable.
        size_t entropy = 0;
        for (size_t i = 0; i + 4 < size; ++i) {
            if (bytes[i] == 0xff && bytes[i + 1] == 0xda) {
                entropy = i + 2 + ((size_t)bytes[i + 2] << 8) + bytes[i + 3];
                break;
            }
        }
        assert(entropy && entropy < size - 2);
        assert(mjpeg_missing_soi_header(bytes + entropy, size - entropy, 64, 48) == SIZE_MAX);
        size_t truncated = entropy + (size - entropy) / 2;
        bytes[truncated] = 0xff;
        bytes[truncated + 1] = 0xd9;
        in.data_bytes = truncated + 2;
        uvc_error_t result = uvc_mjpeg2i420_diagnostic(&in, out, &warnings, message, sizeof(message));
        assert(result != UVC_SUCCESS || warnings > 0);
        assert(message[0]);
        printf("sampling=4:2:%s damaged result=%d warnings=%ld detail=%s\n",
            sampling == 1 ? "2" : "0", result, warnings, message);

        unsigned char invalid[] = {0xff, 0xd8, 0xff, 0xd9};
        in.data = invalid;
        in.data_bytes = sizeof(invalid);
        assert(uvc_mjpeg2i420_diagnostic(&in, out, &warnings, message, sizeof(message)) != UVC_SUCCESS);
        assert(message[0]);
        free(bytes);
    }
    uvc_free_frame(out);
    puts("MJPEG diagnostics native tests passed");
    return 0;
}
