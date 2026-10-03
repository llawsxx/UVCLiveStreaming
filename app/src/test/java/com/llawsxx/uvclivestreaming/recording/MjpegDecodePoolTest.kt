package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MjpegDecodePoolTest {
    @Test fun outOfOrderWorkersRetainCaptureTimestampOrder() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val laterFinished = CountDownLatch(3)
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _ ->
            if (bytes[0].toInt() == 0) {
                firstStarted.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS))
            } else laterFinished.countDown()
            bytes
        }, capacity = 10)
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 1, 1, 100L)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            for (i in 1..3) pool.offer(byteArrayOf(i.toByte()), 1, 1, 1, 100L + i)
            assertTrue(laterFinished.await(2, TimeUnit.SECONDS))
            assertNull(pool.poll(10))
            releaseFirst.countDown()
            for (i in 0..3) {
                val output = pool.poll(2_000)
                assertNotNull(output)
                assertEquals(100L + i, output!!.timestampNs)
                assertEquals(i, output.yuv!![0].toInt())
            }
        } finally { releaseFirst.countDown(); pool.close() }
    }

    @Test fun stalledRendererDropsOldResultsAndCanResume() {
        val completed = Array(100) { CountDownLatch(1) }
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _ ->
            completed[bytes[0].toInt()].countDown()
            ByteArray(1024 * 1024)
        }, workerCount = 1, capacity = 3)
        pool.start()
        try {
            for (i in 0..99) {
                pool.offer(byteArrayOf(i.toByte()), 1, 640, 480, i.toLong())
                assertTrue(completed[i].await(2, TimeUnit.SECONDS))
            }
            val first = pool.poll(2_000)
            assertNotNull("Renderer must recover without waiting on dropped sequences", first)
            assertTrue("Old decoded frames must be discarded", first!!.timestampNs >= 96L)
            var previous = first.timestampNs
            while (true) {
                val next = pool.poll(20) ?: break
                assertTrue(next.timestampNs > previous)
                previous = next.timestampNs
            }
            assertEquals(99L, previous)
        } finally { pool.close() }
    }

    @Test fun missingDecodedFrameDoesNotBlockSubsequentFrames() {
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _ ->
            if (bytes[0].toInt() == 0) null else bytes
        }, workerCount = 1, capacity = 4)
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 1, 1, 10L)
            pool.offer(byteArrayOf(1), 1, 1, 1, 20L)
            assertNull(pool.poll(2_000)!!.yuv)
            assertEquals(20L, pool.poll(2_000)!!.timestampNs)
        } finally { pool.close() }
    }
}
