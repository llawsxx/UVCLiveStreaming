package com.llawsxx.uvclivestreaming.recording

import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** USB callback only offers PCM. DSP, meters and fan-out all run on this worker. */
internal class UsbAudioPipeline(
    private val rate: Int,
    private val channels: Int,
    initialSettings: AudioDspSettings,
    private val onPcm: (ByteArray, Long) -> Unit,
    private val onError: (String) -> Unit,
    private val onStopped: () -> Unit = {},
    private val factory: (Int, Int, AudioDspSettings) -> PcmDsp = NativeAudioDsp::processor,
) : AutoCloseable {
    private data class Packet(val bytes: ByteArray, val timestampNs: Long, val sequence: Long, val sampleBytes: Int)
    private data class Span(val timestampNs: Long, val frames: Int, var offset: Int = 0)
    private val queue = ArrayBlockingQueue<Packet>(16)
    private val queueWake = QueueWakeSignal()
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val stopped = CountDownLatch(1)
    private val peakMeter = UsbAudioPeakMeter(rate, channels)
    @Volatile private var settings = initialSettings.sanitized()
    val droppedPackets = AtomicLong()
    private val worker = Thread(::run, "usb-audio-dsp").apply {
        isDaemon = true
        start()
    }

    fun updateSettings(value: AudioDspSettings) { settings = value.sanitized() }
    fun recentPeakDb(): Float = peakMeter.levelDb()

    private fun publishPcm(bytes: ByteArray, timestampNs: Long) {
        peakMeter.offer(bytes, timestampNs)
        onPcm(bytes, timestampNs)
    }
    fun offer(bytes: ByteArray, timestampNs: Long, sampleBytes: Int = 2) = synchronized(queueWake) {
        if (closed.get() || bytes.isEmpty() || sampleBytes !in 2..4 || bytes.size % (channels * sampleBytes) != 0) return
        val packet = Packet(bytes, timestampNs, sequence.getAndIncrement(), sampleBytes)
        if (!queue.offer(packet)) {
            if (queue.poll() != null) droppedPackets.incrementAndGet()
            if (!queue.offer(packet)) droppedPackets.incrementAndGet()
        }
        queueWake.signal()
    }

    private fun run() {
        var processor: PcmDsp? = null
        var applied: AudioDspSettings? = null
        var skip = 0
        var lastSequence = -1L
        val spans = ArrayDeque<Span>()
        val frameBytes = channels * 2
        fun emit(bytes: ByteArray) {
            var offset = minOf(skip * frameBytes, bytes.size)
            skip -= offset / frameBytes
            while (offset < bytes.size && spans.isNotEmpty()) {
                val span = spans.first
                val length = minOf(bytes.size - offset, (span.frames - span.offset) * frameBytes)
                val timestamp = span.timestampNs + span.offset.toLong() * 1_000_000_000L / rate
                publishPcm(if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length), timestamp)
                offset += length
                span.offset += length / frameBytes
                if (span.offset == span.frames) spans.removeFirst()
            }
        }
        fun finishProcessor() {
            processor?.let { dsp ->
                // Discard only initial padding; emit the complete delayed tail at
                // its original source PTS. No accumulated A/V offset or lost tail.
                if (spans.isNotEmpty()) {
                    val tail = ByteArray(dsp.delayFrames * frameBytes)
                    dsp.process(tail)
                    emit(tail)
                }
                dsp.close()
            }
            processor = null
            spans.clear()
        }
        try {
            while (!closed.get() || queue.isNotEmpty()) {
                val revision = queueWake.snapshot()
                val packet = queue.poll()
                if (packet == null) {
                    queueWake.awaitChange(revision)
                    continue
                }
                val next = settings
                val discontinuity = lastSequence >= 0 && packet.sequence != lastSequence + 1
                val changed = next != applied
                // Targets/thresholds keep measurement and envelope history.
                // Only delay/enable changes or missing input reset the rings.
                val updatedInPlace = changed && !discontinuity && processor?.updateSettings(next) == true
                if (discontinuity || (changed && !updatedInPlace)) {
                    finishProcessor()
                    processor = if (next.active) factory(rate, channels, next) else null
                    skip = processor?.delayFrames ?: 0
                }
                applied = next
                lastSequence = packet.sequence
                val dsp = processor
                if (dsp == null) publishPcm(PcmSamples.toPcm16(packet.bytes, packet.sampleBytes), packet.timestampNs)
                else {
                    spans.add(Span(packet.timestampNs, packet.bytes.size / (channels * packet.sampleBytes)))
                    val output = if (packet.sampleBytes == 2) packet.bytes.also(dsp::process)
                        else dsp.processWide(packet.bytes, packet.sampleBytes)
                    emit(output)
                }
            }
            finishProcessor()
        } catch (error: Throwable) {
            onError(error.message ?: "音频 DSP 处理失败")
        } finally {
            closed.set(true)
            queueWake.close()
            try {
                processor?.close()
                queue.clear()
                onStopped()
            } finally {
                stopped.countDown()
            }
        }
    }

    override fun close() = synchronized(queueWake) {
        closed.set(true)
        queueWake.close()
    }
    fun awaitStopped(timeoutMs: Long): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)
    fun awaitStopped() = stopped.await()
}
