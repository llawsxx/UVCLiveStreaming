package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class RtmpAdaptiveBitrateControllerTest {
    private fun upload(second: Int, connected: Boolean = true, queue: Long = 0,
                       oldest: Long = 0, inFlight: Long = 0, inFlightAge: Long = 0,
                       drops: Long = 0, failures: Long = 0, sent: Long = second * 600_000L) =
        RtmpUploadStats("rtmp", connected, queue, 3_000_000, inFlight, inFlightAge, oldest, sent, drops, failures)
    private fun sample(controller: RtmpAdaptiveBitrateController, second: Int,
                       state: RtmpUploadStats = upload(second)) = controller.sample(second * 1_000_000_000L, state)

    @Test fun standaloneDefaultUsesCbrAndExplicitModeStillWins() {
        val config = RecordingConfig(rtmpEnabled = true, rtmpAutoBitrateEnabled = true)
        assertFalse(config.httpUploadEnabled)
        assertEquals(VideoBitrateMode.CBR.mediaFormatValue, encoderBitrateMode(config) { true })
        assertEquals(VideoBitrateMode.VBR.mediaFormatValue, encoderBitrateMode(config) {
            it == VideoBitrateMode.VBR.mediaFormatValue
        })
        assertNull(encoderBitrateMode(config.copy(rtmpAutoBitrateEnabled = false)) { true })
        assertEquals(VideoBitrateMode.VBR.mediaFormatValue,
            encoderBitrateMode(config.copy(videoBitrateMode = VideoBitrateMode.VBR)) { true })
        // Prepare the encoder when capture starts; RTMP can be attached during recording.
        assertEquals(VideoBitrateMode.CBR.mediaFormatValue, encoderBitrateMode(config.copy(rtmpEnabled = false)) { true })
    }

    @Test fun stableOrBrieflyCongestedStreamKeepsMaximum() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        repeat(100) { sample(controller, it, upload(it, queue = if (it % 2 == 0) 0 else 300_000, oldest = 200)) }
        sample(controller, 100, upload(100, queue = 2_500_000, oldest = 2_500))
        sample(controller, 101, upload(101, queue = 2_600_000, oldest = 3_000))
        repeat(30) { sample(controller, 102 + it) }
        assertEquals(6_000_000, controller.target)
    }

    @Test fun inFlightOnlyStallReducesEvenWhenQueueWasPolledEmpty() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        repeat(12) { sample(controller, it, upload(it, inFlight = 200_000, inFlightAge = it * 1000L, sent = 0)) }
        assertTrue(controller.target < 6_000_000)
    }

    @Test fun tinyBufferOverflowReducesWithoutEverAccumulatingTwoSeconds() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        val changes = mutableListOf<Int>()
        repeat(90) { second ->
            val before = controller.target
            sample(controller, second, upload(second, drops = second * 5L).copy(queueLimitBytes = 75_000))
            if (controller.target != before) changes += second
            assertTrue(controller.target in 1_000_000..6_000_000)
        }
        assertEquals(1_000_000, controller.target)
        assertTrue(changes.first() >= 5)
        assertTrue(changes.zipWithNext().all { (a, b) -> b - a >= 5 })
    }

    @Test fun nearFullSmallQueueReducesBeforeOverflow() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        repeat(12) { sample(controller, it, upload(it, queue = 60_000L + it * 100, oldest = 100)
            .copy(queueLimitBytes = 75_000)) }
        assertTrue(controller.target < 6_000_000)
    }

    @Test fun initialHandshakeDoesNotReduceButFailedConnectionDoes() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        repeat(20) { sample(controller, it, upload(it, connected = false, sent = 0)) }
        assertEquals(6_000_000, controller.target)
        repeat(30) { sample(controller, 20 + it, upload(20 + it, connected = false, failures = 1, sent = 0)) }
        assertTrue(controller.target < 6_000_000)
    }

    @Test fun recoveryDoublesAfterFiveStableSecondsAndDoesNotLockToAchievedThroughput() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        repeat(12) { sample(controller, it, upload(it, inFlight = 100, inFlightAge = it * 1000L, sent = 0)) }
        val reduced = controller.target
        assertTrue(reduced < 6_000_000)
        repeat(5) { sample(controller, 12 + it) }
        assertEquals(reduced, controller.target)
        sample(controller, 17)
        assertEquals(minOf(reduced * 2, 6_000_000), controller.target)
        repeat(30) { sample(controller, 18 + it, upload(18 + it, sent = (18 + it) * 100_000L)) }
        assertEquals(6_000_000, controller.target)
    }

    @Test fun recoveryQueueGrowthRollsBackAndNextProbeIsSmaller() {
        val controller = RtmpAdaptiveBitrateController(12_000_000, 1_000_000)
        repeat(30) { sample(controller, it, upload(it, connected = false, failures = 1, sent = 0)) }
        val stable = controller.target
        for (second in 30..35) sample(controller, second)
        assertEquals(stable * 2, controller.target)
        sample(controller, 36, upload(36, queue = 400_000, oldest = 800))
        sample(controller, 37, upload(37, queue = 500_000, oldest = 900))
        assertEquals(stable * 2, controller.target)
        sample(controller, 38, upload(38, queue = 600_000, oldest = 1000))
        assertEquals(stable, controller.target)
        for (second in 39..44) sample(controller, second)
        assertEquals(stable + stable / 2, controller.target)
    }

    @Test fun staleSamplesAndPausedSamplingDoNotTriggerImmediateReduction() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        sample(controller, 0)
        for (millisecond in 1..500) controller.sample(millisecond * 1_000_000L,
            upload(0, drops = millisecond.toLong()))
        assertEquals(6_000_000, controller.target)
        sample(controller, 1, upload(1, connected = false, failures = 1))
        sample(controller, 2, upload(2, connected = false, failures = 1))
        sample(controller, 20, upload(20, connected = false, failures = 1))
        assertEquals(6_000_000, controller.target)
    }

    @Test fun drainingBacklogDoesNotReduceAndIdleConnectionDoesNotRecover() {
        val draining = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        repeat(10) { sample(draining, it, upload(it, queue = 2_000_000L - it * 100_000, oldest = 3000)) }
        assertEquals(6_000_000, draining.target)
        val idle = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        repeat(12) { sample(idle, it, upload(it, connected = false, failures = 1, sent = 0)) }
        val reduced = idle.target
        repeat(60) { sample(idle, 12 + it, upload(12 + it, sent = 0)) }
        assertEquals(reduced, idle.target)
    }

    @Test fun sustainedBandwidthDropConvergesAndRecoversWithBoundedQueue() {
        val controller = RtmpAdaptiveBitrateController(6_000_000, 1_000_000)
        var queued = 0L; var sent = 0L; var drops = 0L
        var congestedTarget = 0
        repeat(450) { second ->
            val capacity = if (second < 90) 300_000L else 1_000_000L
            queued += (controller.target + 192_000) / 8L
            val moved = minOf(queued, capacity); queued -= moved; sent += moved
            if (queued > 3_000_000) { drops++; queued = 2_500_000 }
            sample(controller, second, upload(second, queue = queued,
                oldest = queued * 1000 / capacity, sent = sent, drops = drops))
            if (second == 89) congestedTarget = controller.target
        }
        assertTrue(congestedTarget <= 2_400_000)
        assertEquals(6_000_000, controller.target)
        assertEquals(0L, queued)
    }
}
