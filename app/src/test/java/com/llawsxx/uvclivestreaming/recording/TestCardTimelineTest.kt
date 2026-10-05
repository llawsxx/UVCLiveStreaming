package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class TestCardTimelineTest {
    @Test fun fractionalRatesDoNotAccumulateRoundingDrift() {
        val origin = 123_456_789_000L
        for (fps in listOf(60.0, 60000.0 / 1001, 30000.0 / 1001, 29.97, 1.0, 240.0)) {
            val timeline = TestCardTimeline(fps, origin)
            for (index in listOf(0L, 1L, 2L, 59L, 600L, 216_000L, 5_184_000L)) {
                val timestamp = timeline.timestamp(index)
                assertEquals(origin + (index * 1_000_000_000.0 / fps).roundToLong(), timestamp)
                assertEquals("$fps frame $index", index, timeline.latestIndex(timestamp))
                assertEquals(index, timeline.latestIndex(timestamp + 1))
            }
        }
    }

    @Test fun lateRendererSkipsExpiredFramesAndPreservesAbsoluteTime() {
        val timeline = TestCardTimeline(60.0, 500_000_000L)
        assertEquals(0L, timeline.latestIndex(0L))
        assertEquals(6L, timeline.latestIndex(600_000_000L))
        assertEquals(60L, timeline.latestIndex(1_500_000_000L))
        assertEquals(1_500_000_000L, timeline.timestamp(60))
        assertEquals(60L, timeline.latestIndex(timeline.timestamp(61) - 1))
    }

    @Test fun rejectsInvalidSettingsAndRates() {
        assertTrue(TestCardSettings().valid)
        assertTrue(TestCardSettings(width = 3840, height = 2160, fps = 240.0).valid)
        for (settings in listOf(TestCardSettings(width = 0), TestCardSettings(width = 3841),
            TestCardSettings(height = 0), TestCardSettings(height = 2161),
            TestCardSettings(fps = Double.NaN), TestCardSettings(fps = 0.0), TestCardSettings(fps = 240.01))) {
            assertFalse(settings.valid)
        }
        for (fps in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 241.0)) {
            assertThrows(IllegalArgumentException::class.java) { TestCardTimeline(fps, 0) }
        }
    }
}
