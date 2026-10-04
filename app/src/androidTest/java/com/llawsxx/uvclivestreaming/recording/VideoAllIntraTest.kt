package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoAllIntraTest {
    @Test fun everySurfaceFrameIsAKeyFrameForH264AndHevc() {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            encode(mime, allIntra = false) // Same input must produce inter frames normally.
            encode(mime, allIntra = true)
            encode(mime, allIntra = false, intervalSeconds = 0.5f)
        }
    }

    private fun encode(mime: String, allIntra: Boolean, intervalSeconds: Float = if (allIntra) 0f else 2f) {
        val width = 1280
        val height = 720
        val frameCount = 60
        val config = RecordingConfig(videoKeyFrameIntervalSeconds = intervalSeconds,
            videoMaxBFrames = if (allIntra) 3 else 0)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            applyEncoderGopSettings(config)
        }
        if (allIntra) assertEquals(0, format.getInteger(MediaFormat.KEY_MAX_B_FRAMES))
        assertEquals(intervalSeconds, format.getFloat(MediaFormat.KEY_I_FRAME_INTERVAL), 0f)
        val codec = MediaCodec.createEncoderByType(mime)
        var surface: Surface? = null
        var frames = 0
        var keys = 0
        var eos = false
        val info = MediaCodec.BufferInfo()
        val outputPts = mutableListOf<Long>()
        fun drain(timeoutUs: Long) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, timeoutUs)
                if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            frames++
                            outputPts += info.presentationTimeUs
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (key) keys++
                            if (allIntra) {
                                assertTrue("${codec.name} frame $frames is not flagged as key", key)
                                val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate().apply {
                                    position(info.offset); limit(info.offset + info.size)
                                }
                                val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                val types = nalTypes(data, mime == MediaFormat.MIMETYPE_VIDEO_HEVC)
                                val vcl = types.filter { if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) it in 0..31 else it in 1..5 }
                                assertTrue("No VCL NAL in frame $frames: $types", vcl.isNotEmpty())
                                assertTrue("Non-IDR/IRAP VCL in frame $frames: $vcl", vcl.all {
                                    if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) it in 16..23 else it == 5
                                })
                            }
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
                val data = ByteArray(width * height * 3 / 2) { if (it < width * height) 90 else 128.toByte() }
                repeat(frameCount) { frame ->
                    data[frame] = (frame + 20).toByte()
                    gpu.render(GpuVideoFrame(data, width, height, 50_000_000_000L + frame * 1_000_000_000L / 60))
                    drain(10_000)
                }
            }
            codec.signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!eos && SystemClock.elapsedRealtime() < deadline) drain(10_000)
            assertTrue("${codec.name} did not reach EOS", eos)
            assertEquals(frameCount, frames)
            if (allIntra) assertEquals(frameCount, keys)
            else assertTrue("Normal GOP unexpectedly has no inter frames", keys < frames)
            if (intervalSeconds == 0.5f) assertTrue("Fractional GOP was ignored", keys >= 2)
            outputPts.forEachIndexed { frame, pts ->
                assertTrue("Output PTS changed or reordered", kotlin.math.abs(pts - (50_000_000L + frame * 1_000_000L / 60)) <= 1L)
            }
            println("${codec.name}: 720p60 GOP=$intervalSeconds frames=$frames keys=$keys, source PTS preserved, EOS reached")
        } finally {
            runCatching { codec.stop() }; codec.release(); surface?.release()
        }
    }

    private fun nalTypes(data: ByteArray, hevc: Boolean): List<Int> {
        val headers = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val prefix = if (data[i + 2] == 1.toByte()) 3
                    else if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) 4 else 0
                if (prefix != 0 && i + prefix < data.size) {
                    headers += data[i + prefix].toInt() and 0xff
                    i += prefix + 1
                    continue
                }
            }
            i++
        }
        if (headers.isEmpty()) {
            // MediaCodec may return four-byte length-prefixed access units.
            i = 0
            while (i + 4 < data.size) {
                var length = 0
                repeat(4) { length = (length shl 8) or (data[i + it].toInt() and 0xff) }
                assertTrue(length > 0 && length <= data.size - i - 4)
                headers += data[i + 4].toInt() and 0xff
                i += 4 + length
            }
            assertEquals(data.size, i)
        }
        return headers.map { if (hevc) (it ushr 1) and 63 else it and 31 }
    }
}
