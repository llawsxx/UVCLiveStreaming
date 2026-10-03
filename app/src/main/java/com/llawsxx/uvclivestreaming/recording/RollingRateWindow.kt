package com.llawsxx.uvclivestreaming.recording

internal sealed class TargetFramePtsResult {
    data class Accepted(val timestampNs: Long) : TargetFramePtsResult()
    data object Dropped : TargetFramePtsResult()
}

internal class TargetFramePtsAligner(private val fps: Double, private val maxDeltaFrames: Double) {
    private var originNs = Long.MIN_VALUE
    private var acceptedFrame = -1L
    fun align(sensorTimestampNs: Long): TargetFramePtsResult {
        if (fps <= 0.0) return TargetFramePtsResult.Accepted(sensorTimestampNs)
        if (originNs == Long.MIN_VALUE) {
            originNs = sensorTimestampNs; acceptedFrame = 0
            return TargetFramePtsResult.Accepted(sensorTimestampNs)
        }
        val target = ((sensorTimestampNs - originNs) * fps / 1_000_000_000.0).roundToLong()
        if (target <= acceptedFrame) return TargetFramePtsResult.Dropped
        if (target - acceptedFrame > maxDeltaFrames + 1.0) return TargetFramePtsResult.Dropped
        acceptedFrame = target
        return TargetFramePtsResult.Accepted(originNs + (target * 1_000_000_000.0 / fps).roundToLong())
    }
    private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
}
