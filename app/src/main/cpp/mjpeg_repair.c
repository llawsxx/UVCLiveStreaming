#include "mjpeg_repair.h"

static unsigned be16(const uint8_t *p) { return ((unsigned)p[0] << 8) | p[1]; }

static int complete_header(const uint8_t *p, size_t size, unsigned width, unsigned height) {
    size_t at = 0;
    unsigned quant_tables = 0, needed_tables = 0;
    unsigned components = 0;
    uint8_t ids[3] = {0};
    while (at < size) {
        if (p[at++] != 0xff) return 0;
        while (at < size && p[at] == 0xff) ++at;
        if (at >= size) return 0;
        unsigned marker = p[at++];
        if (size - at < 2) return 0;
        size_t length = be16(p + at);
        if (length < 2 || length > size - at) return 0;
        const uint8_t *body = p + at + 2;
        size_t body_size = length - 2;
        if (marker == 0xdb) { // Quantization tables must exist in this frame.
            size_t q = 0;
            while (q < body_size) {
                unsigned selector = body[q++];
                unsigned precision = selector >> 4, table = selector & 15;
                if (precision > 1 || table > 3) return 0;
                size_t values = precision ? 128 : 64;
                if (values > body_size - q) return 0;
                quant_tables |= 1u << table;
                q += values;
            }
        } else if (marker == 0xc0) { // Conservative repair: baseline, 8-bit only.
            if (components || body_size < 6 || body[0] != 8 ||
                be16(body + 1) != height || be16(body + 3) != width) return 0;
            components = body[5];
            if ((components != 1 && components != 3) || body_size != 6 + 3 * components) return 0;
            for (unsigned i = 0; i < components; ++i) {
                unsigned sampling = body[7 + 3 * i], table = body[8 + 3 * i];
                if (!(sampling >> 4) || (sampling >> 4) > 4 ||
                    !(sampling & 15) || (sampling & 15) > 4 || table > 3) return 0;
                ids[i] = body[6 + 3 * i];
                for (unsigned j = 0; j < i; ++j) if (ids[j] == ids[i]) return 0;
                needed_tables |= 1u << table;
            }
        } else if (marker == 0xda) { // Complete interleaved baseline scan header.
            if (!components || !needed_tables || (quant_tables & needed_tables) != needed_tables ||
                body_size != 1 + 2 * components + 3 || body[0] != components) return 0;
            unsigned seen = 0;
            for (unsigned i = 0; i < components; ++i) {
                unsigned found = components;
                for (unsigned j = 0; j < components; ++j) if (body[1 + 2 * i] == ids[j]) found = j;
                if (found == components || (seen & (1u << found)) ||
                    (body[2 + 2 * i] >> 4) > 3 || (body[2 + 2 * i] & 15) > 3) return 0;
                seen |= 1u << found;
            }
            size_t spectral = 1 + 2 * components;
            if (body[spectral] != 0 || body[spectral + 1] != 63 || body[spectral + 2] != 0) return 0;
            return size - (at + length) >= 2; // Need some scan data too.
        } else if (marker == 0xc4) { // Huffman segments: bounds-check their structure.
            size_t h = 0;
            while (h < body_size) {
                if (body_size - h < 17 || (body[h] >> 4) > 1 || (body[h] & 15) > 3) return 0;
                unsigned symbols = 0;
                for (unsigned i = 1; i <= 16; ++i) symbols += body[h + i];
                h += 17;
                if (symbols > 256 || symbols > body_size - h) return 0;
                h += symbols;
            }
        } else if (marker == 0xdd) {
            if (body_size != 2) return 0;
        } else if (!((marker >= 0xe0 && marker <= 0xef) || marker == 0xfe)) {
            return 0;
        }
        at += length;
    }
    return 0;
}

size_t mjpeg_missing_soi_header(const uint8_t *data, size_t size, unsigned width, unsigned height) {
    if (!data || !width || !height) return SIZE_MAX;
    // Allow a small transport prefix, not a scan through arbitrary entropy data.
    for (size_t start = 0; start < size && start <= 32; ++start) {
        if (data[start] == 0xff && complete_header(data + start, size - start, width, height)) return start;
    }
    return SIZE_MAX;
}
