package com.llawsxx.uvclivestreaming.recording

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class TestCardGpuTest {
    private val width = 640
    private val height = 360

    private fun pixels(reader: ImageReader): IntArray {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            reader.acquireLatestImage()?.let { image ->
                return image.use {
                    val plane = it.planes[0]
                    IntArray(it.width * it.height) { i ->
                        val offset = (i / it.width) * plane.rowStride + (i % it.width) * plane.pixelStride
                        (0xff shl 24) or ((plane.buffer.get(offset).toInt() and 255) shl 16) or
                            ((plane.buffer.get(offset + 1).toInt() and 255) shl 8) or
                            (plane.buffer.get(offset + 2).toInt() and 255)
                    }
                }
            }
            Thread.sleep(5)
        }
        error("No test card GPU output")
    }

    @Test fun cardsHaveExpectedColorsPixelStripesMotionAndHud() {
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { reader ->
            GpuVideoRenderer().use { gpu ->
                val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                var timestamp = 0L
                fun render(pattern: TestCardPattern, index: Long = 0): IntArray {
                    assertTrue(gpu.render(GpuVideoFrame(null, width, height, ++timestamp,
                        layout = GpuVideoFrame.RGB, fullRange = true,
                        testCard = TestCardFrame(pattern, 60000.0 / 1001, index)), target))
                    return pixels(reader)
                }
                val screenshots = mutableMapOf<TestCardPattern, IntArray>()
                for (pattern in TestCardPattern.entries) {
                    val image = render(pattern)
                    assertTrue("$pattern is blank", image.toSet().size > 2)
                    assertTrue("Missing HUD in $pattern", (348 until 354).any { y ->
                        (0 until width).any { x -> image[y * width + x] == -1 }
                    })
                    screenshots[pattern] = image
                }
                val colors = listOf(0xffffff, 0xffff00, 0x00ffff, 0x00ff00, 0xff00ff, 0xff0000, 0x0000ff, 0)
                for ((pattern, level) in listOf(TestCardPattern.EBU75 to .75, TestCardPattern.EBU100 to 1.0)) {
                    val image = screenshots.getValue(pattern)
                    colors.forEachIndexed { i, color ->
                        val actual = image[30 * width + (i * 2 + 1) * width / 16]
                        for (shift in listOf(0, 8, 16)) {
                            assertTrue("$pattern bar$i channel$shift", abs(((actual ushr shift) and 255) -
                                ((color ushr shift) and 255) * level) <= 1)
                        }
                    }
                }
                val stripes = screenshots.getValue(TestCardPattern.RESOLUTION)
                for (x in 10..100) assertEquals("1-pixel line $x", if (x % 2 == 0) 0 else 255,
                    stripes[30 * width + x] and 255)
                val levels = screenshots.getValue(TestCardPattern.LEVELS)
                for ((i, expected) in listOf(0,4,8,16,235,247,251,255).withIndex()) {
                    assertEquals(expected, levels[210 * width + (i * 2 + 1) * width / 16] and 255)
                }
                val motion0 = screenshots.getValue(TestCardPattern.MOTION)
                val motion1 = render(TestCardPattern.MOTION, 1)
                assertEquals(0, motion0[30 * width + 600] and 255)
                assertEquals(255, motion1[30 * width + 600] and 255)
                assertFalse("HUD frame/time did not advance", motion0.copyOfRange(346 * width, height * width)
                    .contentEquals(motion1.copyOfRange(346 * width, height * width)))
                // Verify switching back to real video on the same renderer uses its original shader.
                assertTrue(gpu.render(GpuVideoFrame(ByteArray(width * height * 3) { if (it % 3 == 0) 255.toByte() else 0 },
                    width, height, ++timestamp, GpuVideoFrame.RGB, true), target))
                assertEquals(0xff0000, pixels(reader)[0] and 0xffffff)
                val folder = File("/data/local/tmp/uvclive-test-card")
                if (folder.isDirectory && folder.canWrite()) screenshots.forEach { (pattern, data) ->
                    Bitmap.createBitmap(data, width, height, Bitmap.Config.ARGB_8888).let { bitmap ->
                        try { File(folder, "${pattern.name}.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        } } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }

    @Test fun virtualFramesReachAvcAndHevcWithFractionalPts() {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            val codec = MediaCodec.createEncoderByType(mime)
            val format = MediaFormat.createVideoFormat(mime, 256, 256).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val timeline = TestCardTimeline(60000.0 / 1001, 50_000_000_000L)
            val pts = mutableListOf<Long>()
            val info = MediaCodec.BufferInfo()
            var eos = false
            var surface: android.view.Surface? = null
            fun drain() {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) pts += info.presentationTimeUs
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
                    repeat(30) { index ->
                        gpu.render(GpuVideoFrame(null, 256, 256, timeline.timestamp(index.toLong()),
                            layout = GpuVideoFrame.RGB, fullRange = true,
                            testCard = TestCardFrame(TestCardPattern.MOTION, timeline.fps, index.toLong())))
                        drain()
                    }
                }
                codec.signalEndOfInputStream()
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!eos && SystemClock.elapsedRealtime() < deadline) drain()
                assertTrue("$mime missing EOS", eos)
                assertEquals("$mime dropped frames", 30, pts.size)
                pts.forEachIndexed { i, actual ->
                    assertTrue("$mime frame$i PTS $actual", abs(actual - timeline.timestamp(i.toLong()) / 1000) <= 1)
                }
                println("${codec.name}: 30 virtual frames, 60000/1001 PTS preserved")
            } finally { runCatching { codec.stop() }; codec.release(); surface?.release() }
        }
    }
}
