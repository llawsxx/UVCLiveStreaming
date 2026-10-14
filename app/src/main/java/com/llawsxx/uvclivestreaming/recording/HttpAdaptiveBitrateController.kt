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
    var measuredVideoBudget: Int? = null
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
    private val uploadQueueTrend = HttpPipelineQueueTrend()
    private val mergeQueueTrend = HttpPipelineQueueTrend()
    private var pressureSince: Long? = null
    private var healthySince: Long? = null
    private val recovery = BitrateRecoveryProbe()

    fun sample(nowNs: Long, upload: HttpUploadStats, effectiveBitrate: Int = target): Adjustment? {
        val previousNs = lastNs
        if (previousNs != null && nowNs - previousNs < 900_000_000L) return null
        val pending = upload.unacknowledgedDurationUs / 1_000_000.0
        if (previousNs == null) {
            startedNs = nowNs; lastNs = nowNs; lastAckNs = nowNs
            acknowledged = upload.acknowledgedBytes; dropped = upload.originalDroppedBlocks
            rescueCount = upload.slowDownloadRescues
            return null
        }
        val seconds = (nowNs - previousNs) / 1_000_000_000.0
        lastNs = nowNs
        if (seconds > 3.0) {
            pressureSince = null; healthySince = null
            uploadQueueTrend.clear(); mergeQueueTrend.clear(); recovery.abandon()
        }
        val uploadPaths = upload.servers.count { it.consecutiveFailures == 0 }.coerceAtLeast(1)
        val uploadTrend = uploadQueueTrend.sample(nowNs, pending, uploadPaths)
        if (upload.acknowledgedBytes > acknowledged) lastAckNs = nowNs
        acknowledged = upload.acknowledgedBytes
        if (upload.originalDroppedBlocks > dropped) lastDropNs = nowNs
        dropped = upload.originalDroppedBlocks
        if (upload.slowDownloadRescues > rescueCount) rescues.addLast(nowNs to (upload.slowDownloadRescues - rescueCount))
        rescueCount = upload.slowDownloadRescues
        while (rescues.isNotEmpty() && nowNs - rescues.first().first > 20_000_000_000L) rescues.removeFirst()
        val recentRescue = rescues.lastOrNull()?.let { nowNs - it.first <= 5_000_000_000L } == true
        val repeatedRescue = recentRescue && rescues.sumOf { it.second } >= 2
        val measuredBudget = httpMeasuredVideoBudget(upload.servers, audioBitrate)
        measuredVideoBudget = measuredBudget
        // Reports share a media timeline; never sum the same merge backlog across stores.
        val reports = upload.servers.mapNotNull { it.mergeFeedback }.filter {
            it.sessionId == upload.sessionId && it.ageMs in 0..10_000
        }
        val newestAge = reports.minOfOrNull { it.ageMs }
        val currentReports = reports.filter { newestAge != null && it.ageMs <= newestAge + 1_000 }
        val mergePending = currentReports.maxOfOrNull { it.pendingDurationUs / 1_000_000.0 }
        val mergeWait = currentReports.maxOfOrNull { it.headWaitMs } ?: 0
        // A parallel download pipeline can retain many seconds at steady throughput.
        // Observe at least one one-second-chunk cycle across the reporting paths.
        val mergePaths = currentReports.size.coerceAtLeast(1)
        val mergeTrend = mergePending?.let { mergeQueueTrend.sample(nowNs, it, mergePaths) }
            ?: run { mergeQueueTrend.clear(); null }
        val mergeGrowing = mergeTrend?.growing == true
        val mergeStalled = mergePending != null && mergePending >= 2.0 &&
            mergeWait >= maxOf(3_000L, (mergePaths + 2) * 1_000L)
        val mergeHealthy = mergePending == null ||
            mergeWait <= maxOf(1_500L, mergePaths * 1_000L) &&
            (mergePending <= 1.5 || mergeTrend?.stable == true)
        val reason = when {
            lastDropNs?.let { nowNs - it <= 5_000_000_000L } == true -> "上传缓存发生淘汰"
            pending >= 2.0 && nowNs - lastAckNs >= maxOf(3_000_000_000L, (uploadPaths + 2) * 1_000_000_000L) -> "上传确认停滞"
            recovery.probing && pending >= 2.0 && uploadTrend.growing -> "试探提升后分段排队"
            recovery.probing && mergePending != null && mergePending >= 2.0 && mergeGrowing -> "试探提升后 merge 下载积压"
            mergeStalled -> "merge 连续分段交付停滞"
            mergePending != null && mergePending >= 3.0 && mergeGrowing -> "store → merge 下载积压持续增加"
            measuredBudget != null && target > measuredBudget * 1.1 && pending >= 2.0 && uploadTrend.growing -> "测速低于当前码率，分段持续排队"
            pending >= 3.0 && uploadTrend.growing -> "待上传分段持续增加"
            repeatedRescue && (mergePending == null || mergeStalled || mergeGrowing) -> "接收端连续请求慢块补传"
            else -> null
        }
        if (reason != null) {
            healthySince = null
            if (pressureSince == null) pressureSince = nowNs
            status = if (target == minimum) "已达最低码率，网络仍拥堵" else "检测到拥堵：$reason"
            if (recovery.probing && nowNs - checkNotNull(pressureSince) >= 2_000_000_000L &&
                canChange(nowNs, 2_000_000_000L)) {
                recovery.reject(nowNs, target, measuredBudget)?.let {
                    return change(nowNs, minOf(it, measuredBudget ?: it).coerceIn(minimum, maximum), "试探出现排队，按测速回退后细调：$reason")
                }
            }
            if (nowNs - checkNotNull(startedNs) >= 5_000_000_000L &&
                nowNs - checkNotNull(pressureSince) >= 3_000_000_000L && canChange(nowNs)) {
                // Reserve audio/TS/retransmission headroom; limit each reduction to 25–50%.
                val next = minOf((target * 0.75).toInt(), measuredBudget ?: target)
                    .coerceAtLeast((target * 0.5).toInt()).coerceIn(minimum, maximum)
                if (next < target) {
                    recovery.reduced(nowNs, target)
                    return change(nowNs, next, "降低码率：$reason")
                }
            }
            return null
        }
        pressureSince = null
        val healthy = (pending <= 1.5 || uploadTrend.stable) &&
            nowNs - lastAckNs <= maxOf(2_500_000_000L, uploadPaths * 1_000_000_000L) &&
            mergeHealthy &&
            (!recentRescue || mergePending != null && mergeHealthy) &&
            (lastDropNs?.let { nowNs - it > 15_000_000_000L } ?: true) &&
            (upload.servers.isEmpty() || upload.servers.any { it.consecutiveFailures == 0 })
        if (healthy && effectiveBitrate >= target) {
            if (healthySince == null) healthySince = nowNs
            status = if (target == maximum) "网络稳定，使用最高码率" else "网络稳定，准备快速试探"
            if (nowNs - checkNotNull(healthySince) >= 5_000_000_000L && canChange(nowNs)) {
                val recoveryBudget = if (httpHasLongThroughput(upload.servers)) measuredBudget else
                    measuredBudget?.coerceAtMost(minOf(target.toLong() * 2, maximum.toLong()).toInt())
                recovery.increase(nowNs, target, maximum, recoveryBudget)?.let {
                    return change(nowNs, it.bitrate, if (it.fine) "网络稳定，结合测速细调码率"
                        else if (measuredBudget != null) "网络稳定，按上传与接收测速提高码率" else "测速尚不完整，大幅试探提高码率")
                }
                if (target < maximum) status = "接近已评估带宽，保持码率等待重新试探"
            }
        } else {
            healthySince = null
            status = if (healthy) "共享编码器受另一推流限制，等待实际码率提高" else "保持码率，等待积压消退"
        }
        return null
    }

    private fun canChange(nowNs: Long, intervalNs: Long = 5_000_000_000L) =
        lastChangeNs == Long.MIN_VALUE || nowNs - lastChangeNs >= intervalNs
    private fun change(nowNs: Long, bitrate: Int, reason: String): Adjustment {
        target = bitrate; adjustments++; lastChangeNs = nowNs
        pressureSince = null; healthySince = null; status = reason
        return Adjustment(bitrate, reason)
    }
}
