package com.llawsxx.uvclivestreaming.recording

/** Wall-clock preview pacing; capture timestamps and encoder submission are independent. */
internal class PreviewFrameLimiter {
    private var limited = false
    private var nextFrameNs = 0L

    fun shouldRender(nowNs: Long, lowFrameRate: Boolean): Boolean {
        if (!lowFrameRate) {
            limited = false
            return true
        }
        if (!limited) {
            limited = true
            nextFrameNs = nowNs
        }
        if (nowNs < nextFrameNs) return false
        // Keep the 5 Hz schedule despite capture jitter and skip missed deadlines.
        nextFrameNs += ((nowNs - nextFrameNs) / INTERVAL_NS + 1) * INTERVAL_NS
        return true
    }

    fun reset() { limited = false }

    private companion object { const val INTERVAL_NS = 200_000_000L }
}
