package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class PcmAudioTimingTest {
    @Test fun disabledSmoothingRetainsClockGapsAndAdvanceDoesNotClampNegativeEncoderPts() {
        val captureTimes = listOf(1_000_000_000L, 1_010_000_000L, 1_100_000_000L)
        val adjusted = captureTimes.map { pcmTimestampWithDelayNs(it, -200) }
        assertEquals(listOf(800_000_000L, 810_000_000L, 900_000_000L), adjusted)
        assertEquals(captureTimes.zipWithNext().map { it.second - it.first },
            adjusted.zipWithNext().map { it.second - it.first })
        val encoderPts = adjusted.map { (it - 1_000_000_000L) / 1000 }
        assertEquals(listOf(-200_000L, -190_000L, -100_000L), encoderPts)
    }

    @Test fun enabledPcmAndAacSmoothingRetainTheRequestedConstantOffset() {
        for (delayMs in listOf(-500, -200, 0, 200, 500)) {
            val pcmSmoother = TimestampSmoother(48000.0, 0.1)
            val aacSmoother = TimestampSmoother(48000.0, 0.1)
            val origin = 1_000_000_000L
            val output = (0..9).map { index ->
                val jitter = if (index == 0) 0 else if (index % 2 == 0) 300_000 else -300_000
                val captured = origin + index * 1024L * 1_000_000_000L / 48000 + jitter
                val adjusted = pcmTimestampWithDelayNs(pcmSmoother.smooth(captured, 1024), delayMs)
                aacSmoother.smooth(adjusted, 1024)
            }
            assertEquals(origin + delayMs * 1_000_000L, output.first())
            assertTrue(output.zipWithNext().all { (a, b) -> b > a })
            assertEquals(192_000_000L, output.last() - output.first())
        }
    }

    @Test fun pcmSmootherRealignmentSurvivesTheDelayAdjustment() {
        val smoother = TimestampSmoother(1000.0, 0.001)
        val first = pcmTimestampWithDelayNs(smoother.smooth(1_000_000_000L, 3), 200)
        val afterGap = pcmTimestampWithDelayNs(smoother.smooth(1_020_000_000L, 3), 200)
        assertEquals(20_000_000L, afterGap - first)
    }
}
