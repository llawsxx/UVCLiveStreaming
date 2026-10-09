package com.llawsxx.uvclivestreaming.recording

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class DistributedHttpTsUploadTest {
    private class Store(val rate: AtomicLong = AtomicLong(375_000), val withholdAck: Boolean = false,
        val ackDelayMs: Long = 0, val keepAlive: Boolean = false, val disconnectAfterAck: Boolean = false) : AutoCloseable {
        val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/upload/live"
        val received = CopyOnWriteArrayList<Long>()
        val requests = CopyOnWriteArrayList<String>()
        val connections = AtomicLong()
        val accepted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val rescue = AtomicLong(-1)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        private val listener = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets += socket
                connections.incrementAndGet()
                pool.execute {
                    try {
                        socket.use {
                            val input = socket.getInputStream().buffered()
                            fun line(): String {
                                val bytes = ByteArrayOutputStream()
                                while (true) {
                                    val value = input.read()
                                    check(value >= 0)
                                    if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                                    bytes.write(value)
                                }
                            }
                            while (true) {
                                val request = line()
                                requests += request
                                val headers = mutableMapOf<String, String>()
                                while (true) {
                                    val header = line()
                                    if (header.isEmpty()) break
                                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                                }
                                val sequence = headers["x-sequence"]
                                val post = request.startsWith("POST")
                                if (post) {
                                    DataInputStream(input).readFully(ByteArray(checkNotNull(headers["content-length"]).toInt()))
                                    received += checkNotNull(sequence).toLong()
                                    accepted.countDown()
                                    if (withholdAck) { while (input.read() >= 0) Unit; disconnected.countDown(); return@execute }
                                    if (ackDelayMs > 0) Thread.sleep(ackDelayMs)
                                }
                                val body = if (post) "Stored\n" else "OK\n"
                                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: " +
                                    (if (keepAlive) "keep-alive" else "close") + "\r\nX-Relay-Mode: store\r\n" +
                                    (if (rate.get() > 0) "X-Download-Rate-Bps: ${rate.get()}\r\n" else "") +
                                    (if (!post && rescue.get() >= 0) "X-Rescue-Session: test\r\nX-Rescue-Sequence: ${rescue.get()}\r\n" else "") +
                                    (sequence?.let { "X-Ack-Sequence: $it\r\n" } ?: "") + "\r\n" + body).toByteArray())
                                if (!keepAlive || (post && disconnectAfterAck)) break
                            }
                        }
                    } catch (_: Exception) { disconnected.countDown() }
                    finally { sockets.remove(socket) }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() {
            server.close(); sockets.forEach { runCatching { it.close() } }; pool.shutdownNow(); listener.join(1_000)
        }
    }
    private fun block(sequence: Long, packets: Int = 128) = TsUploadBlock("test", sequence, 1_000_000, false,
        ByteArray(188 * packets) { if (it % 188 == 0) 0x47 else 7 }, startUs = sequence * 1_000_000)
    private fun await(timeoutMs: Long = 8_000, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Timed out", predicate())
    }

    @Test fun uploadsAndFeedbackReuseIndependentConnectionsAndDrainAckBodies() {
        Store(keepAlive = true).use { first -> Store(keepAlive = true).use { second ->
            DistributedHttpTsUploadWorker(listOf(first.url, second.url), 60) {}.use { worker ->
                repeat(12) { worker.enqueue(block(it.toLong()), 60) }
                await { worker.bytesAcknowledged.get() == 12L * 188 * 128 }
                await { first.requests.count { it.startsWith("GET") } >= 2 && second.requests.count { it.startsWith("GET") } >= 2 }
                assertTrue(first.received.size >= 2 && second.received.size >= 2)
                assertEquals("One upload socket and one feedback socket per destination", 2L, first.connections.get())
                assertEquals(2L, second.connections.get())
            }
        } }
    }

    @Test fun unexpectedlyClosedPersistentSocketsReconnectAndKeepUniqueAcknowledgementCount() {
        Store(keepAlive = true, disconnectAfterAck = true).use { first ->
            Store(keepAlive = true, disconnectAfterAck = true).use { second ->
                DistributedHttpTsUploadWorker(listOf(first.url, second.url), 60) {}.use { worker ->
                    repeat(4) { worker.enqueue(block(it.toLong()), 60) }
                    await { worker.bytesAcknowledged.get() == 4L * 188 * 128 }
                    assertEquals((0L..3L).toSet(), (first.received + second.received).toSet())
                    assertEquals(4L * 188 * 128, worker.snapshot().retainedBytes)
                    assertTrue(first.connections.get() + second.connections.get() > 4)
                }
            }
        }
    }

    @Test fun successfulUploadsWithoutFeedbackKeepBandwidthUnknownUntilEachServerIsMeasured() {
        Store(AtomicLong(0)).use { first -> Store(AtomicLong(0)).use { second ->
            DistributedHttpTsUploadWorker(listOf(first.url, second.url), 60) {}.use { worker ->
                assertTrue(worker.snapshot().servers.all { it.estimatedBitsPerSecond == null })
                repeat(2) { worker.enqueue(block(it.toLong()), 60) }
                await { worker.bytesAcknowledged.get() == 2L * 188 * 128 }
                assertTrue("Upload ACKs without egress measurements must not expose the bootstrap estimate",
                    worker.snapshot().servers.all { it.estimatedBitsPerSecond == null })
                first.rate.set(375_000)
                await { worker.snapshot().servers[0].estimatedBitsPerSecond == 3_000_000L }
                assertNull(worker.snapshot().servers[1].estimatedBitsPerSecond)
                second.rate.set(600_000)
                await { worker.snapshot().servers[1].estimatedBitsPerSecond == 4_800_000L }
            }
        } }
    }

    @Test fun egressFeedbackWeightsDistributionAndRecoveryRemovesAnOldLongWait() {
        Store(AtomicLong(1_000)).use { slow -> Store(AtomicLong(128_000)).use { fast ->
            DistributedHttpTsUploadWorker(listOf(slow.url, fast.url), 60) {}.use { worker ->
                await { worker.snapshot().servers[0].estimatedBitsPerSecond == 8_000L }
                repeat(4) { worker.enqueue(block(it.toLong()), 60) }
                await { worker.bytesAcknowledged.get() == 4L * 188 * 128 }
                assertTrue(fast.received.size > slow.received.size)
                slow.rate.set(500_000)
                await { worker.snapshot().servers[0].estimatedBitsPerSecond == 4_000_000L }
                repeat(8) { worker.enqueue(block(4L + it), 60) }
                await { worker.bytesAcknowledged.get() == 12L * 188 * 128 }
                assertTrue("Recovered server was left behind its old pacing deadline", slow.received.size >= 2)
                assertEquals(12L * 188 * 128, worker.snapshot().retainedBytes)
            }
        } }
    }

    @Test fun stalledAckDoesNotBlockOtherUploadsAndRetriesTheSameSequenceElsewhere() {
        Store(withholdAck = true).use { slow -> Store().use { fast ->
            DistributedHttpTsUploadWorker(listOf(slow.url, fast.url), 60) {}.use { worker ->
                worker.enqueue(block(0, 1), 60)
                assertTrue(slow.accepted.await(2, TimeUnit.SECONDS))
                worker.enqueue(block(1, 1), 60)
                await(2_000) { fast.received.contains(1L) }
                await { worker.bytesAcknowledged.get() == 376L }
                assertTrue(fast.received.contains(0L))
                assertEquals(376L, worker.snapshot().retainedBytes)
            }
        } }
    }

    @Test fun stopCancelsAllInFlightSocketsAndClearsRetainedPayloads() {
        Store(withholdAck = true).use { first -> Store(withholdAck = true).use { second ->
            val worker = DistributedHttpTsUploadWorker(listOf(first.url, second.url), 60) {}
            try {
                worker.enqueue(block(0), 60); worker.enqueue(block(1), 60)
                assertTrue(first.accepted.await(2, TimeUnit.SECONDS))
                assertTrue(second.accepted.await(2, TimeUnit.SECONDS))
                val start = System.nanoTime()
                worker.close()
                assertTrue(System.nanoTime() - start < 2_000_000_000)
                assertTrue(first.disconnected.await(2, TimeUnit.SECONDS))
                assertTrue(second.disconnected.await(2, TimeUnit.SECONDS))
                assertEquals(0, worker.pendingBlocks)
                assertEquals(0L, worker.snapshot().retainedBytes)
            } finally { worker.close() }
        } }
    }

    @Test fun rescueWaitsForTheAlternativeAndDoesNotInflateUniqueAcknowledgements() {
        Store().use { original -> Store(AtomicLong(1_000), ackDelayMs = 2_000).use { alternative ->
            DistributedHttpTsUploadWorker(listOf(original.url, alternative.url), 60) {}.use { worker ->
                worker.enqueue(block(0, 1), 60)
                await { worker.bytesAcknowledged.get() == 188L }
                // Occupy the alternative when the rescue request arrives. The requested replica
                // must wait for it rather than going back to the original store.
                original.rate.set(1_000)
                alternative.rate.set(375_000)
                await { worker.snapshot().servers[0].estimatedBitsPerSecond == 8_000L &&
                    worker.snapshot().servers[1].estimatedBitsPerSecond == 3_000_000L }
                worker.enqueue(block(1, 1), 60)
                await { alternative.received.contains(1L) }
                original.rescue.set(0)
                await { alternative.received.contains(0L) }
                await { worker.pendingBlocks == 0 }
                assertEquals(listOf(0L), original.received.toList())
                assertEquals(376L, worker.bytesAcknowledged.get())
                assertEquals(376L, worker.snapshot().retainedBytes)
            }
        } }
    }
}
