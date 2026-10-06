package com.llawsxx.uvclivestreaming.recording

import java.io.DataInputStream
import java.io.IOException
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RtmpConnectionTest {
    private class Peer(socket: Socket) {
        val reader: RtmpChunkReader
        val writer: RtmpChunkWriter
        val received = mutableListOf<RtmpMessage>()
        init {
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            assertEquals(3, input.readUnsignedByte())
            val c1 = ByteArray(1536).also(input::readFully)
            val output = socket.getOutputStream()
            output.write(3); output.write(ByteArray(1536)); output.write(c1); output.flush()
            input.readFully(ByteArray(1536))
            reader = RtmpChunkReader(input)
            writer = RtmpChunkWriter(output)
        }
        fun command(): Pair<RtmpMessage, List<Any?>> {
            repeat(100) {
                val message = reader.read().also(received::add)
                if (message.type == 20) return message to RtmpAmf.decode(message.payload)
            }
            error("No RTMP command received")
        }
        fun result(transaction: Double, value: Any?) {
            writer.write(20, 0L, RtmpAmf.encode("_result", transaction, null, value), 3, 0)
        }
        fun acceptPublishing(): Pair<RtmpMessage, List<Any?>> {
            assertEquals("connect", command().second[0])
            result(1.0, mapOf("code" to "NetConnection.Connect.Success", "level" to "status"))
            assertEquals("releaseStream", command().second[0]); result(2.0, null)
            assertEquals("FCPublish", command().second[0]); result(3.0, null)
            val create = command().second
            assertEquals("createStream", create[0]); result((create[1] as Number).toDouble(), 7.0)
            return command().also { assertEquals("publish", it.second[0]) }
        }
        fun publishStatus(code: String, level: String = "status") {
            writer.write(20, 0L, RtmpAmf.encode("onStatus", 0.0, null, mapOf(
                "code" to code, "level" to level, "description" to code,
            )), 5, 7)
        }
    }

    @Test fun publishingHandlesFragmentedResponsesTransactionsStreamIdAndLiveControl() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        val peer = Peer(socket)
                        val connect = peer.command().second
                        assertEquals("connect", connect[0])
                        val properties = connect[2] as Map<*, *>
                        assertEquals("rtmp://127.0.0.1:${server.localPort}/live", properties["tcUrl"])
                        assertEquals(1, peer.received.first().type) // Chunk size precedes connect.
                        peer.writer.setChunkSize(64)
                        peer.writer.write(5, 0L, RtmpAmf.uint32Bytes(128), 2, 0)
                        peer.writer.write(6, 0L, RtmpAmf.uint32Bytes(4096) + byteArrayOf(2), 2, 0)
                        peer.result(1.0, mapOf("code" to "NetConnection.Connect.Success", "description" to "x".repeat(500)))
                        assertEquals("releaseStream", peer.command().second[0]); peer.result(2.0, null)
                        assertEquals("FCPublish", peer.command().second[0]); peer.result(3.0, null)
                        val create = peer.command().second
                        assertEquals("createStream", create[0]); peer.result((create[1] as Number).toDouble(), 7.0)
                        val publish = peer.command()
                        assertEquals(7, publish.first.streamId)
                        assertEquals("stream?token=a", publish.second[3])
                        peer.publishStatus("NetStream.Publish.Start")
                        peer.writer.write(4, 0L, byteArrayOf(0, 6, 1, 2, 3, 4), 2, 0)
                        peer.writer.write(18, 0L, ByteArray(700), 5, 7)
                        var pong = false
                        var media = false
                        repeat(100) {
                            val message = peer.reader.read().also(peer.received::add)
                            if (message.type == 4) {
                                assertArrayEquals(byteArrayOf(0, 7, 1, 2, 3, 4), message.payload)
                                pong = true
                            }
                            if (message.type == 9) {
                                assertEquals(7, message.streamId)
                                assertEquals(0xF1234567L, message.timestamp)
                                assertArrayEquals(ByteArray(600) { it.toByte() }, message.payload)
                                media = true
                            }
                            if (pong && media) {
                                assertTrue(peer.received.any { it.type == 3 })
                                assertTrue(peer.received.any { it.type == 5 })
                                return@submit true
                            }
                        }
                        false
                    }
                }
                RtmpConnection("rtmp://127.0.0.1:${server.localPort}/live/stream?token=a").use { client ->
                    client.open()
                    assertEquals(7, client.streamId)
                    client.send(9, 0xF1234567L, ByteArray(600) { it.toByte() }, 6)
                    assertTrue(future.get(5, TimeUnit.SECONDS))
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun rejectedPublishReportsServerStatusAndClosesSocket() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Int> {
                    server.accept().use { socket ->
                        val peer = Peer(socket)
                        peer.acceptPublishing()
                        peer.publishStatus("NetStream.Publish.BadName", "error")
                        socket.getInputStream().read()
                    }
                }
                RtmpConnection("rtmp://127.0.0.1:${server.localPort}/live/stream").use { client ->
                    try { client.open(); fail("Publish should have failed") }
                    catch (error: IOException) { assertTrue(error.message!!.contains("NetStream.Publish.BadName")) }
                }
                assertEquals(-1, future.get(5, TimeUnit.SECONDS).toInt())
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun stoppingClosesSocketDuringHandshake() {
        ServerSocket(0).use { server ->
            val executor = Executors.newFixedThreadPool(2)
            val handshakeStarted = CountDownLatch(1)
            try {
                val peer = executor.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        DataInputStream(socket.getInputStream()).readFully(ByteArray(1537))
                        handshakeStarted.countDown()
                        socket.getInputStream().read()
                    }
                }
                RtmpConnection("rtmp://127.0.0.1:${server.localPort}/live/stream").use { client ->
                    val opening = executor.submit<Boolean> {
                        try { client.open(); false } catch (_: IOException) { true }
                    }
                    assertTrue(handshakeStarted.await(2, TimeUnit.SECONDS))
                    client.close()
                    assertTrue(opening.get(2, TimeUnit.SECONDS))
                    assertEquals(-1, peer.get(2, TimeUnit.SECONDS).toInt())
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun serverThatStopsReadingCannotBlockMediaSendingForever() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val releaseServer = CountDownLatch(1)
            try {
                val peer = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        val session = Peer(socket)
                        session.acceptPublishing()
                        session.publishStatus("NetStream.Publish.Start")
                        // Keep TCP connected, but stop draining it so the sender's write blocks.
                        releaseServer.await(10, TimeUnit.SECONDS)
                    }
                }
                try {
                    RtmpConnection("rtmp://127.0.0.1:${server.localPort}/live/stream",
                        sendTimeoutMs = 500).use { client ->
                        client.open()
                        val payload = ByteArray(4 * 1024 * 1024)
                        try {
                            repeat(8) { client.send(9, it * 40L, payload, 6) }
                            fail("Stalled TCP writes should time out")
                        } catch (error: SocketTimeoutException) {
                            assertTrue(error.message!!.contains("RTMP 发送超时"))
                        }
                    }
                } finally { releaseServer.countDown() }
                assertTrue(peer.get(2, TimeUnit.SECONDS))
            } finally { releaseServer.countDown(); executor.shutdownNow() }
        }
    }

    /** Opt-in verification against a local RTMP server, using a separate diagnostic stream key. */
    @Test fun liveServerAcceptsPublishing() {
        val url = System.getenv("RTMP_DIAGNOSTIC_URL")
        assumeTrue("Set RTMP_DIAGNOSTIC_URL to run against a real server", !url.isNullOrBlank())
        RtmpConnection(url!!) { println("RTMP diagnostic: $it") }.use { client ->
            client.open()
            assertTrue(client.streamId > 0)
            client.send(18, 0L, RtmpAmf.encode("@setDataFrame", "onMetaData", mapOf("duration" to 0.0)), 5)
            client.ensureActive()
        }
    }

    /** Replay known-good FLV media through our transport using a separate diagnostic key. */
    @Test fun liveServerAcceptsFlvMedia() {
        val url = System.getenv("RTMP_DIAGNOSTIC_URL")
        val file = System.getenv("RTMP_DIAGNOSTIC_FLV")
        assumeTrue("Set RTMP_DIAGNOSTIC_URL and RTMP_DIAGNOSTIC_FLV", !url.isNullOrBlank() && !file.isNullOrBlank())
        DataInputStream(File(file!!).inputStream()).use { input ->
            assertEquals("FLV", ByteArray(3).also(input::readFully).toString(Charsets.US_ASCII))
            input.readUnsignedByte(); input.readUnsignedByte()
            val offset = input.readInt()
            input.skipBytes(offset - 9)
            input.readInt()
            RtmpConnection(url!!) { println("RTMP media diagnostic: $it") }.use { client ->
                client.open()
                val start = System.nanoTime()
                var packets = 0
                while (input.available() > 0) {
                    val type = input.readUnsignedByte()
                    fun u24(): Int = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                    val size = u24()
                    val timestamp = u24().toLong() or (input.readUnsignedByte().toLong() shl 24)
                    u24()
                    val payload = ByteArray(size).also(input::readFully)
                    assertEquals(size + 11, input.readInt())
                    val remainingMs = timestamp - (System.nanoTime() - start) / 1_000_000L
                    if (remainingMs > 0) Thread.sleep(remainingMs)
                    client.send(type, timestamp, payload, if (type == 9) 6 else if (type == 8) 4 else 5)
                    packets++
                }
                Thread.sleep(300)
                client.ensureActive()
                assertTrue(packets > 30)
                println("RTMP media diagnostic: sent $packets FLV packets")
            }
        }
    }
}
