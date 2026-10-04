#include "audio_dsp.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); exit(1); } } while (0)
#define PI 3.14159265358979323846
static float params[AUDIO_DSP_PARAM_COUNT] = {0, -.5f, 80, -.5f, 1, 0, -16, 7, -1, 0, 5, 1000};

static void delay_and_bypass(void) {
    for (int effects = 0; effects < 4; ++effects) {
        AudioDsp *s = audio_dsp_create(48000, 2, effects & 1, effects & 2, params);
        CHECK(s);
        int delay = audio_dsp_delay(s);
        float input[1200] = {0}, output[1200];
        input[0] = .1f; input[1] = -.025f;
        audio_dsp_process_float(s, input, output, 600);
        for (int i = 0; i < delay * 2; ++i) CHECK(output[i] == 0.f);
        CHECK(fabsf(output[delay * 2] - .1f) < 1e-6f);
        CHECK(fabsf(output[delay * 2 + 1] + .025f) < 1e-6f);
        if (!effects) CHECK(memcmp(input, output, sizeof(input)) == 0);
        audio_dsp_destroy(s);
    }
    puts("PASS bypass and exact lookahead delay");
}

static void limiter_and_chunking(void) {
    const int rates[] = {8000, 16000, 44100, 48000, 96000, 192000};
    for (int ri = 0; ri < 6; ++ri) {
        for (int channels = 1; channels <= 2; ++channels) {
            float p[AUDIO_DSP_PARAM_COUNT]; memcpy(p, params, sizeof(p));
            p[P_LIM_IN] = 12; p[P_CEILING] = -3; p[P_ADAPTIVE_RELEASE] = 1;
            p[P_LOOKAHEAD] = ri % 2 ? 50 : 0;
            AudioDsp *one = audio_dsp_create(rates[ri], channels, 0, 1, p);
            AudioDsp *chunks = audio_dsp_create(rates[ri], channels, 0, 1, p);
            CHECK(one && chunks);
            int frames = rates[ri];
            float *input = calloc(frames * channels, sizeof(float));
            float *a = calloc(frames * channels, sizeof(float));
            float *b = calloc(frames * channels, sizeof(float));
            for (int i = 0; i < frames; ++i) {
                input[i * channels] = .95f * sinf((float)(i * 2 * PI * 997 / rates[ri]));
                if (i % 777 == 0) input[i * channels] = 1.f;
                if (channels == 2) input[i * 2 + 1] = input[i * 2] * .25f;
            }
            audio_dsp_process_float(one, input, a, frames);
            for (int pos = 0; pos < frames;) {
                int count = frames - pos < 137 ? frames - pos : 137;
                memcpy(b + pos * channels, input + pos * channels, count * channels * sizeof(float));
                audio_dsp_process_float(chunks, b + pos * channels, b + pos * channels, count);
                pos += count;
            }
            CHECK(memcmp(a, b, frames * channels * sizeof(float)) == 0);
            for (int i = 0; i < frames; ++i) {
                CHECK(isfinite(a[i * channels]));
                CHECK(fabsf(a[i * channels]) <= powf(10.f, -3.f / 20.f) + 1e-6f);
                if (channels == 2 && fabsf(a[i * 2 + 1] - a[i * 2] * .25f) >= 1e-6f) {
                    fprintf(stderr, "rate=%d frame=%d L=%g R=%g\n", rates[ri], i, a[i * 2], a[i * 2 + 1]);
                    CHECK(0);
                }
            }
            free(input); free(a); free(b); audio_dsp_destroy(one); audio_dsp_destroy(chunks);
        }
    }
    puts("PASS limiter ceiling, linked stereo, sample rates and block invariance");
}

static void loudness_and_speed(void) {
    for (int channels = 1; channels <= 2; ++channels) {
        AudioDsp *s = audio_dsp_create(48000, channels, 1, 1, params);
        CHECK(s);
        float in[480 * 2], out[480 * 2];
        double energy = 0;
        struct timespec start, end;
        clock_gettime(CLOCK_MONOTONIC, &start);
        for (int block = 0; block < 2000; ++block) {
            for (int i = 0; i < 480; ++i) {
                float value = .04f * sinf((float)((block * 480 + i) * 2 * PI * 1000 / 48000));
                in[i * channels] = value;
                if (channels == 2) in[i * 2 + 1] = value;
            }
            audio_dsp_process_float(s, in, out, 480);
            for (int i = 0; i < 480 * channels; ++i) {
                CHECK(isfinite(out[i]));
                CHECK(fabsf(out[i]) <= powf(10, -.5f / 20) + 1e-6f);
                if (block >= 1900) energy += out[i] * out[i];
            }
        }
        clock_gettime(CLOCK_MONOTONIC, &end);
        // K weighting at 1 kHz adds approximately 0.697 dB. LUFS combines
        // channel energies; this independently catches accidental mono doubling.
        double lufs = 10 * log10(energy / (100 * 480)) + .697 - .691;
        printf("channels=%d steady loudness=%.3f LUFS, 20s DSP+signal tests=%.3fs\n", channels, lufs,
            end.tv_sec - start.tv_sec + (end.tv_nsec - start.tv_nsec) / 1e9);
        CHECK(fabs(lufs + 16) < .25);
        float updated[AUDIO_DSP_PARAM_COUNT]; memcpy(updated, params, sizeof(updated));
        updated[P_LOUDNESS_TARGET] = -20.f;
        audio_dsp_update(s, updated);
        energy = 0;
        for (int block = 0; block < 1000; ++block) {
            audio_dsp_process_float(s, in, out, 480);
            if (block >= 900) for (int i = 0; i < 480 * channels; ++i) energy += out[i] * out[i];
        }
        lufs = 10 * log10(energy / (100 * 480)) + .697 - .691;
        CHECK(fabs(lufs + 20) < .25);
        audio_dsp_destroy(s);
    }
    // Long silence cannot produce NaNs or self-generated noise.
    AudioDsp *s = audio_dsp_create(48000, 2, 1, 1, params);
    float silence[960] = {0};
    for (int i = 0; i < 1000; ++i) audio_dsp_process_float(s, silence, silence, 480);
    for (int i = 0; i < 960; ++i) CHECK(silence[i] == 0);
    audio_dsp_destroy(s);
    puts("PASS LUFS target convergence, mono energy and silence");
}
static void loudness_all_rates(void) {
    const int rates[] = {8000, 16000, 44100, 48000, 96000, 192000};
    for (int ri = 0; ri < 6; ++ri) {
        float p[AUDIO_DSP_PARAM_COUNT]; memcpy(p, params, sizeof(p));
        p[P_LOUDNESS_LOOKAHEAD] = 50;
        p[P_LOUDNESS_TP] = -3;
        AudioDsp *s = audio_dsp_create(rates[ri], 2, 1, 0, p);
        CHECK(s);
        CHECK(audio_dsp_delay(s) == rates[ri] * 50 / 1000);
        float in[960], out[960];
        int frames = rates[ri] * 10;
        for (int pos = 0; pos < frames; pos += 480) {
            int count = frames - pos < 480 ? frames - pos : 480;
            for (int i = 0; i < count; ++i) {
                float x = .6f * sinf((float)((pos + i) * 2 * PI * 999 / rates[ri]));
                in[i * 2] = x; in[i * 2 + 1] = x * .25f;
            }
            audio_dsp_process_float(s, in, out, count);
            for (int i = 0; i < count; ++i) {
                CHECK(isfinite(out[i * 2]));
                CHECK(fabsf(out[i * 2]) <= powf(10, -3.f / 20) + 1e-6f);
                CHECK(fabsf(out[i * 2 + 1] - out[i * 2] * .25f) < 1e-6f);
            }
        }
        audio_dsp_destroy(s);
    }
    puts("PASS loudness sample rates 8-192 kHz, 50 ms delay and peak protection");
}
int main(void) {
    delay_and_bypass(); limiter_and_chunking(); loudness_and_speed(); loudness_all_rates();
    puts("ALL AUDIO DSP TESTS PASSED");
    return 0;
}
