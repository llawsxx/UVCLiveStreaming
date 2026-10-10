package com.llawsxx.uvclivestreaming.recording

/** Uses queue pressure and socket progress; successful writes are not server acknowledgements. */
internal class RtmpAdaptiveBitrateController(maximum: Int, minimum: Int) {
    val maximum = maximum.coerceIn(100_000, 100_000_000)
    val minimum = minimum.coerceIn(100_000, this.maximum)
    var target = this.maximum
        private set
    var adjustments = 0L
        private set
    var status = "评估 RTMP 网络中"
        private set
    private var startedNs: Long? = null
    private var lastNs: Long? = null
    private var lastChangeNs: Long? = null
    private var lastProgressNs = 0L
    private var sentBytes = 0L
    private var droppedPackets = 0L
    private var lastDropNs: Long? = null
    private var pendingBytes = 0L
    private var pressureSince: Long? = null
    private var healthySince: Long? = null

    fun sample(nowNs: Long, upload: RtmpUploadStats) {
        val previousNs = lastNs
        if (previousNs != null && nowNs - previousNs < 900_000_000L) return
        lastNs = nowNs
        if (previousNs == null) {
            startedNs = nowNs; lastProgressNs = nowNs
            sentBytes = upload.sentMediaBytes; droppedPackets = upload.droppedPackets
            pendingBytes = upload.pendingBytes
            return
        }
        if (nowNs - previousNs > 3_000_000_000L) { pressureSince = null; healthySince = null }
        val growing = upload.pendingBytes > pendingBytes
        pendingBytes = upload.pendingBytes
        if (upload.sentMediaBytes > sentBytes) lastProgressNs = nowNs
        sentBytes = upload.sentMediaBytes
        if (upload.droppedPackets > droppedPackets) lastDropNs = nowNs
        droppedPackets = upload.droppedPackets
        val reason = when {
            !upload.connected && upload.connectionFailures > 0 -> "RTMP 连接中断"
            lastDropNs?.let { nowNs - it <= 5_000_000_000L } == true -> "RTMP 缓存溢出丢帧"
            upload.inFlightAgeMs >= 3_000 -> "RTMP 发送阻塞"
            upload.pendingBytes > 0 && nowNs - lastProgressNs >= 3_000_000_000L -> "RTMP 发送停滞"
            upload.oldestPendingAgeMs >= 2_000 && growing -> "RTMP 积压持续增加"
            upload.queuedBytes >= upload.queueLimitBytes * 0.75 && growing -> "RTMP 缓存接近上限"
            else -> null
        }
        if (reason != null) {
            healthySince = null
            if (pressureSince == null) pressureSince = nowNs
            status = if (target == minimum) "已达最低码率，RTMP 网络仍拥堵" else "检测到拥堵：$reason"
            if (nowNs - checkNotNull(startedNs) >= 5_000_000_000L &&
                nowNs - checkNotNull(pressureSince) >= 3_000_000_000L && canChange(nowNs)) {
                val next = (target * 0.75).toInt().coerceAtLeast(minimum)
                if (next < target) change(nowNs, next, "降低码率：$reason")
            }
            return
        }
        pressureSince = null
        val healthy = upload.connected && upload.oldestPendingAgeMs <= 500 &&
            upload.queuedBytes < upload.queueLimitBytes * 0.5 &&
            nowNs - lastProgressNs <= 2_500_000_000L &&
            (lastDropNs?.let { nowNs - it > 15_000_000_000L } ?: true)
        if (healthy) {
            if (healthySince == null) healthySince = nowNs
            status = if (target == maximum) "RTMP 网络稳定，使用最高码率" else "RTMP 网络稳定，等待逐步恢复"
            if (nowNs - checkNotNull(healthySince) >= 20_000_000_000L && canChange(nowNs) && target < maximum)
                change(nowNs, (target * 1.1).toInt().coerceAtMost(maximum), "RTMP 网络稳定，试探提高码率")
        } else {
            healthySince = null
            status = "保持码率，等待 RTMP 发送恢复"
        }
    }

    private fun canChange(nowNs: Long) = lastChangeNs?.let { nowNs - it >= 5_000_000_000L } ?: true
    private fun change(nowNs: Long, bitrate: Int, reason: String) {
        target = bitrate; adjustments++; lastChangeNs = nowNs
        pressureSince = null; healthySince = null; status = reason
    }
}
