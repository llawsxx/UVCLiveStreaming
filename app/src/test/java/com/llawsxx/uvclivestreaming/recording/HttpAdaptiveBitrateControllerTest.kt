package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class HttpAdaptiveBitrateControllerTest {
    @Test fun adaptiveDefaultUsesSupportedCbrAndExplicitModesAreRespected() {
        val adaptive = RecordingConfig(httpUploadEnabled = true, httpAutoBitrateEnabled = true)
        assertEquals(VideoBitrateMode.CBR.mediaFormatValue, encoderBitrateMode(adaptive) { true })
        assertEquals(VideoBitrateMode.VBR.mediaFormatValue, encoderBitrateMode(adaptive) {
            it == VideoBitrateMode.VBR.mediaFormatValue
        })
        assertNull(encoderBitrateMode(adaptive) { false })
        assertNull(encoderBitrateMode(adaptive.copy(httpAutoBitrateEnabled = false)) { true })
        assertNull(encoderBitrateMode(adaptive.copy(httpUploadEnabled = false)) { true })
        assertEquals(VideoBitrateMode.VBR.mediaFormatValue,
            encoderBitrateMode(adaptive.copy(videoBitrateMode = VideoBitrateMode.VBR)) { true })
    }
    private fun upload(second: Int, queue: Double = 0.5, ack: Long = second * 600_000L,
                       drops: Long = 0, rescues: Long = 0, retained: Long = 0,
                       servers: List<HttpUploadServerStats> = emptyList()) = HttpUploadStats(
        "test", second.toLong(), null, second.toLong(), queue.toInt(), 0, (queue * 600_000).toLong(),
        (queue * 1_000_000).toLong(), 0, 0, 0, 60, 256L * 1024 * 1024, ack,
        droppedBlocks = drops, servers = servers, retainedBytes = retained, slowDownloadRescues = rescues)
    private fun sample(controller: HttpAdaptiveBitrateController, second: Int, state: HttpUploadStats = upload(second)) =
        controller.sample(second * 1_000_000_000L, state)

    @Test fun normalChunkOscillationAndLargeRetainedCacheDoNotReduceBitrate() {
        val controller = HttpAdaptiveBitrateController(5_500_000, 1_000_000, 192_000)
        repeat(100) { second ->
            assertNull(sample(controller, second, upload(second, if (second % 2 == 0) 0.0 else 1.0,
                retained = 200L * 1024 * 1024)))
        }
        assertEquals(5_500_000, controller.target)
    }

    @Test fun sustainedGrowingQueueReducesButNeverBelowFloorAndObservesCooldown() {
        val controller = HttpAdaptiveBitrateController(5_500_000, 1_000_000, 0)
        val changes = mutableListOf<Int>()
        repeat(90) { second ->
            sample(controller, second, upload(second, second * 0.8))?.let { changes += second }
            assertTrue(controller.target in 1_000_000..5_500_000)
        }
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.first() >= 5)
        assertTrue(changes.zipWithNext().all { (a, b) -> b - a >= 5 })
        assertEquals(1_000_000, controller.target)
    }

    @Test fun aBriefCongestionDoesNotAdjustAndAnAlreadyDrainingQueueDoesNotAdjust() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(8) { sample(controller, it) }
        assertNull(sample(controller, 8, upload(8, 4.0)))
        assertNull(sample(controller, 9, upload(9, 5.0)))
        repeat(20) { sample(controller, it + 10) }
        assertEquals(6_000_000, controller.target)
        val draining = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(20) { assertNull(sample(draining, it, upload(it, (15.0 - it * 0.8).coerceAtLeast(0.0)))) }
    }

    @Test fun stalledAcknowledgementsReduceEvenWithoutAFailedRequestYet() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(10) { sample(controller, it, upload(it, queue = 2.0, ack = 0)) }
        assertTrue(controller.target < 6_000_000)
    }

    @Test fun lowAchievedDownloadRateAloneDoesNotCauseSelfLocking() {
        val server = HttpUploadServerStats("A", 500_000, false, 0, feedbackAgeMs = 0)
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 192_000)
        repeat(40) { assertNull(sample(controller, it, upload(it, servers = listOf(server)))) }
        assertEquals(6_000_000, controller.target)
    }

    @Test fun freshEgressBudgetReservesAudioAndExpiredOrPartialFeedbackDoesNotLimitBitrate() {
        fun reduced(servers: List<HttpUploadServerStats>): Int {
            val controller = HttpAdaptiveBitrateController(6_000_000, 100_000, 192_000)
            repeat(7) { sample(controller, it, upload(it, queue = 3.0 + it, servers = servers)) }
            return controller.target
        }
        val server = HttpUploadServerStats("A", 4_000_000, false, 0, feedbackAgeMs = 0)
        assertEquals((4_000_000 * 0.85 / 1.06 - 192_000).toInt(), reduced(listOf(server)))
        assertEquals(4_500_000, reduced(listOf(server.copy(feedbackAgeMs = 10_001))))
        assertEquals(4_500_000, reduced(listOf(server.copy(feedbackAgeMs = null))))
        assertEquals(4_500_000, reduced(listOf(server, server.copy(url = "B", estimatedBitsPerSecond = null))))
    }

    @Test fun repeatedReceiverRescuesReduceEvenWhenPhoneUploadsAreFast() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(12) { sample(controller, it, upload(it, rescues = if (it < 4) 0 else if (it < 6) 1 else 2)) }
        assertTrue(controller.target < 6_000_000)
        val isolated = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(25) { sample(isolated, it, upload(it, rescues = if (it < 4) 0 else 1)) }
        assertEquals(6_000_000, isolated.target)
    }

    @Test fun rescueCopyQueueIsExcludedFromUploadCongestion() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(30) { assertNull(sample(controller, it,
            upload(it, 30.0, drops = it.toLong()).copy(unacknowledgedDurationUs = 500_000, originalDroppedBlocks = 0))) }
        assertEquals(6_000_000, controller.target)
    }

    @Test fun cacheEvictionReducesEvenIfQueueWasClearedAndAckContinues() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(12) { sample(controller, it, upload(it, drops = if (it < 5) 0 else 1)) }
        assertTrue(controller.target < 6_000_000)
    }

    @Test fun stableNetworkRecoversInSmallProbesEvenIfPreviousMeasurementWasLow() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(10) { sample(controller, it, upload(it, queue = it.toDouble())) }
        val lowered = controller.target
        assertTrue(lowered < 6_000_000)
        val server = HttpUploadServerStats("A", 1_000_000, false, 0, feedbackAgeMs = 0)
        for (second in 10..29) assertNull(sample(controller, second, upload(second, servers = listOf(server))))
        val raised = checkNotNull(sample(controller, 30, upload(30, servers = listOf(server))))
        assertEquals((lowered * 1.1).toInt(), raised.bitrate)
        for (second in 31..50) assertNull(sample(controller, second, upload(second, servers = listOf(server))))
        assertNotNull(sample(controller, 51, upload(51, servers = listOf(server))))
        repeat(400) { sample(controller, it + 52) }
        assertEquals(6_000_000, controller.target)
    }

    @Test fun oneUnavailableStoreDoesNotPreventRecoveryWhenAnotherDrainsTheQueue() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(10) { sample(controller, it, upload(it, queue = it.toDouble())) }
        val lowered = controller.target
        val servers = listOf(HttpUploadServerStats("A", null, false, 0),
            HttpUploadServerStats("B", null, false, 10))
        for (second in 10..29) assertNull(sample(controller, second, upload(second, servers = servers)))
        assertNotNull(sample(controller, 30, upload(30, servers = servers)))
        assertTrue(controller.target > lowered)
    }

    @Test fun duplicateSamplesOrLongPausedClockDoNotInventAHealthyRecoveryPeriod() {
        val controller = HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0)
        repeat(10) { sample(controller, it, upload(it, queue = it.toDouble())) }
        val lowered = controller.target
        repeat(20) { assertNull(sample(controller, 10)) }
        assertNull(sample(controller, 120))
        assertEquals(lowered, controller.target)
    }

    @Test fun minimumAboveMaximumIsClampedToConfiguredCeiling() {
        val controller = HttpAdaptiveBitrateController(500_000, 1_000_000, 0)
        assertEquals(500_000, controller.minimum)
        repeat(40) { sample(controller, it, upload(it, queue = it.toDouble())) }
        assertEquals(500_000, controller.target)
    }

    @Test fun aBandwidthDropConvergesAndRecoveryProbesReturnToTheCeiling() {
        val controller = HttpAdaptiveBitrateController(5_500_000, 1_000_000, 192_000)
        val chunks = ArrayDeque<Double>()
        var remaining = 0.0
        var acknowledged = 0L
        var highestQueue = 0
        var lowerTarget = 0
        for (second in 0..400) {
            // Each new one-second chunk retains the bitrate at which it was encoded.
            chunks.addLast((controller.target + 192_000) * 1.06 / 8)
            var budget = (if (second < 80) 3_000_000 else 8_000_000) / 8.0
            while (chunks.isNotEmpty() && budget > 0) {
                if (remaining == 0.0) remaining = chunks.first()
                val sent = minOf(budget, remaining)
                budget -= sent; remaining -= sent
                if (remaining == 0.0) acknowledged += chunks.removeFirst().toLong()
            }
            highestQueue = maxOf(highestQueue, chunks.size)
            sample(controller, second, upload(second, chunks.size.toDouble(), acknowledged))
            if (second == 79) lowerTarget = controller.target
        }
        assertTrue("Congestion did not reduce bitrate", lowerTarget < 3_000_000)
        assertTrue("Unbounded network backlog: $highestQueue", highestQueue < 15)
        assertEquals(5_500_000, controller.target)
        assertTrue(chunks.size <= 1)
    }
}
