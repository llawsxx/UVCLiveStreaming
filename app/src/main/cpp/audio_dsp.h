#ifndef UVCLIVE_AUDIO_DSP_H
#define UVCLIVE_AUDIO_DSP_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct AudioDsp AudioDsp;
/* Parameter order shared with AudioDspSettings.nativeParameters(). */
enum {
    P_LIM_IN, P_LIMIT, P_RELEASE, P_CEILING, P_LOOKAHEAD, P_ADAPTIVE_RELEASE,
    P_LOUDNESS_TARGET, P_LOUDNESS_LRA, P_LOUDNESS_TP, P_LOUDNESS_BOOST_ONLY,
    P_LOUDNESS_LOOKAHEAD, P_LOUDNESS_UPDATE_MS, AUDIO_DSP_PARAM_COUNT
};
AudioDsp *audio_dsp_create(int rate, int channels, int loudness, int limiter, const float *parameters);
void audio_dsp_destroy(AudioDsp *state);
int audio_dsp_delay(const AudioDsp *state);
/* Worker-only parameter update; enable flags and delay stay unchanged. */
void audio_dsp_update(AudioDsp *state, const float *parameters);
/* Float PCM throughout both effects; input and output may alias. */
void audio_dsp_process_float(AudioDsp *state, const float *input, float *output, int frames);
#ifdef __cplusplus
}
#endif
#endif
