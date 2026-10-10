package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class VideoAdaptiveBitrateControllerTest {
    private val config = RecordingConfig(videoBitrate = 6_000_000, httpUploadEnabled = true,
        httpAutoBitrateEnabled = true, httpMinVideoBitrate = 1_000_000,
        rtmpAutoBitrateEnabled = true, rtmpMinVideoBitrate = 500_000)
    private fun http(second: Int, blocked: Boolean = false) = HttpUploadStats(
        "http", second.toLong(), null, null, 1, 0, 1, if (blocked) 3_000_000 else 0,
        0, 0, 0, 60, 256L * 1024 * 1024, if (blocked) 0 else second * 600_000L)
    private fun rtmp(second: Int, blocked: Boolean = false) = RtmpUploadStats("rtmp", true,
        0, 3_000_000, if (blocked) 100 else 0, if (blocked) second * 1000L else 0,
        if (blocked) second * 1000L else 0, if (blocked) 0 else second * 600_000L, 0, 0)

    @Test fun rtmpWorksIndependentlyWithHttpDisabled() {
        val controller = VideoAdaptiveBitrateController(config.copy(httpUploadEnabled = false), 0)
        repeat(12) { controller.sample(it * 1_000_000_000L, http(it), rtmp(it, true)) }
        assertNull(controller.httpStats)
        assertNotNull(controller.rtmpStats)
        assertTrue(controller.target < 6_000_000)
    }

    @Test fun bothOutputsChooseLowerRequestAndRemovingEitherKeepsOtherLimit() {
        val controller = VideoAdaptiveBitrateController(config, 192_000)
        repeat(30) { controller.sample(it * 1_000_000_000L, http(it, true), rtmp(it, true)) }
        assertEquals(minOf(controller.httpStats!!.requestedBitsPerSecond,
            controller.rtmpStats!!.requestedBitsPerSecond), controller.target)
        val rtmpTarget = controller.rtmpStats!!.requestedBitsPerSecond
        controller.sample(30_000_000_000L, null, rtmp(30, true))
        assertNull(controller.httpStats)
        assertEquals(rtmpTarget, controller.target)
        controller.sample(31_000_000_000L, null, null)
        assertEquals(6_000_000, controller.target)
        assertNull(controller.rtmpStats)
    }

    @Test fun detachingRtmpRestoresHttpTargetRatherThanConfiguredMaximum() {
        val controller = VideoAdaptiveBitrateController(config, 0)
        repeat(12) { controller.sample(it * 1_000_000_000L, http(it, true), rtmp(it, true)) }
        val httpTarget = controller.httpStats!!.requestedBitsPerSecond
        controller.sample(12_000_000_000L, http(12, true), null)
        assertEquals(httpTarget, controller.target)
        assertTrue(controller.target < config.videoBitrate)
    }

    @Test fun reconnectKeepsReducedTargetAndNewOutputSessionResetsIt() {
        val controller = VideoAdaptiveBitrateController(config, 0)
        repeat(12) { controller.sample(it * 1_000_000_000L, null, rtmp(it, true)) }
        val reduced = controller.target
        controller.sample(12_000_000_000L, null, rtmp(12).copy(connected = false, connectionFailures = 1))
        assertEquals(reduced, controller.target)
        controller.sample(13_000_000_000L, null, rtmp(13))
        assertEquals(reduced, controller.target)
        controller.sample(14_000_000_000L, null, rtmp(14).copy(sessionId = "replacement"))
        assertEquals(config.videoBitrate, controller.target)
    }

    @Test fun disabledControlsHaveNoEffectEvenWhenOutputsAreCongested() {
        val controller = VideoAdaptiveBitrateController(config.copy(httpAutoBitrateEnabled = false,
            rtmpAutoBitrateEnabled = false), 0)
        repeat(30) { controller.sample(it * 1_000_000_000L, http(it, true), rtmp(it, true)) }
        assertEquals(config.videoBitrate, controller.target)
        assertNull(controller.httpStats)
        assertNull(controller.rtmpStats)
    }
}
