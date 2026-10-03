package com.llawsxx.uvclivestreaming.recording

import java.util.TreeMap
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
    private val decoder: (ByteArray, Int, Int, Int) -> ByteArray?,
    workerCount: Int = 4,
    capacity: Int = 10,
) {
    data class DecodedFrame(
        val width: Int,
        val height: Int,
        val timestampNs: Long,
        val yuv: ByteArray?,
    )

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
    private var nextSequence = 0L
    private var nextOutput = 0L
    private var completedBytes = 0L

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
            val frame = InputFrame(nextSequence++, bytes, format, width, height, timestampNs)
            if (!input.offer(frame)) {
                // Drop the oldest queued frame, but publish a completion for
                // its sequence so ordered output never waits forever.
                input.poll()?.let { dropped ->
                    addCompletedLocked(DecodedFrame(
                        dropped.width, dropped.height, dropped.timestampNs, null,
                    ), dropped.sequence)
                }
                if (!input.offer(frame)) {
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
            completed.clear()
            completedBytes = 0L
            monitor.notifyAll()
        }
        workers.forEach { it.interrupt() }
        workers.forEach { runCatching { it.join(1_000) } }
        workers.clear()
    }

    private fun decodeLoop() {
        while (running.get()) {
            val frame = try { input.poll(100, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { break } ?: continue
            val yuv = runCatching {
                decoder(frame.bytes, frame.format, frame.width, frame.height)
            }.getOrNull()
            synchronized(monitor) {
                if (running.get()) {
                    addCompletedLocked(DecodedFrame(
                        frame.width, frame.height, frame.timestampNs, yuv,
                    ), frame.sequence)
                    monitor.notifyAll()
                }
            }
        }
    }

    private fun addCompletedLocked(frame: DecodedFrame, sequence: Long) {
        if (sequence < nextOutput) return
        val bounded = if (frame.yuv != null && frame.yuv.size.toLong() > maxCompletedBytes) {
            frame.copy(yuv = null)
        } else {
            frame
        }
        completed.remove(sequence)?.let { previous ->
            completedBytes -= previous.yuv?.size?.toLong() ?: 0L
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
            if (sequence >= nextOutput) nextOutput = sequence + 1L
        }
        while (completed.isNotEmpty() && completed.firstKey() < nextOutput) {
            val sequence = completed.firstKey()
            val removed = completed.remove(sequence) ?: break
            completedBytes -= removed.yuv?.size?.toLong() ?: 0L
        }
    }
}
