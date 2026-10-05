#include "libuvc/libuvc.h"
#include "libuvc/libuvc_internal.h"

/* Fake USB transport: no physical device is claimed. Exercise actual stream
 * start/stop, including allocation and partial-submit failure cleanup. */
static int allocated, submitted, freed, fail_alloc_at = -1, fail_submit_at = -1;
static pthread_t cancel_threads[LIBUVC_NUM_TRANSFER_BUFS];
static int cancel_count;

struct libusb_transfer *libusb_alloc_transfer(int packets) {
    if (allocated == fail_alloc_at) return NULL;
    ++allocated;
    return calloc(1, sizeof(struct libusb_transfer) +
        packets * sizeof(struct libusb_iso_packet_descriptor));
}
void libusb_free_transfer(struct libusb_transfer *t) { ++freed; free(t); }
int libusb_submit_transfer(struct libusb_transfer *t) {
    (void)t;
    if (submitted == fail_submit_at) return LIBUSB_ERROR_NO_MEM;
    ++submitted;
    return 0;
}
static void *cancel_transfer(void *arg) {
    struct libusb_transfer *t = arg;
    t->status = LIBUSB_TRANSFER_CANCELLED;
    t->callback(t);
    return NULL;
}
int libusb_cancel_transfer(struct libusb_transfer *t) {
    assert(cancel_count < LIBUVC_NUM_TRANSFER_BUFS);
    assert(!pthread_create(&cancel_threads[cancel_count++], NULL, cancel_transfer, t));
    return 0;
}
libusb_device *libusb_get_device(libusb_device_handle *h) { (void)h; return NULL; }
int libusb_get_device_speed(libusb_device *d) { (void)d; return LIBUSB_SPEED_SUPER; }
int libusb_set_interface_alt_setting(libusb_device_handle *h, int i, int a) {
    (void)h; (void)i; (void)a; return 0;
}
int libusb_get_ss_endpoint_companion_descriptor(libusb_context *c,
        const struct libusb_endpoint_descriptor *e,
        struct libusb_ss_endpoint_companion_descriptor **out) {
    (void)c; (void)e; *out = NULL; return LIBUSB_ERROR_NOT_FOUND;
}
void libusb_free_ss_endpoint_companion_descriptor(struct libusb_ss_endpoint_companion_descriptor *d) { free(d); }
uvc_error_t uvc_ensure_frame_size(uvc_frame_t *frame, size_t size) {
    (void)frame; (void)size; return UVC_SUCCESS;
}

static void check_queue(int count, int iso, int allocation_failure, int submit_failure) {
    struct libusb_endpoint_descriptor ep = { .bEndpointAddress = 0x83, .wMaxPacketSize = 1024 };
    struct libusb_interface_descriptor alts[2] = {
        { .bNumEndpoints = 1, .endpoint = &ep },
        { .bNumEndpoints = 1, .endpoint = &ep, .bAlternateSetting = 1 },
    };
    struct libusb_interface interface = { .altsetting = alts, .num_altsetting = iso ? 2 : 1 };
    struct libusb_config_descriptor config = { .interface = &interface };
    struct uvc_device_info info = { .config = &config };
    struct uvc_device_handle device = { .info = &info };
    struct uvc_streaming_interface stream_if = { .bEndpointAddress = 0x83 };
    uvc_format_desc_t format = { .parent = &stream_if, .bFormatIndex = 1,
        .guidFormat = {'Y','U','Y','2',0,0,0x10,0,0x80,0,0,0xaa,0,0x38,0x9b,0x71} };
    uvc_frame_desc_t frame = { .parent = &format, .bFrameIndex = 1, .wWidth = 64, .wHeight = 32 };
    format.frame_descs = &frame;
    stream_if.format_descs = &format;
    struct uvc_stream_handle stream = { .devh = &device, .stream_if = &stream_if,
        .cur_ctrl = { .bFormatIndex = 1, .bFrameIndex = 1,
            .dwMaxPayloadTransferSize = iso ? 1024 : 15360, .dwMaxVideoFrameSize = 4096 } };
    pthread_mutex_init(&stream.cb_mutex, NULL);
    pthread_cond_init(&stream.cb_cond, NULL);
    allocated = submitted = freed = cancel_count = 0;
    fail_alloc_at = allocation_failure;
    fail_submit_at = submit_failure;
    if (count) assert(uvc_stream_set_bulk_transfer_count(&stream, count) == UVC_SUCCESS);
    const int expected = iso ? 8 : count ? count : 64;
    uvc_error_t result = uvc_stream_start(&stream, NULL, NULL, 0);
    if (allocation_failure >= 0 || submit_failure >= 0) {
        assert(result == UVC_ERROR_NO_MEM);
        assert(!stream.running);
    } else {
        assert(result == UVC_SUCCESS);
        assert(allocated == expected && submitted == expected);
        assert(uvc_stream_set_bulk_transfer_count(&stream, 64) == UVC_ERROR_BUSY);
        assert(uvc_stream_stop(&stream) == UVC_SUCCESS);
    }
    for (int i = 0; i < cancel_count; ++i) pthread_join(cancel_threads[i], NULL);
    assert(freed == allocated);
    for (int i = 0; i < LIBUVC_NUM_TRANSFER_BUFS; ++i)
        assert(!stream.transfers[i] && !stream.transfer_bufs[i]);
    pthread_cond_destroy(&stream.cb_cond);
    pthread_mutex_destroy(&stream.cb_mutex);
}

int main(void) {
    struct uvc_stream_handle stream = {0};
    assert(uvc_stream_set_bulk_transfer_count(NULL, 64) == UVC_ERROR_INVALID_PARAM);
    assert(uvc_stream_set_bulk_transfer_count(&stream, 7) == UVC_ERROR_INVALID_PARAM);
    assert(uvc_stream_set_bulk_transfer_count(&stream, LIBUVC_NUM_TRANSFER_BUFS + 1) == UVC_ERROR_INVALID_PARAM);
    const int counts[] = {0, 8, 16, 32, 64, 128, 256};
    for (unsigned i = 0; i < sizeof(counts) / sizeof(counts[0]); ++i) {
        check_queue(counts[i], 0, -1, -1);
        check_queue(counts[i], 1, -1, -1);
    }
    check_queue(256, 0, 0, -1);
    check_queue(256, 0, 127, -1);
    check_queue(256, 0, -1, 0);
    check_queue(256, 0, -1, 127);
    check_queue(256, 1, 3, -1);
    check_queue(256, 1, -1, 3);
    puts("UVC receive queue tests passed (Bulk counts, ISO isolation, failure cleanup)");
    return 0;
}
