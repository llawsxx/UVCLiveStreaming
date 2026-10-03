package com.llawsxx.uvclivestreaming.recording

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class GpuVideoPipelineTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test fun rawFormatsKeepLumaAndChromaWithoutRgbConversion() {
        val expected = bytes(16, 32, 64, 235, 90, 240)
        assertArrayEquals(expected, NativeUsbCapture.nativeDecodeToI420(expected, 6, 2, 2))
        assertArrayEquals(expected, NativeUsbCapture.nativeDecodeToI420(expected, 5, 2, 2))
        assertArrayEquals(expected, NativeUsbCapture.nativeDecodeToI420(
            bytes(16, 90, 32, 240, 64, 90, 235, 240), 2, 2, 2))
        assertArrayEquals(expected, NativeUsbCapture.nativeDecodeToI420(
            bytes(90, 16, 240, 32, 90, 64, 240, 235), 3, 2, 2))
        val p010 = expected.flatMap { listOf(0.toByte(), it) }.toByteArray()
        assertArrayEquals(expected, NativeUsbCapture.nativeDecodeToI420(p010, 7, 2, 2))
        assertNull(NativeUsbCapture.nativeDecodeToI420(bytes(1, 2), 5, 2, 2))
        assertNull(NativeUsbCapture.nativeDecodeToI420(ByteArray(32), 2, 3, 2))
    }

    @Test fun mjpegNormalizationAndGeometryChecksStillWork() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val reference = NativeUsbCapture.nativeDecodeToI420(jpeg, 1, 32, 32)
        assertNotNull(reference)
        assertEquals(32 * 32 * 3 / 2, reference!!.size)
        val y = reference[0].toInt() and 255
        val u = reference[32 * 32].toInt() and 255
        val v = reference[32 * 32 * 5 / 4].toInt() and 255
        assertTrue(y in 65..90); assertTrue(u in 70..100); assertTrue(v > 230)
        assertArrayEquals(reference, NativeUsbCapture.nativeDecodeToI420(bytes(0, 1, 2) + jpeg + bytes(0, 0), 1, 32, 32))
        assertArrayEquals(reference, NativeUsbCapture.nativeDecodeToI420(jpeg.copyOf(jpeg.size - 2), 1, 32, 32))
        assertNull(NativeUsbCapture.nativeDecodeToI420(jpeg, 1, 64, 32))
        assertNull(NativeUsbCapture.nativeDecodeToI420(bytes(0, 1, 2), 1, 32, 32))
        // UVC cameras often omit DHT segments; use the same defaults as before.
        val withoutDht = ByteArrayOutputStream()
        var offset = 0
        while (offset < jpeg.size) {
            if (offset + 3 < jpeg.size && jpeg[offset] == 0xff.toByte() && jpeg[offset + 1] == 0xc4.toByte()) {
                val length = ((jpeg[offset + 2].toInt() and 255) shl 8) or (jpeg[offset + 3].toInt() and 255)
                offset += length + 2
            } else { withoutDht.write(jpeg[offset].toInt()); offset++ }
        }
        assertArrayEquals(reference, NativeUsbCapture.nativeDecodeToI420(withoutDht.toByteArray(), 1, 32, 32))
    }

    private fun readPixels(reader: ImageReader): List<IntArray> {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) return image.use {
                val plane = it.planes[0]
                val buffer = plane.buffer
                (0 until it.height).flatMap { y ->
                    (0 until it.width).map { x ->
                        val off = y * plane.rowStride + x * plane.pixelStride
                        intArrayOf(buffer.get(off).toInt() and 255, buffer.get(off + 1).toInt() and 255,
                            buffer.get(off + 2).toInt() and 255)
                    }
                }
            }
            Thread.sleep(5)
        }
        error("GPU output did not reach ImageReader")
    }

    @Test fun gpuUsesCorrectRangeOrientationAndBgrOrder() {
        ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { reader ->
            GpuVideoRenderer().use { gpu ->
                val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                assertTrue(gpu.render(GpuVideoFrame(bytes(235, 235, 16, 16, 128, 128), 2, 2, 1), target))
                val limited = readPixels(reader)
                assertTrue("Top row should be white", limited[0].all { it > 245 })
                assertTrue("Bottom row should be black", limited[2].all { it < 10 })
                assertTrue(gpu.render(GpuVideoFrame(bytes(255, 255, 0, 0, 128, 128), 2, 2, 2, fullRange = true), target))
                val full = readPixels(reader)
                assertTrue(full[0].all { it > 245 }); assertTrue(full[2].all { it < 10 })
                val bgr = ByteArray(12) { if (it % 3 == 2) 255.toByte() else 0 }
                assertTrue(gpu.render(GpuVideoFrame(bgr, 2, 2, 3, GpuVideoFrame.BGR, true), target))
                val red = readPixels(reader)[0]
                assertTrue(red[0] > 245 && red[1] < 10 && red[2] < 10)
                gpu.render(GpuVideoFrame(bgr, 2, 2, 4, GpuVideoFrame.BGR), null)
                assertTrue(gpu.render(GpuVideoFrame(bgr, 2, 2, 5, GpuVideoFrame.BGR), target.copy(revision = 1)))
                readPixels(reader)
            }
        }
    }

    @Test fun selectableMatricesProduceReferenceRgbPixels() {
        val references = listOf(
            UsbYuvMatrix.BT601 to intArrayOf(187, 111, 61),
            UsbYuvMatrix.BT709 to intArrayOf(194, 115, 57),
            UsbYuvMatrix.BT2020 to intArrayOf(190, 110, 57),
            UsbYuvMatrix.SMPTE240M to intArrayOf(194, 117, 59),
        )
        ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { reader ->
            GpuVideoRenderer().use { gpu ->
                val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                references.forEachIndexed { i, (matrix, expected) ->
                    gpu.setColorSettings(matrix, UsbSourceRange.FULL)
                    assertTrue(gpu.render(GpuVideoFrame(bytes(128, 128, 128, 128, 90, 170), 2, 2, i.toLong()), target))
                    val actual = readPixels(reader)[0]
                    expected.zip(actual).forEach { (reference, output) ->
                        assertTrue("${matrix.label}: expected $reference, got $output", kotlin.math.abs(reference - output) <= 2)
                    }
                }
            }
        }
    }

    @Test fun sourceRangeOverrideChangesPixelsIncludingNativeRgb() {
        ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { reader ->
            GpuVideoRenderer().use { gpu ->
                val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                val yuv = GpuVideoFrame(bytes(235, 235, 16, 16, 128, 128), 2, 2, 1, fullRange = true)
                gpu.setColorSettings(UsbYuvMatrix.BT709, UsbSourceRange.TV)
                assertTrue(gpu.render(yuv, target))
                val tv = readPixels(reader)
                assertTrue(tv[0].all { it >= 253 }); assertTrue(tv[2].all { it <= 2 })
                gpu.setColorSettings(UsbYuvMatrix.BT709, UsbSourceRange.FULL)
                assertTrue(gpu.render(yuv.copy(timestampNs = 2, fullRange = false), target))
                val full = readPixels(reader)
                assertTrue(full[0].all { it in 233..237 }); assertTrue(full[2].all { it in 14..18 })
                val rgb = GpuVideoFrame(bytes(235, 235, 235, 235, 235, 235, 16, 16, 16, 16, 16, 16),
                    2, 2, 3, GpuVideoFrame.RGB, true)
                gpu.setColorSettings(UsbYuvMatrix.BT601, UsbSourceRange.TV)
                assertTrue(gpu.render(rgb, target))
                val expanded = readPixels(reader)
                assertTrue(expanded[0].all { it >= 253 }); assertTrue(expanded[2].all { it <= 2 })
            }
        }
    }

    @Test fun fiveFpsPreviewKeepsEveryEncoderSurfaceFrame() {
        ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { encoder ->
            ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { preview ->
                var nowNs = 0L
                GpuVideoRenderer(encoderSurface = encoder.surface, previewClockNs = { nowNs }).use { gpu ->
                    val target = GpuVideoRenderer.PreviewTarget(preview.surface, 0, lowFrameRate = true)
                    var displayed = 0
                    for (i in 0 until 60) {
                        nowNs = i * 1_000_000_000L / 60
                        val frame = GpuVideoFrame(ByteArray(12) { i.toByte() }, 2, 2,
                            50_000_000_000L + nowNs, GpuVideoFrame.RGB, true)
                        val shown = gpu.render(frame, target)
                        assertEquals(i % 12 == 0, shown)
                        assertTrue("Encoder frame $i was lost", readPixels(encoder)[0].all { it == i })
                        if (shown) { readPixels(preview); displayed++ }
                    }
                    assertEquals(5, displayed)
                    assertTrue(gpu.render(GpuVideoFrame(ByteArray(12), 2, 2, 52_000_000_000L,
                        GpuVideoFrame.RGB, true), target.copy(lowFrameRate = false)))
                    readPixels(encoder); readPixels(preview)
                }
            }
        }
    }

    @Test fun hardwareEncoderPreservesCapturePtsAt60Fps() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 64, 64).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        val pts = mutableListOf<Long>()
        val expected = (0 until 12).map { 50_000_000L + it * 16_666_667L / 1_000 }
        var eos = false
        val info = MediaCodec.BufferInfo()
        fun drain(timeout: Long) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, timeout)
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) pts += info.presentationTimeUs
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    codec.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }
        try {
            codec.start()
            GpuVideoRenderer(surface).use { gpu ->
                val data = ByteArray(64 * 64 * 3 / 2) { if (it < 64 * 64) 90 else 128.toByte() }
                for (i in 0 until 12) {
                    gpu.render(GpuVideoFrame(data, 64, 64, 50_000_000_000L + i * 16_666_667L))
                    drain(10_000)
                }
            }
            codec.signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!eos && SystemClock.elapsedRealtime() < deadline) drain(10_000)
            assertTrue(eos)
            assertEquals("All frames must reach encoder; no SurfaceTexture coalescing", 12, pts.size)
            expected.zip(pts).forEach { (capture, output) -> assertTrue("PTS $capture became $output", kotlin.math.abs(capture - output) <= 1L) }
        } finally {
            runCatching { codec.stop() }; codec.release(); surface.release()
        }
    }
}
