package com.llawsxx.uvclivestreaming.recording

import android.media.Image
import android.media.MediaCodec
import java.util.concurrent.atomic.AtomicLong

internal class YuvCodecInput(private val codec: MediaCodec, private val config: RecordingConfig) : AutoCloseable {
    data class Diagnostics(val submitted: Long, val averageInputWaitMs: Double, val averageConversionMs: Double,
        val averageQueueMs: Double)
    private val converter = YuvEncoderConverter(config)
    private var pendingIndex = -1
    private var pendingImage: Image? = null
    private var lastTimestampNs = Long.MIN_VALUE
    private val submitted = AtomicLong()
    private val inputWaitNs = AtomicLong()
    private val conversionNs = AtomicLong()
    private val queueNs = AtomicLong()

    fun prime() {
        pendingIndex = codec.dequeueInputBuffer(2_000_000)
        check(pendingIndex >= 0) { "Encoder did not provide a YUV input buffer" }
        pendingImage = checkNotNull(codec.getInputImage(pendingIndex)) { "Encoder does not expose writable YUV input images" }
        check(pendingImage!!.planes.size == 3) { "Unsupported encoder input image" }
    }

    fun submit(frame: GpuVideoFrame, active: () -> Boolean): Boolean {
        if (frame.timestampNs <= lastTimestampNs) return false
        require(frame.width == config.width && frame.height == config.height)
        val waitStart = System.nanoTime()
        var index = pendingIndex
        while (index < 0 && active()) index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return false
        val image = pendingImage ?: checkNotNull(codec.getInputImage(index)) { "YUV input image unavailable" }
        pendingIndex = index
        pendingImage = image
        inputWaitNs.addAndGet(System.nanoTime() - waitStart)
        val conversionStart = System.nanoTime()
        converter.write(frame, image)
        conversionNs.addAndGet(System.nanoTime() - conversionStart)
        image.close()
        pendingImage = null
        val queueStart = System.nanoTime()
        codec.queueInputBuffer(index, 0, frame.width * frame.height * 3 / 2, frame.timestampNs / 1_000, 0)
        queueNs.addAndGet(System.nanoTime() - queueStart)
        pendingIndex = -1
        lastTimestampNs = frame.timestampNs
        submitted.incrementAndGet()
        return true
    }

    fun endOfStream() {
        pendingImage?.close()
        pendingImage = null
        val deadline = System.nanoTime() + 2_000_000_000L
        var index = pendingIndex
        while (index < 0 && System.nanoTime() < deadline) index = codec.dequeueInputBuffer(10_000)
        check(index >= 0) { "YUV encoder EOS input timeout" }
        codec.queueInputBuffer(index, 0, 0,
            if (lastTimestampNs == Long.MIN_VALUE) 0 else lastTimestampNs / 1_000 + 1,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        pendingIndex = -1
    }

    fun diagnostics(): Diagnostics {
        val count = submitted.get()
        return Diagnostics(count, if (count == 0L) 0.0 else inputWaitNs.get() / 1_000_000.0 / count,
            if (count == 0L) 0.0 else conversionNs.get() / 1_000_000.0 / count,
            if (count == 0L) 0.0 else queueNs.get() / 1_000_000.0 / count)
    }

    override fun close() {
        pendingImage?.close()
        pendingImage = null
    }
}
