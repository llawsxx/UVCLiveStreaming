package com.llawsxx.uvclivestreaming.recording

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface AudioMonitorOutput {
    /** Nonblocking write; zero means that the playback buffer is full. */
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun close()
}

/** The capture callback only offers immutable PCM; all playback I/O belongs to the worker. */
internal class UsbAudioMonitor(
    sampleRate: Int,
    channels: Int,
    private val onError: (String) -> Unit,
    queueCapacity: Int = 8,
    private val clockNs: () -> Long = System::nanoTime,
    private val outputFactory: () -> AudioMonitorOutput = { androidOutput(sampleRate, channels) },
) : AutoCloseable {
    private class Mode(val enabled: Boolean)
    private data class Chunk(val bytes: ByteArray, val mode: Mode, val enqueuedNs: Long)
    private val mode = AtomicReference(Mode(false))
    private val closed = AtomicBoolean(false)
    private val stopped = CountDownLatch(1)
    private val queue = ArrayBlockingQueue<Chunk>(queueCapacity)
    private val frameBytes = channels * 2
    private val maxChunkBytes = sampleRate * frameBytes / 10 // Bound even malformed/oversized input.
    private val worker: Thread

    init {
        require(sampleRate in 1..384_000 && channels in 1..2)
        worker = Thread(::playbackLoop, "usb-audio-monitor").apply { isDaemon = true; start() }
    }

    fun setEnabled(enabled: Boolean) {
        if (closed.get()) return
        val previous = mode.getAndUpdate { if (it.enabled == enabled) it else Mode(enabled) }
        if (previous.enabled == enabled) return
        // In-flight offers carry the old mode identity and cannot play after a toggle.
        queue.clear()
        worker.interrupt()
    }

    fun offer(bytes: ByteArray) {
        val current = mode.get()
        if (closed.get() || !current.enabled || bytes.isEmpty() ||
            bytes.size > maxChunkBytes || bytes.size % frameBytes != 0) return
        val chunk = Chunk(bytes, current, clockNs())
        if (!queue.offer(chunk)) {
            queue.poll()
            queue.offer(chunk)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        worker.interrupt()
    }

    /** Cleanup owners may wait off the capture/UI thread. */
    fun awaitStopped(timeoutMs: Long): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun playbackLoop() {
        var output: AudioMonitorOutput? = null
        var activeMode: Mode? = null
        var failedMode: Mode? = null
        try {
            while (!closed.get()) {
                try {
                    val beforePoll = mode.get()
                    if (beforePoll !== activeMode) {
                        runCatching { output?.close() }
                        output = null
                        activeMode = beforePoll
                    }
                    val chunk = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                    val current = mode.get()
                    if (current !== activeMode) {
                        runCatching { output?.close() }
                        output = null
                        activeMode = current
                    }
                    if (!current.enabled || current === failedMode || chunk.mode !== current ||
                        mode.get() !== current || clockNs() - chunk.enqueuedNs > MAX_AGE_NS) continue
                    if (output == null) output = outputFactory()
                    var offset = 0
                    while (offset < chunk.bytes.size && !closed.get() && mode.get() === current) {
                        if (clockNs() - chunk.enqueuedNs > MAX_AGE_NS) break
                        val written = output.write(chunk.bytes, offset, chunk.bytes.size - offset)
                        check(written in 0..(chunk.bytes.size - offset)) { "AudioTrack write failed: $written" }
                        if (written == 0) Thread.sleep(2) else offset += written
                    }
                } catch (_: InterruptedException) {
                    // A toggle/close wakes the worker; capture never waits for it.
                } catch (error: Exception) {
                    val failed = activeMode
                    runCatching { output?.close() }
                    output = null
                    failedMode = failed
                    if (!closed.get() && mode.get() === failed) {
                        runCatching { onError(error.message ?: error.javaClass.simpleName) }
                    }
                }
            }
        } finally {
            runCatching { output?.close() }
            queue.clear()
            stopped.countDown()
        }
    }

    companion object {
        private const val MAX_AGE_NS = 100_000_000L

        private fun androidOutput(sampleRate: Int, channels: Int): AudioMonitorOutput {
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Unsupported playback format: $sampleRate Hz, $channels channels" }
            val builder = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(channelMask).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, sampleRate * channels * 2 / 50))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            val track = builder.build()
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
                track.play()
            } catch (error: Exception) {
                track.release()
                throw error
            }
            return object : AudioMonitorOutput {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
                    track.write(bytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)

                override fun close() {
                    try {
                        track.pause()
                        track.flush()
                    } finally { track.release() }
                }
            }
        }
    }
}
