#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jpeglib.h>
#include <libuvc/libuvc.h>

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

int main(void) {
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
