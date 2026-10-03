package com.llawsxx.uvclivestreaming.recording

import kotlin.math.abs
import kotlin.math.roundToLong

/** Convert nominal NTSC modes once; fractional modes and PAL rates keep their chosen rate. */
internal fun videoSmoothingFrameRate(selectedFps: Double, useNtsc: Boolean): Double =
    if (!useNtsc) selectedFps else when (selectedFps) {
        60.0 -> 60_000.0 / 1_001.0
        30.0 -> 30_000.0 / 1_001.0
        else -> selectedFps
    }

/** One unit is a video frame or a PCM sample frame (all channels at one instant). */
internal class TimestampSmoother(
    private val unitsPerSecond: Double,
    maxDeltaSeconds: Double,
) {
    private val maxDeltaNs = maxDeltaSeconds * 1_000_000_000.0
    private var originNs: Long? = null
    private var elapsedUnits = 0L
    private var lastTimestampNs: Long? = null

    init {
        require(unitsPerSecond.isFinite() && unitsPerSecond > 0)
        require(maxDeltaSeconds.isFinite() && maxDeltaSeconds >= 0)
    }

    /** Call before dropping queued data so dropped frames retain their time on the timeline. */
    fun smooth(actualTimestampNs: Long, units: Long = 1): Long {
        require(units > 0)
        val origin = originNs
        val calculated = origin?.let {
            it + (elapsedUnits * 1_000_000_000.0 / unitsPerSecond).roundToLong()
        }
        val withinThreshold = calculated != null &&
            abs(actualTimestampNs.toDouble() - calculated.toDouble()) <= maxDeltaNs
        val selected = if (withinThreshold) checkNotNull(calculated) else actualTimestampNs
        // A backwards device timestamp must not make the encoder discard frames or regress PTS.
        val timestamp = lastTimestampNs?.let { selected.coerceAtLeast(it + 1) } ?: selected
        if (!withinThreshold || timestamp != selected) {
            originNs = timestamp
            elapsedUnits = 0
        }
        elapsedUnits += units
        lastTimestampNs = timestamp
        return timestamp
    }
}
