package com.llawsxx.uvclivestreaming.recording

import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** One bounded queue; independent destinations cannot hold up the queue head. */
internal class DistributedHttpTsUploadWorker(
    urls: List<String>, private val cacheSeconds: Int, private val onNotice: (String) -> Unit,
) : HttpUploadTransport {
    private class Destination(val url: String, watchdog: java.util.concurrent.ScheduledExecutorService) {
        val upload = PersistentHttpConnection(url, watchdog)
        val feedback = PersistentHttpConnection(URI(url).let { uri ->
            URI(uri.scheme, null, uri.host, uri.port, uri.rawPath.replace("/upload/", "/status/"), null, null).toString()
        }, watchdog)
        var rate = 375_000.0 // Bootstrap estimate; replaced by measured server egress feedback, not a cap.
        var measuredRate: Double? = null
        var measuredNs = 0L
        val uploadRate = HttpUploadRateMeter()
        var availableNs = 0L
        var busy = false
        var failures = 0
        var scheduledNs = 0L
        var scheduledBytes = 0
        var acknowledgedBytes = 0L
        var acknowledgedBlocks = 0L
    }
    private class Pending(val block: TsUploadBlock, val alreadyAcknowledged: Boolean = false, val rescuedNs: Long = 0) {
        var destination: Destination? = null
        var lastDestination: Destination? = null
        var rescueDestination: Destination? = null
        var retry = false
        var redirectReason = HttpUploadRedirectReason.UPLOAD_FAILURE
        var redirect: HttpUploadRedirectStats? = null
    }
    private data class Delivered(val block: TsUploadBlock, val destination: Destination, val rescuedNs: Long)
    private val lock = Object()
    private val pending = sortedMapOf<Long, Pending>() // Rescued old blocks must precede newly generated chunks.
    private val history = linkedMapOf<Long, Delivered>()
    private val monitors = mutableListOf<Thread>()
    private val pool = Executors.newFixedThreadPool(urls.size) { Thread(it, "http-distributed-upload").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "http-upload-deadline").apply { isDaemon = true } }
    private val destinations = urls.map { Destination(it, watchdog) }
    private var closed = false
    private var dropped = 0L
    private var originalDropped = 0L
    private var acknowledged: Long? = null
    private var redirectAttempts = 0L
    private var redirectAcknowledged = 0L
    private var slowDownloadRescues = 0L
    private val recentRedirects = mutableListOf<HttpUploadRedirectStats>()
    override val bytesAcknowledged = AtomicLong()
    private val dispatcher: Thread

    init {
        require(urls.size in 2..8 && validHttpUploadUrl(urls.joinToString("\n")))
        dispatcher = Thread(::dispatch, "http-upload-distributor").apply { isDaemon = true; start() }
        destinations.forEach { target -> monitors += Thread({ monitor(target) }, "http-upload-feedback").apply { isDaemon = true; start() } }
    }
    override val pendingBlocks: Int get() = synchronized(lock) { pending.size }
    override fun snapshot(): HttpTsUploadWorker.Stats = synchronized(lock) {
        val retries = pending.values.count { it.retry }
        val now = System.nanoTime()
        HttpTsUploadWorker.Stats(TsUploadQueue.Stats(pending.size - retries, retries,
            pending.values.sumOf { it.block.data.size.toLong() }, pending.values.sumOf { it.block.durationUs }, dropped),
            pending.values.firstOrNull { it.destination != null }?.block?.sequence, acknowledged, bytesAcknowledged.get(),
            destinations.map {
                val age = ((now - it.measuredNs) / 1_000_000).takeIf { age -> it.measuredRate != null && age in 0..10_000 }
                val upstream = it.uploadRate.reading(now)
                HttpUploadServerStats(it.url, it.measuredRate?.takeIf { age != null }?.let { rate -> (rate * 8).toLong() },
                    it.busy, it.failures, it.acknowledgedBytes, it.acknowledgedBlocks, age, upstream?.bitsPerSecond, upstream?.ageMs)
            },
            history.values.sumOf { it.block.data.size.toLong() }, redirectAttempts, redirectAcknowledged, recentRedirects.toList(),
            pending.values.filter { !it.alreadyAcknowledged }.sumOf { it.block.durationUs }, slowDownloadRescues, originalDropped)
    }
    override fun enqueue(block: TsUploadBlock, cacheSeconds: Int) = synchronized(lock) {
        check(!closed)
        expire()
        val maximumUs = cacheSeconds.coerceIn(30, 300) * 1_000_000L
        if (block.data.size > MAX_BYTES || block.durationUs > maximumUs ||
            System.nanoTime() - block.createdNs >= maximumUs * 1_000) { dropped++; originalDropped++; return@synchronized }
        while (history.isNotEmpty() && (history.size + pending.size >= 16_384 ||
            history.values.sumOf { it.block.data.size.toLong() } + pending.values.sumOf { it.block.data.size.toLong() } + block.data.size > MAX_BYTES))
            history.remove(history.values.minBy { it.block.createdNs }.block.sequence)
        while (pending.isNotEmpty() && (pending.size >= 16_384 ||
            pending.values.sumOf { it.block.data.size.toLong() } + block.data.size > MAX_BYTES ||
            pending.values.sumOf { it.block.durationUs } + block.durationUs > maximumUs)) retire(pending.keys.first())
        pending[block.sequence] = Pending(block)
        lock.notifyAll()
    }
    private fun retire(sequence: Long) {
        pending.remove(sequence)?.let { item ->
            item.destination?.upload?.invalidate(); dropped++
            if (!item.alreadyAcknowledged) originalDropped++
            redirectState(item, HttpUploadRedirectState.EXPIRED)
        }
    }
    private fun redirectState(item: Pending, state: HttpUploadRedirectState) {
        val at = recentRedirects.indexOfFirst { it.attempt == item.redirect?.attempt }
        val record = recentRedirects.getOrNull(at) ?: return
        if ((state == HttpUploadRedirectState.EXPIRED || state == HttpUploadRedirectState.CANCELLED) &&
            record.state != HttpUploadRedirectState.UPLOADING) return
        recentRedirects[at] = record.copy(state = state)
    }
    private fun expire() {
        val oldest = System.nanoTime() - cacheSeconds.coerceIn(30, 300) * 1_000_000_000L
        pending.values.filter { it.block.createdNs <= oldest }.map { it.block.sequence }.forEach(::retire)
        history.values.filter { it.block.createdNs <= oldest }.map { it.block.sequence }.forEach { history.remove(it) }
    }
    private fun dispatch() {
        while (true) synchronized(lock) {
            if (closed) return
            expire()
            val item = pending.values.firstOrNull { it.destination == null }
            val now = System.nanoTime()
            var free = destinations.filter { !it.busy }
            // Wait for a healthy alternative even when it is busy/paced; re-posting to the same store
            // cannot rescue a block whose download path is stalled.
            if (item?.rescueDestination != null) {
                // This store is reachable by merge and is asking for a copy it does not own.
                free = free.filter { it === item.rescueDestination }
            } else if (item?.lastDestination != null && destinations.any {
                it !== item.lastDestination && (it.failures == 0 || it.availableNs <= now)
            })
                free = free.filter { it !== item.lastDestination }
            val target = item?.let { block -> free.minByOrNull {
                max(now, it.availableNs).toDouble() + block.block.data.size / it.rate * 1e9
            } }
            if (item != null && target != null && target.availableNs <= now) {
                item.destination = target
                val from = item.lastDestination
                if (from != null && from !== target) {
                    item.redirect = HttpUploadRedirectStats(++redirectAttempts, item.block.sequence, from.url, target.url, item.redirectReason).also {
                        recentRedirects.add(0, it)
                        if (recentRedirects.size > 5) recentRedirects.removeAt(recentRedirects.lastIndex)
                    }
                } else if (item.redirect?.toUrl == target.url) redirectState(item, HttpUploadRedirectState.UPLOADING)
                else item.redirect = null
                target.busy = true
                target.scheduledNs = now
                target.scheduledBytes = item.block.data.size
                target.availableNs = now + (item.block.data.size / target.rate * 1e9).toLong()
                pool.execute { transfer(item, target) }
            } else lock.wait(50)
        }
    }
    private fun transfer(item: Pending, target: Destination) {
        val started = System.nanoTime()
        var notice: String? = null
        try {
            val rate = upload(item, target)
            synchronized(lock) {
                if (closed || pending[item.block.sequence] !== item) return@synchronized
                pending.remove(item.block.sequence)
                target.acknowledgedBytes += item.block.data.size
                target.acknowledgedBlocks++
                if (item.redirect != null) {
                    redirectAcknowledged++
                    redirectState(item, HttpUploadRedirectState.ACKNOWLEDGED)
                }
                if (!item.alreadyAcknowledged) bytesAcknowledged.addAndGet(item.block.data.size.toLong())
                acknowledged = max(acknowledged ?: -1L, item.block.sequence)
                history[item.block.sequence] = Delivered(item.block, target, item.rescuedNs)
                if (rate > 0) {
                    target.rate = rate.toDouble().coerceIn(1_000.0, 125_000_000.0)
                    target.measuredRate = target.rate
                    target.measuredNs = System.nanoTime()
                }
                if (target.failures > 0) notice = "HTTP 服务器已恢复：${target.url}"
                target.failures = 0
            }
        } catch (error: Exception) {
            target.upload.invalidate()
            synchronized(lock) {
                if (!closed && pending[item.block.sequence] === item) {
                    redirectState(item, HttpUploadRedirectState.FAILED)
                    item.destination = null
                    item.lastDestination = target
                    item.rescueDestination = null
                    item.retry = true
                    item.redirectReason = HttpUploadRedirectReason.UPLOAD_FAILURE
                    target.failures++
                    target.availableNs = System.nanoTime() + (1L shl target.failures.coerceAtMost(4)) * 1_000_000_000L
                    if (target.failures == 1) notice = "HTTP 分块 ${item.block.sequence} 上传失败，等待转投：${target.url}（${error.message}）"
                }
            }
        } finally {
            synchronized(lock) {
                target.busy = false
                if (target.failures == 0) target.availableNs = max(System.nanoTime(),
                    started + (item.block.data.size / target.rate * 1e9).toLong())
                lock.notifyAll()
            }
        }
        notice?.let { runCatching { onNotice(it) } }
    }

    /** Feedback measures server egress, independently of how quickly the phone can upload. */
    private fun monitor(target: Destination) {
        while (true) {
            synchronized(lock) { if (closed) return }
            try {
                val response = target.feedback.request("GET", deadlineNs = System.nanoTime() + 5_000_000_000L)
                if (response.status == 200 && response.headers["x-relay-mode"] == "store") {
                    val rate = response.headers["x-download-rate-bps"]?.toLongOrNull()
                    val sequence = response.headers["x-rescue-sequence"]?.toLongOrNull()
                    val session = response.headers["x-rescue-session"]
                    synchronized(lock) {
                        if (closed) return
                        expire()
                        if (rate != null && rate > 0) {
                            target.rate = rate.toDouble().coerceIn(1_000.0, 125_000_000.0)
                            target.measuredRate = target.rate
                            target.measuredNs = System.nanoTime()
                            if (!target.busy && target.failures == 0) target.availableNs = max(System.nanoTime(),
                                target.scheduledNs + (target.scheduledBytes / target.rate * 1e9).toLong())
                            lock.notifyAll()
                        } else target.measuredRate = null
                        val saved = history[sequence]
                        val now = System.nanoTime()
                        if (saved != null && saved.block.session == session &&
                            now - saved.rescuedNs >= 5_000_000_000L && !pending.containsKey(sequence)) {
                            history.remove(sequence)
                            slowDownloadRescues++
                            pending[saved.block.sequence] = Pending(saved.block, true, now).apply {
                                lastDestination = saved.destination; retry = true
                                if (saved.destination !== target) rescueDestination = target
                                redirectReason = HttpUploadRedirectReason.SLOW_DOWNLOAD
                            }
                            lock.notifyAll()
                        }
                    }
                }
            } catch (_: Exception) {
                target.feedback.invalidate()
                // Upload attempts own notices; missing feedback must not stop publishing.
            }
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
        }
    }
    private fun upload(item: Pending, destination: Destination): Long {
        val block = item.block
        val (rate, generation) = synchronized(lock) {
            if (closed || pending[block.sequence] !== item) throw IOException("Block retired")
            destination.rate to destination.upload.generation()
        }
        val now = System.nanoTime()
        val budgetNs = ((block.data.size / rate * 2 + 2) * 1e9).toLong().coerceIn(3_000_000_000L, 15_000_000_000L)
        val remainingNs = cacheSeconds.coerceIn(30, 300) * 1_000_000_000L - (now - block.createdNs)
        val headers = block.httpHeaders() + (item.redirect?.let { mapOf(
            "X-Redirect-From" to it.fromUrl, "X-Redirect-Reason" to it.reason.wireValue,
        ) } ?: emptyMap())
        val response = destination.upload.request("POST", headers, block.data,
            now + minOf(budgetNs, remainingNs.coerceAtLeast(0)), expectedGeneration = generation)
        check(response.status == 200 && response.headers["x-ack-sequence"] == block.sequence.toString()) {
            "HTTP ${response.status}: block not acknowledged"
        }
        check(response.headers["x-relay-mode"] == "store") { "多服务器上传需要服务端 --mode store" }
        synchronized(lock) {
            if (!closed && pending[block.sequence] === item)
                destination.uploadRate.record(block.data.size, response.transferNs, System.nanoTime())
        }
        return response.headers["x-download-rate-bps"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.values.forEach { redirectState(it, HttpUploadRedirectState.CANCELLED) }
            pending.clear()
            history.clear()
            destinations.forEach { it.upload.close(); it.feedback.close() }
            lock.notifyAll()
        }
        watchdog.shutdownNow()
        pool.shutdownNow()
        dispatcher.join(1_000)
        pool.awaitTermination(1, TimeUnit.SECONDS)
        monitors.forEach { it.interrupt() }
    }
    private companion object { const val MAX_BYTES = 256L * 1024 * 1024 }
}
