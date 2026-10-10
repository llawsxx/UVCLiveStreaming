package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test

class VideoEncoderSettingsDeviceTest {
    @Test fun explicitRequestsEncodeAvcAndHevcThroughSurfaceAndYuv() {
        for (videoCodec in VideoCodec.entries) {
            val available = readVideoEncoderCapabilities(videoCodec)
            val preferred = if (videoCodec == VideoCodec.H264) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            val profile = preferred.takeIf { it in available.profiles } ?: available.profiles.first()
            val suggestedLevel = if (videoCodec == VideoCodec.H264) MediaCodecInfo.CodecProfileLevel.AVCLevel31
                else MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31
            for (direct in listOf(false, true)) {
                val codec = MediaCodec.createEncoderByType(videoCodec.encoderMime)
                val caps = VideoEncoderCapabilities.from(codec.codecInfo, videoCodec.encoderMime)
                val level = suggestedLevel.takeIf { it in caps.levels(videoCodec, profile) }
                val config = RecordingConfig(videoCodec = videoCodec,
                    videoEncoderComplexity = if (direct) caps.complexityRange.last else caps.complexityRange.first,
                    videoEncoderProfile = profile, videoEncoderLevel = if (direct) level else null)
                val format = MediaFormat.createVideoFormat(videoCodec.encoderMime, 256, 256).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, if (direct) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        else MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    applyEncoderAdvancedSettings(RecordingConfig(), caps)
                    assertFalse(containsKey(MediaFormat.KEY_COMPLEXITY))
                    assertFalse(containsKey(MediaFormat.KEY_PROFILE))
                    assertFalse(containsKey(MediaFormat.KEY_LEVEL))
                    applyEncoderAdvancedSettings(config, caps)
                    if (!direct) assertTrue("Automatic level overstates the small video mode",
                        getInteger(MediaFormat.KEY_LEVEL) <= checkNotNull(level))
                }
                var surface: android.view.Surface? = null
                val info = MediaCodec.BufferInfo()
                var output: MediaFormat? = null
                var samples = 0
                var eos = false
                fun drain() {
                    while (true) {
                        val index = codec.dequeueOutputBuffer(info, 10_000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) output = codec.outputFormat
                        else if (index >= 0) {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) samples++
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                            codec.releaseOutputBuffer(index, false)
                        } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    }
                }
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    if (!direct) surface = codec.createInputSurface()
                    codec.start()
                    if (direct) {
                        val pixels = ByteArray(256 * 256 * 3 / 2) { if (it < 256 * 256) 16 else 128.toByte() }
                        repeat(6) { frame ->
                            val index = codec.dequeueInputBuffer(2_000_000)
                            assertTrue("No YUV input buffer", index >= 0)
                            checkNotNull(codec.getInputBuffer(index)).apply { clear(); put(pixels) }
                            codec.queueInputBuffer(index, 0, pixels.size, frame * 33_333L, 0)
                            drain()
                        }
                        val index = codec.dequeueInputBuffer(2_000_000)
                        assertTrue(index >= 0)
                        codec.queueInputBuffer(index, 0, 0, 6 * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        GpuVideoRenderer(checkNotNull(surface)).use { gpu ->
                            repeat(6) { frame ->
                                gpu.render(GpuVideoFrame(null, 256, 256, 50_000_000_000L + frame * 33_333_333L,
                                    layout = GpuVideoFrame.RGB, fullRange = true,
                                    testCard = TestCardFrame(TestCardPattern.MOTION, 30.0, frame.toLong())))
                                drain()
                            }
                        }
                        codec.signalEndOfInputStream()
                    }
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (!eos && SystemClock.elapsedRealtime() < deadline) drain()
                    assertTrue("${codec.name} missing EOS", eos)
                    assertEquals("${codec.name} dropped frames", 6, samples)
                    assertEquals("Encoder did not apply the selected profile", profile,
                        checkNotNull(output).getInteger(MediaFormat.KEY_PROFILE))
                    println("PASS encoder=${codec.name} input=${if (direct) "YUV" else "Surface"} " +
                        "complexityRange=${caps.complexityRange} request=$format output=$output frames=$samples")
                } finally {
                    runCatching { codec.stop() }; codec.release(); surface?.release()
                }
            }
        }
    }
}
