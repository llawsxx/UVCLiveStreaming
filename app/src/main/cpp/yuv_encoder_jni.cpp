#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {
struct Plane {
    uint8_t *data;
    jlong capacity;
    int row_stride;
    int pixel_stride;

    bool valid(int width, int height) const {
        return data && width > 0 && height > 0 && row_stride > 0 && pixel_stride > 0 &&
            static_cast<int64_t>(width - 1) * pixel_stride + 1 <= row_stride &&
            static_cast<int64_t>(height - 1) * row_stride + static_cast<int64_t>(width - 1) * pixel_stride < capacity;
    }

    void put(int column, int row, uint8_t value) const {
        data[static_cast<size_t>(row) * row_stride + static_cast<size_t>(column) * pixel_stride] = value;
    }
};

template<int layout>
void read_sample(const uint8_t *source, int width, int height, int chroma_width, int chroma_height,
                 int column, int row, int sample[3]) {
    const int pixel = row * width + column;
    if constexpr (layout == 1 || layout == 2) {
        sample[0] = source[pixel * 3 + (layout == 1 ? 0 : 2)];
        sample[1] = source[pixel * 3 + 1];
        sample[2] = source[pixel * 3 + (layout == 1 ? 2 : 0)];
    } else if constexpr (layout == 4 || layout == 5) {
        const int pair = (row * width + (column & ~1)) * 2;
        sample[0] = source[pair + (layout == 4 ? 0 : 1) + (column & 1) * 2];
        sample[1] = source[pair + (layout == 4 ? 1 : 0)];
        sample[2] = source[pair + (layout == 4 ? 3 : 2)];
    } else {
        const int chroma = (row * chroma_height / height) * chroma_width + column * chroma_width / width;
        const int pixels = width * height;
        const int chroma_size = chroma_width * chroma_height;
        if constexpr (layout == 0) {
            sample[0] = source[pixel];
            sample[1] = source[pixels + chroma];
            sample[2] = source[pixels + chroma_size + chroma];
        } else if constexpr (layout == 6) {
            sample[0] = source[pixel];
            sample[1] = source[pixels + chroma * 2];
            sample[2] = source[pixels + chroma * 2 + 1];
        } else {
            const int offsets[3] = {pixel, pixels + (layout == 7 ? chroma * 2 : chroma),
                pixels + (layout == 7 ? chroma * 2 + 1 : chroma_size + chroma)};
            for (int component = 0; component < 3; ++component) {
                const int offset = offsets[component] * 2;
                sample[component] = (source[offset] | (source[offset + 1] << 8)) >> 6;
            }
        }
    }
}

uint8_t transform(const jint *coefficients, const int *sample, int count) {
    const int64_t value = static_cast<int64_t>(coefficients[0]) * sample[0] +
        static_cast<int64_t>(coefficients[1]) * sample[1] +
        static_cast<int64_t>(coefficients[2]) * sample[2] + static_cast<int64_t>(coefficients[3]) * count;
    const int shift = count == 1 ? 14 : 16;
    return static_cast<uint8_t>(std::clamp<int64_t>((value + (int64_t{1} << (shift - 1))) >> shift, 0, 255));
}

template<int layout>
void convert(const uint8_t *source, int width, int height, int chroma_width, int chroma_height,
             const jint *coefficients, const Plane &y_plane, const Plane &u_plane, const Plane &v_plane) {
    for (int row = 0; row < height; row += 2) {
        for (int column = 0; column < width; column += 2) {
            int total[3] = {};
            for (int vertical = 0; vertical < 2; ++vertical) {
                for (int horizontal = 0; horizontal < 2; ++horizontal) {
                    int sample[3];
                    read_sample<layout>(source, width, height, chroma_width, chroma_height,
                        column + horizontal, row + vertical, sample);
                    y_plane.put(column + horizontal, row + vertical, transform(coefficients, sample, 1));
                    for (int component = 0; component < 3; ++component) total[component] += sample[component];
                }
            }
            u_plane.put(column / 2, row / 2, transform(coefficients + 4, total, 4));
            v_plane.put(column / 2, row / 2, transform(coefficients + 8, total, 4));
        }
    }
}

bool identity(const jint *coefficients) {
    const int expected[12] = {16384, 0, 0, 0, 0, 16384, 0, 0, 0, 0, 16384, 0};
    for (int index = 0; index < 12; ++index) {
        const int64_t delta = static_cast<int64_t>(coefficients[index]) - expected[index];
        if (delta < -2 || delta > 2) return false;
    }
    return true;
}

void copy420(const uint8_t *source, int width, int height, bool semiplanar,
             const Plane &y_plane, const Plane &u_plane, const Plane &v_plane) {
    for (int row = 0; row < height; ++row) {
        if (y_plane.pixel_stride == 1) std::memcpy(y_plane.data + static_cast<size_t>(row) * y_plane.row_stride,
            source + row * width, width);
        else for (int column = 0; column < width; ++column) y_plane.put(column, row, source[row * width + column]);
    }
    const int pixels = width * height;
    const int chroma_size = pixels / 4;
    for (int row = 0; row < height / 2; ++row) {
        if (!semiplanar && u_plane.pixel_stride == 1 && v_plane.pixel_stride == 1) {
            std::memcpy(u_plane.data + static_cast<size_t>(row) * u_plane.row_stride, source + pixels + row * width / 2, width / 2);
            std::memcpy(v_plane.data + static_cast<size_t>(row) * v_plane.row_stride, source + pixels + chroma_size + row * width / 2, width / 2);
        } else if (semiplanar && u_plane.pixel_stride == 2 && v_plane.pixel_stride == 2 &&
            u_plane.data + 1 == v_plane.data && u_plane.row_stride == v_plane.row_stride) {
            std::memcpy(u_plane.data + static_cast<size_t>(row) * u_plane.row_stride, source + pixels + row * width, width);
        } else {
            int column = 0;
#if defined(__aarch64__)
            const bool planar_output = u_plane.pixel_stride == 1 && v_plane.pixel_stride == 1;
            const bool interleaved_output = u_plane.pixel_stride == 2 && v_plane.pixel_stride == 2 &&
                u_plane.row_stride == v_plane.row_stride &&
                (u_plane.data + 1 == v_plane.data || v_plane.data + 1 == u_plane.data);
            if (planar_output || interleaved_output) for (; column + 16 <= width / 2; column += 16) {
                const int chroma = row * width / 2 + column;
                uint8x16x2_t values;
                if (semiplanar) values = vld2q_u8(source + pixels + chroma * 2);
                else {
                    values.val[0] = vld1q_u8(source + pixels + chroma);
                    values.val[1] = vld1q_u8(source + pixels + chroma_size + chroma);
                }
                auto *destination_u = u_plane.data + static_cast<size_t>(row) * u_plane.row_stride + column * u_plane.pixel_stride;
                auto *destination_v = v_plane.data + static_cast<size_t>(row) * v_plane.row_stride + column * v_plane.pixel_stride;
                if (planar_output) {
                    vst1q_u8(destination_u, values.val[0]);
                    vst1q_u8(destination_v, values.val[1]);
                } else if (destination_u + 1 == destination_v) vst2q_u8(destination_u, values);
                else {
                    const uint8x16x2_t reversed{{values.val[1], values.val[0]}};
                    vst2q_u8(destination_v, reversed);
                }
            }
#endif
            for (; column < width / 2; ++column) {
                const int chroma = row * width / 2 + column;
                u_plane.put(column, row, source[pixels + (semiplanar ? chroma * 2 : chroma)]);
                v_plane.put(column, row, source[pixels + (semiplanar ? chroma * 2 + 1 : chroma_size + chroma)]);
            }
        }
    }
}

#if defined(__aarch64__)
int32x4_t color_term(int16x4_t source_u, int16x4_t source_v, const jint *coefficients) {
    return vmlal_n_s16(vmlal_n_s16(vdupq_n_s32(coefficients[3]), source_u,
        static_cast<int16_t>(coefficients[1])), source_v, static_cast<int16_t>(coefficients[2]));
}

void write_luma16(const uint8_t *source, uint8_t *destination, int coefficient,
                  int32x4_t color_low, int32x4_t color_high) {
    const uint8x16_t bytes = vld1q_u8(source);
    const int16x8_t low = vreinterpretq_s16_u16(vmovl_u8(vget_low_u8(bytes)));
    const int16x8_t high = vreinterpretq_s16_u16(vmovl_u8(vget_high_u8(bytes)));
    const int32x4x2_t low_terms = vzipq_s32(color_low, color_low);
    const int32x4x2_t high_terms = vzipq_s32(color_high, color_high);
    const int32x4_t rounding = vdupq_n_s32(8192);
    const int16x8_t output_low = vcombine_s16(
        vshrn_n_s32(vaddq_s32(vmlal_n_s16(low_terms.val[0], vget_low_s16(low), static_cast<int16_t>(coefficient)), rounding), 14),
        vshrn_n_s32(vaddq_s32(vmlal_n_s16(low_terms.val[1], vget_high_s16(low), static_cast<int16_t>(coefficient)), rounding), 14));
    const int16x8_t output_high = vcombine_s16(
        vshrn_n_s32(vaddq_s32(vmlal_n_s16(high_terms.val[0], vget_low_s16(high), static_cast<int16_t>(coefficient)), rounding), 14),
        vshrn_n_s32(vaddq_s32(vmlal_n_s16(high_terms.val[1], vget_high_s16(high), static_cast<int16_t>(coefficient)), rounding), 14));
    vst1q_u8(destination, vcombine_u8(vqmovun_s16(output_low), vqmovun_s16(output_high)));
}

uint8x8_t chroma8(int16x8_t source_u, int16x8_t source_v, const jint *coefficients) {
    const int32x4_t rounding = vdupq_n_s32(8192);
    return vqmovun_s16(vcombine_s16(
        vshrn_n_s32(vaddq_s32(color_term(vget_low_s16(source_u), vget_low_s16(source_v), coefficients), rounding), 14),
        vshrn_n_s32(vaddq_s32(color_term(vget_high_s16(source_u), vget_high_s16(source_v), coefficients), rounding), 14)));
}

void write_chroma8(uint8x8_t values_u, uint8x8_t values_v, int column, int row,
                   const Plane &u_plane, const Plane &v_plane) {
    auto *destination_u = u_plane.data + static_cast<size_t>(row) * u_plane.row_stride + column * u_plane.pixel_stride;
    auto *destination_v = v_plane.data + static_cast<size_t>(row) * v_plane.row_stride + column * v_plane.pixel_stride;
    if (u_plane.pixel_stride == 1 && v_plane.pixel_stride == 1) {
        vst1_u8(destination_u, values_u); vst1_u8(destination_v, values_v);
    } else if (u_plane.pixel_stride == 2 && v_plane.pixel_stride == 2 && destination_u + 1 == destination_v) {
        const uint8x8x2_t values{{values_u, values_v}};
        vst2_u8(destination_u, values);
    } else if (u_plane.pixel_stride == 2 && v_plane.pixel_stride == 2 && destination_v + 1 == destination_u) {
        const uint8x8x2_t values{{values_v, values_u}};
        vst2_u8(destination_v, values);
    } else {
        uint8_t bytes_u[8], bytes_v[8];
        vst1_u8(bytes_u, values_u); vst1_u8(bytes_v, values_v);
        for (int index = 0; index < 8; ++index) {
            u_plane.put(column + index, row, bytes_u[index]);
            v_plane.put(column + index, row, bytes_v[index]);
        }
    }
}
#endif

template<int layout>
void copy422(const uint8_t *source, int width, int height,
             const Plane &y_plane, const Plane &u_plane, const Plane &v_plane) {
    constexpr int y_index = layout == 4 ? 0 : 1;
    constexpr int u_index = layout == 4 ? 1 : 0;
    constexpr int v_index = layout == 4 ? 3 : 2;
    for (int row = 0; row < height; row += 2) {
        int column = 0;
#if defined(__aarch64__)
        if (y_plane.pixel_stride == 1) for (; column + 16 <= width; column += 16) {
            const uint8x8x4_t top = vld4_u8(source + (static_cast<size_t>(row) * width + column) * 2);
            const uint8x8x4_t bottom = vld4_u8(source + (static_cast<size_t>(row + 1) * width + column) * 2);
            const uint8x8x2_t top_y{{top.val[y_index], top.val[y_index + 2]}};
            const uint8x8x2_t bottom_y{{bottom.val[y_index], bottom.val[y_index + 2]}};
            vst2_u8(y_plane.data + static_cast<size_t>(row) * y_plane.row_stride + column, top_y);
            vst2_u8(y_plane.data + static_cast<size_t>(row + 1) * y_plane.row_stride + column, bottom_y);
            write_chroma8(vrhadd_u8(top.val[u_index], bottom.val[u_index]),
                vrhadd_u8(top.val[v_index], bottom.val[v_index]), column / 2, row / 2, u_plane, v_plane);
        }
#endif
        for (; column < width; column += 2) {
            const auto *top = source + (static_cast<size_t>(row) * width + column) * 2;
            const auto *bottom = top + width * 2;
            y_plane.put(column, row, top[y_index]);
            y_plane.put(column + 1, row, top[y_index + 2]);
            y_plane.put(column, row + 1, bottom[y_index]);
            y_plane.put(column + 1, row + 1, bottom[y_index + 2]);
            u_plane.put(column / 2, row / 2, (top[u_index] + bottom[u_index] + 1) / 2);
            v_plane.put(column / 2, row / 2, (top[v_index] + bottom[v_index] + 1) / 2);
        }
    }
}

void convert420(const uint8_t *source, int width, int height, const jint *coefficients,
                const Plane &y_plane, const Plane &u_plane, const Plane &v_plane) {
    const int pixels = width * height;
    const int chroma_size = pixels / 4;
    for (int row = 0; row < height; row += 2) {
        int column = 0;
#if defined(__aarch64__)
        for (; column + 16 <= width; column += 16) {
            const int chroma = row / 2 * (width / 2) + column / 2;
            const int16x8_t source_u = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(source + pixels + chroma)));
            const int16x8_t source_v = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(source + pixels + chroma_size + chroma)));
            const int32x4_t low = color_term(vget_low_s16(source_u), vget_low_s16(source_v), coefficients);
            const int32x4_t high = color_term(vget_high_s16(source_u), vget_high_s16(source_v), coefficients);
            for (int vertical = 0; vertical < 2; ++vertical) write_luma16(source + (row + vertical) * width + column,
                y_plane.data + static_cast<size_t>(row + vertical) * y_plane.row_stride + column, coefficients[0], low, high);
            write_chroma8(chroma8(source_u, source_v, coefficients + 4), chroma8(source_u, source_v, coefficients + 8),
                column / 2, row / 2, u_plane, v_plane);
        }
#endif
        for (; column < width; column += 2) {
            const int chroma = row / 2 * (width / 2) + column / 2;
            const int source_u = source[pixels + chroma];
            const int source_v = source[pixels + chroma_size + chroma];
            for (int vertical = 0; vertical < 2; ++vertical) for (int horizontal = 0; horizontal < 2; ++horizontal) {
                const int sample[3] = {source[(row + vertical) * width + column + horizontal], source_u, source_v};
                y_plane.put(column + horizontal, row + vertical, transform(coefficients, sample, 1));
            }
            const int sample[3] = {0, source_u, source_v};
            u_plane.put(column / 2, row / 2, transform(coefficients + 4, sample, 1));
            v_plane.put(column / 2, row / 2, transform(coefficients + 8, sample, 1));
        }
    }
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_llawsxx_uvclivestreaming_recording_YuvEncoderConverter_00024Companion_nativeWrite(
    JNIEnv *env, jobject, jobject source_buffer, jint layout, jint width, jint height,
    jint chroma_width, jint chroma_height, jintArray coefficient_array,
    jobject y_buffer, jint y_row_stride, jint y_pixel_stride,
    jobject u_buffer, jint u_row_stride, jint u_pixel_stride,
    jobject v_buffer, jint v_row_stride, jint v_pixel_stride) {
    if (width < 2 || width > 3840 || height < 2 || height > 2160 || (width & 1) || (height & 1) ||
        chroma_width < 1 || chroma_width > width || chroma_height < 1 || chroma_height > height ||
        layout < 0 || layout > 7 || env->GetArrayLength(coefficient_array) != 12) return JNI_FALSE;
    const auto *source = static_cast<const uint8_t *>(env->GetDirectBufferAddress(source_buffer));
    const int64_t size = layout == 1 || layout == 2 ? int64_t{width} * height * 3 :
        layout == 4 || layout == 5 ? int64_t{width} * height * 2 :
        (int64_t{width} * height + int64_t{2} * chroma_width * chroma_height) * (layout == 3 || layout == 7 ? 2 : 1);
    if (!source || env->GetDirectBufferCapacity(source_buffer) < size) return JNI_FALSE;
    const Plane y_plane{static_cast<uint8_t *>(env->GetDirectBufferAddress(y_buffer)),
        env->GetDirectBufferCapacity(y_buffer), y_row_stride, y_pixel_stride};
    const Plane u_plane{static_cast<uint8_t *>(env->GetDirectBufferAddress(u_buffer)),
        env->GetDirectBufferCapacity(u_buffer), u_row_stride, u_pixel_stride};
    const Plane v_plane{static_cast<uint8_t *>(env->GetDirectBufferAddress(v_buffer)),
        env->GetDirectBufferCapacity(v_buffer), v_row_stride, v_pixel_stride};
    if (!y_plane.valid(width, height) || !u_plane.valid(width / 2, height / 2) ||
        !v_plane.valid(width / 2, height / 2)) return JNI_FALSE;
    jint coefficients[12];
    env->GetIntArrayRegion(coefficient_array, 0, 12, coefficients);
    if ((layout == 4 || layout == 5) && identity(coefficients)) {
        if (layout == 4) copy422<4>(source, width, height, y_plane, u_plane, v_plane);
        else copy422<5>(source, width, height, y_plane, u_plane, v_plane);
        return JNI_TRUE;
    }
    if ((layout == 0 || layout == 6) && chroma_width == width / 2 && chroma_height == height / 2 && identity(coefficients)) {
        copy420(source, width, height, layout == 6, y_plane, u_plane, v_plane);
        return JNI_TRUE;
    }
    bool vector_safe = y_plane.pixel_stride == 1 && coefficients[4] == 0 && coefficients[8] == 0;
    for (int index = 0; index < 12; ++index) {
        const int bound = index % 4 == 3 ? 8388608 : 32767;
        if (coefficients[index] < -bound || coefficients[index] > bound) vector_safe = false;
    }
    if (layout == 0 && chroma_width == width / 2 && chroma_height == height / 2 && vector_safe) {
        convert420(source, width, height, coefficients, y_plane, u_plane, v_plane);
        return JNI_TRUE;
    }
    switch (layout) {
        case 0: convert<0>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
        case 1: convert<1>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
        case 2: convert<2>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
        case 3: convert<3>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
        case 4: convert<4>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
        case 5: convert<5>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
        case 6: convert<6>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
        case 7: convert<7>(source, width, height, chroma_width, chroma_height, coefficients, y_plane, u_plane, v_plane); break;
    }
    return JNI_TRUE;
}
