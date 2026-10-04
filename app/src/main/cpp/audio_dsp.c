/* Adapted from LiveAudioProcess/native_audio.c: BS.1770 K-weighted,
 * gated loudness + linked lookahead limiter (FFmpeg af_alimiter algorithm).
 * Independent state per capture session; called only by the audio worker.
 * PCM stays float between effects; double is used for metering precision. */
#include "audio_dsp.h"
#include "loudness_peak.h"
#include <stdlib.h>
#define LOUDNESS_SUBBLOCKS 30
#define LOUDNESS_GATE_BLOCKS 300
#define LOUDNESS_LRA_BLOCKS 30
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
struct AudioDsp {
    int rate, channels, loudness, limiter;
    float parameters[AUDIO_DSP_PARAM_COUNT];
    float *lookahead;
    int look_size, look_pos;
    int *limiter_next_pos;
    float *limiter_next_delta;
    int limiter_delay_frames, limiter_next_iter, limiter_next_len;
    float limiter_gain, limiter_delta, limiter_peak_activity;
    /* BS.1770 measurement runs on a side-chain and never buffers output. */
    double loudness_b[5], loudness_a[5], loudness_v[2][5];
    double loudness_subblocks[LOUDNESS_SUBBLOCKS];
    double loudness_gate_blocks[LOUDNESS_GATE_BLOCKS];
    double loudness_lra_blocks[LOUDNESS_LRA_BLOCKS];
    double loudness_subblock_sum;
    int loudness_subblock_frames, loudness_subblock_count;
    int loudness_update_subblocks;
    int loudness_subblock_pos, loudness_subblock_valid;
    int loudness_gate_pos, loudness_gate_valid;
    int loudness_lra_pos, loudness_lra_valid, loudness_lra_hop;
    double loudness_measured_lra;
    double loudness_gain, loudness_desired_gain, loudness_gain_coeff;
    double loudness_tp_limit;
    double loudness_tp_db;
    LoudnessPeakLookahead loudness_peak;
};
static float db_to_linear(float db) { return powf(10.f, db / 20.f); }
static float clampf(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
static void limiter_reset(AudioDsp *s, int delay_frames) {
    memset(s->lookahead, 0, (size_t)s->look_size * sizeof(*s->lookahead));
    memset(s->limiter_next_delta, 0, (size_t)s->look_size * sizeof(*s->limiter_next_delta));
    for (int i = 0; i < s->look_size; i++) s->limiter_next_pos[i] = -1;
    s->look_pos = 0;
    s->limiter_delay_frames = delay_frames;
    s->limiter_next_iter = 0;
    s->limiter_next_len = 0;
    s->limiter_gain = 1.0;
    s->limiter_delta = 0.0;
    s->limiter_peak_activity = 0.0;
}

static double loudness_to_energy(double loudness) {
    return pow(10.0, (loudness + 0.691) / 10.0);
}

static double loudness_from_energy(double energy) {
    return 10.0 * log10(fmax(energy, 1.0e-12)) - 0.691;
}

static double loudness_recent_average(const double *values, int size,
                                      int next_pos, int count) {
    double sum = 0.0;
    if (count > size) count = size;
    for (int i = 1; i <= count; ++i) {
        int index = next_pos - i;
        if (index < 0) index += size;
        sum += values[index];
    }
    return count > 0 ? sum / (double)count : 0.0;
}

/* Coefficients match FFmpeg's ebur128 K-weighting filter. The filtered signal
 * is used only for measurement, so its phase response adds no output delay. */
static void loudness_init_filter(AudioDsp *s, int rate) {
    double f0 = 1681.974450955533;
    double gain = 3.999843853973347;
    double q = 0.7071752369554196;
    double k = tan(M_PI * f0 / (double)rate);
    double vh = pow(10.0, gain / 20.0);
    double vb = pow(vh, 0.4996667741545416);
    double pb[3], pa[3] = {1.0, 0.0, 0.0};
    double rb[3] = {1.0, -2.0, 1.0}, ra[3] = {1.0, 0.0, 0.0};
    double a0 = 1.0 + k / q + k * k;
    pb[0] = (vh + vb * k / q + k * k) / a0;
    pb[1] = 2.0 * (k * k - vh) / a0;
    pb[2] = (vh - vb * k / q + k * k) / a0;
    pa[1] = 2.0 * (k * k - 1.0) / a0;
    pa[2] = (1.0 - k / q + k * k) / a0;

    f0 = 38.13547087602444;
    q = 0.5003270373238773;
    k = tan(M_PI * f0 / (double)rate);
    a0 = 1.0 + k / q + k * k;
    ra[1] = 2.0 * (k * k - 1.0) / a0;
    ra[2] = (1.0 - k / q + k * k) / a0;

    s->loudness_b[0] = pb[0] * rb[0];
    s->loudness_b[1] = pb[0] * rb[1] + pb[1] * rb[0];
    s->loudness_b[2] = pb[0] * rb[2] + pb[1] * rb[1] + pb[2] * rb[0];
    s->loudness_b[3] = pb[1] * rb[2] + pb[2] * rb[1];
    s->loudness_b[4] = pb[2] * rb[2];
    s->loudness_a[0] = 1.0;
    s->loudness_a[1] = ra[1] + pa[1];
    s->loudness_a[2] = ra[2] + pa[1] * ra[1] + pa[2];
    s->loudness_a[3] = pa[1] * ra[2] + pa[2] * ra[1];
    s->loudness_a[4] = pa[2] * ra[2];
}

static void loudness_reset_lookahead(AudioDsp *s, int delay_frames) {
    loudness_peak_reset(&s->loudness_peak, delay_frames);
}

static void loudness_reset(AudioDsp *s) {
    memset(s->loudness_v, 0, sizeof(s->loudness_v));
    memset(s->loudness_subblocks, 0, sizeof(s->loudness_subblocks));
    memset(s->loudness_gate_blocks, 0, sizeof(s->loudness_gate_blocks));
    memset(s->loudness_lra_blocks, 0, sizeof(s->loudness_lra_blocks));
    s->loudness_subblock_sum = 0.0;
    s->loudness_subblock_count = 0;
    s->loudness_update_subblocks = 0;
    s->loudness_subblock_pos = s->loudness_subblock_valid = 0;
    s->loudness_gate_pos = s->loudness_gate_valid = 0;
    s->loudness_lra_pos = s->loudness_lra_valid = s->loudness_lra_hop = 0;
    s->loudness_measured_lra = 0.0;
    s->loudness_gain = s->loudness_desired_gain = 1.0;
    s->loudness_gain_coeff = 0.0;
    loudness_reset_lookahead(s, s->loudness_peak.delay_frames);
    s->loudness_tp_limit = 1.0;
    s->loudness_tp_db = 1000.0;
}

static void loudness_init(AudioDsp *s, int rate) {
    int safe_rate = rate > 0 ? rate : 48000;
    int delay_frames = (safe_rate * 5 + 999) / 1000;
    loudness_peak_reset(&s->loudness_peak, delay_frames);
    loudness_init_filter(s, safe_rate);
    s->loudness_subblock_frames = (safe_rate + 5) / 10;
    if (s->loudness_subblock_frames < 1) s->loudness_subblock_frames = 1;
    s->loudness_peak.release_coeff = 1.0 -
            exp(-1.0 / ((double)safe_rate * 0.100));
    loudness_reset(s);
}

static double loudness_filter_sample(AudioDsp *s, int channel, double sample) {
    double *v = s->loudness_v[channel];
    v[0] = sample - s->loudness_a[1] * v[1] - s->loudness_a[2] * v[2]
                  - s->loudness_a[3] * v[3] - s->loudness_a[4] * v[4];
    double output = s->loudness_b[0] * v[0] + s->loudness_b[1] * v[1]
                  + s->loudness_b[2] * v[2] + s->loudness_b[3] * v[3]
                  + s->loudness_b[4] * v[4];
    v[4] = v[3]; v[3] = v[2]; v[2] = v[1]; v[1] = v[0];
    return output;
}

static void loudness_update_lra(AudioDsp *s, double short_term_energy) {
    if (++s->loudness_lra_hop < 10) return;
    s->loudness_lra_hop = 0;
    s->loudness_lra_blocks[s->loudness_lra_pos] = short_term_energy;
    s->loudness_lra_pos = (s->loudness_lra_pos + 1) % LOUDNESS_LRA_BLOCKS;
    if (s->loudness_lra_valid < LOUDNESS_LRA_BLOCKS) s->loudness_lra_valid++;

    const double absolute_gate = loudness_to_energy(-70.0);
    double sum = 0.0;
    int count = 0;
    for (int i = 0; i < s->loudness_lra_valid; ++i) {
        if (s->loudness_lra_blocks[i] >= absolute_gate) {
            sum += s->loudness_lra_blocks[i];
            count++;
        }
    }
    if (count < 4) return;
    double gate = fmax(absolute_gate, (sum / (double)count) * 0.01);
    double sorted[LOUDNESS_LRA_BLOCKS];
    int sorted_count = 0;
    for (int i = 0; i < s->loudness_lra_valid; ++i) {
        double energy = s->loudness_lra_blocks[i];
        if (energy < gate) continue;
        double loudness = loudness_from_energy(energy);
        int insert = sorted_count;
        while (insert > 0 && sorted[insert - 1] > loudness) {
            sorted[insert] = sorted[insert - 1];
            insert--;
        }
        sorted[insert] = loudness;
        sorted_count++;
    }
    if (sorted_count < 4) return;
    int low = (int)floor(0.10 * (double)(sorted_count - 1));
    int high = (int)ceil(0.95 * (double)(sorted_count - 1));
    s->loudness_measured_lra = sorted[high] - sorted[low];
}

static void loudness_update_target(AudioDsp *s, const float *p) {
    /* The gate and LRA still receive every 100 ms measurement block. */
    int interval_subblocks = (int)(clampf(p[P_LOUDNESS_UPDATE_MS], 100.f, 3000.f) /
                                   100.f + 0.5f);
    s->loudness_update_subblocks++;
    int update_due = s->loudness_update_subblocks >= interval_subblocks;
    if (update_due) s->loudness_update_subblocks %= interval_subblocks;
    if (s->loudness_subblock_valid < 4) return;

    double momentary_energy = loudness_recent_average(
            s->loudness_subblocks, LOUDNESS_SUBBLOCKS,
            s->loudness_subblock_pos, 4);
    s->loudness_gate_blocks[s->loudness_gate_pos] = momentary_energy;
    s->loudness_gate_pos = (s->loudness_gate_pos + 1) % LOUDNESS_GATE_BLOCKS;
    if (s->loudness_gate_valid < LOUDNESS_GATE_BLOCKS) s->loudness_gate_valid++;

    int short_count = s->loudness_subblock_valid < LOUDNESS_SUBBLOCKS ?
                      s->loudness_subblock_valid : LOUDNESS_SUBBLOCKS;
    double short_term_energy = loudness_recent_average(
            s->loudness_subblocks, LOUDNESS_SUBBLOCKS,
            s->loudness_subblock_pos, short_count);
    double short_term = loudness_from_energy(short_term_energy);
    if (short_term >= -60.0 && short_count == LOUDNESS_SUBBLOCKS)
        loudness_update_lra(s, short_term_energy);
    if (!update_due || short_term < -60.0) return;

    /* BS.1770 integrated gate: first reject blocks below -70 LUFS, then reject
     * blocks more than 10 LU below the absolute-gated mean. */
    const double absolute_gate = loudness_to_energy(-70.0);
    double absolute_sum = 0.0;
    int absolute_count = 0;
    for (int i = 0; i < s->loudness_gate_valid; ++i) {
        double energy = s->loudness_gate_blocks[i];
        if (energy >= absolute_gate) { absolute_sum += energy; absolute_count++; }
    }
    if (absolute_count == 0) return;
    double relative_gate = (absolute_sum / (double)absolute_count) * 0.1;
    double gate = fmax(absolute_gate, relative_gate);
    double gated_sum = 0.0;
    int gated_count = 0;
    for (int i = 0; i < s->loudness_gate_valid; ++i) {
        double energy = s->loudness_gate_blocks[i];
        if (energy >= gate) { gated_sum += energy; gated_count++; }
    }
    if (gated_count == 0) return;

    double integrated = loudness_from_energy(gated_sum / (double)gated_count);
    double target = clampf(p[P_LOUDNESS_TARGET], -70.f, -5.f);
    double target_lra = clampf(p[P_LOUDNESS_LRA], 1.f, 50.f);
    double deviation = short_term - integrated;
    double dynamic_correction = 0.0;
    if (s->loudness_measured_lra > target_lra) {
        double range_ratio = target_lra / s->loudness_measured_lra;
        dynamic_correction = deviation * (range_ratio - 1.0);
    }
    dynamic_correction = fmin(fmax(dynamic_correction, -6.0), 6.0);
    double gain_db = fmin(fmax(target - integrated + dynamic_correction,
                              -12.0), 18.0);
    if (p[P_LOUDNESS_BOOST_ONLY] >= 0.5f)
        gain_db = fmax(gain_db, 0.0);
    s->loudness_desired_gain = pow(10.0, gain_db / 20.0);
    double gain_tau = s->loudness_desired_gain < s->loudness_gain ? 0.080 :
                      fmin(1.500, 0.250 + 0.035 * target_lra);
    s->loudness_gain_coeff = 1.0 - exp(-1.0 / ((double)s->rate * gain_tau));
}

/* K-weighted, gated loudness measurement and linked-stereo gain control.
 * The configured 5-50 ms delay ramps peak attenuation before a transient. */
static void loudness_process(AudioDsp *s, float *l, float *r, const float *p) {
    double target_tp = clampf(p[P_LOUDNESS_TP], -9.f, 0.f);
    if (target_tp != s->loudness_tp_db) {
        s->loudness_tp_db = target_tp;
        s->loudness_tp_limit = pow(10.0, target_tp / 20.0);
    }
    double weighted_l = loudness_filter_sample(s, 0, *l);
    double weighted_r = s->channels == 2 ? loudness_filter_sample(s, 1, *r) : 0.0;
    s->loudness_subblock_sum += weighted_l * weighted_l + weighted_r * weighted_r;
    if (++s->loudness_subblock_count >= s->loudness_subblock_frames) {
        double energy = s->loudness_subblock_sum /
                        (double)s->loudness_subblock_count;
        s->loudness_subblocks[s->loudness_subblock_pos] = energy;
        s->loudness_subblock_pos = (s->loudness_subblock_pos + 1) % LOUDNESS_SUBBLOCKS;
        if (s->loudness_subblock_valid < LOUDNESS_SUBBLOCKS)
            s->loudness_subblock_valid++;
        s->loudness_subblock_sum = 0.0;
        s->loudness_subblock_count = 0;
        loudness_update_target(s, p);
    }

    s->loudness_gain += (s->loudness_desired_gain - s->loudness_gain) *
                       s->loudness_gain_coeff;
    s->loudness_gain = fmin(fmax(s->loudness_gain, 0.0630957), 7.94328);

    float applied_gain;
    loudness_peak_process(&s->loudness_peak, l, r, s->loudness_gain,
                          s->loudness_tp_limit,
                          p[P_LOUDNESS_BOOST_ONLY] >= 0.5f, &applied_gain);
}

/*
 * Linked-stereo lookahead limiter adapted from FFmpeg af_alimiter's
 * scheduled-peak attenuation algorithm. Each detected peak installs an
 * attack ramp that reaches the required gain when that peak leaves the ring.
 */
static void limiter_process(AudioDsp *s, float *l, float *r, const float *p) {
    const int channels = 2;
    float input_gain = db_to_linear(p[P_LIM_IN]);
    double threshold = fmin(fmax(pow(10.0, (double)p[P_LIMIT] / 20.0), 0.000001), 1.0);
    double ceiling = fmin(fmax(pow(10.0, (double)p[P_CEILING] / 20.0), 0.000001), 1.0);
    double limit = fmin(threshold, ceiling);
    double base_release = fmax((double)p[P_RELEASE], 10.0) / 1000.0;
    int adaptive_release = p[P_ADAPTIVE_RELEASE] >= 0.5f;
    int delay_frames = (int)(clampf(p[P_LOOKAHEAD], 0.f, 50.f) * s->rate / 1000.f);
    int max_delay_frames = s->look_size / channels;
    if (delay_frames < 1) delay_frames = 1;
    if (delay_frames > max_delay_frames) delay_frames = max_delay_frames;
    int buffer_size = delay_frames * channels;

    if (delay_frames != s->limiter_delay_frames) limiter_reset(s, delay_frames);

    float x0 = *l * input_gain;
    float x1 = *r * input_gain;
    float peak = fmaxf(fabsf(x0), fabsf(x1));
    s->lookahead[s->look_pos] = x0;
    s->lookahead[s->look_pos + 1] = x1;

    /* A smoothed overload activity tracks peak duration without resetting at
       waveform zero crossings. Transients use 0.5x release; sustained peaks
       approach 2x release. The final time remains inside the UI's range. */
    if (adaptive_release) {
        if ((double)peak > limit)
            s->limiter_peak_activity += (1.0 - s->limiter_peak_activity) /
                                       ((double)s->rate * 0.150);
        else
            s->limiter_peak_activity -= s->limiter_peak_activity /
                                       ((double)s->rate * 0.400);
        s->limiter_peak_activity = fmin(fmax(s->limiter_peak_activity, 0.0), 1.0);
    } else {
        s->limiter_peak_activity = 0.0;
    }
    double release_scale = adaptive_release ?
            0.5 + 1.5 * s->limiter_peak_activity : 1.0;
    double release = fmin(fmax(base_release * release_scale, 0.010), 10.0);

    if (peak > limit) {
        double target_gain = limit / (double)peak;
        double release_delta = (1.0 - target_gain) / ((double)s->rate * release);
        double attack_delta = (target_gain - s->limiter_gain) / (double)delay_frames;

        if (attack_delta < s->limiter_delta) {
            s->limiter_delta = attack_delta;
            s->limiter_next_pos[0] = s->look_pos;
            if (buffer_size > 1) s->limiter_next_pos[1] = -1;
            s->limiter_next_delta[0] = release_delta;
            s->limiter_next_len = 1;
            s->limiter_next_iter = 0;
        } else {
            int found = 0;
            int i;
            for (i = s->limiter_next_iter;
                 i < s->limiter_next_iter + s->limiter_next_len; i++) {
                int j = i % buffer_size;
                int scheduled_pos = s->limiter_next_pos[j];
                if (scheduled_pos < 0) continue;
                float scheduled_peak = fmaxf(fabsf(s->lookahead[scheduled_pos]),
                                              fabsf(s->lookahead[scheduled_pos + 1]));
                int distance_frames = ((buffer_size - scheduled_pos + s->look_pos) %
                                       buffer_size) / channels;
                if (scheduled_peak <= 0.f || distance_frames <= 0) continue;
                double scheduled_delta = (target_gain - limit / (double)scheduled_peak) /
                                         (double)distance_frames;
                if (scheduled_delta < s->limiter_next_delta[j]) {
                    s->limiter_next_delta[j] = scheduled_delta;
                    found = 1;
                    break;
                }
            }
            if (found) {
                s->limiter_next_len = i - s->limiter_next_iter + 1;
                int insert = (s->limiter_next_iter + s->limiter_next_len) % buffer_size;
                int sentinel = (insert + 1) % buffer_size;
                s->limiter_next_pos[insert] = s->look_pos;
                s->limiter_next_delta[insert] = release_delta;
                s->limiter_next_pos[sentinel] = -1;
                s->limiter_next_len++;
            }
        }
    }

    int output_pos = (s->look_pos + channels) % buffer_size;
    float out0 = s->lookahead[output_pos];
    float out1 = s->lookahead[output_pos + 1];
    float output_peak = fmaxf(fabsf(out0), fabsf(out1));

    s->limiter_gain += s->limiter_delta;
    // Float envelope rounding must not clip one channel independently: apply
    // the same final peak guard to both channels before quantizing PCM.
    float output_gain = fminf(s->limiter_gain,
            output_peak > 0.f ? (float)(limit / output_peak) : 1.f);
    output_gain = fmaxf(output_gain, 0.f);
    *l = out0 * output_gain;
    *r = out1 * output_gain;

    if (s->limiter_next_len > 0 &&
        output_pos == s->limiter_next_pos[s->limiter_next_iter]) {
        s->limiter_delta = s->limiter_next_delta[s->limiter_next_iter];
        s->limiter_gain = output_peak > 0.f ? fmin(limit / (double)output_peak, 1.0) : 1.0;
        s->limiter_next_len--;
        s->limiter_next_pos[s->limiter_next_iter] = -1;
        s->limiter_next_iter = (s->limiter_next_iter + 1) % buffer_size;
    }

    /* Numerical guard rails copied from af_alimiter: keep the envelope
       finite and avoid spending time accumulating sub-ULP deltas. */
    if (s->limiter_gain > 1.0) {
        s->limiter_gain = 1.0;
        s->limiter_delta = 0.0;
        s->limiter_next_iter = 0;
        s->limiter_next_len = 0;
        s->limiter_next_pos[0] = -1;
    }
    if (s->limiter_gain <= 0.0) {
        s->limiter_gain = 0.0000000000001;
        s->limiter_delta = (1.0 - s->limiter_gain) / ((double)s->rate * release);
    }
    if (s->limiter_gain != 1.0 && (1.0 - s->limiter_gain) < 0.0000000000001)
        s->limiter_gain = 1.0;
    if (s->limiter_delta != 0.0 && fabs(s->limiter_delta) < 0.00000000000001)
        s->limiter_delta = 0.0;

    *l = clampf(*l, -ceiling, ceiling);
    *r = clampf(*r, -ceiling, ceiling);
    s->look_pos = output_pos;
}


AudioDsp *audio_dsp_create(int rate, int channels, int loudness, int limiter, const float *p) {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2 || !p) return NULL;
    for (int i = 0; i < AUDIO_DSP_PARAM_COUNT; ++i) if (!isfinite(p[i])) return NULL;
    AudioDsp *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->rate = rate; s->channels = channels; s->loudness = loudness; s->limiter = limiter;
    memcpy(s->parameters, p, sizeof(s->parameters));
    s->look_size = ((rate * 50 + 999) / 1000 + 1) * 2;
    s->lookahead = calloc(s->look_size, sizeof(*s->lookahead));
    s->limiter_next_pos = calloc(s->look_size, sizeof(*s->limiter_next_pos));
    s->limiter_next_delta = calloc(s->look_size, sizeof(*s->limiter_next_delta));
    if (!s->lookahead || !s->limiter_next_pos || !s->limiter_next_delta) {
        audio_dsp_destroy(s); return NULL;
    }
    loudness_init(s, rate);
    loudness_reset_lookahead(s, (int)(clampf(p[P_LOUDNESS_LOOKAHEAD], 5.f, 50.f) * rate / 1000.f));
    limiter_reset(s, (int)(clampf(p[P_LOOKAHEAD], 0.f, 50.f) * rate / 1000.f));
    if (s->limiter_delay_frames < 1) s->limiter_delay_frames = 1;
    return s;
}
void audio_dsp_destroy(AudioDsp *s) {
    if (!s) return;
    free(s->lookahead); free(s->limiter_next_pos); free(s->limiter_next_delta); free(s);
}
int audio_dsp_delay(const AudioDsp *s) {
    return (s->loudness ? s->loudness_peak.delay_frames : 0) +
           (s->limiter ? s->limiter_delay_frames - 1 : 0);
}
void audio_dsp_update(AudioDsp *s, const float *parameters) {
    for (int i = 0; i < AUDIO_DSP_PARAM_COUNT; ++i) if (!isfinite(parameters[i])) return;
    memcpy(s->parameters, parameters, sizeof(s->parameters));
}
void audio_dsp_process_float(AudioDsp *s, const float *input, float *output, int frames) {
    for (int i = 0; i < frames; ++i) {
        float l = input[i * s->channels];
        float r = s->channels == 2 ? input[i * 2 + 1] : l;
        if (s->loudness) loudness_process(s, &l, &r, s->parameters);
        if (s->limiter) limiter_process(s, &l, &r, s->parameters);
        output[i * s->channels] = l;
        if (s->channels == 2) output[i * 2 + 1] = r;
    }
}
