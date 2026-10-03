#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "h26x_vui_rewriter.h"

JNIEXPORT jbyteArray JNICALL
Java_com_llawsxx_uvclivestreaming_recording_H26xVuiRewriter_nativeRewrite(
        JNIEnv *env, jclass clazz, jbyteArray input, jint codec,
        jint nal_length_size, jint full_range, jint colour_primaries,
        jint transfer_characteristics, jint matrix_coefficients) {
    (void)clazz;
    if (!input) return NULL;
    jsize input_size = (*env)->GetArrayLength(env, input);
    jbyte *input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (!input_bytes) return NULL;
    H26xVuiColor color = {
        full_range, colour_primaries, transfer_characteristics,
        matrix_coefficients
    };
    uint8_t *output = NULL;
    size_t output_size = 0;
    int result = h26x_rewrite_vui((const uint8_t *)input_bytes,
                                  (size_t)input_size, codec, nal_length_size,
                                  &color, &output, &output_size);
    int unchanged = result == 0 && output_size == (size_t)input_size &&
        memcmp(input_bytes, output, output_size) == 0;
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    if (result < 0 || output_size > INT32_MAX) {
        free(output);
        return NULL;
    }
    if (unchanged) {
        free(output);
        return input;
    }
    jbyteArray array = (*env)->NewByteArray(env, (jsize)output_size);
    if (array)
        (*env)->SetByteArrayRegion(env, array, 0, (jsize)output_size,
                                  (const jbyte *)output);
    free(output);
    return array;
}
