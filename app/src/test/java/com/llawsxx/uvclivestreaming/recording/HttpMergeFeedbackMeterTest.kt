package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class HttpMergeFeedbackMeterTest {
    private val headers = mapOf("x-relay-mode" to "store", "x-merge-session" to "live",
        "x-merge-pending-us" to "4000000", "x-merge-wait-ms" to "1500", "x-merge-feedback-age-ms" to "2000")

    @Test fun forwardedSnapshotsRetainTheirAgeAndFreshAcksCannotKeepDeadMergeFeedbackAlive() {
        val meter = HttpMergeFeedbackMeter()
        meter.record(headers, 5_000_000_000)
        assertEquals(HttpMergeFeedback("live", 4_000_000, 1500, 2000), meter.reading(5_000_000_000))
        meter.record(headers + ("x-merge-feedback-age-ms" to "7000"), 10_000_000_000)
        assertEquals(7000L, meter.reading(10_000_000_000)!!.ageMs)
        assertNull(meter.reading(13_001_000_000))
    }

    @Test fun oldInFlightAckCannotOverwriteNewerFeedback() {
        val meter = HttpMergeFeedbackMeter()
        meter.record(headers + ("x-merge-feedback-age-ms" to "0"), 5_000_000_000)
        meter.record(headers + ("x-merge-pending-us" to "9000000"), 6_000_000_000)
        assertEquals(4_000_000L, meter.reading(6_000_000_000)!!.pendingDurationUs)
        assertEquals(1000L, meter.reading(6_000_000_000)!!.ageMs)
    }

    @Test fun legacyPartialInvalidAndExpiredFeedbackAreIgnored() {
        val invalid = listOf(emptyMap(), headers - "x-merge-wait-ms",
            headers + ("x-merge-feedback-age-ms" to "10001"),
            headers + ("x-merge-pending-us" to "-1"), headers + ("x-merge-wait-ms" to "bad"),
            headers + ("x-relay-mode" to "relay"))
        invalid.forEach { value ->
            val meter = HttpMergeFeedbackMeter()
            meter.record(value, 5_000_000_000)
            assertNull(meter.reading(5_000_000_000))
        }
    }
}
