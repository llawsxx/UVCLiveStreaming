package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class HttpUploadRateMeterTest {
    @Test fun successfulTransferSamplesFallQuicklyRecoverSmoothlyAndExpire() {
        val meter = HttpUploadRateMeter()
        meter.record(1_000_000, 1_000_000_000L, 1_000_000_000L)
        assertEquals(8_000_000L, meter.reading(1_000_000_000L)!!.bitsPerSecond)
        meter.record(1_000_000, 2_000_000_000L, 3_000_000_000L)
        assertEquals(4_000_000L, meter.reading(3_000_000_000L)!!.bitsPerSecond)
        meter.record(1_000_000, 1_000_000_000L, 4_000_000_000L)
        assertEquals(6_000_000L, meter.reading(4_000_000_000L)!!.bitsPerSecond)
        assertEquals(10_000L, meter.reading(14_000_000_000L)!!.ageMs)
        assertNull(meter.reading(14_001_000_000L))
        assertNull(meter.reading(3_000_000_000L))
        meter.record(1_000_000, 1_000_000_000L, 15_000_000_000L)
        assertEquals(8_000_000L, meter.reading(15_000_000_000L)!!.bitsPerSecond)
    }

    @Test fun tinyFinalChunksAndInvalidDurationsDoNotInventCapacity() {
        val meter = HttpUploadRateMeter()
        meter.record(188, 1, 0)
        meter.record(100_000, 0, 0)
        assertNull(meter.reading(0))
    }

    private fun server(up: Long?, down: Long?, failures: Int = 0) = HttpUploadServerStats(
        "A", down, false, failures, feedbackAgeMs = down?.let { 0 },
        uploadBitsPerSecond = up, uploadRateAgeMs = up?.let { 0 })
    private fun budget(bits: Long, audio: Int = 192_000) = (bits * 0.85 / 1.06 - audio).toInt()

    @Test fun eachStoresUploadAndMergeLegsAreCombinedBeforeSummingAcrossStores() {
        // Summing each leg then taking min would incorrectly promise 21 Mbps for these paths.
        assertEquals(budget(2_000_000), httpMeasuredVideoBudget(listOf(
            server(20_000_000, 1_000_000), server(1_000_000, 20_000_000)), 192_000))
        assertEquals(budget(6_000_000), httpMeasuredVideoBudget(listOf(
            server(20_000_000, 3_000_000), server(20_000_000, 3_000_000)), 192_000))
        assertEquals(budget(4_000_000), httpMeasuredVideoBudget(listOf(server(4_000_000, 20_000_000)), 192_000))
    }

    @Test fun missingAndExpiredLegsUseOtherMeasuredLegAndUnknownDestinationsPreventPartialSum() {
        assertEquals(budget(4_000_000), httpMeasuredVideoBudget(listOf(server(4_000_000, null)), 192_000))
        assertEquals(budget(4_000_000), httpMeasuredVideoBudget(listOf(server(4_000_000, 1_000_000)
            .copy(feedbackAgeMs = 10_001)), 192_000))
        assertEquals(budget(3_000_000), httpMeasuredVideoBudget(listOf(server(1_000_000, 3_000_000)
            .copy(uploadRateAgeMs = 10_001)), 192_000))
        assertNull(httpMeasuredVideoBudget(listOf(server(4_000_000, 3_000_000), server(null, null)), 192_000))
        assertEquals(budget(3_000_000), httpMeasuredVideoBudget(listOf(
            server(4_000_000, 3_000_000), server(null, null, failures = 1)), 192_000))
    }
}
