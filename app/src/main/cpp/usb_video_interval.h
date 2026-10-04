#ifndef UVCLIVE_USB_VIDEO_INTERVAL_H
#define UVCLIVE_USB_VIDEO_INTERVAL_H

#include <stdint.h>
#include <math.h>

/* Use declared NTSC intervals for nominal integer rates, retain fractional rates,
 * and permit an undeclared interval only for an explicit custom request. */
static inline uint32_t usb_video_interval(double fps, const uint32_t *intervals,
                                         uint32_t minimum, uint32_t maximum,
                                         uint32_t step, int allow_unlisted) {
    if (!isfinite(fps) || fps < 1.0 || fps > 240.0) return 0;
    const uint32_t requested = (uint32_t)llround(10000000.0 / fps);
    const int nominal = fabs(fps - round(fps)) < 0.000001;
    uint32_t best = 0;
    double distance = 1e30;
    if (intervals) {
        for (const uint32_t *p = intervals; *p; ++p) {
            const double delta = fabs((double)*p - requested);
            if ((delta <= 1.0 || (nominal && llround(10000000.0 / *p) == llround(fps))) && delta < distance) {
                best = *p; distance = delta;
            }
        }
        if (best) return best;
    } else if (minimum && minimum <= maximum && requested >= minimum && requested <= maximum) {
        if (!step || (requested - minimum) % step == 0) return requested;
        const uint64_t nearest = (uint64_t)minimum +
            (uint64_t)llround((requested - minimum) / (double)step) * step;
        if (nearest <= maximum && fabs((double)nearest - requested) <= 1.0) return (uint32_t)nearest;
    }
    return allow_unlisted ? requested : 0;
}

#endif
