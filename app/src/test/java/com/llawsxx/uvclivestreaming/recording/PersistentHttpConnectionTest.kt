package com.llawsxx.uvclivestreaming.recording

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PersistentHttpConnectionTest {
    private fun request(input: BufferedInputStream): Pair<String, Map<String, String>> {
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                check(value >= 0)
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(value)
            }
        }
        val first = line()
        val headers = buildMap {
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
            }
        }
        DataInputStream(input).readFully(ByteArray(headers["content-length"]?.toInt() ?: 0))
        return first to headers
    }

    @Test fun singleUploaderSendsThreeBlocksOnOneSocketAndConsumesNonemptyAckBodies() {
        val finished = CountDownLatch(1)
        val confirmed = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().buffered()
                        repeat(3) { sequence ->
                            val (first, headers) = request(input)
                            assertEquals("POST /upload/live HTTP/1.1", first)
                            assertEquals("keep-alive", headers["connection"])
                            assertEquals(sequence.toString(), headers["x-sequence"])
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: keep-alive\r\n" +
                                "X-Ack-Sequence: $sequence\r\n\r\nAccepted\n").toByteArray())
                        }
                        confirmed.countDown()
                        assertEquals(-1, input.read()) // Stop must also close an idle cached connection.
                    }
                } catch (error: Throwable) { failures += error }
                finally { finished.countDown() }
            }.apply { isDaemon = true; start() }
            HttpTsUploadWorker("http://127.0.0.1:${server.localPort}/upload/live", 60, {}).use { worker ->
                repeat(3) { worker.enqueue(TsUploadBlock("single", it.toLong(), 1_000_000, false,
                    ByteArray(188) { index -> if (index == 0) 0x47 else 1 }), 60) }
                assertTrue(confirmed.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + 2_000_000_000L
                while (worker.bytesAcknowledged.get() != 564L && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(564L, worker.bytesAcknowledged.get())
            }
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            thread.join(1_000)
            failures.firstOrNull()?.let { throw AssertionError(it) }
        }
    }

    @Test fun completedRequestDeadlineCannotCancelTheNextRequestOnTheReusedSocket() {
        val failures = CopyOnWriteArrayList<Throwable>()
        ServerSocket(0).use { server ->
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().buffered()
                        repeat(2) { attempt ->
                            request(input)
                            if (attempt == 1) Thread.sleep(350)
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\nOK\n".toByteArray())
                        }
                    }
                } catch (error: Throwable) { failures += error }
            }.apply { isDaemon = true; start() }
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            try {
                PersistentHttpConnection("http://127.0.0.1:${server.localPort}/status/live", watchdog).use { connection ->
                    assertEquals(200, connection.request("GET", deadlineNs = System.nanoTime() + 200_000_000L).status)
                    assertEquals(200, connection.request("GET", deadlineNs = System.nanoTime() + 2_000_000_000L).status)
                }
            } finally { watchdog.shutdownNow() }
            thread.join(1_000)
            failures.firstOrNull()?.let { throw AssertionError(it) }
        }
    }

    @Test fun retiringARequestBeforeItStartsPreventsItFromOpeningANewConnection() {
        ServerSocket(0).use { server ->
            server.soTimeout = 100
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            try {
                PersistentHttpConnection("http://127.0.0.1:${server.localPort}/upload/live", watchdog).use { connection ->
                    val generation = connection.generation()
                    connection.invalidate()
                    assertThrows(IOException::class.java) {
                        connection.request("GET", deadlineNs = System.nanoTime() + 1_000_000_000L, expectedGeneration = generation)
                    }
                    assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().use {} }
                }
            } finally { watchdog.shutdownNow() }
        }
    }
}
