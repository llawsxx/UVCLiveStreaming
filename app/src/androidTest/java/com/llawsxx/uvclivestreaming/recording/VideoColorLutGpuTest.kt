package com.llawsxx.uvclivestreaming.recording

import android.graphics.PixelFormat
import android.media.ImageReader
import android.opengl.GLES20
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class VideoColorLutGpuTest {
    private fun waitForLut(gpu: GpuVideoRenderer) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!gpu.colorGradeReady && SystemClock.elapsedRealtime() < deadline) Thread.sleep(2)
        assertTrue("Color LUT did not become ready", gpu.colorGradeReady)
    }

    private fun pixels(reader: ImageReader): List<IntArray> {
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) return image.use {
                val plane = it.planes[0]
                (0 until it.height).flatMap { y -> (0 until it.width).map { x ->
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    IntArray(3) { c -> plane.buffer.get(offset + c).toInt() and 255 }
                } }
            }
            Thread.sleep(2)
        }
        error("No GPU output")
    }

    @Test fun atlasMatchesReferenceFor33And65AndSharesPreviewEncoderOutput() {
        val colors = listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(255, 0, 0), intArrayOf(0, 255, 0),
            intArrayOf(0, 0, 255), intArrayOf(0, 128, 255), intArrayOf(40, 80, 40), intArrayOf(128, 100, 48),
            intArrayOf(16, 16, 16), intArrayOf(100, 100, 100), intArrayOf(200, 150, 80), intArrayOf(255, 128, 255),
            intArrayOf(64, 128, 47), intArrayOf(64, 128, 48), intArrayOf(180, 64, 247), intArrayOf(180, 64, 248))
        val data = colors.flatMap { it.toList() }.map { it.toByte() }.toByteArray()
        val settings = listOf(
            VideoColorGradeSettings(enabled = true, exposureEv = .4f, contrast = .85f,
                temperatureKelvin = 8000, tint = 8f, saturation = 1.2f, transfer = GradeTransfer.SRGB),
            VideoColorGradeSettings(enabled = true, exposureEv = -.3f, saturation = .6f, lutSize = 65),
            VideoColorGradeSettings(enabled = true, exposureEv = .5f, contrast = .9f, transfer = GradeTransfer.PQ, primaries = GradePrimaries.BT2020),
            VideoColorGradeSettings(enabled = true, exposureEv = -.5f, transfer = GradeTransfer.HLG, primaries = GradePrimaries.BT2020),
        )
        ImageReader.newInstance(8, 2, PixelFormat.RGBA_8888, 2).use { encoder ->
            ImageReader.newInstance(8, 2, PixelFormat.RGBA_8888, 2).use { preview ->
                GpuVideoRenderer(encoder.surface).use { gpu ->
                    val target = GpuVideoRenderer.PreviewTarget(preview.surface, 0)
                    settings.forEachIndexed { frame, s ->
                        val reference = checkNotNull(VideoColorLutBaker.bake(s))
                        gpu.setColorGrade(s); waitForLut(gpu)
                        assertTrue(gpu.render(GpuVideoFrame(data, 8, 2, frame + 1L, GpuVideoFrame.RGB, true), target))
                        val encoded = pixels(encoder); val displayed = pixels(preview)
                        colors.forEachIndexed { i, color ->
                            val expected = reference.sample(color[0]/255f, color[1]/255f, color[2]/255f)
                            repeat(3) { c ->
                                assertTrue("$s pixel $i channel $c", abs((expected[c]*255).roundToInt() - encoded[i][c]) <= 2)
                                assertEquals(encoded[i][c], displayed[i][c])
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun disablingAndNeutralSettingsBypassExactlyAndBgrUsesRgbLut() {
        ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { reader ->
            GpuVideoRenderer().use { gpu ->
                val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                val data = byteArrayOf(50, 80, 120, 50, 80, 120, 50, 80, 120, 50, 80, 120)
                val frame = GpuVideoFrame(data, 2, 2, 1, GpuVideoFrame.BGR, true)
                gpu.render(frame, target)
                val baseline = pixels(reader)
                val s = VideoColorGradeSettings(enabled = true, exposureEv = 1f, transfer = GradeTransfer.SRGB)
                gpu.setColorGrade(s); waitForLut(gpu)
                gpu.render(frame.copy(timestampNs = 2), target)
                val adjusted = pixels(reader)
                assertTrue(adjusted[0][0] > baseline[0][0] + 20)
                gpu.setColorGrade(s.copy(enabled = false))
                gpu.render(frame.copy(timestampNs = 3), target)
                pixels(reader).zip(baseline).forEach { (a, b) -> assertArrayEquals(b, a) }
                gpu.setColorGrade(VideoColorGradeSettings(enabled = true))
                gpu.render(frame.copy(timestampNs = 4), target)
                pixels(reader).zip(baseline).forEach { (a, b) -> assertArrayEquals(b, a) }
            }
        }
    }

    @Test fun yuvAndP010EnterTheSameLutAfterRangeConversion() {
        val s = VideoColorGradeSettings(enabled = true, exposureEv = 1f, transfer = GradeTransfer.LINEAR)
        ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2).use { reader ->
            GpuVideoRenderer(initialColorGrade = s).use { gpu ->
                waitForLut(gpu)
                val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                gpu.render(GpuVideoFrame(byteArrayOf(64,64,64,64,128.toByte(),128.toByte()), 2,2,1, fullRange = true), target)
                val eight = pixels(reader)[0]
                val ten = ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(4) { putShort((256 shl 6).toShort()) }; repeat(2) { putShort((512 shl 6).toShort()) }
                }.array()
                gpu.render(GpuVideoFrame(ten,2,2,2,GpuVideoFrame.YUV10,true), target)
                val tenBit = pixels(reader)[0]
                eight.forEach { assertTrue(abs(it - 128) <= 2) }
                tenBit.forEach { assertTrue(abs(it - 128) <= 2) }
            }
        }
    }

    /** Manual ABBA benchmark; excludes image acquisition and includes GPU completion. */
    fun benchmarkLutOverhead() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160)) {
            val ySize = width * height
            val buffer = ByteBuffer.allocateDirect(ySize * 3 / 2)
            for (y in 0 until height) for (x in 0 until width) buffer.put(((x * 255 / width + y / 8) % 256).toByte())
            repeat(ySize / 4) { buffer.put((80 + (it / 16) % 96).toByte()) }
            repeat(ySize / 4) { buffer.put((80 + (it / 31) % 96).toByte()) }
            val frame = GpuVideoFrame(null, width, height, 1, fullRange = true, directBuffer = buffer)
            val on = VideoColorGradeSettings(enabled = true, exposureEv = .35f, contrast = 1.05f, temperatureKelvin = 7200, saturation = 1.05f)
            val results = mutableMapOf(false to mutableListOf<Double>(), true to mutableListOf<Double>())
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { reader ->
                GpuVideoRenderer().use { gpu ->
                    val target = GpuVideoRenderer.PreviewTarget(reader.surface, 0)
                    for (enabled in listOf(false, true, true, false)) {
                        gpu.setColorGrade(on.copy(enabled = enabled)); waitForLut(gpu)
                        repeat(50) { i ->
                            val start = System.nanoTime()
                            gpu.render(frame, target)
                            GLES20.glFinish()
                            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                            discardImage(reader) // Outside the timed region; no pixel readback.
                            if (i >= 10) results.getValue(enabled).add(elapsedMs)
                        }
                    }
                }
            }
            for (enabled in listOf(false, true)) {
                val timings = results.getValue(enabled).sorted()
                println("LUT_BENCH ${width}x$height enabled=$enabled n=${timings.size} meanMs=${timings.average()} medianMs=${timings[timings.size/2]} p95Ms=${timings[(timings.size*.95).toInt()]}")
            }
        }
    }

    private fun discardImage(reader: ImageReader) {
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) { image.close(); return }
            Thread.sleep(2)
        }
        error("No GPU benchmark output")
    }
}
