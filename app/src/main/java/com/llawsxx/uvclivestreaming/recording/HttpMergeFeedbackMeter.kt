package com.llawsxx.uvclivestreaming.recording

/** Preserve the original feedback age when a store forwards a cached snapshot in a fresh ACK. */
internal class HttpMergeFeedbackMeter {
    private var feedback: HttpMergeFeedback? = null
    private var measuredNs = 0L

    fun record(headers: Map<String, String>, nowNs: Long) {
        if (headers["x-relay-mode"] != "store") return
        val session = headers["x-merge-session"]?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return
        val pending = headers["x-merge-pending-us"]?.toLongOrNull()?.takeIf { it in 0..3_600_000_000L } ?: return
        val wait = headers["x-merge-wait-ms"]?.toLongOrNull()?.takeIf { it in 0..3_600_000L } ?: return
        val age = headers["x-merge-feedback-age-ms"]?.toLongOrNull()?.takeIf { it in 0..10_000L } ?: return
        val originalNs = nowNs - age * 1_000_000
        if (feedback != null && originalNs < measuredNs) return
        measuredNs = originalNs
        feedback = HttpMergeFeedback(session, pending, wait, age)
    }

    fun reading(nowNs: Long): HttpMergeFeedback? {
        val age = (nowNs - measuredNs) / 1_000_000
        return feedback?.takeIf { age in 0..10_000 }?.copy(ageMs = age)
    }
}
