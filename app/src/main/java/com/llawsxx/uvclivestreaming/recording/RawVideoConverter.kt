package com.llawsxx.uvclivestreaming.recording

import java.nio.ByteBuffer

/** Captured buffers retain their native layout and ownership until GL upload finishes. */
internal class RawVideoConverter(
    private val converter: (ByteArray, Int, Int, Int, ByteBuffer) -> Boolean = { bytes, format, width, height, destination ->
        NativeUsbCapture.nativeConvertRawToGpuBuffer(bytes, format, width, height, destination)
    },
) : AutoCloseable {
    class ConvertedFrame internal constructor(
        val frame: GpuVideoFrame,
        private val lease: AutoCloseable,
    ) : AutoCloseable {
        override fun close() { lease.close() }
    }

    private val buffers = DirectVideoBufferPool(1)
    @Volatile private var closed = false

    fun convert(bytes: CapturedVideoBuffer, format: Int, width: Int, height: Int, timestampNs: Long): ConvertedFrame? {
        try {
            val frame = if (closed) null else GpuVideoFrame.fromRaw(bytes.buffer, format, width, height, timestampNs)
            if (frame == null) {
                bytes.close()
                return null
            }
            return ConvertedFrame(frame, bytes)
        } catch (error: Throwable) {
            bytes.close()
            throw error
        }
    }

    fun convert(bytes: ByteArray, format: Int, width: Int, height: Int, timestampNs: Long): ConvertedFrame? =
        convertImpl(bytes.size, format, width, height, timestampNs) { destination ->
            converter(bytes, format, width, height, destination)
        }

    private inline fun convertImpl(inputBytes: Int, format: Int, width: Int, height: Int, timestampNs: Long,
        convert: (ByteBuffer) -> Boolean): ConvertedFrame? {
        if (width !in 1..3840 || height !in 1..2160) return null
        val rgb = format == 4 || format == 9
        if (!rgb && format != 2 && format != 3 && format != 5 && format != 6 && format != 7) return null
        if (format != 6 && !rgb && ((width and 1) != 0 || (height and 1) != 0)) return null
        val pixels = width * height
        val cw = (width + 1) / 2
        val ch = if (format == 2 || format == 3) height else (height + 1) / 2
        val size = if (rgb) pixels * 3 else (pixels + 2 * cw * ch) * if (format == 7) 2 else 1
        val inputSize = when (format) {
            2, 3 -> pixels * 2
            4, 7, 9 -> pixels * 3
            else -> size
        }
        if (inputBytes < inputSize) return null
        val lease = buffers.acquire(size) ?: return null
        try {
            if (!convert(lease.buffer)) {
                lease.close()
                return null
            }
            val layout = when (format) { 4 -> GpuVideoFrame.RGB; 9 -> GpuVideoFrame.BGR; 7 -> GpuVideoFrame.YUV10; else -> GpuVideoFrame.I420 }
            return ConvertedFrame(GpuVideoFrame(null, width, height, timestampNs,
                layout = layout, fullRange = rgb, directBuffer = lease.buffer, chromaWidth = cw, chromaHeight = ch), lease)
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    fun diagnostics() = buffers.diagnostics()
    override fun close() { closed = true; buffers.close() }
}
