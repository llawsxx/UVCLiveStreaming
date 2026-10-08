package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MjpegDecodePoolTest {
    @Test fun completionAndDecodeFailureWakeEventConsumer() {
        for (success in listOf(true, false)) {
            val wake = QueueWakeSignal()
            val completed = CountDownLatch(1)
            val pool = MjpegDecodePool(decoder = { _, _, _, _, _ -> success }, workerCount = 1,
                onOutputReady = { wake.signal(); completed.countDown() })
            pool.start()
            try {
                val revision = wake.snapshot()
                assertNull(pool.poll(0))
                pool.offer(byteArrayOf(0), 1, 2, 2, 123)
                assertTrue(completed.await(2, TimeUnit.SECONDS))
                assertTrue(wake.awaitChange(revision))
                val frame = pool.poll(0)
                assertNotNull(frame)
                assertEquals(success, frame!!.yuv != null)
                frame.close()
            } finally { wake.close(); pool.close() }
        }
    }

    @Test fun droppedInputPublishesWakeWithoutWaitingForDecoder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wake = QueueWakeSignal()
        val pool = MjpegDecodePool(decoder = { _, _, _, _, _ ->
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            true
        }, workerCount = 1, capacity = 1, onOutputReady = wake::signal)
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 2, 2, 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pool.offer(byteArrayOf(1), 1, 2, 2, 1)
            val revision = wake.snapshot()
            pool.offer(byteArrayOf(2), 1, 2, 2, 2)
            assertTrue(wake.snapshot() > revision)
            assertTrue(wake.awaitChange(revision))
            assertEquals(1L, pool.diagnostics().inputDrops)
        } finally { release.countDown(); wake.close(); pool.close() }
    }

    @Test fun fullHeightChromaReachesRendererWithoutDownsampling() {
        val pool = MjpegDecodePool(decoder = { _, _, _, _, destination ->
            assertEquals(16, destination.capacity()) // 4x2 Y plus two 2x2 chroma planes.
            destination.put(8, 11); destination.put(10, 222.toByte())
            true
        }, workerCount = 1, chromaGeometry = { _, _, _ -> 2 to 2 })
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 4, 2, 123)
            pool.poll(2_000)!!.use { output ->
                assertEquals(2, output.chromaWidth); assertEquals(2, output.chromaHeight)
                val gpu = GpuVideoFrame.fromDecoded(output)!!
                assertEquals(16, gpu.byteSize)
                assertEquals(11, gpu.directBuffer!!.get(8).toInt() and 255)
                assertEquals(222, gpu.directBuffer.get(10).toInt() and 255)
            }
            assertEquals(0, pool.diagnostics().outputBuffers.inUse)
        } finally { pool.close() }
    }

    @Test fun outOfOrderWorkersRetainCaptureTimestampOrder() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val laterFinished = CountDownLatch(3)
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, destination ->
            if (bytes[0].toInt() == 0) {
                firstStarted.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS))
            } else laterFinished.countDown()
            destination.put(0, bytes[0])
            true
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
                assertEquals(i, output.yuv!!.buffer.get(0).toInt())
                output.close()
            }
        } finally { releaseFirst.countDown(); pool.close() }
    }

    @Test fun stalledRendererDropsOldResultsAndCanResume() {
        val completed = Array(100) { CountDownLatch(1) }
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, _ ->
            completed[bytes[0].toInt()].countDown()
            true
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
            first.close()
            while (true) {
                val next = pool.poll(20) ?: break
                assertTrue(next.timestampNs > previous)
                previous = next.timestampNs
                next.close()
            }
            assertEquals(99L, previous)
        } finally { pool.close() }
    }

    @Test fun missingDecodedFrameDoesNotBlockSubsequentFrames() {
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, destination ->
            destination.put(0, bytes[0])
            bytes[0].toInt() != 0
        }, workerCount = 1, capacity = 4)
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 1, 1, 10L)
            pool.offer(byteArrayOf(1), 1, 1, 1, 20L)
            assertNull(pool.poll(2_000)!!.yuv)
            pool.poll(2_000)!!.use { assertEquals(20L, it.timestampNs) }
            val diagnostics = pool.diagnostics()
            assertEquals(2L, diagnostics.offered)
            assertEquals(2L, diagnostics.decodeAttempts)
            assertEquals(1L, diagnostics.decodeFailures)
            assertEquals(0L, diagnostics.inputDrops)
            assertEquals(0L, diagnostics.outputSkippedSequences)
            assertEquals(1L, diagnostics.delivered)
            assertEquals(0, diagnostics.outputBuffers.inUse)
        } finally { pool.close() }
    }

    @Test fun inputOverflowIsCountedSeparatelyFromDecodeFailure() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, destination ->
            if (bytes[0].toInt() == 0) {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            destination.put(0, bytes[0])
            true
        }, workerCount = 1, capacity = 1)
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 1, 1, 0L)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            pool.offer(byteArrayOf(1), 1, 1, 1, 1L)
            pool.offer(byteArrayOf(2), 1, 1, 1, 2L)
            val diagnostics = pool.diagnostics()
            assertEquals(3L, diagnostics.offered)
            assertEquals(1L, diagnostics.inputDrops)
            assertEquals(0L, diagnostics.decodeFailures)
        } finally { release.countDown(); pool.close() }
    }

    @Test fun heldOutputCannotBeOverwrittenAndIsReusedAfterRelease() {
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, destination ->
            destination.put(0, bytes[0]); true
        }, workerCount = 1, capacity = 4)
        pool.start()
        try {
            pool.offer(byteArrayOf(11), 1, 2, 2, 1)
            val first = pool.poll(2_000)!!
            val firstBuffer = first.yuv!!.buffer
            pool.offer(byteArrayOf(22), 1, 2, 2, 2)
            val second = pool.poll(2_000)!!
            assertNotSame(firstBuffer, second.yuv!!.buffer)
            assertEquals(11, firstBuffer.get(0).toInt())
            first.close()
            pool.offer(byteArrayOf(33), 1, 2, 2, 3)
            pool.poll(2_000)!!.use { third ->
                assertSame(firstBuffer, third.yuv!!.buffer)
                assertEquals(33, third.yuv.buffer.get(0).toInt())
                assertEquals(22, second.yuv.buffer.get(0).toInt())
            }
            second.close()
            assertEquals(2L, pool.diagnostics().outputBuffers.allocations)
            assertEquals(1L, pool.diagnostics().outputBuffers.reuses)
            assertEquals(0, pool.diagnostics().outputBuffers.inUse)
        } finally { pool.close() }
    }

    @Test fun exceptionsAndDiscardedCompletionsReleaseTheirBuffers() {
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, _ ->
            if (bytes[0].toInt() == 0) error("bad frame")
            true
        }, workerCount = 1, capacity = 2)
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 2, 2, 0)
            assertNull(pool.poll(2_000)!!.yuv)
            assertEquals(0, pool.diagnostics().outputBuffers.inUse)
            for (i in 1..20) {
                pool.offer(byteArrayOf(i.toByte()), 1, 2, 2, i.toLong())
                awaitCondition { pool.diagnostics().decodeAttempts == i + 1L }
            }
            assertEquals(2, pool.diagnostics().outputBuffers.inUse)
            assertTrue(pool.diagnostics().outputBuffers.allocations <= 3)
        } finally { pool.close() }
        assertEquals(0, pool.diagnostics().outputBuffers.inUse)
        assertEquals(0, pool.diagnostics().outputBuffers.cached)
    }

    @Test fun shutdownKeepsConsumerLeaseAliveAndReleasesInFlightDecode() {
        val started = CountDownLatch(1)
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, destination ->
            destination.put(0, bytes[0])
            if (bytes[0].toInt() == 2) {
                started.countDown()
                CountDownLatch(1).await()
            }
            true
        }, workerCount = 1, capacity = 2)
        pool.start()
        pool.offer(byteArrayOf(1), 1, 2, 2, 1)
        val held = pool.poll(2_000)!!
        try {
            pool.offer(byteArrayOf(2), 1, 2, 2, 2)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            pool.close()
            assertEquals(1, held.yuv!!.buffer.get(0).toInt())
            assertEquals(1, pool.diagnostics().outputBuffers.inUse)
        } finally { held.close(); pool.close() }
        assertEquals(0, pool.diagnostics().outputBuffers.inUse)
        assertEquals(0, pool.diagnostics().outputBuffers.cached)
    }

    @Test fun lateDecodeCompletionReturnsItsBufferAfterSequenceWasSkipped() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, destination ->
            if (bytes[0].toInt() == 0) {
                firstStarted.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS))
            }
            destination.put(0, bytes[0]); true
        }, workerCount = 2, capacity = 1)
        pool.start()
        try {
            pool.offer(byteArrayOf(0), 1, 2, 2, 0)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            pool.offer(byteArrayOf(1), 1, 2, 2, 1)
            awaitCondition { pool.diagnostics().decodeAttempts == 1L }
            pool.offer(byteArrayOf(2), 1, 2, 2, 2)
            awaitCondition { pool.diagnostics().decodeAttempts == 2L }
            pool.poll(2_000)!!.use { assertEquals(2L, it.timestampNs) }
            releaseFirst.countDown()
            awaitCondition { pool.diagnostics().lateCompletions == 1L }
            assertEquals(0, pool.diagnostics().outputBuffers.inUse)
        } finally { releaseFirst.countDown(); pool.close() }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(condition())
    }    @Test fun capturedInputsAreReleasedOnSuccessFailureAndRejectedOffer() {
        val released = java.util.concurrent.atomic.AtomicInteger()
        fun input(value: Int) = CapturedVideoBuffer(java.nio.ByteBuffer.allocateDirect(1).apply {
            put(0, value.toByte())
        }) { released.incrementAndGet() }
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, _ ->
            when (bytes.get(0).toInt()) { 0 -> true; 1 -> false; else -> error("decode error") }
        }, workerCount = 1)
        val rejected = input(0)
        pool.offer(rejected, 1, 2, 2, 0)
        rejected.close()
        assertEquals(1, released.get())
        pool.start()
        try {
            for (i in 0..2) {
                pool.offer(input(i), 1, 2, 2, i.toLong())
                pool.poll(2_000)!!.close()
            }
            assertEquals(4, released.get())
            assertEquals(0, pool.diagnostics().outputBuffers.inUse)
        } finally { pool.close() }
        pool.offer(input(0), 1, 2, 2, 0)
        assertEquals(5, released.get())
    }

    @Test fun overflowAndStopReleaseQueuedInputsWithoutReleasingActiveDecode() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val released = Array(3) { java.util.concurrent.atomic.AtomicInteger() }
        fun input(index: Int) = CapturedVideoBuffer(java.nio.ByteBuffer.allocateDirect(1).apply {
            put(0, index.toByte())
        }) { released[index].incrementAndGet() }
        val pool = MjpegDecodePool(decoder = { bytes, _, _, _, _ ->
            if (bytes.get(0).toInt() == 0) {
                started.countDown()
                // Simulate native decode, which cannot be cancelled by Thread.interrupt().
                while (finish.count > 0) {
                    try { finish.await() } catch (_: InterruptedException) { interrupted.countDown() }
                }
            }
            true
        }, workerCount = 1, capacity = 1)
        pool.start()
        var closer: Thread? = null
        try {
            pool.offer(input(0), 1, 2, 2, 0)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            pool.offer(input(1), 1, 2, 2, 1)
            pool.offer(input(2), 1, 2, 2, 2)
            assertEquals(1, released[1].get())
            assertEquals(0, released[0].get())
            closer = Thread { pool.close() }.apply { start() }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertEquals(1, released[2].get())
            assertEquals(0, released[0].get())
            finish.countDown()
            closer.join(2_000)
            assertFalse(closer.isAlive)
            assertEquals(1, released[0].get())
            assertEquals(0, pool.diagnostics().outputBuffers.inUse)
        } finally { finish.countDown(); closer?.join(2_000); pool.close() }
    }


}
