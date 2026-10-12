package com.llawsxx.uvclivestreaming.recording

/** Compare media backlog across a parallel chunk cycle, including its recent direction. */
internal class HttpPipelineQueueTrend {
    data class Reading(val growing: Boolean, val stable: Boolean)
    private val samples = ArrayDeque<Pair<Long, Double>>()
    fun clear() = samples.clear()

    fun sample(nowNs: Long, pendingSeconds: Double, paths: Int): Reading {
        val windowNs = maxOf(5, paths) * 1_000_000_000L
        samples.addLast(nowNs to pendingSeconds)
        while (samples.size > 1 && samples.elementAt(1).first <= nowNs - windowNs) samples.removeFirst()
        val first = samples.first()
        val ready = nowNs - first.first >= windowNs
        val growth = if (ready) (pendingSeconds - first.second) / ((nowNs - first.first) / 1_000_000_000.0) else 0.0
        // A filled startup pipeline must not inherit growth from its initial ramp.
        val recent = samples.lastOrNull { nowNs - it.first >= 4_000_000_000L }
        val recentGrowth = recent?.let { (pendingSeconds - it.second) / ((nowNs - it.first) / 1_000_000_000.0) } ?: 0.0
        return Reading(ready && growth > 0.1 && recentGrowth > 0.1,
            ready && kotlin.math.abs(growth) <= 0.1 && kotlin.math.abs(recentGrowth) <= 0.1)
    }
}
