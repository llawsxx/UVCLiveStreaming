package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class PreviewFrameLimiterTest {
    @Test fun highRateCaptureProducesFivePreviewFramesPerSecond() {
        for (captureFps in listOf(30, 60, 120)) {
            val limiter = PreviewFrameLimiter()
            val displayed = (0 until captureFps * 10).count {
                limiter.shouldRender(it * 1_000_000_000L / captureFps, true)
            }
            assertEquals("Capture $captureFps fps", 50, displayed)
        }
    }

    @Test fun slowerSourcesAreNeverForcedToGenerateFrames() {
        val limiter = PreviewFrameLimiter()
        assertEquals(20, (0 until 20).count { limiter.shouldRender(it * 500_000_000L, true) })
    }

    @Test fun togglingOffRestoresAllFramesAndTogglingOnStartsImmediately() {
        val limiter = PreviewFrameLimiter()
        assertTrue(limiter.shouldRender(0, true))
        assertFalse(limiter.shouldRender(10_000_000, true))
        for (i in 11..100) assertTrue(limiter.shouldRender(i * 1_000_000L, false))
        assertTrue(limiter.shouldRender(101_000_000, true))
        assertFalse(limiter.shouldRender(102_000_000, true))
        limiter.reset()
        assertTrue(limiter.shouldRender(103_000_000, true))
    }

    @Test fun stallsSkipMissedDeadlinesRatherThanBurstingOldFrames() {
        val limiter = PreviewFrameLimiter()
        assertTrue(limiter.shouldRender(0, true))
        assertTrue(limiter.shouldRender(3_010_000_000L, true))
        for (i in 3011..3199) assertFalse(limiter.shouldRender(i * 1_000_000L, true))
        assertTrue(limiter.shouldRender(3_200_000_000L, true))
    }
}
