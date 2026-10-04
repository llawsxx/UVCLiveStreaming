package com.llawsxx.uvclivestreaming.recording

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class GpuVideoPipelineTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test fun hevcSurfaceOutputProducesDecodableEnhancedFlv() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 256, 256).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val frames = mutableListOf<Pair<Boolean, ByteArray>>()
        var configuration: ByteArray? = null
        var lengthSize = 4
        var eos = false
        var surface: android.view.Surface? = null
        val info = MediaCodec.BufferInfo()
        fun copy(buffer: ByteBuffer) = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        fun drain() {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    val csd = copy(checkNotNull(output.getByteBuffer("csd-0")))
                    lengthSize = RtmpHevc.sourceLengthSize(csd)
                    configuration = RtmpHevc.configuration(csd, output.getByteBuffer("csd-1")?.let(::copy))
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate().apply {
                                position(info.offset); limit(info.offset + info.size)
                            }
                            frames += (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) to
                                RtmpHevc.codedFrame(copy(buffer), lengthSize)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    } finally { codec.releaseOutputBuffer(index, false) }
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            GpuVideoRenderer(surface).use { gpu ->
                repeat(6) { frame ->
                    val rgb = ByteArray(256 * 256 * 3) { if (it % 3 == frame % 3) 200.toByte() else 50 }
                    gpu.render(GpuVideoFrame(rgb, 256, 256, 50_000_000_000L + frame * 33_333_333L,
                        GpuVideoFrame.RGB, true))
                    drain()
                }
            }
            codec.signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!eos && SystemClock.elapsedRealtime() < deadline) drain()
            assertTrue("HEVC encoder did not reach EOS", eos)
            assertEquals(6, frames.size)
            val header = checkNotNull(configuration)
            assertEquals(4, RtmpHevc.sourceLengthSize(header))
            val flv = ByteArrayOutputStream()
            DataOutputStream(flv).use { out ->
                out.write(bytes(0x46, 0x4c, 0x56, 1, 1, 0, 0, 0, 9)); out.writeInt(0)
                fun tag(time: Int, payload: ByteArray) {
                    out.writeByte(9)
                    out.writeByte(payload.size ushr 16); out.writeByte(payload.size ushr 8); out.writeByte(payload.size)
                    out.writeByte(time ushr 16); out.writeByte(time ushr 8); out.writeByte(time); out.writeByte(time ushr 24)
                    out.write(bytes(0, 0, 0)); out.write(payload); out.writeInt(11 + payload.size)
                }
                tag(0, bytes(0x90, 0x68, 0x76, 0x63, 0x31) + header)
                frames.forEachIndexed { index, (key, frame) ->
                    tag(index * 1000 / 30, bytes(if (key) 0x91 else 0xa1, 0x68, 0x76, 0x63, 0x31, 0, 0, 0) + frame)
                }
            }
            // A host smoke runner can decode this with FFmpeg, without installing the test APK.
            println("RTMP_HEVC_FLV_BASE64=" + Base64.encodeToString(flv.toByteArray(), Base64.NO_WRAP))
            println("${codec.name}: normalized HEVC CSD and ${frames.size} Surface frames")
        } finally {
            runCatching { codec.stop() }; codec.release(); surface?.release()
        }
    }

    @Test fun encoderColorRequestsReachH264AndHevcOutput() {
        for ((videoCodec, mime) in listOf(VideoCodec.H264 to MediaFormat.MIMETYPE_VIDEO_AVC,
            VideoCodec.H265 to MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            val config = RecordingConfig(videoCodec = videoCodec,
                colorStandard = VideoColorStandard.BT709,
                colorTransfer = VideoColorTransfer.BT709,
                colorRange = VideoColorRange.LIMITED,
                // Independent SPS override settings must not affect codec initialization.
                forceSpsVui = true, rewriteColorStandard = VideoColorStandard.BT2020,
                rewriteColorTransfer = VideoColorTransfer.ST2084, rewriteColorRange = VideoColorRange.FULL)
            val format = MediaFormat.createVideoFormat(mime, 256, 256).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                applyEncoderColorSettings(RecordingConfig())
                assertFalse(containsKey(MediaFormat.KEY_COLOR_STANDARD))
                assertFalse(containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                assertFalse(containsKey(MediaFormat.KEY_COLOR_RANGE))
                applyEncoderColorSettings(config)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            val caps = checkNotNull(codec.codecInfo.getCapabilitiesForType(mime).videoCapabilities)
            println("${codec.name}: supports256=${caps.isSizeSupported(256, 256)} widths=${caps.supportedWidths} heights=${caps.supportedHeights}; request=$format")
            var surface: android.view.Surface? = null
            var reported: MediaFormat? = null
            var samples = 0
            var eos = false
            val info = MediaCodec.BufferInfo()
            fun drain(timeoutUs: Long) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, timeoutUs)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) reported = codec.outputFormat
                    else if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) samples++
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                        codec.releaseOutputBuffer(index, false)
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                }
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
                GpuVideoRenderer(surface).use { gpu ->
                    val rgb = ByteArray(256 * 256 * 3) { if (it % 3 == 1) 200.toByte() else 50 }
                    repeat(3) {
                        gpu.render(GpuVideoFrame(rgb, 256, 256, 50_000_000_000L + it * 33_333_333L, GpuVideoFrame.RGB, true))
                        drain(10_000)
                    }
                }
                codec.signalEndOfInputStream()
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!eos && SystemClock.elapsedRealtime() < deadline) drain(10_000)
                assertTrue("$mime did not reach EOS", eos)
                assertEquals("$mime lost encoded frames", 3, samples)
                val output = checkNotNull(reported)
                assertEquals(MediaFormat.COLOR_STANDARD_BT709, output.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, output.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                assertEquals(MediaFormat.COLOR_RANGE_LIMITED, output.getInteger(MediaFormat.KEY_COLOR_RANGE))
                println("${codec.name}: reported BT.709 / SDR / Limited after Surface encoding")
            } finally {
                runCatching { codec.stop() }; codec.release(); surface?.release()
            }
        }
    }

    @Test fun rawDirectOutputMatchesLegacyAndProtectsBufferBounds() {
        for (format in listOf(2, 3, 5, 6, 7, 4, 9)) {
            val dimensions = if (format in listOf(4, 6, 9)) listOf(2 to 2, 8 to 6, 33 to 31)
                else listOf(2 to 2, 8 to 6)
            for ((width, height) in dimensions) {
                val pixels = width * height
                val yuvSize = pixels + 2 * ((width + 1) / 2) * ((height + 1) / 2)
                val inputSize = when (format) { 2, 3 -> pixels * 2; 4, 7, 9 -> pixels * 3; else -> yuvSize }
                val size = if (format == 4 || format == 9) pixels * 3 else yuvSize
                val input = ByteArray(inputSize + 3) { ((it * 73 + format * 19) and 255).toByte() }
                val original = input.copyOf()
                val reference = if (format == 4 || format == 9) input.copyOf(size)
                    else NativeUsbCapture.nativeDecodeToI420(input, format, width, height)!!.copyOf(size)
                val guarded = ByteBuffer.allocateDirect(size + 16)
                repeat(3) {
                    for (i in 0 until guarded.capacity()) guarded.put(i, 0x5a.toByte())
                    val output = guarded.duplicate().apply { position(8); limit(8 + size) }.slice()
                    output.position(1)
                    assertTrue(NativeUsbCapture.nativeConvertRawToGpuBuffer(input, format, width, height, output))
                    assertEquals(1, output.position())
                    val actual = ByteArray(size).also { output.duplicate().apply { clear(); get(it) } }
                    assertArrayEquals(reference, actual)
                    assertArrayEquals(original, input)
                    for (i in 0 until 8) assertEquals(0x5a.toByte(), guarded.get(i))
                    for (i in size + 8 until guarded.capacity()) assertEquals(0x5a.toByte(), guarded.get(i))
                }
                assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(input, format, width, height, ByteBuffer.allocate(size)))
                assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(input, format, width, height, ByteBuffer.allocateDirect(size - 1)))
                assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(input, format, width, height, guarded.asReadOnlyBuffer()))
                assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(input.copyOf(inputSize - 1), format, width, height, guarded))
            }
        }
        val output = ByteBuffer.allocateDirect(64)
        for (format in listOf(0, 1, 8, 10))
            assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(ByteArray(64), format, 2, 2, output))
        for (format in listOf(2, 3, 5, 7))
            assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(ByteArray(64), format, 3, 2, output))
        assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(ByteArray(64), 6, 0, 2, output))
        assertFalse(NativeUsbCapture.nativeConvertRawToGpuBuffer(ByteArray(64), 6, 3841, 2, output))
    }

    @Test fun rawDirectPipelinePreservesGpuPixelsLayoutAndRange() {
        ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { reader ->
            GpuVideoRenderer().use { gpu ->
                RawVideoConverter().use { converter ->
                    val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                    for (format in listOf(2, 3, 5, 6, 7, 4, 9)) repeat(3) { round ->
                        val inputSize = when (format) { 2, 3 -> 8; 4, 7, 9 -> 12; else -> 6 }
                        val input = ByteArray(inputSize) { ((it * 73 + format * 19 + round * 11) and 255).toByte() }
                        val reference = GpuVideoFrame.fromUsb(input, format, 2, 2, 1)!!
                        assertTrue(gpu.render(reference, target))
                        val expected = readPixels(reader)
                        converter.convert(input, format, 2, 2, 2)!!.use { converted ->
                            assertEquals(reference.layout, converted.frame.layout)
                            assertEquals(reference.fullRange, converted.frame.fullRange)
                            assertTrue(gpu.render(converted.frame, target))
                            expected.zip(readPixels(reader)).forEach { (a, b) -> assertArrayEquals(a, b) }
                        }
                    }
                    assertEquals(0, converter.diagnostics().inUse)
                    assertTrue(converter.diagnostics().reuses > 0)
                }
            }
        }
    }

    @Test fun directMjpegOutputMatchesLegacyIncludingMarkerRecoveryAndBounds() {
        val bitmap = Bitmap.createBitmap(33, 31, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                bitmap.setPixel(x, y, Color.rgb(x * 7, y * 8, (x + y) * 3))
            ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val size = 33 * 31 + 2 * 17 * 16
        val output = ByteBuffer.allocateDirect(size + 16)
        val variants = listOf(jpeg, bytes(0, 1, 2) + jpeg + bytes(0, 0),
            jpeg.copyOf(jpeg.size - 2), jpeg.copyOfRange(2, jpeg.size))
        repeat(3) {
            for (input in variants) {
                val reference = NativeUsbCapture.nativeDecodeToI420(input, 1, 33, 31)
                assertNotNull(reference)
                for (i in 0 until output.capacity()) output.put(i, 0x5a.toByte())
                output.position(7)
                assertTrue(NativeUsbCapture.nativeDecodeMjpegToI420(input, 33, 31, output))
                assertEquals(7, output.position())
                val actual = ByteArray(size)
                output.duplicate().apply { clear(); get(actual) }
                assertArrayEquals(reference, actual)
                for (i in size until output.capacity()) assertEquals(0x5a.toByte(), output.get(i))
            }
        }
        assertFalse(NativeUsbCapture.nativeDecodeMjpegToI420(jpeg, 33, 31, ByteBuffer.allocate(size)))
        assertFalse(NativeUsbCapture.nativeDecodeMjpegToI420(jpeg, 33, 31, ByteBuffer.allocateDirect(size - 1)))
        assertFalse(NativeUsbCapture.nativeDecodeMjpegToI420(jpeg, 33, 31, output.asReadOnlyBuffer()))
        assertFalse(NativeUsbCapture.nativeDecodeMjpegToI420(jpeg, 32, 31, output))
        assertFalse(NativeUsbCapture.nativeDecodeMjpegToI420(bytes(0, 1, 2), 33, 31, output))
        assertTrue(NativeUsbCapture.nativeDecodeMjpegToI420(jpeg, 33, 31, output))
    }

    @Test fun directBufferUploadsTheSamePixelsAndCanBeReusedAfterRender() {
        DirectVideoBufferPool(1).use { buffers ->
            ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { reader ->
                GpuVideoRenderer().use { gpu ->
                    val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                    val original = bytes(235, 235, 16, 16, 128, 128)
                    assertTrue(gpu.render(GpuVideoFrame(original, 2, 2, 1), target))
                    val expected = readPixels(reader)
                    val first = buffers.acquire(6)!!
                    val memory = first.buffer
                    try {
                        memory.put(original)
                        assertTrue(gpu.render(GpuVideoFrame(null, 2, 2, 2, directBuffer = memory), target))
                    } finally { first.close() }
                    expected.zip(readPixels(reader)).forEach { (a, b) -> assertArrayEquals(a, b) }
                    buffers.acquire(6)!!.use { second ->
                        assertSame(memory, second.buffer)
                        second.buffer.put(bytes(16, 16, 235, 235, 128, 128))
                        assertTrue(gpu.render(GpuVideoFrame(null, 2, 2, 3, directBuffer = second.buffer), target))
                        val reversed = readPixels(reader)
                        assertTrue(reversed[0].all { it < 10 })
                        assertTrue(reversed[2].all { it > 245 })
                    }
                }
            }
        }
    }

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
        // UVC cameras often omit standard DHT segments. Bitmap.compress may
        // use custom tables, so stripping them cannot promise the original
        // pixels; verify that both output paths use the same fallback instead.
        val withoutDht = ByteArrayOutputStream()
        var offset = 0
        while (offset < jpeg.size) {
            if (offset + 3 < jpeg.size && jpeg[offset] == 0xff.toByte() && jpeg[offset + 1] == 0xc4.toByte()) {
                val length = ((jpeg[offset + 2].toInt() and 255) shl 8) or (jpeg[offset + 3].toInt() and 255)
                offset += length + 2
            } else { withoutDht.write(jpeg[offset].toInt()); offset++ }
        }
        val fallback = NativeUsbCapture.nativeDecodeToI420(withoutDht.toByteArray(), 1, 32, 32)
        assertNotNull(fallback)
        val direct = ByteBuffer.allocateDirect(reference.size)
        assertTrue(NativeUsbCapture.nativeDecodeMjpegToI420(withoutDht.toByteArray(), 32, 32, direct))
        assertArrayEquals(fallback, ByteArray(reference.size).also { direct.get(it) })
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
