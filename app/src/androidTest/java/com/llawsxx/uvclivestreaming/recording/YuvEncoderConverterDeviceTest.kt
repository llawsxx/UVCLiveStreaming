package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

class YuvEncoderConverterDeviceTest {
    @Test fun optimized420CopyPreservesSamplesWithPlanarNv12AndNv21Output() {
        for (layout in listOf(GpuVideoFrame.I420, GpuVideoFrame.NV12)) for (width in listOf(34, 64))
            for (pixelStride in listOf(1, 2)) for (reversed in listOf(false, true)) {
                val height = 4
                val pixels = width * height
                val chromaSize = pixels / 4
                val source = ByteBuffer.allocateDirect(pixels + 2 * chromaSize).apply {
                    repeat(capacity()) { put(((it * 37 + 19) and 255).toByte()) }; flip()
                }
                val yStride = width + 8
                val yPlane = ByteBuffer.allocateDirect(yStride * height).apply {
                    repeat(capacity()) { put(99) }; clear()
                }
                val stride = width / 2 * pixelStride + 8
                val chroma = ByteBuffer.allocateDirect(stride * height).apply {
                    repeat(capacity()) { put(99) }; clear()
                }
                val uOffset = if (pixelStride == 1) 0 else if (reversed) 1 else 0
                val vOffset = if (pixelStride == 1) stride * height / 2 else 1 - uOffset
                val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT709, false, UsbYuvMatrix.BT709, false)
                assertTrue(YuvEncoderConverter.nativeWrite(source, layout, width, height, width / 2, height / 2,
                    transform, yPlane, yStride, 1,
                    chroma.duplicate().apply { position(uOffset) }.slice(), stride, pixelStride,
                    chroma.duplicate().apply { position(vOffset) }.slice(), stride, pixelStride))
                for (row in 0 until height) {
                    for (column in 0 until width) assertEquals(source.get(row * width + column), yPlane.get(row * yStride + column))
                    for (column in width until yStride) assertEquals(99, yPlane.get(row * yStride + column).toInt())
                }
                for (row in 0 until height / 2) for (column in 0 until width / 2) {
                    val sample = row * width / 2 + column
                    val sourceU = pixels + if (layout == GpuVideoFrame.NV12) sample * 2 else sample
                    val sourceV = pixels + if (layout == GpuVideoFrame.NV12) sample * 2 + 1 else chromaSize + sample
                    assertEquals(source.get(sourceU), chroma.get(uOffset + row * stride + column * pixelStride))
                    assertEquals(source.get(sourceV), chroma.get(vOffset + row * stride + column * pixelStride))
                }
                for (row in 0 until height / 2) for (column in width / 2 * pixelStride until stride) {
                    assertEquals(99, chroma.get(row * stride + column).toInt())
                    if (pixelStride == 1) assertEquals(99, chroma.get(vOffset + row * stride + column).toInt())
                }
            }
    }

    @Test fun packed422FastCopyPreservesLumaAndAveragesChromaWithTailAndPadding() {
        for (layout in listOf(GpuVideoFrame.YUYV, GpuVideoFrame.UYVY))
            for (width in listOf(18, 32)) for (pixelStride in listOf(1, 2)) for (reversed in listOf(false, true)) {
                val height = 4
                val source = ByteBuffer.allocateDirect(width * height * 2).apply {
                    repeat(capacity()) { put(((it * 37 + 19) and 255).toByte()) }; flip()
                }
                val stride = width * pixelStride + 8
                val yPlane = ByteBuffer.allocateDirect(stride * height).apply {
                    repeat(capacity()) { put(99) }; clear()
                }
                val uv = ByteBuffer.allocateDirect((width + 8) * height / 2).apply {
                    repeat(capacity()) { put(99) }; clear()
                }
                val uOffset = if (reversed) 1 else 0
                val vOffset = 1 - uOffset
                val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT709, false, UsbYuvMatrix.BT709, false)
                assertTrue(YuvEncoderConverter.nativeWrite(source, layout, width, height, width / 2, height,
                    transform, yPlane, stride, pixelStride,
                    uv.duplicate().apply { position(uOffset) }.slice(), width + 8, 2,
                    uv.duplicate().apply { position(vOffset) }.slice(), width + 8, 2))
                val yOffset = if (layout == GpuVideoFrame.YUYV) 0 else 1
                val uIndex = 1 - yOffset
                for (row in 0 until height) {
                    for (column in 0 until width) {
                        assertEquals(source.get((row * width + column) * 2 + yOffset).toInt() and 255,
                            yPlane.get(row * stride + column * pixelStride).toInt() and 255)
                        if (pixelStride == 2) assertEquals(99, yPlane.get(row * stride + column * 2 + 1).toInt())
                    }
                    for (column in width * pixelStride until stride) assertEquals(99, yPlane.get(row * stride + column).toInt())
                }
                for (row in 0 until height / 2) {
                    for (column in 0 until width / 2) for (component in 0..1) {
                        val offset = (row * 2 * width + column * 2) * 2 + uIndex + component * 2
                        val expected = ((source.get(offset).toInt() and 255) +
                            (source.get(offset + width * 2).toInt() and 255) + 1) / 2
                        assertEquals(expected, uv.get(row * (width + 8) + column * 2 +
                            if (component == 0) uOffset else vOffset).toInt() and 255)
                    }
                    for (column in width until width + 8) assertEquals(99, uv.get(row * (width + 8) + column).toInt())
                }
            }
    }

    @Test fun optimized420ConversionMatchesScalarReferenceIncludingTailAndNv21() {
        for (width in listOf(18, 32)) for (reversed in listOf(false, true)) {
            val height = 4
            val pixels = width * height
            val chromaSize = pixels / 4
            val source = ByteBuffer.allocateDirect(pixels + 2 * chromaSize).apply {
                repeat(capacity()) { put(((it * 37 + 19) and 255).toByte()) }; flip()
            }
            val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT601, true, UsbYuvMatrix.BT709, false)
            val stride = width + 8
            val yPlane = ByteBuffer.allocateDirect(stride * height)
            val uv = ByteBuffer.allocateDirect(stride * height / 2)
            val uOffset = if (reversed) 1 else 0
            val vOffset = 1 - uOffset
            assertTrue(YuvEncoderConverter.nativeWrite(source, GpuVideoFrame.I420, width, height, width / 2, height / 2,
                transform, yPlane, stride, 1,
                uv.duplicate().apply { position(uOffset) }.slice(), stride, 2,
                uv.duplicate().apply { position(vOffset) }.slice(), stride, 2))
            fun expected(row: Int, sample: IntArray): Int {
                val total = transform[row * 4 + 3].toLong() + sample.indices.sumOf {
                    transform[row * 4 + it].toLong() * sample[it]
                }
                return ((total + 8192) shr 14).toInt().coerceIn(0, 255)
            }
            for (row in 0 until height) for (column in 0 until width) {
                val chroma = row / 2 * (width / 2) + column / 2
                val sample = intArrayOf(source.get(row * width + column).toInt() and 255,
                    source.get(pixels + chroma).toInt() and 255,
                    source.get(pixels + chromaSize + chroma).toInt() and 255)
                assertEquals(expected(0, sample), yPlane.get(row * stride + column).toInt() and 255)
                if (row % 2 == 0 && column % 2 == 0) {
                    assertEquals(expected(1, sample), uv.get(row / 2 * stride + column + uOffset).toInt() and 255)
                    assertEquals(expected(2, sample), uv.get(row / 2 * stride + column + vOffset).toInt() and 255)
                }
            }
        }
    }

    @Test fun allRawLayoutsWritePaddedInterleavedEncoderPlanes() {
        for (format in listOf(2, 3, 4, 5, 6, 7, 9)) {
            val source = SyntheticRawVideo(format, 256, 64)
            val frame = checkNotNull(GpuVideoFrame.fromRaw(source.template, format, 256, 64, 1))
            val yPlane = ByteBuffer.allocateDirect(264 * 64).apply { repeat(capacity()) { put(99) }; clear() }
            val uv = ByteBuffer.allocateDirect(264 * 32).apply { repeat(capacity()) { put(99) }; clear() }
            val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT709, frame.fullRange,
                UsbYuvMatrix.BT709, false, if (frame.sampleBytes == 2) 10 else 8, frame.isRgb)
            assertTrue(YuvEncoderConverter.nativeWrite(source.template.duplicate().slice(), frame.layout,
                256, 64, frame.chromaWidth, frame.chromaHeight, transform,
                yPlane, 264, 1, uv.duplicate().apply { limit(capacity() - 1) }.slice(), 264, 2,
                uv.duplicate().apply { position(1) }.slice(), 264, 2))
            for (row in 0 until 64) {
                for (column in 256 until 264) assertEquals(99, yPlane.get(row * 264 + column).toInt())
            }
            for (row in 0 until 32) for (column in 256 until 264)
                assertEquals(99, uv.get(row * 264 + column).toInt())
            assertEquals(16, yPlane.get(0).toInt() and 255)
            assertEquals(128, uv.get(0).toInt() and 255)
            assertEquals(128, uv.get(1).toInt() and 255)
            for (index in 0 until 8) {
                val color = source.colors[index]
                val luma = .2126 * color[0] + .7152 * color[1] + .0722 * color[2]
                val expected = intArrayOf((16 + luma * 219 / 255).roundToInt(),
                    (128 + (color[2] - luma) / (2 * (1 - .0722)) * 224 / 255).roundToInt(),
                    (128 + (color[0] - luma) / (2 * (1 - .2126)) * 224 / 255).roundToInt())
                val column = (2 * index + 1) * 256 / 16
                val actual = intArrayOf(yPlane.get(16 * 264 + column).toInt() and 255,
                    uv.get(8 * 264 + column / 2 * 2).toInt() and 255,
                    uv.get(8 * 264 + column / 2 * 2 + 1).toInt() and 255)
                for (component in 0..2) assertTrue("format=$format bar=$index plane=$component",
                    abs(actual[component] - expected[component]) <= 2)
            }
        }
    }

    @Test fun undersizedPlanesAreRejectedBeforeWriting() {
        val source = ByteBuffer.allocateDirect(24)
        val yPlane = ByteBuffer.allocateDirect(15).apply { repeat(capacity()) { put(99) }; clear() }
        val chroma = ByteBuffer.allocateDirect(4)
        val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT709, false, UsbYuvMatrix.BT709, false)
        assertFalse(YuvEncoderConverter.nativeWrite(source, GpuVideoFrame.I420, 4, 4, 2, 2, transform,
            yPlane, 4, 1, chroma, 2, 1, chroma, 2, 1))
        repeat(yPlane.capacity()) { assertEquals(99, yPlane.get(it).toInt()) }
    }

    @Test fun fullHeightJpegChromaIsDownsampledAndConverted() {
        val source = ByteBuffer.allocateDirect(16).apply {
            repeat(8) { put(76) }; repeat(4) { put(85) }; repeat(4) { put(255.toByte()) }; flip()
        }
        val yPlane = ByteBuffer.allocateDirect(8)
        val uPlane = ByteBuffer.allocateDirect(2)
        val vPlane = ByteBuffer.allocateDirect(2)
        val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT601, true, UsbYuvMatrix.BT709, false)
        assertTrue(YuvEncoderConverter.nativeWrite(source, GpuVideoFrame.I420, 4, 2, 2, 2, transform,
            yPlane, 4, 1, uPlane, 2, 1, vPlane, 2, 1))
        repeat(8) { assertEquals(62, yPlane.get(it).toInt() and 255) }
        repeat(2) {
            assertEquals(102, uPlane.get(it).toInt() and 255)
            assertTrue(abs((vPlane.get(it).toInt() and 255) - 240) <= 1)
        }
    }
}
