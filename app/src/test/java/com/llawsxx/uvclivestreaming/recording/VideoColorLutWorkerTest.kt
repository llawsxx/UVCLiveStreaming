package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoColorLutWorkerTest {
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))
    private fun waitUninterrupted(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { }
    }

    @Test fun stalledBakerDoesNotBlockRequestsAndOnlyLatestSettingsArePublished() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latest = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<Float>())
        val worker = VideoColorLutWorker(VideoColorGradeSettings(enabled = true, exposureEv = 1f),
            baker = { s, cancelled ->
                requests += s.exposureEv
                if (s.exposureEv == 1f) { entered.countDown(); waitUninterrupted(release) }
                VideoColorLutBaker.bake(s, cancelled).also { if (s.exposureEv == 2f) latest.countDown() }
            })
        try {
            await(entered)
            val offered = CountDownLatch(1)
            Thread {
                repeat(30) { worker.update(VideoColorGradeSettings(enabled = true, exposureEv = 1.1f + it * .01f)) }
                worker.update(VideoColorGradeSettings(enabled = true, exposureEv = 2f))
                offered.countDown()
            }.start()
            await(offered)
            release.countDown(); await(latest)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!worker.ready && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(worker.ready)
            assertEquals(2f, worker.current?.settings?.exposureEv)
            worker.close(); assertTrue(worker.awaitStopped(3000))
            assertEquals(listOf(1f, 2f), requests.toList())
        } finally { release.countDown(); worker.close(); worker.awaitStopped(3000) }
    }

    @Test fun disablingWhileBakingCannotPublishAnOldEnabledTable() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = VideoColorLutWorker(VideoColorGradeSettings(enabled = true, exposureEv = 1f),
            baker = { s, cancelled ->
                entered.countDown(); waitUninterrupted(release)
                VideoColorLutBaker.bake(s, cancelled).also { finished.countDown() }
            })
        try {
            await(entered)
            worker.update(VideoColorGradeSettings())
            assertTrue(worker.ready); assertNull(worker.current)
            release.countDown(); await(finished)
            worker.close(); assertTrue(worker.awaitStopped(3000))
            assertNull(worker.current)
        } finally { release.countDown(); worker.close(); worker.awaitStopped(3000) }
    }
}
