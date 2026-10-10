package com.llawsxx.uvclivestreaming.recording

/** One-second, monotonic samples. Egress measurements guide a reduction only after congestion;
 * achieved throughput alone is not a capacity limit (otherwise recovery would lock itself out). */
internal class HttpAdaptiveBitrateController(maximum: Int, minimum: Int, private val audioBitrate: Int) {
    val maximum = maximum.coerceIn(100_000, 100_000_000)
    val minimum = minimum.coerceIn(100_000, this.maximum)
    var target = this.maximum
        private set
    var adjustments = 0L
        private set
    var status = "评估网络中"
        private set
    data class Adjustment(val bitrate: Int, val reason: String)
    private var startedNs: Long? = null
    private var lastNs: Long? = null
    private var lastChangeNs = Long.MIN_VALUE
    private var lastAckNs = 0L
    private var acknowledged = 0L
    private var dropped = 0L
    private var lastDropNs: Long? = null
    private var rescueCount = 0L
    private val rescues = ArrayDeque<Pair<Long, Long>>()
    private var queueSeconds = 0.0
    private var pressureSince: Long? = null
    private var healthySince: Long? = null

    fun sample(nowNs: Long, upload: HttpUploadStats): Adjustment? {
        val previousNs = lastNs
        if (previousNs != null && nowNs - previousNs < 900_000_000L) return null
        val pending = upload.unacknowledgedDurationUs / 1_000_000.0
        if (previousNs == null) {
            startedNs = nowNs; lastNs = nowNs; lastAckNs = nowNs
            acknowledged = upload.acknowledgedBytes; dropped = upload.originalDroppedBlocks
            rescueCount = upload.slowDownloadRescues; queueSeconds = pending
            return null
        }
        val seconds = (nowNs - previousNs) / 1_000_000_000.0
        lastNs = nowNs
        if (seconds > 3.0) { pressureSince = null; healthySince = null; queueSeconds = pending }
        val previousQueue = queueSeconds
        queueSeconds = queueSeconds * 0.5 + pending * 0.5
        val growth = (queueSeconds - previousQueue) / seconds
        if (upload.acknowledgedBytes > acknowledged) lastAckNs = nowNs
        acknowledged = upload.acknowledgedBytes
        if (upload.originalDroppedBlocks > dropped) lastDropNs = nowNs
        dropped = upload.originalDroppedBlocks
        if (upload.slowDownloadRescues > rescueCount) rescues.addLast(nowNs to (upload.slowDownloadRescues - rescueCount))
        rescueCount = upload.slowDownloadRescues
        while (rescues.isNotEmpty() && nowNs - rescues.first().first > 20_000_000_000L) rescues.removeFirst()
        val recentRescue = rescues.lastOrNull()?.let { nowNs - it.first <= 5_000_000_000L } == true
        val repeatedRescue = recentRescue && rescues.sumOf { it.second } >= 2
        val reason = when {
            lastDropNs?.let { nowNs - it <= 5_000_000_000L } == true -> "上传缓存发生淘汰"
            pending >= 2.0 && nowNs - lastAckNs >= 3_000_000_000L -> "上传确认停滞"
            pending >= 3.0 && growth > 0.1 -> "待上传分段持续增加"
            pending >= 6.0 && growth >= -0.1 -> "上传积压未消退"
            repeatedRescue -> "接收端连续请求慢块补传"
            else -> null
        }
        if (reason != null) {
            healthySince = null
            if (pressureSince == null) pressureSince = nowNs
            status = if (target == minimum) "已达最低码率，网络仍拥堵" else "检测到拥堵：$reason"
            if (nowNs - checkNotNull(startedNs) >= 5_000_000_000L &&
                nowNs - checkNotNull(pressureSince) >= 3_000_000_000L && canChange(nowNs)) {
                val rates = upload.servers.filter { it.consecutiveFailures == 0 }
                val measuredBudget = if (rates.isNotEmpty() && rates.all {
                    it.estimatedBitsPerSecond != null && it.feedbackAgeMs?.let { age -> age in 0..10_000 } == true
                }) (rates.sumOf { checkNotNull(it.estimatedBitsPerSecond) } * 0.85 / 1.06 - audioBitrate).toInt() else null
                // Reserve audio/TS/retransmission headroom; limit each reduction to 25–50%.
                val next = minOf((target * 0.75).toInt(), measuredBudget ?: target)
                    .coerceAtLeast((target * 0.5).toInt()).coerceIn(minimum, maximum)
                if (next < target) return change(nowNs, next, "降低码率：$reason")
            }
            return null
        }
        pressureSince = null
        val healthy = pending <= 1.5 && nowNs - lastAckNs <= 2_500_000_000L &&
            !recentRescue && (lastDropNs?.let { nowNs - it > 15_000_000_000L } ?: true) &&
            (upload.servers.isEmpty() || upload.servers.any { it.consecutiveFailures == 0 })
        if (healthy) {
            if (healthySince == null) healthySince = nowNs
            status = if (target == maximum) "网络稳定，使用最高码率" else "网络稳定，等待逐步恢复"
            if (nowNs - checkNotNull(healthySince) >= 20_000_000_000L && canChange(nowNs) && target < maximum)
                return change(nowNs, (target * 1.1).toInt().coerceAtMost(maximum), "网络稳定，试探提高码率")
        } else {
            healthySince = null
            status = "保持码率，等待积压消退"
        }
        return null
    }

    private fun canChange(nowNs: Long) = lastChangeNs == Long.MIN_VALUE || nowNs - lastChangeNs >= 5_000_000_000L
    private fun change(nowNs: Long, bitrate: Int, reason: String): Adjustment {
        target = bitrate; adjustments++; lastChangeNs = nowNs
        pressureSince = null; healthySince = null; status = reason
        return Adjustment(bitrate, reason)
    }
}
