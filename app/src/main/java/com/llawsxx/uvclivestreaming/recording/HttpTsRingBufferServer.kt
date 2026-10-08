package com.llawsxx.uvclivestreaming.recording

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Small HTTP MPEG-TS server. The buffer keeps complete muxer output chunks. */
internal class HttpTsRingBufferServer(
    private val port: Int,
    maxBytes: Long,
) : Closeable {
    private data class Chunk(
        val sequence: Long,
        val data: ByteArray,
        val ptsUs: Long,
        val keyFrame: Boolean,
        val tables: Boolean,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val chunks = ArrayDeque<Chunk>()
    private val capacityBytes = maxBytes.coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES)
    @Volatile private var closed = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var nextSequence = 1L
    private var bufferedBytes = 0L
    private var servedBytes = 0L

    val bytesServed: Long get() = lock.withLock { servedBytes }
    val endpoint: String get() = "http://<设备IP>:$port/live.ts"
    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        check(!closed) { "HTTP TS 服务已关闭" }
        if (serverSocket != null) return
        val socket = ServerSocket(port, 8)
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "http-ts-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun write(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (closed || data.isEmpty()) return
        val copy = data.copyOf()
        lock.withLock {
            if (closed) return
            chunks.addLast(Chunk(nextSequence++, copy, ptsUs, keyFrame, containsTables(copy)))
            bufferedBytes += copy.size
            while (bufferedBytes > capacityBytes && chunks.isNotEmpty()) {
                bufferedBytes -= chunks.removeFirst().data.size
            }
            changed.signalAll()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            try {
                val client = socket.accept()
                Thread({
                    // A client is allowed to disappear at any point while a chunk is being written.
                    // Do not let the resulting broken-pipe exception escape the worker thread.
                    try {
                        serve(client)
                    } catch (_: Exception) {
                        runCatching { client.close() }
                    }
                }, "http-ts-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: SocketException) {
                if (!closed) Thread.yield()
            } catch (_: Throwable) {
                if (!closed) Thread.yield()
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            while (reader.readLine()?.isNotEmpty() == true) Unit
            if (!requestLine.startsWith("GET /live.ts ") && !requestLine.startsWith("GET / ")) {
                client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val output = client.getOutputStream()
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Connection: close\r\n" +
                    "Transfer-Encoding: chunked\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            var cursor = 0L
            while (!closed) {
                val batch = lock.withLock {
                    while (!closed && (chunks.isEmpty() || (cursor != 0L && chunks.last().sequence < cursor))) {
                        changed.await()
                    }
                    if (closed || chunks.isEmpty()) emptyList()
                    else {
                        // HTTP is live playback, not a reliable queue.  When a client falls
                        // behind, rebase it to the newest decodable point (the latest
                        // PAT/PMT + keyframe) instead of replaying stale buffered data.
                        val liveStart = startSequence()
                        val start = if (cursor == 0L || cursor < chunks.first.sequence || cursor < liveStart) {
                            liveStart
                        } else {
                            cursor
                        }
                        chunks.asSequence()
                            .filter { it.sequence >= start }
                            .take(MAX_BATCH_CHUNKS)
                            .toList()
                            .also {
                                cursor = it.lastOrNull()?.sequence?.plus(1L) ?: start
                            }
                    }
                }
                if (batch.isEmpty()) continue
                for (chunk in batch) {
                    writeChunked(output, chunk.data)
                    lock.withLock { servedBytes += chunk.data.size }
                }
                output.flush()
            }
        }
    }

    private fun startSequence(): Long {
        val snapshot = chunks.toList()
        val keyIndex = snapshot.indexOfLast { it.keyFrame }
        if (keyIndex < 0) return chunks.first.sequence
        val tableIndex = snapshot.subList(0, keyIndex + 1).indexOfLast { it.tables }
        return snapshot[if (tableIndex >= 0) tableIndex else keyIndex].sequence
    }

    private fun writeChunked(output: OutputStream, data: ByteArray) {
        output.write((data.size.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
        output.write(data)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun containsTables(data: ByteArray): Boolean {
        var offset = 0
        while (offset + TS_PACKET_SIZE <= data.size) {
            if (data[offset] == 0x47.toByte()) {
                val pid = ((data[offset + 1].toInt() and 0x1f) shl 8) or (data[offset + 2].toInt() and 0xff)
                if (pid == 0 || pid == 0x1000) return true
            }
            offset += TS_PACKET_SIZE
        }
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        lock.withLock { changed.signalAll() }
        runCatching { serverSocket?.close() }
        acceptThread?.join(1_000)
        serverSocket = null
        acceptThread = null
        lock.withLock {
            chunks.clear()
            bufferedBytes = 0L
        }
    }

    private companion object {
        const val TS_PACKET_SIZE = 188
        // Keep batches short so a slow client can be re-based to a newer keyframe
        // as soon as one enters the ring.
        const val MAX_BATCH_CHUNKS = 16
        const val MIN_BUFFER_BYTES = 1L * 1024L * 1024L
        const val MAX_BUFFER_BYTES = 256L * 1024L * 1024L
    }
}
