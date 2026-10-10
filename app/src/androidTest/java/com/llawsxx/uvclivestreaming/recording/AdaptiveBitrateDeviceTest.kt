package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import java.util.Random
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.*
import org.junit.Test

class AdaptiveBitrateDeviceTest {
    @Test fun avcAndHevcChangeBitrateWithoutRestartThroughSurfaceAndYuv() {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            for (direct in listOf(false, true)) encode(mime, direct, true)
        }
    }

    private fun encode(mime: String, direct: Boolean, cbr: Boolean) {
        val fps = 30
        val phaseFrames = fps * 3
        val totalFrames = phaseFrames * 4
        val high = 6_000_000
        val low = 1_500_000
        val width = 1280
        val height = 720
        val codec = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, if (direct) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_BIT_RATE, high)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val info = MediaCodec.BufferInfo()
        val bytes = LongArray(4)
        val counts = IntArray(4)
        var samples = 0
        var eos = false
        var formats = 0
        var surface: android.view.Surface? = null
        var gpu: GpuVideoRenderer? = null
        val random = Random(42)
        val pixels = if (direct) ByteArray(width * height * 3 / 2) else null
        fun drain() {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 1_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) formats++
                else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        samples++
                        val frame = (info.presentationTimeUs * fps / 1_000_000).toInt()
                        val phase = frame / phaseFrames
                        // Exclude the first second after each change to allow encoder rate control to settle.
                        if (phase in 0..3 && frame % phaseFrames >= fps) {
                            bytes[phase] += info.size; counts[phase]++
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    codec.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (!direct) surface = codec.createInputSurface()
            codec.start()
            if (!direct) gpu = GpuVideoRenderer(checkNotNull(surface))
            val started = System.nanoTime()
            repeat(totalFrames) { frame ->
                if (frame > 0 && frame % phaseFrames == 0) codec.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, if (frame / phaseFrames % 2 == 1) low else high)
                })
                val remaining = started + frame * 1_000_000_000L / fps - System.nanoTime()
                if (remaining > 0) LockSupport.parkNanos(remaining)
                val timestampNs = frame * 1_000_000_000L / fps
                if (direct) {
                    random.nextBytes(checkNotNull(pixels))
                    val index = codec.dequeueInputBuffer(2_000_000)
                    assertTrue("Missing YUV input", index >= 0)
                    // Match production's writable Image input, including vendor row/pixel strides.
                    checkNotNull(codec.getInputImage(index)).use { image ->
                        var source = 0
                        image.planes.forEachIndexed { planeIndex, plane ->
                            val planeWidth = if (planeIndex == 0) width else width / 2
                            val planeHeight = if (planeIndex == 0) height else height / 2
                            for (y in 0 until planeHeight) for (x in 0 until planeWidth) {
                                // Bounded temporal noise, comparable to the 50% RGB test card;
                                // full-range random Y/U/V can exceed even the largest supported QP.
                                val noise = (pixels[source++].toInt() and 255) - 128
                                plane.buffer.put(y * plane.rowStride + x * plane.pixelStride,
                                    (128 + noise / if (planeIndex == 0) 2 else 4).toByte())
                            }
                        }
                    }
                    codec.queueInputBuffer(index, 0, pixels.size, timestampNs / 1000, 0)
                } else checkNotNull(gpu).render(GpuVideoFrame(null, width, height, timestampNs,
                    layout = GpuVideoFrame.RGB, fullRange = true,
                    testCard = TestCardFrame(TestCardPattern.EBU75, fps.toDouble(), frame.toLong(), 50)))
                drain()
            }
            if (direct) {
                val index = codec.dequeueInputBuffer(2_000_000)
                assertTrue(index >= 0)
                codec.queueInputBuffer(index, 0, 0, totalFrames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else codec.signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!eos && SystemClock.elapsedRealtime() < deadline) drain()
            val rates = bytes.indices.map { bytes[it] * 8L * fps / counts[it].coerceAtLeast(1) }
            val label = "${codec.name} ${if (direct) "YUV" else "Surface"} ${if (cbr) "CBR" else "default"}"
            println("$label target=$high/$low/$high/$low actual=$rates frames=$samples formats=$formats")
            assertTrue("$label missing EOS", eos)
            assertEquals("$label dropped frames", totalFrames, samples)
            assertTrue("$label missing phase samples", counts.all { it >= fps })
            // Some encoders initially undershoot the configured rate while warming up;
            // compare both low phases with the high phase after startup, then lower again.
            assertTrue("$label did not reduce actual bitrate: $rates", rates[1] < rates[2] * 0.65)
            assertTrue("$label did not recover actual bitrate: $rates", rates[2] > rates[1] * 1.5)
            assertTrue("$label did not reduce again after recovery: $rates", rates[3] < rates[2] * 0.65)
        } finally {
            gpu?.close(); runCatching { codec.stop() }; codec.release(); surface?.release()
        }
    }
}
