package com.llawsxx.uvclivestreaming.recording

/** Double while capacity is unknown; bisect a failed probe, then periodically reassess capacity. */
internal class BitrateRecoveryProbe {
    private var base: Int? = null
    private var attempted: Int? = null
    private var rejectedCeiling: Int? = null
    private var probeMeasurement: Int? = null
    private var rejectedMeasurement: Int? = null
    private var rejectedNs = 0L
    val probing: Boolean get() = base != null
    data class Increase(val bitrate: Int, val fine: Boolean)

    /** Call after five consecutive healthy seconds at the requested bitrate. */
    fun increase(nowNs: Long, current: Int, maximum: Int, measuredTarget: Int? = null): Increase? {
        abandon()
        if (current >= maximum) return null
        if (rejectedCeiling != null && (nowNs - rejectedNs >= 30_000_000_000L ||
            measuredTarget?.let { it > (rejectedMeasurement ?: checkNotNull(rejectedCeiling)) * 1.25 } == true)) {
            // New measurements can demonstrate a recovery before the old boundary expires.
            rejectedCeiling = null; rejectedMeasurement = null
        }
        val ceiling = rejectedCeiling
        val candidate = if (ceiling != null) {
            val gap = ceiling - current
            // Stop chasing a boundary within 5% / 50 kbps; allow a new large probe after 30 seconds.
            if (gap <= maxOf(50_000, current / 20)) return null
            current + gap / 2
        } else minOf(current.toLong() * 2, maximum.toLong()).toInt()
        val next = when {
            measuredTarget == null -> candidate
            measuredTarget > current -> minOf(if (ceiling == null) maximum else candidate, measuredTarget)
            // A small/rate-limited payload can underestimate capacity. Refresh the measurement gently.
            else -> minOf(candidate, current + maxOf(50_000, current / 10))
        }.coerceAtMost(maximum)
        base = current; attempted = next; probeMeasurement = measuredTarget
        return Increase(next, ceiling != null || measuredTarget != null && measuredTarget <= current)
    }

    fun reject(nowNs: Long, current: Int, measuredTarget: Int? = null): Int? {
        val previous = base?.takeIf { attempted == current } ?: return null
        rejectedCeiling = minOf(rejectedCeiling ?: current, current)
        rejectedMeasurement = measuredTarget ?: probeMeasurement
        rejectedNs = nowNs
        abandon()
        return previous
    }

    /** A later capacity drop can invalidate an earlier successful point. */
    fun reduced(nowNs: Long, previous: Int) {
        if (rejectedCeiling != null) {
            rejectedCeiling = minOf(checkNotNull(rejectedCeiling), previous)
            rejectedNs = nowNs
        }
        abandon()
    }

    fun abandon() { base = null; attempted = null; probeMeasurement = null }
}
