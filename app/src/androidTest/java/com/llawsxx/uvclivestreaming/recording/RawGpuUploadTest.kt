package com.llawsxx.uvclivestreaming.recording

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class RawGpuUploadTest {
    private fun input(format: Int, width: Int, height: Int): ByteBuffer {
        val pixels = width * height
        val size = when (format) {
            2, 3 -> pixels * 2
            4, 7, 9 -> pixels * 3
            else -> pixels + 2 * ((width + 1) / 2) * ((height + 1) / 2)
        }
        return ByteBuffer.allocateDirect(size).apply {
            if (format == 7) {
                for (sample in 0 until size / 2) {
                    val word = (((32 + sample * 73 % 192) * 4 + sample % 4) shl 6)
                    put(word.toByte()); put((word ushr 8).toByte())
                }
            } else {
                for (index in 0 until size) put((32 + index * 73 % 192).toByte())
            }
            flip()
        }
    }

    private fun planar(buffer: ByteBuffer, format: Int, width: Int, height: Int, timestampNs: Long) =
        GpuVideoFrame(null, width, height, timestampNs,
            layout = when (format) { 4 -> GpuVideoFrame.RGB; 9 -> GpuVideoFrame.BGR; 7 -> GpuVideoFrame.YUV10; else -> GpuVideoFrame.I420 },
            fullRange = format == 4 || format == 9, directBuffer = buffer,
            chromaHeight = if (format == 2 || format == 3) height else (height + 1) / 2)

    private fun awaitImage(reader: ImageReader): android.media.Image {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            reader.acquireNextImage()?.let { return it }
            Thread.sleep(1)
        }
        error("GPU output did not reach ImageReader")
    }

    private fun pixels(reader: ImageReader): IntArray = awaitImage(reader).use { image ->
        val plane = image.planes[0]
        IntArray(image.width * image.height * 3) { index ->
            val pixel = index / 3
            val offset = pixel / image.width * plane.rowStride + pixel % image.width * plane.pixelStride + index % 3
            plane.buffer.get(offset).toInt() and 255
        }
    }

    @Test fun nativeLayoutsMatchPlanarPixelsAtNativeAndScaledSizes() {
        for (dimensions in listOf(2 to 2, 8 to 6, 9 to 7)) {
            val (width, height) = dimensions
            for ((outputWidth, outputHeight) in listOf(width to height, width * 2 to height * 2, 5 to 3)) {
                ImageReader.newInstance(outputWidth, outputHeight, PixelFormat.RGBA_8888, 3).use { reader ->
                    GpuVideoRenderer().use { gpu ->
                        val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                        for (format in listOf(2, 3, 5, 6, 7, 4, 9)) {
                            if (width % 2 != 0 && format !in listOf(4, 6, 9)) continue
                            val raw = input(format, width, height)
                            val output = ByteBuffer.allocateDirect(raw.limit())
                            assertTrue(NativeUsbCapture.nativeConvertRawBufferToGpuBuffer(raw, raw.limit(), format, width, height, output))
                            for (matrix in UsbYuvMatrix.entries) {
                                for (range in listOf(UsbSourceRange.TV, UsbSourceRange.FULL)) {
                                    gpu.setColorSettings(matrix, range)
                                    assertTrue(gpu.render(planar(output, format, width, height, 1), target))
                                    val expected = pixels(reader)
                                    var released = 0
                                    RawVideoConverter().use { converter ->
                                        converter.convert(CapturedVideoBuffer(raw) { released++ }, format, width, height, 2)!!.use { frame ->
                                            assertEquals(0, released)
                                            assertTrue(gpu.render(frame.frame, target))
                                        }
                                        assertEquals(1, released)
                                        assertEquals(0L, converter.diagnostics().allocations)
                                    }
                                    val actual = pixels(reader)
                                    val maxError = expected.indices.maxOf { abs(expected[it] - actual[it]) }
                                    assertTrue("format=$format ${width}x$height -> ${outputWidth}x$outputHeight $matrix $range error=$maxError", maxError <= 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun rawLayoutsReachAvcAndHevcWithCapturePts() {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            val codec = MediaCodec.createEncoderByType(mime)
            var surface: android.view.Surface? = null
            val expected = mutableListOf<Long>()
            val actual = mutableListOf<Long>()
            val info = MediaCodec.BufferInfo()
            var eos = false
            fun drain() {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) actual += info.presentationTimeUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                        codec.releaseOutputBuffer(index, false)
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                }
            }
            try {
                val format = MediaFormat.createVideoFormat(mime, 256, 256).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
                GpuVideoRenderer(surface).use { gpu ->
                    RawVideoConverter().use { converter ->
                        for (inputFormat in listOf(2, 3, 5, 6, 7, 4, 9)) {
                            val raw = input(inputFormat, 256, 256)
                            repeat(3) {
                                val timestampNs = 50_000_000_000L + expected.size * 16_666_667L
                                expected += timestampNs / 1_000
                                var released = 0
                                converter.convert(CapturedVideoBuffer(raw) { released++ }, inputFormat, 256, 256, timestampNs)!!.use { frame ->
                                    gpu.render(frame.frame)
                                    assertEquals(0, released)
                                }
                                assertEquals(1, released)
                                drain()
                            }
                        }
                        assertEquals(21L, gpu.encodedFrameCount)
                        assertEquals(0L, converter.diagnostics().allocations)
                    }
                }
                codec.signalEndOfInputStream()
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!eos && SystemClock.elapsedRealtime() < deadline) drain()
                assertTrue("$mime did not reach EOS", eos)
                assertEquals("$mime lost encoded frames", expected.size, actual.size)
                expected.zip(actual).forEach { (capture, encoded) -> assertTrue("$mime PTS $capture became $encoded", abs(capture - encoded) <= 1) }
                println("RAW_ENCODER ${codec.name}: ${actual.size} frames, all layouts, capture PTS preserved")
            } finally {
                runCatching { codec.stop() }; codec.release(); surface?.release()
            }
        }
    }

    @Test fun benchmarkRawLayouts() {
        benchmarkSizes(listOf(1280 to 720, 1920 to 1080))
    }

    @Test fun benchmark4kRawLayouts() {
        benchmarkSizes(listOf(3840 to 2160))
    }

    private fun benchmarkSizes(dimensions: List<Pair<Int, Int>>) {
        for ((width, height) in dimensions) {
            for (format in listOf(2, 3, 5, 6, 7, 4, 9)) {
                val raw = input(format, width, height)
                val output = ByteBuffer.allocateDirect(raw.limit())
                val prepareTimes = Array(2) { mutableListOf<Double>() }
                val totalTimes = Array(2) { mutableListOf<Double>() }
                ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).use { reader ->
                    GpuVideoRenderer(reader.surface).use { gpu ->
                        var timestampNs = 1L
                        for (direct in listOf(false, true, true, false)) {
                            repeat(80) { iteration ->
                                val started = System.nanoTime()
                                val frame = if (direct) {
                                    checkNotNull(GpuVideoFrame.fromRaw(raw, format, width, height, timestampNs++))
                                } else {
                                    check(NativeUsbCapture.nativeConvertRawBufferToGpuBuffer(raw, raw.limit(), format, width, height, output))
                                    planar(output, format, width, height, timestampNs++)
                                }
                                val prepared = System.nanoTime()
                                gpu.render(frame)
                                GLES20.glFinish()
                                val finished = System.nanoTime()
                                awaitImage(reader).close()
                                if (iteration >= 20) {
                                    val mode = if (direct) 1 else 0
                                    prepareTimes[mode].add((prepared - started) / 1_000_000.0)
                                    totalTimes[mode].add((finished - started) / 1_000_000.0)
                                }
                            }
                        }
                    }
                }
                for (mode in 0..1) {
                    val times = totalTimes[mode].sorted()
                    println(String.format(Locale.ROOT,
                        "RAW_BENCH %dx%d format=%d direct=%s frames=%d prepare_ms=%.4f mean_ms=%.4f p95_ms=%.4f over_16_67ms=%d",
                        width, height, format, mode == 1, times.size, prepareTimes[mode].average(), times.average(),
                        times[ceil(times.size * 0.95).toInt() - 1], times.count { it > 1000.0 / 60 }))
                }
            }
        }
    }
}
