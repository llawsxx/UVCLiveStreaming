package com.llawsxx.uvclivestreaming.recording

/** Wall-clock goodput while this path has work, including scheduling, polling and retry gaps. */
internal class HttpThroughputMeter {
    private data class Point(val ns: Long, val bytes: Long)
    private val points = ArrayDeque<Point>()
    private var bytes = 0L
    private var result: HttpThroughput? = null
    private var measuredNs = 0L

    fun demand(nowNs: Long, available: Boolean) {
        if (!available) { points.clear(); bytes = 0; return }
        if (points.isEmpty()) points.addLast(Point(nowNs, 0))
    }

    fun record(size: Int, nowNs: Long) {
        if (points.isEmpty() || size < 188 * 32) return
        bytes += size
        points.addLast(Point(nowNs, bytes))
        fun rate(seconds: Int): Long? = points.lastOrNull { it.ns <= nowNs - seconds * 1_000_000_000L }?.let {
            ((bytes - it.bytes) * 8_000_000_000.0 / (nowNs - it.ns)).toLong().coerceIn(8_000, 1_000_000_000)
        }
        val short = rate(5)
        val long = rate(30)
        if (short != null) { result = HttpThroughput(short, long, 0); measuredNs = nowNs }
        while (points.size > 1 && points.elementAt(1).ns <= nowNs - 30_000_000_000L) points.removeFirst()
    }

    fun reading(nowNs: Long): HttpThroughput? {
        val age = (nowNs - measuredNs) / 1_000_000
        return result?.takeIf { age in 0..10_000 }?.copy(ageMs = age)
    }
}

/** A forwarded download sample retains the age of the actual completed transfer. */
internal class HttpDownloadRateMeter {
    data class Reading(val bitsPerSecond: Long, val ageMs: Long, val throughput: HttpThroughput?)
    private var result: Reading? = null
    private var measuredNs = 0L

    fun record(headers: Map<String, String>, nowNs: Long) {
        if (headers["x-relay-mode"] != "store") return
        val rate = headers["x-download-rate-bps"]?.toLongOrNull()?.takeIf { it in 1_000..125_000_000 }
        val age = headers["x-download-rate-age-ms"]?.toLongOrNull() ?: if ("x-download-rate-age-ms" !in headers) 0 else -1
        if (rate == null || age !in 0..10_000) { result = null; return }
        val originalNs = nowNs - age * 1_000_000
        if (result != null && originalNs < measuredNs) return
        fun window(name: String) = headers[name]?.toLongOrNull()?.takeIf { it in 1_000..125_000_000 }?.times(8)
        val short = window("x-download-short-rate-bps")
        val long = window("x-download-long-rate-bps")
        val windowAge = headers["x-download-window-age-ms"]?.toLongOrNull()
        val throughput = if (windowAge != null && windowAge in age..10_000 && (short != null || long != null))
            HttpThroughput(short, long, windowAge) else null
        measuredNs = originalNs
        result = Reading(rate * 8, age, throughput)
    }

    fun reading(nowNs: Long): Reading? {
        val age = (nowNs - measuredNs) / 1_000_000
        return result?.takeIf { age in 0..10_000 }?.let {
            val throughput = it.throughput?.let { window ->
                window.copy(ageMs = window.ageMs + age - it.ageMs).takeIf { current -> current.ageMs in 0..10_000 }
            }
            it.copy(ageMs = age, throughput = throughput)
        }
    }
}
