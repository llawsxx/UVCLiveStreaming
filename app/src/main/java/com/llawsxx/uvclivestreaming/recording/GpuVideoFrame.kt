package com.llawsxx.uvclivestreaming.recording

import java.nio.ByteBuffer
/** Pixels remain YUV until the fragment shader; RGB cameras keep their native RGB. */
internal data class GpuVideoFrame(
    val bytes: ByteArray?,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val layout: Int = I420,
    val fullRange: Boolean = false,
    val directBuffer: ByteBuffer? = null,
    val chromaWidth: Int = (width + 1) / 2,
    val chromaHeight: Int = (height + 1) / 2,
    val testCard: TestCardFrame? = null,
) {
    val isRgb: Boolean get() = layout == RGB || layout == BGR
    val sampleBytes: Int get() = if (layout == YUV10) 2 else 1
    val byteSize: Int get() = if (isRgb) width * height * 3 else
        (width * height + 2 * chromaWidth * chromaHeight) * sampleBytes

    companion object {
        // Planar Y/U/V; chroma dimensions also describe 4:2:2, 4:4:4 and JPEG subsampling.
        const val I420 = 0
        const val RGB = 1
        const val BGR = 2
        // Three little-endian 16-bit planes, preserving P010's ten MSBs.
        const val YUV10 = 3

        /** The caller must hold the decoded frame's lease throughout render(). */
        fun fromDecoded(frame: MjpegDecodePool.DecodedFrame): GpuVideoFrame? = frame.yuv?.let {
            GpuVideoFrame(null, frame.width, frame.height, frame.timestampNs,
                fullRange = true, directBuffer = it.buffer,
                chromaWidth = frame.chromaWidth, chromaHeight = frame.chromaHeight)
        }

        fun fromUsb(bytes: ByteArray, format: Int, width: Int, height: Int, timestampNs: Long): GpuVideoFrame? {
            if (width !in 1..3840 || height !in 1..2160) return null
            if (format == 4 || format == 9) {
                if (bytes.size < width.toLong() * height * 3) return null
                return GpuVideoFrame(bytes, width, height, timestampNs, if (format == 4) RGB else BGR, true)
            }
            if (format == 1) {
                val geometry = MjpegChromaGeometry.read(bytes, width, height) ?: return null
                val buffer = ByteBuffer.allocateDirect(width * height + 2 * geometry.first * geometry.second)
                if (!NativeUsbCapture.nativeDecodeMjpegToYuv(bytes, width, height, geometry.first, geometry.second, buffer)) return null
                return GpuVideoFrame(null, width, height, timestampNs, fullRange = true, directBuffer = buffer,
                    chromaWidth = geometry.first, chromaHeight = geometry.second)
            }
            // Legacy callers receive an owned buffer; the live renderer uses RawVideoConverter leases.
            return RawVideoConverter().use { converter ->
                converter.convert(bytes, format, width, height, timestampNs)?.use { converted ->
                    converted.frame.copy(directBuffer = null,
                        bytes = ByteArray(converted.frame.byteSize).also { converted.frame.directBuffer!!.duplicate().apply { clear(); get(it) } })
                }
            }
        }
    }
}
