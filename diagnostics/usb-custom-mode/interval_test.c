#include "usb_video_interval.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    const uint32_t ntsc[] = {333667, 166833, 0};
    const uint32_t both[] = {166833, 166667, 0};
    assert(usb_video_interval(60, ntsc, 0, 0, 0, 0) == 166833);
    assert(usb_video_interval(30, ntsc, 0, 0, 0, 0) == 333667);
    assert(usb_video_interval(59.94, ntsc, 0, 0, 0, 0) == 166833);
    assert(usb_video_interval(60, both, 0, 0, 0, 0) == 166667);
    assert(usb_video_interval(50, ntsc, 0, 0, 0, 0) == 0);
    assert(usb_video_interval(50, ntsc, 0, 0, 0, 1) == 200000);
    assert(usb_video_interval(23.976, ntsc, 0, 0, 0, 1) == 417084);
    assert(usb_video_interval(50, NULL, 166667, 1000000, 0, 0) == 200000);
    assert(usb_video_interval(50, NULL, 166667, 1000000, 166666, 0) == 0);
    assert(usb_video_interval(60, NULL, 166666, 1000000, 166666, 0) == 166666);
    assert(usb_video_interval(120, NULL, 166667, 1000000, 0, 0) == 0);
    assert(usb_video_interval(120, NULL, 166667, 1000000, 0, 1) == 83333);
    assert(usb_video_interval(0, ntsc, 0, 0, 0, 1) == 0);
    assert(usb_video_interval(NAN, ntsc, 0, 0, 0, 1) == 0);
    assert(usb_video_interval(INFINITY, ntsc, 0, 0, 0, 1) == 0);
    assert(usb_video_interval(241, ntsc, 0, 0, 0, 1) == 0);
    puts("PASS nominal NTSC, fractional, continuous/step and explicit unlisted interval requests");
    return 0;
}
