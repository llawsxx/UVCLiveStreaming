package com.llawsxx.uvclivestreaming.recording

/** Pixels remain YUV until the fragment shader; RGB cameras keep their native RGB. */
internal data class GpuVideoFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val layout: Int = I420,
    val fullRange: Boolean = false,
) {
    companion object {
        const val I420 = 0
        const val RGB = 1
        const val BGR = 2

        fun fromUsb(bytes: ByteArray, format: Int, width: Int, height: Int, timestampNs: Long): GpuVideoFrame? {
            if (width !in 1..3840 || height !in 1..2160) return null
            if (format == 4 || format == 9) {
                if (bytes.size < width.toLong() * height * 3) return null
                return GpuVideoFrame(bytes, width, height, timestampNs, if (format == 4) RGB else BGR, true)
            }
            val yuv = NativeUsbCapture.nativeDecodeToI420(bytes, format, width, height) ?: return null
            return GpuVideoFrame(yuv, width, height, timestampNs, fullRange = format == 1)
        }
    }
}
