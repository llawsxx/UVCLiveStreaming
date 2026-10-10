package com.llawsxx.uvclivestreaming.recording

/** Successful payload-to-ACK transfers, excluding connection setup, generation and pacing waits. */
internal class HttpUploadRateMeter {
    private var rate: Long? = null
    private var measuredNs = 0L
    data class Reading(val bitsPerSecond: Long, val ageMs: Long)

    fun record(bytes: Int, transferNs: Long, nowNs: Long) {
        if (bytes < 188 * 32 || transferNs <= 0) return
        val sample = (bytes * 8_000_000_000.0 / transferNs).toLong().coerceIn(8_000, 1_000_000_000)
        val previous = reading(nowNs)?.bitsPerSecond
        rate = if (previous == null || sample < previous) sample else (previous * 0.5 + sample * 0.5).toLong()
        measuredNs = nowNs
    }

    fun reading(nowNs: Long): Reading? {
        val age = (nowNs - measuredNs) / 1_000_000
        return rate?.takeIf { age in 0..10_000 }?.let { Reading(it, age) }
    }
}

/** A block traverses both legs of its store. Sum each usable path's bottleneck, not both totals. */
internal fun httpMeasuredVideoBudget(servers: List<HttpUploadServerStats>, audioBitrate: Int): Int? {
    val usable = servers.filter { it.consecutiveFailures == 0 }
    if (usable.isEmpty()) return null
    var total = 0L
    for (server in usable) {
        val upload = server.uploadBitsPerSecond?.takeIf {
            it > 0 && server.uploadRateAgeMs?.let { age -> age in 0..10_000 } == true
        }
        val download = server.estimatedBitsPerSecond?.takeIf {
            it > 0 && server.feedbackAgeMs?.let { age -> age in 0..10_000 } == true
        }
        total += listOfNotNull(upload, download).minOrNull() ?: return null
    }
    return (total * 0.85 / 1.06 - audioBitrate).toInt().coerceIn(100_000, 100_000_000)
}
