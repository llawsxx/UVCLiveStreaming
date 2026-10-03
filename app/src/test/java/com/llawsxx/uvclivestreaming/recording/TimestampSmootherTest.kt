package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class TimestampSmootherTest {
    private val origin = 12_000_000_000L

    @Test fun ntscOptionConvertsNominalRatesWithoutChangingFractionalOrPalModes() {
        assertEquals(60_000.0 / 1_001.0, videoSmoothingFrameRate(60.0, true), 0.0)
        assertEquals(30_000.0 / 1_001.0, videoSmoothingFrameRate(30.0, true), 0.0)
        for (fps in listOf(24.0, 25.0, 29.97, 50.0, 59.94, 120.0)) {
            assertEquals(fps, videoSmoothingFrameRate(fps, true), 0.0)
        }
        assertEquals(60.0, videoSmoothingFrameRate(60.0, false), 0.0)
        assertEquals(30.0, videoSmoothingFrameRate(30.0, false), 0.0)
    }

    @Test fun ntscTimelineUsesExactRatioInsteadOfRounded59Point94Or29Point97() {
        for (nominal in listOf(30, 60)) {
            val smoother = TimestampSmoother(videoSmoothingFrameRate(nominal.toDouble(), true), 0.1)
            val frameCount = nominal * 1_000
            for (frame in 0..frameCount) {
                val ideal = origin + (frame * 1_001_000_000.0 / nominal).roundToLong()
                val actual = ideal + if (frame == 0) 0 else (frame % 5 - 2) * 1_000_000L
                assertEquals(ideal, smoother.smooth(actual))
            }
            // 30,000/60,000 frame intervals must be exactly 1,001 seconds.
            assertEquals(origin + 1_001_000_000_000L,
                origin + (frameCount * 1_001_000_000.0 / nominal).roundToLong())
        }
    }

    @Test fun sixtyFpsRemovesJitterWithoutAccumulatingRoundingError() {
        val smoother = TimestampSmoother(60.0, 0.1)
        assertEquals(origin, smoother.smooth(origin))
        for (i in 1..36_000) {
            val ideal = origin + (i * 1_000_000_000.0 / 60).roundToLong()
            val jitter = if (i % 2 == 0) 3_000_000L else -2_000_000L
            assertEquals(ideal, smoother.smooth(ideal + jitter))
        }
    }

    @Test fun thresholdIsInclusiveAndLargeGapReanchorsTheTimeline() {
        val smoother = TimestampSmoother(50.0, 0.1)
        smoother.smooth(origin)
        assertEquals(origin + 20_000_000, smoother.smooth(origin + 120_000_000))
        val discontinuity = origin + 140_000_001
        assertEquals(discontinuity, smoother.smooth(discontinuity))
        assertEquals(discontinuity + 20_000_000, smoother.smooth(discontinuity + 23_000_000))
    }

    @Test fun audioUsesSampleFramesWithVariablePacketSizesAndNoDrift() {
        val smoother = TimestampSmoother(44_100.0, 0.1)
        var samples = 0L
        for (i in 0..10_000) {
            val sampleFrames = listOf(441L, 882L, 220L, 1024L)[i % 4]
            val ideal = origin + (samples * 1_000_000_000.0 / 44_100).roundToLong()
            val actual = ideal + if (i == 0) 0 else (i % 5 - 2) * 1_000_000L
            assertEquals(ideal, smoother.smooth(actual, sampleFrames))
            samples += sampleFrames
        }
    }

    @Test fun firstAudioAndVideoTimestampsKeepTheirOriginalOffset() {
        val video = TimestampSmoother(60.0, 0.1)
        val audio = TimestampSmoother(48_000.0, 0.1)
        assertEquals(origin, video.smooth(origin))
        assertEquals(origin + 15_000_000, audio.smooth(origin + 15_000_000, 480))
        assertEquals(origin + 25_000_000, audio.smooth(origin + 28_000_000, 480))
    }

    @Test fun droppedQueuedFramesKeepGapsInTheSurvivingTimeline() {
        val smoother = TimestampSmoother(50.0, 0.1)
        val first = smoother.smooth(origin)
        for (i in 1..4) smoother.smooth(origin + i * 20_000_000) // captured, then discarded by queue
        assertEquals(100_000_000L, smoother.smooth(origin + 102_000_000) - first)
    }

    @Test fun backwardsClockIsMonotonicAndRecoversAfterReset() {
        val smoother = TimestampSmoother(50.0, 0.1)
        val first = smoother.smooth(origin)
        val second = smoother.smooth(origin - 500_000_000)
        assertTrue(second > first)
        assertEquals(second + 20_000_000, smoother.smooth(second + 22_000_000))
    }

    @Test fun fractionalFpsAndConfiguredThresholdAreRespected() {
        val narrow = TimestampSmoother(59.94, 0.001)
        val wide = TimestampSmoother(59.94, 0.1)
        narrow.smooth(origin)
        wide.smooth(origin)
        val ideal = origin + (1_000_000_000.0 / 59.94).roundToLong()
        assertEquals(ideal + 2_000_000, narrow.smooth(ideal + 2_000_000))
        assertEquals(ideal, wide.smooth(ideal + 2_000_000))
    }
}
