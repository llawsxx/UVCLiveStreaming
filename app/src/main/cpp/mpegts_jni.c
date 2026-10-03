#include <jni.h>
#include <stdint.h>

#include "mpegts_muxer.h"

static TsMuxer *from_handle(jlong handle) {
    return (TsMuxer *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeMpegTsMuxer_nativeCreate(
        JNIEnv *env, jclass clazz, jint video_codec, jboolean has_audio,
        jint sample_rate, jint channels, jint aac_object_type) {
    (void)env;
    (void)clazz;
    return (jlong)(intptr_t)ts_muxer_create(video_codec, has_audio, sample_rate,
                                            channels, aac_object_type);
}

JNIEXPORT void JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeMpegTsMuxer_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    ts_muxer_destroy(from_handle(handle));
}

static jbyte *get_bytes(JNIEnv *env, jbyteArray array, jsize *size) {
    if (!array) {
        *size = 0;
        return NULL;
    }
    *size = (*env)->GetArrayLength(env, array);
    return (*env)->GetByteArrayElements(env, array, NULL);
}

static void release_bytes(JNIEnv *env, jbyteArray array, jbyte *bytes) {
    if (array && bytes)
        (*env)->ReleaseByteArrayElements(env, array, bytes, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeMpegTsMuxer_nativeSetVideoConfig(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray csd0,
        jbyteArray csd1, jbyteArray csd2) {
    (void)clazz;
    jsize size0, size1, size2;
    jbyte *data0 = get_bytes(env, csd0, &size0);
    jbyte *data1 = get_bytes(env, csd1, &size1);
    jbyte *data2 = get_bytes(env, csd2, &size2);
    int result = ts_muxer_set_video_config(
        from_handle(handle), (const uint8_t *)data0, (size_t)size0,
        (const uint8_t *)data1, (size_t)size1,
        (const uint8_t *)data2, (size_t)size2);
    release_bytes(env, csd0, data0);
    release_bytes(env, csd1, data1);
    release_bytes(env, csd2, data2);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray output_array(JNIEnv *env, const uint8_t *output, size_t size,
                               int result) {
    if (result < 0 || !output || size > INT32_MAX)
        return NULL;
    jbyteArray array = (*env)->NewByteArray(env, (jsize)size);
    if (array)
        (*env)->SetByteArrayRegion(env, array, 0, (jsize)size,
                                  (const jbyte *)output);
    return array;
}

JNIEXPORT jbyteArray JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeMpegTsMuxer_nativeWriteVideo(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray sample,
        jlong pts_us, jboolean key_frame) {
    (void)clazz;
    jsize size;
    jbyte *data = get_bytes(env, sample, &size);
    const uint8_t *output = NULL;
    size_t output_size = 0;
    int result = ts_muxer_write_video(from_handle(handle),
                                      (const uint8_t *)data, (size_t)size,
                                      pts_us, key_frame, &output, &output_size);
    release_bytes(env, sample, data);
    return output_array(env, output, output_size, result);
}

JNIEXPORT jbyteArray JNICALL
Java_com_llawsxx_uvclivestreaming_recording_NativeMpegTsMuxer_nativeWriteAudio(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray sample,
        jlong pts_us) {
    (void)clazz;
    jsize size;
    jbyte *data = get_bytes(env, sample, &size);
    const uint8_t *output = NULL;
    size_t output_size = 0;
    int result = ts_muxer_write_audio(from_handle(handle),
                                      (const uint8_t *)data, (size_t)size,
                                      pts_us, &output, &output_size);
    release_bytes(env, sample, data);
    return output_array(env, output, output_size, result);
}
