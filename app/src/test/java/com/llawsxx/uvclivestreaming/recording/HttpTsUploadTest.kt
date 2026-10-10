package com.llawsxx.uvclivestreaming.recording

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HttpTsUploadTest {
    private fun ts(value: Int) = ByteArray(188) { if (it == 0) 0x47 else value.toByte() }

    @Test fun retryStatisticsTrackOutageBacklogRecoveryAndClear() {
        val queue = TsUploadQueue()
        val frames = (0L..3L).map { TsUploadBlock("session", it, 1_000_000, false, ts(it.toInt())) }
        queue.append(frames[0], 60); queue.append(frames[1], 60)
        assertEquals(TsUploadQueue.Stats(2, 0, 376, 2_000_000), queue.snapshot())
        queue.markForRetry()
        queue.append(frames[2], 60, retry = true) // Captured during the outage.
        assertEquals(TsUploadQueue.Stats(0, 3, 564, 3_000_000), queue.snapshot())
        queue.acknowledge(frames[0])
        queue.append(frames[3], 60) // Fresh data after the first successful ACK.
        assertEquals(TsUploadQueue.Stats(1, 2, 564, 3_000_000), queue.snapshot())
        queue.acknowledge(frames[1]); queue.acknowledge(frames[2])
        assertEquals(TsUploadQueue.Stats(1, 0, 188, 1_000_000), queue.snapshot())
        queue.clear()
        assertEquals(TsUploadQueue.Stats(0, 0, 0, 0), queue.snapshot())
        queue.append(frames[0], 60)
        assertEquals(0, queue.snapshot().retryBlocks)
    }

    @Test fun assemblingStatisticsIncludePartialBytesTimeAndAllocatedBuffer() {
        var now = 0L
        val chunker = TsUploadChunker({}, { now }, "stats-session")
        assertNull(chunker.latestSequence)
        chunker.write(ts(1))
        now = 500_000_000
        assertEquals("stats-session", chunker.session)
        assertEquals(188, chunker.pendingBytes)
        assertTrue(chunker.allocatedBytes >= chunker.pendingBytes)
        assertEquals(500_000L, chunker.pendingDurationUs)
        now = 1_000_000_000; chunker.write(ts(2))
        assertEquals(0L, chunker.latestSequence)
        assertEquals(0L, chunker.pendingDurationUs)
        assertEquals(188, chunker.pendingBytes)
        chunker.discard()
        assertEquals(0, chunker.pendingBytes)
        assertEquals(0, chunker.allocatedBytes)
        assertEquals(0L, chunker.pendingDurationUs)
    }

    @Test fun chunkingUsesElapsedTimeAndPreservesEveryByteWithoutKeyframeChecks() {
        var now = 0L
        val output = mutableListOf<TsUploadBlock>()
        val chunker = TsUploadChunker(output::add, { now }, "session")
        chunker.write(ts(1))
        now = 500_000_000; chunker.write(ts(2))
        now = 1_000_000_000; chunker.write(ts(3))
        assertEquals(1, output.size)
        assertArrayEquals(ts(1) + ts(2), output[0].data)
        assertEquals(1_000_000L, output[0].durationUs)
        now = 1_500_000_000; chunker.close(); chunker.close()
        assertEquals(listOf(0L, 1L), output.map { it.sequence })
        assertTrue(output.last().final)
        assertEquals(500_000L, output.last().durationUs)
        assertArrayEquals(ts(1) + ts(2) + ts(3), output.flatMap { it.data.asIterable() }.toByteArray())
    }

    @Test fun stoppingAnEmptySessionProducesAnAcknowledgableFinalMarker() {
        val output = mutableListOf<TsUploadBlock>()
        TsUploadChunker(output::add).close()
        assertEquals(1, output.size)
        assertTrue(output.single().final)
        assertEquals(0L, output.single().durationUs)
        assertEquals(0, output.single().data.size)
    }

    @Test fun discardDropsThePartialChunkWithoutEmittingAFinalBlock() {
        val output = mutableListOf<TsUploadBlock>()
        val chunker = TsUploadChunker(output::add)
        chunker.write(ts(1))
        chunker.discard(); chunker.close(); chunker.discard()
        assertTrue(output.isEmpty())
        assertThrows(IllegalStateException::class.java) { chunker.write(ts(2)) }
    }

    @Test fun memoryQueueRetainsOriginalBlocksAcrossSessionBoundariesUntilAcknowledged() {
        val queue = TsUploadQueue()
        val frames = listOf(TsUploadBlock("old", 0, 1_000_000, false, ts(1)),
            TsUploadBlock("old", 1, 0, true, byteArrayOf()),
            TsUploadBlock("new", 0, 1_000_000, true, ts(2)))
        frames.forEach { queue.append(it, 60) }
        assertEquals(3, queue.size)
        frames.forEach { frame ->
            assertSame(frame, queue.first())
            assertSame(frame, queue.first()) // Reading/retrying does not remove or copy it.
            queue.acknowledge(frame)
        }
        assertNull(queue.first())
        assertEquals(0, queue.size)
    }

    @Test fun fullDurationCacheDropsOldestBlocksAndKeepsPublishing() {
        val queue = TsUploadQueue()
        val first = TsUploadBlock("session", 0, 31_000_000, false, ts(1))
        val next = TsUploadBlock("session", 1, 31_000_000, false, ts(2))
        queue.append(first, 60, retry = true)
        queue.append(next, 60)
        assertSame(next, queue.first())
        assertEquals(TsUploadQueue.Stats(1, 0, 188, 31_000_000, 1), queue.snapshot())
        queue.append(next.copy(sequence = 2, durationUs = 61_000_000), 60)
        assertSame(next, queue.first()) // An oversized incoming block cannot poison the backlog.
        assertEquals(2L, queue.snapshot().droppedBlocks)
    }

    @Test fun expiredBlockCannotBeRetriedEvenWithoutNewInput() {
        var now = 0L
        val queue = TsUploadQueue(clockNs = { now })
        queue.append(TsUploadBlock("session", 0, 1_000_000, false, ts(1), createdNs = 0), 30)
        now = 29_000_000_000
        queue.expire(30)
        assertEquals(1, queue.size)
        now = 30_000_000_000
        queue.expire(30)
        assertEquals(TsUploadQueue.Stats(0, 0, 0, 0, 1), queue.snapshot())
        queue.append(TsUploadBlock("session", 1, 1_000_000, false, ts(2), createdNs = 0), 30)
        assertEquals(0, queue.size)
        assertEquals(2L, queue.snapshot().droppedBlocks)
    }

    @Test fun acknowledgementMustMatchTheHeadOfTheMemoryQueue() {
        val queue = TsUploadQueue()
        val first = TsUploadBlock("session", 0, 1, false, ts(1))
        val second = TsUploadBlock("session", 1, 1, true, ts(2))
        queue.append(first, 60); queue.append(second, 60)
        assertThrows(IllegalStateException::class.java) { queue.acknowledge(second) }
        assertThrows(IllegalStateException::class.java) { queue.acknowledge(first.copy()) }
        assertSame(first, queue.first())
    }

    @Test fun memoryCapacityDropsOldestAndAcknowledgementReleasesCapacity() {
        val queue = TsUploadQueue(maxBytes = 188)
        val first = TsUploadBlock("session", 0, 1, false, ts(1))
        val second = TsUploadBlock("session", 1, 1, false, ts(2))
        queue.append(first, 60)
        queue.append(second, 60)
        assertSame(second, queue.first())
        assertEquals(1L, queue.snapshot().droppedBlocks)
        queue.acknowledge(second)
        assertEquals(0L, queue.snapshot().bytes)
        queue.append(first, 60)
        assertSame(first, queue.first())
        assertNull(TsUploadQueue().first())
    }

    @Test fun clearReleasesBothTheByteAndDurationBudgets() {
        val queue = TsUploadQueue(188)
        val block = TsUploadBlock("session", 0, 60_000_000, false, ts(1))
        queue.append(block, 60)
        queue.clear(); queue.clear()
        assertEquals(0, queue.size)
        queue.append(block, 60)
        assertSame(block, queue.first())
    }

    @Test fun uploadUrlRequiresAStableStreamPathAndAllowsHttps() {
        assertTrue(validHttpUploadUrl("http://127.0.0.1:8080/upload/live"))
        assertTrue(validHttpUploadUrl("https://example.com/upload/camera_1"))
        for (url in listOf("rtmp://host/upload/live", "http://host/live.ts", "http://host/upload/",
            "http://user:password@host/upload/live", "http://host/upload/live?token=x", "http://host/upload/a/b")) {
            assertFalse(url, validHttpUploadUrl(url))
        }
    }

    @Test fun lostAcknowledgementRetriesIdenticalBlockWhilePublishing() {
        data class Received(val session: String, val sequence: String, val final: String, val body: ByteArray)
        val received = CopyOnWriteArrayList<Received>()
        val done = CountDownLatch(1)
        val retryNotice = CountDownLatch(1)
        val retryAccepted = CountDownLatch(1)
        val acknowledgeRetry = CountDownLatch(1)
        val failure = CopyOnWriteArrayList<Throwable>()
        ServerSocket(0).use { server ->
            server.soTimeout = 8_000
            val thread = Thread {
                try {
                    repeat(2) { attempt ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream().buffered()
                            fun line(): String {
                                val bytes = java.io.ByteArrayOutputStream()
                                while (true) {
                                    val value = input.read()
                                    check(value >= 0)
                                    if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                                    bytes.write(value)
                                }
                            }
                            assertEquals("POST /upload/live HTTP/1.1", line())
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val header = line()
                                if (header.isEmpty()) break
                                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                            }
                            val body = ByteArray(checkNotNull(headers["content-length"]).toInt())
                            java.io.DataInputStream(input).readFully(body)
                            received += Received(checkNotNull(headers["x-session-id"]),
                                checkNotNull(headers["x-sequence"]), checkNotNull(headers["x-final"]), body)
                            if (attempt > 0) {
                                retryAccepted.countDown()
                                check(acknowledgeRetry.await(5, TimeUnit.SECONDS))
                                socket.getOutputStream().write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\nX-Ack-Sequence: ${headers["x-sequence"]}\r\n\r\n".toByteArray())
                            }
                        }
                    }
                } catch (error: Throwable) { failure += error } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            val url = "http://127.0.0.1:${server.localPort}/upload/live"
            val first = HttpTsUploadSink(url, 60) { if (it.contains("中断")) retryNotice.countDown() }
            assertNull(first.stats.latestSequence)
            first.write(ts(1)); Thread.sleep(1_100); first.write(ts(2))
            assertTrue(retryNotice.await(3, TimeUnit.SECONDS))
            assertTrue(retryAccepted.await(3, TimeUnit.SECONDS))
            val retry = first.stats
            assertEquals(0L, retry.latestSequence)
            assertEquals(0L, retry.uploadingSequence)
            assertNull(retry.acknowledgedSequence)
            assertEquals(0, retry.pendingUploadBlocks)
            assertEquals(1, retry.pendingRetryBlocks)
            assertEquals(188L, retry.queuedBytes)
            assertEquals(188, retry.assemblingBytes)
            assertEquals(376L, retry.cachedDataBytes)
            assertTrue(retry.cachedDurationUs >= 1_100_000)
            acknowledgeRetry.countDown()
            assertTrue("Upload retries timed out", done.await(8, TimeUnit.SECONDS))
            thread.join(1_000)
            failure.firstOrNull()?.let { throw AssertionError(it) }
            assertEquals(2, received.size)
            assertEquals(received[0].session, received[1].session)
            assertEquals(received[0].sequence, received[1].sequence)
            assertArrayEquals(received[0].body, received[1].body)
            assertArrayEquals(ts(1), received[1].body)
            assertTrue(received.all { it.final == "0" })
            val deadline = System.nanoTime() + 2_000_000_000
            while (first.bytesSent < 188 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(188L, first.bytesSent)
            assertEquals(0L, first.stats.acknowledgedSequence)
            assertEquals(0, first.stats.pendingRetryBlocks)
            assertEquals(0L, first.stats.queuedBytes)
            assertEquals(188L, first.stats.cachedDataBytes) // Only the unfinished chunk remains.
            first.close()
            assertEquals(0, first.pendingBlocks)
        }
    }

    @Test fun stopCancelsBlockedAcknowledgementClearsBacklogAndNeverRetries() {
        val accepted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val failure = CopyOnWriteArrayList<Throwable>()
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) {
                                val value = input.read()
                                check(value >= 0)
                                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                                bytes.write(value)
                            }
                        }
                        line()
                        var length = 0
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                        }
                        java.io.DataInputStream(input).readFully(ByteArray(length))
                        accepted.countDown()
                        // Withhold the ACK. Stop must close this read immediately,
                        // rather than waiting for the ten/fifteen-second timeouts.
                        assertEquals(-1, input.read())
                    }
                    disconnected.countDown()
                    server.soTimeout = 1_500
                    assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().use { } }
                } catch (error: Throwable) { failure += error; disconnected.countDown() }
            }.apply { isDaemon = true; start() }
            val sink = HttpTsUploadSink("http://127.0.0.1:${server.localPort}/upload/live", 60) {}
            sink.write(ts(1)); Thread.sleep(1_100); sink.write(ts(2))
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            Thread.sleep(1_100); sink.write(ts(3))
            assertEquals(2, sink.pendingBlocks)
            val start = System.nanoTime()
            sink.close(); sink.close()
            assertTrue("Stop waited for a network timeout", System.nanoTime() - start < 1_500_000_000)
            assertEquals(0, sink.pendingBlocks)
            assertEquals(0L, sink.bytesSent)
            assertEquals(0, sink.stats.pendingUploadBlocks)
            assertEquals(0, sink.stats.pendingRetryBlocks)
            assertNull(sink.stats.uploadingSequence)
            assertEquals(0L, sink.stats.cachedDataBytes)
            assertEquals(0L, sink.stats.cacheAllocatedBytes)
            assertEquals(0L, sink.stats.cachedDurationUs)
            assertThrows(IllegalStateException::class.java) { sink.write(ts(4)) }
            assertTrue(disconnected.await(1, TimeUnit.SECONDS))
            thread.join(3_000)
            assertFalse(thread.isAlive)
            failure.firstOrNull()?.let { throw AssertionError(it) }
        }
    }

    @Test fun multipleDestinationsRequireTheSameStreamAndHaveABoundedCount() {
        assertTrue(validHttpUploadUrl("http://a:8080/upload/live\nhttps://b/upload/live"))
        assertTrue(validHttpUploadUrl("http://a/upload/live, http://b/upload/live"))
        assertFalse(validHttpUploadUrl("http://a/upload/live\nhttp://b/upload/other"))
        assertFalse(validHttpUploadUrl((1..9).joinToString("\n") { "http://host$it/upload/live" }))
        assertEquals(listOf("http://a/upload/live", "http://b/upload/live"),
            httpUploadUrls("http://a/upload/live\nhttp://b/upload/live\nhttp://a/upload/live"))
    }

    @Test fun distributedTimelineHasContiguousAbsoluteMicrosecondBoundaries() {
        var now = 0L
        val blocks = mutableListOf<TsUploadBlock>()
        val chunker = TsUploadChunker(blocks::add, { now }, "timeline")
        repeat(100) {
            chunker.write(ts(1))
            now += 1_000_000_400L
        }
        chunker.close()
        assertEquals(100, blocks.size)
        blocks.zipWithNext().forEach { (before, after) ->
            assertEquals(before.startUs + before.durationUs, after.startUs)
            assertEquals(before.sessionStartedMs, after.sessionStartedMs)
        }
        assertEquals(now / 1_000, blocks.sumOf { it.durationUs })
    }

    @Test fun retiringInFlightBlockCancelsSocketAndUploadsNextSequence() {
        val accepted = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val thread = Thread {
                try {
                    repeat(2) { sequence ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream().buffered()
                            fun line(): String {
                                val bytes = java.io.ByteArrayOutputStream()
                                while (true) {
                                    val value = input.read()
                                    check(value >= 0)
                                    if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                                    bytes.write(value)
                                }
                            }
                            line()
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val header = line()
                                if (header.isEmpty()) break
                                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                            }
                            assertEquals(sequence.toString(), headers["x-sequence"])
                            val body = ByteArray(checkNotNull(headers["content-length"]).toInt())
                            java.io.DataInputStream(input).readFully(body)
                            assertArrayEquals(ts(sequence), body)
                            if (sequence == 0) {
                                accepted.countDown()
                                assertEquals(-1, input.read()) // Retirement cancels the withheld ACK immediately.
                            } else socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-Ack-Sequence: 1\r\n\r\n".toByteArray())
                        }
                    }
                } catch (error: Throwable) { failures += error } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            val worker = HttpTsUploadWorker("http://127.0.0.1:${server.localPort}/upload/live", 60, {}, TsUploadQueue(188))
            try {
                worker.enqueue(TsUploadBlock("session", 0, 1_000_000, false, ts(0)), 60)
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                worker.enqueue(TsUploadBlock("session", 1, 1_000_000, false, ts(1)), 60)
                assertTrue(done.await(3, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + 2_000_000_000
                while (worker.bytesAcknowledged.get() < 188 && System.nanoTime() < deadline) Thread.sleep(10)
                failures.firstOrNull()?.let { throw AssertionError(it) }
                val stats = worker.snapshot()
                assertEquals(188L, stats.acknowledgedBytes)
                assertEquals(1L, stats.acknowledgedSequence)
                assertEquals(TsUploadQueue.Stats(0, 0, 0, 0, 1), stats.queue)
            } finally { worker.close() }
            thread.join(1_000)
        }
    }
}
