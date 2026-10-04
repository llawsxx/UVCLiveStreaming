package com.llawsxx.uvclivestreaming.recording

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.log10

/** Rolling sample peak over one second, in 10 ms source-time buckets.
 * Called on processed PCM, before UI throttling. Queries expire old peaks even
 * when capture stops producing data. Worker writes and UI reads are synchronized.
 */
internal class UsbAudioPeakMeter(private val rate: Int, private val channels: Int) {
    private data class Peak(val bucket: Long, val amplitude: Int)
    private val peaks = ArrayDeque<Peak>()
    private var lastBucket: Long? = null

    init { require(rate > 0 && channels in 1..2) }

    @Synchronized
    fun offer(bytes: ByteArray, timestampNs: Long) {
        val frames = bytes.size / (channels * 2)
        var bucket = Long.MIN_VALUE
        var peak = 0
        for (frame in 0 until frames) {
            val nextBucket = (timestampNs + frame.toLong() * 1_000_000_000L / rate) / BUCKET_NS
            if (nextBucket != bucket) {
                if (bucket != Long.MIN_VALUE) add(bucket, peak)
                bucket = nextBucket
                peak = 0
            }
            for (channel in 0 until channels) {
                val offset = (frame * channels + channel) * 2
                val sample = ((bytes[offset + 1].toInt() shl 8) or
                    (bytes[offset].toInt() and 0xff)).toShort().toInt()
                peak = maxOf(peak, abs(sample))
            }
        }
        if (bucket != Long.MIN_VALUE) add(bucket, peak)
    }

    private fun add(bucket: Long, amplitude: Int) {
        // A source clock discontinuity starts a new history.
        if (lastBucket?.let { bucket < it } == true) peaks.clear()
        lastBucket = bucket
        expire(bucket * BUCKET_NS)
        if (amplitude == 0) return
        // At most one candidate per bucket, and only candidates that could
        // become the maximum as older peaks expire (at most 101 candidates).
        if (peaks.peekLast()?.let { it.bucket == bucket && it.amplitude > amplitude } == true) return
        while (peaks.peekLast()?.let { it.amplitude <= amplitude } == true) peaks.removeLast()
        peaks.addLast(Peak(bucket, amplitude))
    }

    private fun expire(nowNs: Long) {
        val cutoff = nowNs - WINDOW_NS
        while (peaks.peekFirst()?.let { (it.bucket + 1) * BUCKET_NS <= cutoff } == true) peaks.removeFirst()
    }

    @Synchronized
    fun levelDb(nowNs: Long = System.nanoTime()): Float {
        expire(nowNs)
        val amplitude = peaks.peekFirst()?.amplitude ?: return -60f
        return (20.0 * log10(amplitude / 32768.0)).toFloat().coerceIn(-60f, 0f)
    }

    private companion object {
        const val BUCKET_NS = 10_000_000L
        const val WINDOW_NS = 1_000_000_000L
    }
}
