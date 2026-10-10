package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodecInfo.CodecProfileLevel.*
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test

class VideoEncoderSettingsTest {
    private val avc = VideoEncoderCapabilities("test-avc", 0..10,
        listOf(AVCProfileBaseline to AVCLevel42, AVCProfileHigh to AVCLevel51))

    @Test fun defaultsLeaveAllKeysUnsetAndComplexityZeroIsExplicit() {
        assertTrue(avc.requestedValues(RecordingConfig()).isEmpty())
        val config = RecordingConfig(videoEncoderComplexity = 0)
        assertEquals(mapOf(MediaFormat.KEY_COMPLEXITY to 0), avc.requestedValues(config))
        assertTrue(config.customVideoEncoderParameters)
    }

    @Test fun profileWithAutomaticLevelUsesThatProfilesAdvertisedLevel() {
        val values = avc.requestedValues(RecordingConfig(videoEncoderProfile = AVCProfileBaseline))
        assertEquals(AVCProfileBaseline, values[MediaFormat.KEY_PROFILE])
        assertEquals(AVCLevel42, values[MediaFormat.KEY_LEVEL])
        assertEquals(AVCLevel31, avc.requestedValues(RecordingConfig(videoEncoderProfile = AVCProfileHigh,
            videoEncoderLevel = AVCLevel31))[MediaFormat.KEY_LEVEL])
    }

    @Test fun rejectsUnpairedLevelUnsupportedProfileAndOutOfRangeRequests() {
        for (config in listOf(RecordingConfig(videoEncoderLevel = AVCLevel31),
            RecordingConfig(videoEncoderProfile = AVCProfileMain),
            RecordingConfig(videoEncoderProfile = AVCProfileBaseline, videoEncoderLevel = AVCLevel52),
            RecordingConfig(videoEncoderComplexity = -1), RecordingConfig(videoEncoderComplexity = 11))) {
            try { avc.requestedValues(config); fail("Accepted unsupported request: $config") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun hevcMainTierDoesNotOfferHighTierAndProfilesHaveSeparateLimits() {
        val caps = VideoEncoderCapabilities("test-hevc", 0..0, listOf(
            HEVCProfileMain to HEVCMainTierLevel51, HEVCProfileMain10 to HEVCHighTierLevel52))
        val main = caps.levels(VideoCodec.H265, HEVCProfileMain)
        assertTrue(HEVCMainTierLevel31 in main)
        assertFalse(HEVCHighTierLevel31 in main)
        assertFalse(HEVCMainTierLevel52 in main)
        val main10 = caps.levels(VideoCodec.H265, HEVCProfileMain10)
        assertTrue(HEVCHighTierLevel31 in main10)
        assertTrue(HEVCMainTierLevel52 in main10)
        assertEquals("Main Tier 5.1", VideoCodec.H265.encoderLevelLabel(HEVCMainTierLevel51))
        assertEquals("4.2", VideoCodec.H264.encoderLevelLabel(AVCLevel42))
    }
}
