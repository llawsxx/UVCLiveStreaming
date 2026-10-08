package com.llawsxx.uvclivestreaming.recording

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal fun validHttpUploadUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        Regex("/upload/[A-Za-z0-9_-]{1,64}").matches(uri.rawPath)
}.getOrDefault(false)

internal data class TsUploadBlock(
    val session: String, val sequence: Long, val durationUs: Long,
    val final: Boolean, val data: ByteArray,
    val createdNs: Long = System.nanoTime(),
)

/** Splits complete TS packet batches by elapsed monotonic time; never edits the TS bytes. */
internal class TsUploadChunker(
    seconds: Int,
    private val emit: (TsUploadBlock) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
    val session: String = UUID.randomUUID().toString(),
) : Closeable {
    private class Buffer : ByteArrayOutputStream() {
        val capacity: Int get() = buf.size
    }
    private val intervalNs = seconds.coerceIn(1, 5) * 1_000_000_000L
    private var bytes = Buffer()
    private var startNs: Long? = null
    private var sequence = 0L
    private var closed = false
    val latestSequence: Long? get() = (sequence - 1).takeIf { it >= 0 }
    val pendingBytes: Int get() = bytes.size()
    val allocatedBytes: Int get() = if (closed) 0 else bytes.capacity
    val pendingDurationUs: Long get() = startNs?.let { ((clockNs() - it) / 1_000).coerceAtLeast(0) } ?: 0

    fun write(data: ByteArray) {
        check(!closed)
        require(data.size % 188 == 0 && data.size <= MAX_BLOCK_BYTES)
        if (data.isEmpty()) return
        val now = clockNs()
        val start = startNs
        if (start != null && bytes.size() > 0 &&
            (now - start >= intervalNs || bytes.size() + data.size > MAX_BLOCK_BYTES)) {
            flush(now, final = false)
        }
        if (startNs == null) startNs = now
        bytes.write(data)
    }

    private fun flush(now: Long, final: Boolean) {
        val data = bytes.toByteArray()
        val duration = if (data.isEmpty()) 0L else ((now - checkNotNull(startNs)) / 1_000).coerceAtLeast(1L)
        emit(TsUploadBlock(session, sequence, duration, final, data, startNs ?: now))
        sequence++
        bytes = Buffer()
        startNs = null
    }

    override fun close() {
        if (closed) return
        closed = true
        flush(clockNs(), final = true)
    }

    fun discard() {
        closed = true
        bytes = Buffer()
        startNs = null
    }

    companion object { const val MAX_BLOCK_BYTES = 16 * 1024 * 1024 }
}

/** Bounded live backlog. Oldest blocks can be retired even while an upload awaits ACK. */
internal class TsUploadQueue(private val maxBytes: Long = 256L * 1024 * 1024,
                             private val clockNs: () -> Long = System::nanoTime) {
    private val queue = ArrayDeque<TsUploadBlock>()
    private var pendingBytes = 0L
    private var pendingDurationUs = 0L
    private var pendingRetryBlocks = 0
    private var droppedBlocks = 0L
    data class Stats(val uploadBlocks: Int, val retryBlocks: Int, val bytes: Long, val durationUs: Long,
                     val droppedBlocks: Long = 0)

    @Synchronized fun snapshot(): Stats {
        return Stats(queue.size - pendingRetryBlocks, pendingRetryBlocks, pendingBytes, pendingDurationUs, droppedBlocks)
    }

    @Synchronized fun markForRetry() {
        pendingRetryBlocks = queue.size
    }
    val size: Int
        @Synchronized get() = queue.size

    @Synchronized fun append(block: TsUploadBlock, maxSeconds: Int, retry: Boolean = false) {
        expire(maxSeconds)
        val maxDurationUs = maxSeconds.coerceIn(30, 300) * 1_000_000L
        if (block.data.size > maxBytes || block.durationUs > maxDurationUs ||
            clockNs() - block.createdNs >= maxDurationUs * 1_000) {
            droppedBlocks++
            return
        }
        while (queue.isNotEmpty() && (queue.size >= MAX_BLOCKS || pendingBytes + block.data.size > maxBytes ||
                pendingDurationUs + block.durationUs > maxDurationUs)) {
            removeHead(); droppedBlocks++
        }
        // The chunker transfers ownership of this byte array; retries use the same block.
        queue.addLast(block)
        pendingBytes += block.data.size
        pendingDurationUs += block.durationUs
        if (retry) pendingRetryBlocks = queue.size
    }

    @Synchronized fun expire(maxSeconds: Int) {
        val oldestNs = clockNs() - maxSeconds.coerceIn(30, 300) * 1_000_000_000L
        while (queue.peekFirst()?.let { it.createdNs <= oldestNs } == true) {
            removeHead(); droppedBlocks++
        }
    }

    @Synchronized fun clear() {
        queue.clear()
        pendingBytes = 0
        pendingDurationUs = 0
        pendingRetryBlocks = 0
    }

    @Synchronized fun first(): TsUploadBlock? = queue.peekFirst()

    @Synchronized fun acknowledge(block: TsUploadBlock) {
        val pending = checkNotNull(queue.peekFirst())
        check(pending === block) { "Acknowledgement must match the pending queue head" }
        removeHead()
    }

    private fun removeHead() {
        val pending = queue.removeFirst()
        if (pendingRetryBlocks > 0) pendingRetryBlocks--
        pendingBytes -= pending.data.size
        pendingDurationUs -= pending.durationUs
    }

    private companion object { const val MAX_BLOCKS = 16_384 }
}

internal class HttpTsUploadSink(
    url: String, seconds: Int, private val cacheSeconds: Int,
    private val onNotice: (String) -> Unit,
) : Closeable {
    private val worker = HttpTsUploadWorker(url, cacheSeconds, onNotice)
    private val chunker = TsUploadChunker(seconds, { worker.enqueue(it, cacheSeconds) })
    val bytesSent: Long get() = worker.bytesAcknowledged.get()
    internal val pendingBlocks: Int get() = worker.pendingBlocks
    val stats: HttpUploadStats
        @Synchronized get() = worker.snapshot().let { state ->
            HttpUploadStats(
                sessionId = chunker.session, latestSequence = chunker.latestSequence,
                uploadingSequence = state.uploadingSequence, acknowledgedSequence = state.acknowledgedSequence,
                pendingUploadBlocks = state.queue.uploadBlocks, pendingRetryBlocks = state.queue.retryBlocks,
                queuedBytes = state.queue.bytes, queuedDurationUs = state.queue.durationUs,
                assemblingBytes = chunker.pendingBytes, assemblingCapacityBytes = chunker.allocatedBytes,
                assemblingDurationUs = chunker.pendingDurationUs,
                cacheLimitSeconds = cacheSeconds.coerceIn(30, 300), cacheLimitBytes = 256L * 1024 * 1024,
                acknowledgedBytes = state.acknowledgedBytes,
                droppedBlocks = state.queue.droppedBlocks,
            )
        }

    @Synchronized fun write(data: ByteArray) = chunker.write(data)
    @Synchronized override fun close() {
        try { worker.close() } finally { chunker.discard() }
    }
}

internal class HttpTsUploadWorker(private val url: String, private val cacheSeconds: Int,
                                  private val onNotice: (String) -> Unit,
                                  private val queue: TsUploadQueue = TsUploadQueue()) : Closeable {
    private val wake = Object()
    val bytesAcknowledged = AtomicLong()
    @Volatile private var closed = false
    private var activeSocket: Socket? = null
    private var activeBlock: TsUploadBlock? = null
    private var failing = false
    private var uploadingSequence: Long? = null
    private var acknowledgedSequence: Long? = null
    private val thread: Thread
    val pendingBlocks: Int get() = queue.size
    data class Stats(val queue: TsUploadQueue.Stats, val uploadingSequence: Long?,
                     val acknowledgedSequence: Long?, val acknowledgedBytes: Long)
    fun snapshot(): Stats = synchronized(wake) {
        Stats(queue.snapshot(), uploadingSequence, acknowledgedSequence, bytesAcknowledged.get())
    }

    init {
        require(validHttpUploadUrl(url)) { "HTTP 上传地址应为 http(s)://服务器:端口/upload/流名称" }
        thread = Thread(::run, "http-ts-upload").apply { isDaemon = true; start() }
    }

    fun enqueue(block: TsUploadBlock, cacheSeconds: Int) {
        synchronized(wake) {
            check(!closed) { "HTTP upload is stopped" }
            queue.append(block, cacheSeconds, retry = failing)
            if (activeBlock != null && queue.first() !== activeBlock) {
                runCatching { activeSocket?.close() }
                uploadingSequence = null
            }
            wake.notifyAll()
        }
    }

    override fun close() {
        val socket = synchronized(wake) {
            if (closed) return
            closed = true
            queue.clear()
            uploadingSequence = null
            wake.notifyAll()
            activeSocket.also { activeSocket = null }
        }
        runCatching { socket?.close() }
        if (Thread.currentThread() != thread) thread.join(1_000)
    }

    private fun run() {
        while (!closed) {
            val pending = synchronized(wake) {
                while (!closed) {
                    queue.expire(cacheSeconds)
                    if (queue.first() != null) break
                    wake.wait()
                }
                if (closed) return
                checkNotNull(queue.first()).also { activeBlock = it; uploadingSequence = it.sequence }
            }
            try {
                upload(pending)
                val recovered = synchronized(wake) {
                    if (closed) return
                    if (queue.first() !== pending) continue
                    queue.acknowledge(pending)
                    uploadingSequence = null
                    acknowledgedSequence = pending.sequence
                    bytesAcknowledged.addAndGet(pending.data.size.toLong())
                    failing.also { failing = false }
                }
                if (recovered) onNotice("HTTP 上传已恢复，正在按序补传")
            } catch (error: Exception) {
                val firstFailure = synchronized(wake) {
                    if (closed) return
                    uploadingSequence = null
                    queue.expire(cacheSeconds)
                    if (queue.first() !== pending) continue
                    queue.markForRetry()
                    (!failing).also { failing = true }
                }
                if (firstFailure) onNotice("HTTP 上传中断，数据保留等待补传：${error.message}")
                synchronized(wake) { if (!closed) wake.wait(1_000) }
            } finally {
                synchronized(wake) { activeBlock = null; uploadingSequence = null }
            }
        }
    }

    private fun upload(block: TsUploadBlock) {
        val uri = URI(url)
        val tls = uri.scheme == "https"
        val port = if (uri.port >= 0) uri.port else if (tls) 443 else 80
        val transport = Socket()
        synchronized(wake) {
            if (closed || queue.first() !== block) { transport.close(); throw IOException("HTTP block was retired") }
            activeSocket = transport
        }
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "http-upload-timeout").apply { isDaemon = true }
        }
        // Close the underlying socket directly, so even a blocked request-body
        // write is unblocked. The deadline also covers TLS and acknowledgement.
        val remainingNs = cacheSeconds.coerceIn(30, 300) * 1_000_000_000L - (System.nanoTime() - block.createdNs)
        val deadline = watchdog.schedule({ runCatching { transport.close() } },
            remainingNs.coerceIn(0, 15_000_000_000L), TimeUnit.NANOSECONDS)
        try {
            transport.connect(InetSocketAddress(uri.host, port), 5_000)
            transport.soTimeout = 10_000
            val socket = if (tls) {
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(transport, uri.host, port, true) as SSLSocket).apply {
                    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    soTimeout = 10_000
                    startHandshake()
                }
            } else transport
            socket.use {
                val headers = "POST ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:$port\r\n" +
                    "Connection: close\r\nContent-Type: video/mp2t\r\nContent-Length: ${block.data.size}\r\n" +
                    "X-Session-ID: ${block.session}\r\nX-Sequence: ${block.sequence}\r\n" +
                    "X-Duration-Us: ${block.durationUs}\r\nX-Final: ${if (block.final) 1 else 0}\r\n\r\n"
                socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(block.data); flush() }
                val input = socket.getInputStream().buffered()
                var headerBytes = 0
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val value = input.read()
                        check(value >= 0) { "服务器在确认前断开连接" }
                        check(++headerBytes <= 16_384) { "HTTP 响应头过大" }
                        if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                        bytes.write(value)
                    }
                }
                var status: Int?
                var ack: String?
                do {
                    status = line().split(' ').getOrNull(1)?.toIntOrNull()
                    ack = null
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.substringBefore(':').equals("X-Ack-Sequence", ignoreCase = true)) {
                            check(ack == null) { "重复的 HTTP 确认头" }
                            ack = header.substringAfter(':').trim()
                        }
                    }
                } while (status == 100)
                check(status == 200 && ack == block.sequence.toString()) {
                    "HTTP $status，服务器未确认块 ${block.sequence}"
                }
            }
        } finally {
            deadline.cancel(false)
            watchdog.shutdownNow()
            transport.close()
            synchronized(wake) { if (activeSocket === transport) activeSocket = null }
        }
    }
}
