package com.llawsxx.uvclivestreaming.recording

/** Samples the native cumulative video endpoint counter using host monotonic time. */
internal class UsbReceiveRate {
    private var previousBytes: Long? = null
    private var previousNs = 0L

    fun sample(totalBytes: Long, nowNs: Long = System.nanoTime()): Double? {
        val bytes = previousBytes
        if (bytes != null && nowNs <= previousNs) return null
        val rate = if (bytes != null && totalBytes >= bytes)
            (totalBytes - bytes) * 8_000_000_000.0 / (nowNs - previousNs) else null
        previousBytes = totalBytes
        previousNs = nowNs
        return rate
    }
}
