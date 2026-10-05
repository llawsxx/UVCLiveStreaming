#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#include "audio_dsp.h"

struct PcmDsp {
    AudioDsp *state;
    int channels;
    std::vector<uint8_t> pcm;
    std::vector<float> samples;
    ~PcmDsp() { audio_dsp_destroy(state); }
};
extern "C" JNIEXPORT jlong JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeAudioDsp_create(
        JNIEnv *env, jobject, jint rate, jint channels, jboolean loudness,
        jboolean limiter, jfloatArray parameters) {
    if (!parameters || env->GetArrayLength(parameters) != AUDIO_DSP_PARAM_COUNT) return 0;
    float p[AUDIO_DSP_PARAM_COUNT];
    env->GetFloatArrayRegion(parameters, 0, AUDIO_DSP_PARAM_COUNT, p);
    AudioDsp *state = audio_dsp_create(rate, channels, loudness, limiter, p);
    if (!state) return 0;
    return reinterpret_cast<jlong>(new PcmDsp{state, channels, {}, {}});
}
extern "C" JNIEXPORT jint JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeAudioDsp_delayFrames(
        JNIEnv *, jobject, jlong handle) {
    return audio_dsp_delay(reinterpret_cast<PcmDsp *>(handle)->state);
}
extern "C" JNIEXPORT void JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeAudioDsp_update(
        JNIEnv *env, jobject, jlong handle, jfloatArray parameters) {
    if (env->GetArrayLength(parameters) != AUDIO_DSP_PARAM_COUNT) return;
    float p[AUDIO_DSP_PARAM_COUNT];
    env->GetFloatArrayRegion(parameters, 0, AUDIO_DSP_PARAM_COUNT, p);
    audio_dsp_update(reinterpret_cast<PcmDsp *>(handle)->state, p);
}
extern "C" JNIEXPORT void JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeAudioDsp_process(
        JNIEnv *env, jobject, jlong handle, jbyteArray bytes) {
    auto *dsp = reinterpret_cast<PcmDsp *>(handle);
    const int size = env->GetArrayLength(bytes);
    if (size % (dsp->channels * 2) != 0) return;
    dsp->pcm.resize(size);
    dsp->samples.resize(size / 2);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(dsp->pcm.data()));
    for (int i = 0; i < size / 2; ++i) {
        const auto value = static_cast<int16_t>(dsp->pcm[i * 2] | (dsp->pcm[i * 2 + 1] << 8));
        dsp->samples[i] = value / 32768.f;
    }
    audio_dsp_process_float(dsp->state, dsp->samples.data(), dsp->samples.data(), size / (dsp->channels * 2));
    for (int i = 0; i < size / 2; ++i) {
        float sample = dsp->samples[i];
        if (!std::isfinite(sample)) sample = 0.f;
        const int value = static_cast<int>(std::lrintf(std::clamp(sample, -1.f, 32767.f / 32768.f) * 32768.f));
        dsp->pcm[i * 2] = static_cast<uint8_t>(value);
        dsp->pcm[i * 2 + 1] = static_cast<uint8_t>(value >> 8);
    }
    env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(dsp->pcm.data()));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeAudioDsp_processWide(
        JNIEnv *env, jobject, jlong handle, jbyteArray bytes, jint sample_bytes) {
    auto *dsp = reinterpret_cast<PcmDsp *>(handle);
    if (!dsp || !bytes || sample_bytes < 2 || sample_bytes > 4) return nullptr;
    const int size = env->GetArrayLength(bytes);
    if (size % (dsp->channels * sample_bytes) != 0) return nullptr;
    const int samples = size / sample_bytes;
    dsp->pcm.resize(size);
    dsp->samples.resize(samples);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(dsp->pcm.data()));
    if (env->ExceptionCheck()) return nullptr;
    const float divisor = sample_bytes == 2 ? 32768.f : sample_bytes == 3 ? 8388608.f : 2147483648.f;
    for (int i = 0; i < samples; ++i) {
        uint32_t raw = 0;
        for (int b = 0; b < sample_bytes; ++b) raw |= uint32_t(dsp->pcm[i * sample_bytes + b]) << (8 * b);
        if (sample_bytes == 3 && (raw & 0x800000u)) raw |= 0xff000000u;
        const int32_t value = sample_bytes == 2 ? int16_t(raw) : int32_t(raw);
        dsp->samples[i] = value / divisor;
    }
    audio_dsp_process_float(dsp->state, dsp->samples.data(), dsp->samples.data(), samples / dsp->channels);
    auto output = env->NewByteArray(samples * 2);
    if (!output) return nullptr;
    dsp->pcm.resize(samples * 2);
    for (int i = 0; i < samples; ++i) {
        float sample = dsp->samples[i];
        if (!std::isfinite(sample)) sample = 0.f;
        const int value = static_cast<int>(std::lrintf(std::clamp(sample, -1.f, 32767.f / 32768.f) * 32768.f));
        dsp->pcm[i * 2] = static_cast<uint8_t>(value);
        dsp->pcm[i * 2 + 1] = static_cast<uint8_t>(value >> 8);
    }
    env->SetByteArrayRegion(output, 0, samples * 2, reinterpret_cast<const jbyte *>(dsp->pcm.data()));
    return output;
}
extern "C" JNIEXPORT void JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeAudioDsp_destroy(
        JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<PcmDsp *>(handle);
}
