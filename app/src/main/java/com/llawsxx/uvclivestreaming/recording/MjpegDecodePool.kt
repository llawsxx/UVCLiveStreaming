package com.llawsxx.uvclivestreaming.recording

import java.util.TreeMap
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, ordered MJPEG decode pipeline. Four workers decode independently,
 * while the sequence map keeps frames in capture order for the renderer and
 * encoder. The input cache is bounded by the caller's video-cache setting so
 * latency cannot grow without bound when the USB source outruns the device.
 */
internal class MjpegDecodePool(
    private val decoder: (ByteArray, Int, Int, Int, ByteBuffer) -> Boolean,
    workerCount: Int = 4,
    capacity: Int = 10,
) {
    data class Diagnostics(
        val offered: Long,
        val decodeAttempts: Long,
        val decodeFailures: Long,
        val inputDrops: Long,
        val outputSkippedSequences: Long,
        val lateCompletions: Long,
        val delivered: Long,
        val averageDecodeMs: Double,
        val inputQueued: Int,
        val outputQueued: Int,
        val outputBuffers: DirectVideoBufferPool.Diagnostics,
    )
    data class DecodedFrame(
        val width: Int,
        val height: Int,
        val timestampNs: Long,
        val yuv: DirectVideoBufferPool.Lease?,
    ) : AutoCloseable {
        override fun close() { yuv?.close() }
    }

    private data class InputFrame(
        val sequence: Long,
        val bytes: ByteArray,
        val format: Int,
        val width: Int,
        val height: Int,
        val timestampNs: Long,
    )

    private val running = AtomicBoolean(false)
    private val input = ArrayBlockingQueue<InputFrame>(capacity.coerceAtLeast(1))
    private val completed = TreeMap<Long, DecodedFrame>()
    private val monitor = Object()
    private val workers = mutableListOf<Thread>()
    private val count = workerCount.coerceIn(1, 8)
    // YUV frames are much larger than compressed MJPEG. Keep their completed
    // side strictly bounded even when the UI cache is configured larger.
    private val maxCompletedFrames = capacity.coerceIn(1, 8)
    private val maxCompletedBytes = 32L * 1024L * 1024L
    private val buffers = DirectVideoBufferPool(count + maxCompletedFrames + 1, maxCompletedBytes)
    private var nextSequence = 0L
    private var nextOutput = 0L
    private var completedBytes = 0L
    private var decodeAttempts = 0L
    private var decodeFailures = 0L
    private var inputDrops = 0L
    private var outputSkippedSequences = 0L
    private var lateCompletions = 0L
    private var delivered = 0L
    private var decodeTotalNs = 0L

    fun diagnostics(): Diagnostics = synchronized(monitor) {
        Diagnostics(nextSequence, decodeAttempts, decodeFailures, inputDrops,
            outputSkippedSequences, lateCompletions, delivered,
            if (decodeAttempts > 0) decodeTotalNs / 1_000_000.0 / decodeAttempts else 0.0,
            input.size, completed.size, buffers.diagnostics())
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        repeat(count) { index ->
            Thread({ decodeLoop() }, "mjpeg-decode-$index").also {
                workers += it
                it.start()
            }
        }
    }

    fun offer(bytes: ByteArray, format: Int, width: Int, height: Int, timestampNs: Long) {
        if (!running.get()) return
        synchronized(monitor) {
            if (!running.get()) return
            val frame = InputFrame(nextSequence++, bytes, format, width, height, timestampNs)
            if (!input.offer(frame)) {
                // Drop the oldest queued frame, but publish a completion for
                // its sequence so ordered output never waits forever.
                input.poll()?.let { dropped ->
                    inputDrops++
                    addCompletedLocked(DecodedFrame(
                        dropped.width, dropped.height, dropped.timestampNs, null,
                    ), dropped.sequence)
                }
                if (!input.offer(frame)) {
                    inputDrops++
                    addCompletedLocked(
                        DecodedFrame(frame.width, frame.height, frame.timestampNs, null),
                        frame.sequence,
                    )
                }
            }
            monitor.notifyAll()
        }
    }

    fun poll(timeoutMs: Long): DecodedFrame? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        synchronized(monitor) {
            while (running.get() || completed.containsKey(nextOutput)) {
                completed.remove(nextOutput)?.let {
                    completedBytes -= it.yuv?.size?.toLong() ?: 0L
                    nextOutput++
                    if (it.yuv != null) delivered++
                    return it
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return null
                val waitMs = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)
                monitor.wait(waitMs)
            }
        }
        return null
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        input.clear()
        synchronized(monitor) {
            completed.values.forEach { it.close() }
            completed.clear()
            completedBytes = 0L
            monitor.notifyAll()
        }
        buffers.close()
        workers.forEach { it.interrupt() }
        workers.forEach { runCatching { it.join(1_000) } }
        workers.clear()
    }

    private fun decodeLoop() {
        while (running.get()) {
            val frame = try { input.poll(100, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { break } ?: continue
            val decodeStartNs = System.nanoTime()
            var yuv: DirectVideoBufferPool.Lease? = null
            try {
                if (frame.width in 1..3840 && frame.height in 1..2160) {
                    val size = frame.width * frame.height + 2 * ((frame.width + 1) / 2) * ((frame.height + 1) / 2)
                    yuv = buffers.acquire(size)
                    if (yuv != null && !decoder(frame.bytes, frame.format, frame.width, frame.height, yuv.buffer)) {
                        yuv.close()
                        yuv = null
                    }
                }
            } catch (_: Throwable) {
                yuv?.close()
                yuv = null
            }
            val decodeElapsedNs = System.nanoTime() - decodeStartNs
            synchronized(monitor) {
                if (running.get()) {
                    decodeAttempts++
                    decodeTotalNs += decodeElapsedNs
                    if (yuv == null) decodeFailures++
                    addCompletedLocked(DecodedFrame(
                        frame.width, frame.height, frame.timestampNs, yuv,
                    ), frame.sequence)
                    monitor.notifyAll()
                } else yuv?.close()
            }
        }
    }

    private fun addCompletedLocked(frame: DecodedFrame, sequence: Long) {
        if (sequence < nextOutput) {
            lateCompletions++
            frame.close()
            return
        }
        val bounded = if (frame.yuv != null && frame.yuv.size.toLong() > maxCompletedBytes) {
            frame.close()
            frame.copy(yuv = null)
        } else {
            frame
        }
        completed.remove(sequence)?.let { previous ->
            completedBytes -= previous.yuv?.size?.toLong() ?: 0L
            previous.close()
        }
        completed[sequence] = bounded
        completedBytes += bounded.yuv?.size?.toLong() ?: 0L
        trimCompletedLocked()
    }

    private fun trimCompletedLocked() {
        while (completed.isNotEmpty() &&
            (completed.size > maxCompletedFrames || completedBytes > maxCompletedBytes)
        ) {
            val sequence = completed.firstKey()
            val removed = completed.remove(sequence) ?: break
            completedBytes -= removed.yuv?.size?.toLong() ?: 0L
            removed.close()
            if (sequence >= nextOutput) {
                outputSkippedSequences += sequence + 1L - nextOutput
                nextOutput = sequence + 1L
            }
        }
        while (completed.isNotEmpty() && completed.firstKey() < nextOutput) {
            val sequence = completed.firstKey()
            val removed = completed.remove(sequence) ?: break
            completedBytes -= removed.yuv?.size?.toLong() ?: 0L
            removed.close()
        }
    }
}
