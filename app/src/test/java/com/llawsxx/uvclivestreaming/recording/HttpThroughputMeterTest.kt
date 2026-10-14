package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class HttpThroughputMeterTest {
    @Test fun burstTransfersExposeThreeMbpsSustainedCapacityAcrossBothWindows() {
        val meter = HttpThroughputMeter()
        meter.demand(0, true)
        // 0.75 MB arrives in 0.5 s (12 Mbps); another 1.5 s passes with work waiting.
        repeat(21) { meter.record(750_000, (it * 2L * 1_000_000_000L) + 500_000_000L) }
        val reading = meter.reading(40_500_000_000L)!!
        assertEquals(3_000_000L, reading.shortBitsPerSecond)
        assertEquals(3_000_000L, reading.longBitsPerSecond)
        assertNull(meter.reading(50_501_000_000L))
    }

    @Test fun waitingForNewSourceDataDoesNotClaimALowCapacity() {
        val meter = HttpThroughputMeter()
        repeat(60) { second ->
            val start = second * 1_000_000_000L
            meter.demand(start, true)
            meter.record(250_000, start + 100_000_000L)
            meter.demand(start + 100_000_000L, false)
        }
        assertNull(meter.reading(60_000_000_000L))
    }

    @Test fun completedWindowExpiresEvenWhenANewEpochHasNotYetQualified() {
        val meter = HttpThroughputMeter()
        meter.demand(0, true)
        meter.record(3_750_000, 10_000_000_000L)
        meter.demand(10_000_000_000L, false)
        meter.demand(20_000_000_000L, true)
        meter.record(100_000, 20_100_000_000L)
        assertNull(meter.reading(20_100_000_000L))
    }

    private fun headers(age: Long, windowAge: Long = age) = mapOf(
        "x-relay-mode" to "store", "x-download-rate-bps" to "1500000",
        "x-download-rate-age-ms" to age.toString(), "x-download-short-rate-bps" to "375000",
        "x-download-long-rate-bps" to "375000", "x-download-window-age-ms" to windowAge.toString())

    @Test fun forwardedReportsPreserveOriginalTransferAndWindowAges() {
        val meter = HttpDownloadRateMeter()
        for (second in 1..10) meter.record(headers(second * 1_000L), second * 1_000_000_000L)
        val reading = meter.reading(10_000_000_000L)!!
        assertEquals(10_000L, reading.ageMs)
        assertEquals(12_000_000L, reading.bitsPerSecond)
        assertEquals(3_000_000L, reading.throughput!!.bitsPerSecond)
        assertNull(meter.reading(10_001_000_000L))
        meter.record(headers(0), 12_000_000_000L)
        meter.record(headers(5_000), 13_000_000_000L) // An older response arrives after the new sample.
        assertEquals(1_000L, meter.reading(13_000_000_000L)!!.ageMs)
    }

    @Test fun oldWindowCannotBeRefreshedByFreshSingleBlockSamples() {
        val meter = HttpDownloadRateMeter()
        meter.record(headers(0, 9_000), 10_000_000_000L)
        assertEquals(3_000_000L, meter.reading(11_000_000_000L)!!.throughput!!.bitsPerSecond)
        assertNull(meter.reading(11_001_000_000L)!!.throughput)
        meter.record(headers(0, 10_001), 12_000_000_000L)
        assertNull(meter.reading(12_000_000_000L)!!.throughput)
    }
}
