#ifndef UVCLIVE_MJPEG_REPAIR_H
#define UVCLIVE_MJPEG_REPAIR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Return the start of a complete baseline JPEG header missing only SOI.
 * SIZE_MAX means that no conservative repair is possible. Entropy-only
 * fragments and headers missing quantization/frame/scan information fail. */
size_t mjpeg_missing_soi_header(const uint8_t *data, size_t size,
    unsigned width, unsigned height);

#ifdef __cplusplus
}
#endif
#endif
