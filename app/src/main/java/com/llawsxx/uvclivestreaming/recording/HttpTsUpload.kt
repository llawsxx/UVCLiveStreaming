package com.llawsxx.uvclivestreaming.recording

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal fun httpUploadUrls(value: String): List<String> = value.split(Regex("[\\s,]+"))
    .filter { it.isNotBlank() }.distinct()

internal fun validHttpUploadUrl(value: String): Boolean {
    val urls = httpUploadUrls(value)
    return urls.size in 1..8 && urls.all(::validSingleHttpUploadUrl) &&
        urls.map { URI(it).rawPath }.distinct().size == 1
}

private fun validSingleHttpUploadUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        Regex("/upload/[A-Za-z0-9_-]{1,64}").matches(uri.rawPath)
}.getOrDefault(false)

internal data class TsUploadBlock(
    val session: String, val sequence: Long, val durationUs: Long,
    val final: Boolean, val data: ByteArray,
    val createdNs: Long = System.nanoTime(),
    val startUs: Long = 0,
    val sessionStartedMs: Long = System.currentTimeMillis(),
)

/** Splits complete TS packet batches by elapsed monotonic time; never edits the TS bytes. */
internal class TsUploadChunker(
    private val emit: (TsUploadBlock) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
    val session: String = UUID.randomUUID().toString(),
) : Closeable {
    private class Buffer : ByteArrayOutputStream() {
        val capacity: Int get() = buf.size
    }
    private val intervalNs = 1_000_000_000L
    private var bytes = Buffer()
    private var startNs: Long? = null
    private var originNs: Long? = null
    private val sessionStartedMs = System.currentTimeMillis()
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
        if (originNs == null) originNs = now
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
        val startUs = ((startNs ?: now) - (originNs ?: now)) / 1_000
        val endUs = (now - (originNs ?: now)) / 1_000
        val duration = if (data.isEmpty()) 0L else (endUs - startUs).coerceAtLeast(1L)
        emit(TsUploadBlock(session, sequence, duration, final, data, startNs ?: now, startUs, sessionStartedMs))
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
    url: String, private val cacheSeconds: Int,
    private val onNotice: (String) -> Unit,
) : Closeable {
    private val worker: HttpUploadTransport = if (httpUploadUrls(url).size > 1)
        DistributedHttpTsUploadWorker(httpUploadUrls(url), cacheSeconds, onNotice)
        else HttpTsUploadWorker(url.trim(), cacheSeconds, onNotice)
    private val chunker = TsUploadChunker({ worker.enqueue(it, cacheSeconds) })
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
                servers = state.servers,
                retainedBytes = state.retainedBytes,
                redirectAttempts = state.redirectAttempts,
                redirectAcknowledged = state.redirectAcknowledged,
                recentRedirects = state.recentRedirects,
                unacknowledgedDurationUs = state.unacknowledgedDurationUs,
                slowDownloadRescues = state.slowDownloadRescues,
                originalDroppedBlocks = state.originalDroppedBlocks,
            )
        }

    @Synchronized fun write(data: ByteArray) = chunker.write(data)
    @Synchronized override fun close() {
        try { worker.close() } finally { chunker.discard() }
    }
}

internal interface HttpUploadTransport : Closeable {
    val bytesAcknowledged: AtomicLong
    val pendingBlocks: Int
    fun enqueue(block: TsUploadBlock, cacheSeconds: Int)
    fun snapshot(): HttpTsUploadWorker.Stats
}

internal class HttpTsUploadWorker(private val url: String, private val cacheSeconds: Int,
                                  private val onNotice: (String) -> Unit,
                                  private val queue: TsUploadQueue = TsUploadQueue()) : HttpUploadTransport {
    private val wake = Object()
    override val bytesAcknowledged = AtomicLong()
    @Volatile private var closed = false
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "http-upload-timeout").apply { isDaemon = true }
    }
    private val connection = PersistentHttpConnection(url, watchdog)
    private var activeBlock: TsUploadBlock? = null
    private var failing = false
    private var uploadingSequence: Long? = null
    private var acknowledgedSequence: Long? = null
<<<<<<< Updated upstream
=======
    private var acknowledgedBlocks = 0L
    private var consecutiveFailures = 0
    private var measuredRate: Long? = null
    private var measuredNs = 0L
>>>>>>> Stashed changes
    private val thread: Thread
    override val pendingBlocks: Int get() = queue.size
    data class Stats(val queue: TsUploadQueue.Stats, val uploadingSequence: Long?,
                     val acknowledgedSequence: Long?, val acknowledgedBytes: Long,
                     val servers: List<HttpUploadServerStats> = emptyList(), val retainedBytes: Long = 0,
                     val redirectAttempts: Long = 0, val redirectAcknowledged: Long = 0,
                     val recentRedirects: List<HttpUploadRedirectStats> = emptyList(),
                     val unacknowledgedDurationUs: Long = queue.durationUs, val slowDownloadRescues: Long = 0,
                     val originalDroppedBlocks: Long = queue.droppedBlocks)
    override fun snapshot(): Stats = synchronized(wake) {
<<<<<<< Updated upstream
        Stats(queue.snapshot(), uploadingSequence, acknowledgedSequence, bytesAcknowledged.get())
=======
        val age = ((System.nanoTime() - measuredNs) / 1_000_000).takeIf { measuredRate != null && it in 0..10_000 }
        Stats(queue.snapshot(), uploadingSequence, acknowledgedSequence, bytesAcknowledged.get(),
            servers = listOf(HttpUploadServerStats(url, measuredRate?.takeIf { age != null }, uploadingSequence != null, consecutiveFailures,
                bytesAcknowledged.get(), acknowledgedBlocks, age)))
>>>>>>> Stashed changes
    }

    init {
        require(validHttpUploadUrl(url)) { "HTTP 上传地址应为 http(s)://服务器:端口/upload/流名称" }
        thread = Thread(::run, "http-ts-upload").apply { isDaemon = true; start() }
    }

    override fun enqueue(block: TsUploadBlock, cacheSeconds: Int) {
        synchronized(wake) {
            check(!closed) { "HTTP upload is stopped" }
            queue.append(block, cacheSeconds, retry = failing)
            if (activeBlock != null && queue.first() !== activeBlock) {
                connection.invalidate()
                uploadingSequence = null
            }
            wake.notifyAll()
        }
    }

    override fun close() {
        synchronized(wake) {
            if (closed) return
            closed = true
            queue.clear()
            uploadingSequence = null
            wake.notifyAll()
        }
        connection.close()
        watchdog.shutdownNow()
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
        val generation = synchronized(wake) {
            if (closed || queue.first() !== block) throw IOException("HTTP block was retired")
            connection.generation()
        }
        val now = System.nanoTime()
        val remainingNs = cacheSeconds.coerceIn(30, 300) * 1_000_000_000L - (now - block.createdNs)
        try {
            val response = connection.request("POST", block.httpHeaders(), block.data,
                now + remainingNs.coerceIn(0, 15_000_000_000L), readTimeoutMs = 10_000, expectedGeneration = generation)
            check(response.status == 200 && response.headers["x-ack-sequence"] == block.sequence.toString()) {
                "HTTP ${response.status}，服务器未确认块 ${block.sequence}"
            }
            synchronized(wake) {
                measuredRate = response.headers["x-download-rate-bps"]?.toLongOrNull()?.takeIf {
                    response.headers["x-relay-mode"] == "store" && it in 1_000..125_000_000
                }?.times(8)
                measuredNs = System.nanoTime()
            }
        } catch (error: Exception) {
            connection.invalidate()
            throw error
        }
    }
}
