#include "libuvc/libuvc.h"
#include "libuvc/libuvc_internal.h"

extern void _uvc_process_payload(uvc_stream_handle_t *, uint8_t *, size_t);
extern void _uvc_process_bulk_payload(uvc_stream_handle_t *, uint8_t *, size_t, int);
extern size_t _uvc_bulk_fixed_frame_size(enum uvc_frame_format, size_t, size_t, size_t, size_t);

static void init_bulk_stream(struct uvc_stream_handle *stream, struct uvc_device_handle *device) {
    memset(stream, 0, sizeof(*stream));
    stream->devh = device;
    stream->frame_format = UVC_FRAME_FORMAT_MJPEG;
    stream->cur_ctrl.dwMaxVideoFrameSize = 8192;
    stream->cur_ctrl.dwMaxPayloadTransferSize = 2048;
    stream->bulk_packet_size = 512;
    stream->seq = 1;
    stream->outbuf = malloc(8192);
    stream->holdbuf = malloc(8192);
    stream->meta_outbuf = malloc(LIBUVC_XFER_META_BUF_SIZE);
    stream->meta_holdbuf = malloc(LIBUVC_XFER_META_BUF_SIZE);
    assert(stream->outbuf && stream->holdbuf && stream->meta_outbuf && stream->meta_holdbuf);
    pthread_mutex_init(&stream->cb_mutex, NULL);
    pthread_cond_init(&stream->cb_cond, NULL);
}

static void free_bulk_stream(struct uvc_stream_handle *stream) {
    free(stream->outbuf); free(stream->holdbuf);
    free(stream->meta_outbuf); free(stream->meta_holdbuf);
    free(stream->bulk_pending_buf);
    pthread_cond_destroy(&stream->cb_cond);
    pthread_mutex_destroy(&stream->cb_mutex);
}

static void test_bulk_repair(size_t boundary, size_t tail_size, int separate_zlp) {
    struct uvc_device_handle device = {0};
    struct uvc_stream_handle stream;
    init_bulk_stream(&stream, &device);
    uint8_t old_first[] = {12, 0x8d, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0xff, 0xd8, 0xff, 0xdb};
    _uvc_process_bulk_payload(&stream, old_first, sizeof(old_first), 1);
    const uint8_t old_last[] = {12, 0x8f, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0};
    const uint8_t new_first[] = {12, 0x8c, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0};
    const uint8_t new_last[] = {12, 0x8e, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0};
    size_t wire_size = boundary + 2048 + tail_size;
    uint8_t *wire = malloc(wire_size);
    assert(wire);
    memset(wire, 0x55, wire_size);
    memcpy(wire, old_last, sizeof(old_last));
    wire[boundary - 2] = 0xff; wire[boundary - 1] = 0xd9;
    memcpy(wire + boundary, new_first, sizeof(new_first));
    wire[boundary + 12] = 0xff; wire[boundary + 13] = 0xd8;
    wire[boundary + 14] = 0xff; wire[boundary + 15] = 0xdb;
    memcpy(wire + boundary + 2048, new_last, sizeof(new_last));
    if (tail_size == 12) { // Header-only EOF; EOI is in the preceding payload.
        wire[boundary + 2046] = 0xff; wire[boundary + 2047] = 0xd9;
    } else {
        wire[wire_size - 2] = 0xff; wire[wire_size - 1] = 0xd9;
    }
    _uvc_process_bulk_payload(&stream, wire, 2048, 0);
    assert(stream.diagnostic_bulk_repairs == 1 && stream.bulk_realign_active);
    assert(stream.bulk_pending_bytes == 2048 - boundary);
    assert(stream.hold_seq == 1 && stream.hold_bytes == 4 + boundary - 12);
    assert(memcmp(stream.holdbuf, old_first + 12, 4) == 0);
    assert(memcmp(stream.holdbuf + 4, wire + 12, boundary - 12) == 0);

    size_t second_size = wire_size - 2048;
    assert(second_size <= 2048);
    assert(!separate_zlp || second_size == 2048);
    _uvc_process_bulk_payload(&stream, wire + 2048, second_size, !separate_zlp);
    if (separate_zlp) {
        assert(stream.bulk_realign_active && stream.bulk_pending_bytes == tail_size);
        _uvc_process_bulk_payload(&stream, NULL, 0, 1);
    }
    assert(!stream.bulk_realign_active && stream.bulk_pending_bytes == 0);
    assert(stream.hold_seq == 2 && stream.seq == 3);
    assert(stream.hold_bytes == 2036 + tail_size - 12);
    assert(memcmp(stream.holdbuf, wire + boundary + 12, 2036) == 0);
    assert(memcmp(stream.holdbuf + 2036, wire + boundary + 2048 + 12, tail_size - 12) == 0);
    assert(stream.diagnostic_frames_without_soi == 0);
    assert(stream.diagnostic_bad_headers == 0 && stream.diagnostic_error_payloads == 0);
    assert(stream.diagnostic_missing_eoh == 0 && stream.diagnostic_reserved_flags == 0);
    assert(stream.diagnostic_fid_boundaries == 0);

    // After a short/ZLP end, the next normal frame takes the original path.
    uint8_t normal[] = {12, 0x8f, 5, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0xff, 0xd8, 0xff, 0xd9};
    _uvc_process_bulk_payload(&stream, normal, sizeof(normal), 1);
    assert(stream.hold_seq == 3 && stream.hold_bytes == 4);
    assert(memcmp(stream.holdbuf, normal + 12, 4) == 0);
    assert(stream.diagnostic_bulk_repairs == 1);
    free(wire);
    free_bulk_stream(&stream);
}

static void test_bulk_false_boundaries(void) {
    for (unsigned variant = 0; variant < 7; ++variant) {
        struct uvc_device_handle device = {0};
        struct uvc_stream_handle stream;
        init_bulk_stream(&stream, &device);
        uint8_t data[2048];
        memset(data, 0x55, sizeof(data));
        uint8_t header[] = {12, 0x8f, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0};
        uint8_t next[] = {12, 0x8c, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0xff, 0xd8, 0xff, 0xdb};
        size_t offset = variant == 3 ? 513 : 512;
        memcpy(data, header, sizeof(header));
        data[offset - 2] = 0xff; data[offset - 1] = 0xd9;
        memcpy(data + offset, next, sizeof(next));
        if (variant == 0) data[offset + 1] |= UVC_STREAM_FID; // Same FID.
        if (variant == 1) data[offset + 2] = 1; // Same PTS.
        if (variant == 2) data[offset - 1] = 0; // No prior EOI.
        if (variant == 4) data[offset + 15] = 0; // Invalid JPEG marker.
        if (variant == 5) stream.frame_format = UVC_FRAME_FORMAT_YUYV;
        if (variant == 6) data[1] &= ~UVC_STREAM_EOF;
        _uvc_process_bulk_payload(&stream, data, sizeof(data), 0);
        assert(stream.diagnostic_bulk_repairs == 0 && !stream.bulk_realign_active);
        assert(stream.bulk_pending_buf == NULL);
        free_bulk_stream(&stream);
    }
}

static void test_bulk_chained_boundaries(void) {
    struct uvc_device_handle device = {0};
    struct uvc_stream_handle stream;
    init_bulk_stream(&stream, &device);
    uint8_t old_first[] = {12, 0x8d, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0xff, 0xd8, 0xff, 0xdb};
    _uvc_process_bulk_payload(&stream, old_first, sizeof(old_first), 1);
    // Two consecutive frame tails omit their short/ZLP delimiters.
    uint8_t wire[512 + 1024 + 2048 + 123];
    memset(wire, 0x55, sizeof(wire));
    const uint8_t first_tail[] = {12, 0x8f, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0};
    const uint8_t second_frame[] = {12, 0x8e, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0xff, 0xd8, 0xff, 0xdb};
    const uint8_t third_start[] = {12, 0x8d, 5, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0xff, 0xd8, 0xff, 0xdb};
    const uint8_t third_tail[] = {12, 0x8f, 5, 0, 0, 0, 6, 0, 0, 0, 0, 0};
    memcpy(wire, first_tail, sizeof(first_tail));
    wire[510] = 0xff; wire[511] = 0xd9;
    memcpy(wire + 512, second_frame, sizeof(second_frame));
    wire[1534] = 0xff; wire[1535] = 0xd9;
    memcpy(wire + 1536, third_start, sizeof(third_start));
    memcpy(wire + 3584, third_tail, sizeof(third_tail));
    wire[sizeof(wire) - 2] = 0xff; wire[sizeof(wire) - 1] = 0xd9;
    _uvc_process_bulk_payload(&stream, wire, 2048, 0);
    assert(stream.hold_seq == 1 && stream.diagnostic_bulk_repairs == 1);
    _uvc_process_bulk_payload(&stream, wire + 2048, sizeof(wire) - 2048, 1);
    assert(stream.diagnostic_bulk_repairs == 2 && stream.seq == 4 && stream.hold_seq == 3);
    assert(stream.hold_bytes == 2036 + 111);
    assert(memcmp(stream.holdbuf, wire + 1548, 2036) == 0);
    assert(memcmp(stream.holdbuf + 2036, wire + 3596, 111) == 0);
    assert(stream.diagnostic_frames_without_soi == 0 && stream.diagnostic_bad_headers == 0);
    assert(!stream.bulk_realign_active && stream.bulk_pending_bytes == 0);
    free_bulk_stream(&stream);
}

static void test_raw_bulk_repair(enum uvc_frame_format format, size_t frame_size, int pts_only) {
    struct uvc_device_handle device = {0};
    struct uvc_stream_handle stream;
    init_bulk_stream(&stream, &device);
    stream.frame_format = format;
    stream.bulk_fixed_frame_size = _uvc_bulk_fixed_frame_size(format, 64, 32, 0, 8192);
    assert(stream.bulk_fixed_frame_size == frame_size);
    size_t header_size = pts_only ? 6 : 12;
    uint8_t old_header[] = {12, 0x8d, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0};
    uint8_t next_header[] = {12, 0x8c, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0};
    if (pts_only) {
        old_header[0] = next_header[0] = 6;
        old_header[1] &= ~UVC_STREAM_SCR;
        next_header[1] &= ~UVC_STREAM_SCR;
    }
    uint8_t *old_image = malloc(frame_size), *new_image = malloc(frame_size);
    assert(old_image && new_image);
    for (size_t i = 0; i < frame_size; ++i) {
        old_image[i] = (uint8_t)(i * 17 + 31);
        new_image[i] = (uint8_t)(i * 37 + 91);
    }
    // Pixels may themselves look like JPEG markers or UVC headers.
    new_image[0] = 0xff; new_image[1] = 0xd8;
    memcpy(new_image + 500, next_header, header_size);
    size_t boundary = 512, prefix = frame_size - (boundary - header_size);
    uint8_t packet[2048];
    for (size_t offset = 0; offset < prefix;) {
        size_t take = prefix - offset;
        if (take > sizeof(packet) - header_size) take = sizeof(packet) - header_size;
        memcpy(packet, old_header, header_size);
        memcpy(packet + header_size, old_image + offset, take);
        _uvc_process_bulk_payload(&stream, packet, header_size + take,
            header_size + take < sizeof(packet));
        offset += take;
    }
    assert(stream.got_bytes == prefix && stream.hold_bytes == 0);
    size_t payload_count = (frame_size + 2048 - header_size - 1) / (2048 - header_size);
    size_t wire_size = boundary + frame_size + payload_count * header_size;
    uint8_t *wire = malloc(wire_size);
    assert(wire);
    memcpy(wire, old_header, header_size);
    wire[1] |= UVC_STREAM_EOF;
    memcpy(wire + header_size, old_image + prefix, boundary - header_size);
    size_t wire_offset = boundary;
    for (size_t offset = 0; offset < frame_size;) {
        size_t take = frame_size - offset;
        if (take > 2048 - header_size) take = 2048 - header_size;
        memcpy(wire + wire_offset, next_header, header_size);
        if (offset + take == frame_size) wire[wire_offset + 1] |= UVC_STREAM_EOF;
        memcpy(wire + wire_offset + header_size, new_image + offset, take);
        wire_offset += header_size + take;
        offset += take;
    }
    assert(wire_offset == wire_size);
    _uvc_process_bulk_payload(&stream, wire, 2048, 0);
    assert(stream.hold_seq == 1 && stream.hold_bytes == frame_size);
    assert(memcmp(stream.holdbuf, old_image, frame_size) == 0);
    assert(stream.diagnostic_bulk_raw_repairs == 1);
    for (size_t offset = 2048; offset < wire_size;) {
        size_t take = wire_size - offset;
        if (take > 2048) take = 2048;
        _uvc_process_bulk_payload(&stream, wire + offset, take, take < 2048);
        offset += take;
    }
    if (wire_size % 2048 == 0) _uvc_process_bulk_payload(&stream, NULL, 0, 1);
    assert(stream.hold_seq == 2 && stream.hold_bytes == frame_size);
    assert(memcmp(stream.holdbuf, new_image, frame_size) == 0);
    assert(stream.diagnostic_bulk_repairs == 1 && stream.diagnostic_bulk_raw_repairs == 1);
    assert(stream.diagnostic_bad_headers == 0 && stream.diagnostic_error_payloads == 0);
    assert(stream.diagnostic_missing_eoh == 0 && stream.diagnostic_reserved_flags == 0);
    assert(!stream.bulk_realign_active && stream.bulk_pending_bytes == 0);
    free(wire); free(old_image); free(new_image);
    free_bulk_stream(&stream);
}

static void test_raw_sizes_and_false_boundaries(void) {
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_YUYV, 1280, 720, 2560, 2000000) == 1843200);
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_P010, 1280, 720, 2560, 3000000) == 2764800);
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_NV12, 1280, 720, 1280, 2000000) == 1382400);
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_RGB, 1280, 720, 4096, 3000000) == 0);
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_NV12, 1281, 720, 0, 2000000) == 0);
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_YUYV, 64, 32, 0, 4000) == 0);
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_H264, 64, 32, 0, 8192) == 0);
    assert(_uvc_bulk_fixed_frame_size(UVC_FRAME_FORMAT_RGB, SIZE_MAX, 2, 0, SIZE_MAX) == 0);
    for (unsigned variant = 0; variant < 7; ++variant) {
        struct uvc_device_handle device = {0};
        struct uvc_stream_handle stream;
        init_bulk_stream(&stream, &device);
        stream.frame_format = UVC_FRAME_FORMAT_YUYV;
        stream.bulk_fixed_frame_size = 4096;
        stream.got_bytes = 4096 - 500;
        stream.fid = 1; stream.pts = 1;
        memset(stream.outbuf, 0x55, stream.got_bytes);
        uint8_t data[2048];
        memset(data, 0x55, sizeof(data));
        uint8_t header[] = {12, 0x8f, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0};
        uint8_t next[] = {12, 0x8c, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0};
        memcpy(data, header, sizeof(header));
        memcpy(data + 512, next, sizeof(next));
        if (variant == 0) stream.got_bytes--; // Missing byte; exact boundary no longer matches.
        if (variant == 1) stream.pts = 9; // EOF doesn't belong to assembled frame.
        if (variant == 2) data[512 + 1] |= UVC_STREAM_FID;
        if (variant == 3) data[512 + 2] = 1;
        if (variant == 4) data[1] &= ~UVC_STREAM_EOF;
        if (variant == 5) data[512 + 1] |= UVC_STREAM_ERR;
        if (variant == 6) stream.got_bytes = 4096 - 1012; // Plausible header lies in pixels.
        _uvc_process_bulk_payload(&stream, data, sizeof(data), 0);
        assert(stream.diagnostic_bulk_repairs == 0 && !stream.bulk_realign_active);
        free_bulk_stream(&stream);
    }
}

static void test_receive_diagnostics(void) {
    struct uvc_device_handle device = {0};
    struct uvc_stream_handle stream;
    init_bulk_stream(&stream, &device);
    stream.frame_format = UVC_FRAME_FORMAT_YUYV;
    stream.bulk_fixed_frame_size = 4;
    // PTS wrap to zero is a valid positive delta, not a missing timestamp.
    uint8_t frame[] = {6, 0x86, 0xff, 0xff, 0xff, 0xff, 1, 2, 3, 4};
    _uvc_process_payload(&stream, frame, sizeof(frame));
    memset(frame + 2, 0, 4);
    _uvc_process_payload(&stream, frame, sizeof(frame));
    assert(stream.diagnostic_pts_samples == 1 && stream.diagnostic_pts_ticks == 1);
    assert(stream.diagnostic_pts_missing == 0 && stream.diagnostic_pts_backward == 0);
    _uvc_process_payload(&stream, frame, sizeof(frame));
    assert(stream.diagnostic_pts_repeated == 1);
    memset(frame + 2, 0xff, 4);
    _uvc_process_payload(&stream, frame, sizeof(frame));
    assert(stream.diagnostic_pts_backward == 1 && stream.diagnostic_pts_samples == 1);
    uint8_t no_pts[] = {2, 0x82, 1, 2, 3};
    _uvc_process_payload(&stream, no_pts, sizeof(no_pts));
    assert(stream.diagnostic_pts_missing == 1 && stream.diagnostic_raw_short_frames == 1);
    frame[2] = 1; frame[3] = frame[4] = frame[5] = 0;
    _uvc_process_payload(&stream, frame, sizeof(frame));
    assert(stream.diagnostic_pts_samples == 1); // Do not bridge a frame without PTS.
    assert(stream.diagnostic_frame_intervals == 5 && stream.diagnostic_frame_ns > 0);
    assert(stream.diagnostic_raw_long_frames == 0);
    stream.cur_ctrl.dwMaxVideoFrameSize = 3;
    _uvc_process_payload(&stream, frame, sizeof(frame));
    assert(stream.diagnostic_truncated_bytes == 1 && stream.hold_bytes == 3);
    assert(stream.diagnostic_raw_short_frames == 2);
    free_bulk_stream(&stream);
}

int main(void) {
    test_receive_diagnostics();
    struct uvc_device_handle device = {0};
    struct uvc_stream_handle stream = {0};
    stream.devh = &device;
    stream.frame_format = UVC_FRAME_FORMAT_MJPEG;
    stream.cur_ctrl.dwMaxVideoFrameSize = 128;
    stream.seq = 1;
    stream.outbuf = malloc(128);
    stream.holdbuf = malloc(128);
    stream.meta_outbuf = malloc(LIBUVC_XFER_META_BUF_SIZE);
    stream.meta_holdbuf = malloc(LIBUVC_XFER_META_BUF_SIZE);
    assert(stream.outbuf && stream.holdbuf && stream.meta_outbuf && stream.meta_holdbuf);
    pthread_mutex_init(&stream.cb_mutex, NULL);
    pthread_cond_init(&stream.cb_cond, NULL);

    uint8_t first[] = {2, 0x80, 0xff, 0xd8, 0x11, 0x22};
    uint8_t last[] = {2, 0x82, 0x33, 0xff, 0xd9};
    const uint8_t image[] = {0xff, 0xd8, 0x11, 0x22, 0x33, 0xff, 0xd9};
    _uvc_process_payload(&stream, first, sizeof(first));
    assert(stream.got_bytes == 4 && stream.hold_bytes == 0);

    // Advertised PTS/SCR fields must fit inside the UVC header. A malformed
    // FID must not prematurely publish the accumulated frame.
    uint8_t malformed[] = {2, 0x8d};
    _uvc_process_payload(&stream, malformed, sizeof(malformed));
    assert(stream.diagnostic_bad_headers == 1 && stream.got_bytes == 4 && stream.seq == 1);
    uint8_t short_header[] = {1};
    _uvc_process_payload(&stream, short_header, sizeof(short_header));
    assert(stream.diagnostic_bad_headers == 2 && stream.got_bytes == 4);
    uint8_t error[] = {2, 0xc0, 0xaa};
    _uvc_process_payload(&stream, error, sizeof(error));
    assert(stream.diagnostic_error_payloads == 1 && stream.got_bytes == 4);
    _uvc_process_payload(&stream, last, sizeof(last));
    assert(stream.hold_bytes == sizeof(image) && memcmp(stream.holdbuf, image, sizeof(image)) == 0);

    // A header-only EOF closes the previously accumulated data.
    _uvc_process_payload(&stream, first, sizeof(first));
    uint8_t header_only_eof[] = {2, 0x82};
    _uvc_process_payload(&stream, header_only_eof, sizeof(header_only_eof));
    assert(stream.got_bytes == 0 && stream.hold_bytes == 4 && stream.hold_seq == 2);
    assert(memcmp(stream.holdbuf, first + 2, 4) == 0);

    // Missing EOF followed by a FID toggle stays observable and preserves
    // the next frame instead of merging two frames together.
    _uvc_process_payload(&stream, first, sizeof(first));
    uint8_t next_first[] = {2, 0x81, 0xff, 0xd8, 0x11, 0x22};
    uint8_t next_last[] = {2, 0x83, 0x33, 0xff, 0xd9};
    _uvc_process_payload(&stream, next_first, sizeof(next_first));
    assert(stream.diagnostic_fid_boundaries == 1 && stream.got_bytes == 4 && stream.hold_seq == 3);
    _uvc_process_payload(&stream, next_last, sizeof(next_last));
    assert(stream.hold_seq == 4 && stream.hold_bytes == sizeof(image));
    assert(memcmp(stream.holdbuf, image, sizeof(image)) == 0);

    // Reproduce the current parser's permissive behavior: arbitrary bytes
    // resembling a header can publish an entropy fragment as another frame.
    // Diagnostics must expose this without changing device compatibility.
    uint8_t pseudo_header[] = {4, 0x03, 0xab, 0xcd, 0xe0, 0x9c, 0x83, 0x81};
    uint32_t sequence_before = stream.seq;
    _uvc_process_payload(&stream, pseudo_header, sizeof(pseudo_header));
    assert(stream.seq == sequence_before + 1 && stream.hold_bytes == 4);
    assert(memcmp(stream.holdbuf, pseudo_header + 4, 4) == 0);
    assert(stream.diagnostic_missing_eoh == 1);
    assert(stream.diagnostic_frames_without_soi == 1);
    unsigned latest = (stream.diagnostic_history_next + LIBUVC_DIAGNOSTIC_HISTORY - 1)
        % LIBUVC_DIAGNOSTIC_HISTORY;
    assert(stream.diagnostic_history[latest].length == sizeof(pseudo_header));
    assert(memcmp(stream.diagnostic_history[latest].head, pseudo_header, sizeof(pseudo_header)) == 0);

    // Ring history stays bounded, including empty bulk returns, and never
    // dereferences the payload when its length is zero.
    for (unsigned i = 0; i < LIBUVC_DIAGNOSTIC_HISTORY + 2; ++i)
        _uvc_process_payload(&stream, NULL, 0);
    assert(stream.diagnostic_history_count == LIBUVC_DIAGNOSTIC_HISTORY);
    latest = (stream.diagnostic_history_next + LIBUVC_DIAGNOSTIC_HISTORY - 1)
        % LIBUVC_DIAGNOSTIC_HISTORY;
    assert(stream.diagnostic_history[latest].length == 0);
    assert(stream.diagnostic_history[latest].head_length == 0);
    assert(stream.seq == sequence_before + 1);

    // Model an EOF payload ending exactly on a 512-byte USB packet boundary
    // without a short/ZLP delimiter. A host read can include the next frame's
    // header and JPEG beginning. Verify diagnostics expose exact offsets.
    stream.cur_ctrl.dwMaxVideoFrameSize = 4096;
    stream.cur_ctrl.dwMaxPayloadTransferSize = 2048;
    stream.outbuf = realloc(stream.outbuf, 4096);
    stream.holdbuf = realloc(stream.holdbuf, 4096);
    assert(stream.outbuf && stream.holdbuf);
    uint8_t coalesced[2048];
    memset(coalesced, 0x55, sizeof(coalesced));
    const uint8_t eof_header[] = {12, 0x8f, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0};
    const uint8_t next_header[] = {12, 0x8c, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0};
    memcpy(coalesced, eof_header, sizeof(eof_header));
    coalesced[510] = 0xff; coalesced[511] = 0xd9;
    memcpy(coalesced + 512, next_header, sizeof(next_header));
    coalesced[524] = 0xff; coalesced[525] = 0xd8;
    coalesced[526] = 0xff; coalesced[527] = 0xdb;
    _uvc_process_payload(&stream, coalesced, sizeof(coalesced));
    latest = (stream.diagnostic_history_next + LIBUVC_DIAGNOSTIC_HISTORY - 1)
        % LIBUVC_DIAGNOSTIC_HISTORY;
    assert(stream.diagnostic_full_eof_transfers == 1);
    assert(stream.diagnostic_embedded_frame_headers == 1);
    assert(stream.diagnostic_history[latest].markers_inspected == 1);
    assert(stream.diagnostic_history[latest].first_soi == 524);
    assert(stream.diagnostic_history[latest].first_eoi == 510);
    assert(stream.diagnostic_history[latest].embedded_frame_header == 512);

    // A full EOF return alone is not proof of a missing delimiter.
    memset(coalesced + 12, 0x55, sizeof(coalesced) - 12);
    coalesced[2046] = 0xff; coalesced[2047] = 0xd9;
    _uvc_process_payload(&stream, coalesced, sizeof(coalesced));
    latest = (stream.diagnostic_history_next + LIBUVC_DIAGNOSTIC_HISTORY - 1)
        % LIBUVC_DIAGNOSTIC_HISTORY;
    assert(stream.diagnostic_full_eof_transfers == 2);
    assert(stream.diagnostic_embedded_frame_headers == 1);
    assert(stream.diagnostic_history[latest].first_soi == -1);
    assert(stream.diagnostic_history[latest].embedded_frame_header == -1);
    assert(stream.diagnostic_history[latest].first_eoi == 2046);

    free(stream.outbuf); free(stream.holdbuf);
    free(stream.meta_outbuf); free(stream.meta_holdbuf);
    pthread_cond_destroy(&stream.cb_cond);
    pthread_mutex_destroy(&stream.cb_mutex);
    test_bulk_repair(512, 123, 0);
    test_bulk_repair(1024, 123, 0);
    test_bulk_repair(1536, 123, 0);
    test_bulk_repair(512, 12, 0);
    test_bulk_repair(1536, 512, 1);
    test_bulk_false_boundaries();
    test_bulk_chained_boundaries();
    test_raw_bulk_repair(UVC_FRAME_FORMAT_YUYV, 4096, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_UYVY, 4096, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_RGB, 6144, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_BGR, 6144, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_NV12, 3072, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_NV21, 3072, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_I420, 3072, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_P010, 6144, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_GRAY8, 2048, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_GRAY16, 4096, 0);
    test_raw_bulk_repair(UVC_FRAME_FORMAT_YUYV, 4096, 1);
    test_raw_sizes_and_false_boundaries();
    puts("UVC payload assembly native tests passed");
    return 0;
}
