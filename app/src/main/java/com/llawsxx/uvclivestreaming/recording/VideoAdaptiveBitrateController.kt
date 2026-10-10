package com.llawsxx.uvclivestreaming.recording

/** One encoder feeds every output. A reconnect keeps its target; replacing an output resets it. */
internal class VideoAdaptiveBitrateController(private val config: RecordingConfig, private val audioBitrate: Int) {
    private var httpSession: String? = null
    private var rtmpSession: String? = null
    private var http: HttpAdaptiveBitrateController? = null
    private var rtmp: RtmpAdaptiveBitrateController? = null
    val maximum = config.videoBitrate.coerceIn(100_000, 100_000_000)
    var target = maximum
        private set
    var httpStats: VideoAutoBitrateStats? = null
        private set
    var rtmpStats: VideoAutoBitrateStats? = null
        private set

    fun sample(nowNs: Long, httpUpload: HttpUploadStats?, rtmpUpload: RtmpUploadStats?) {
        val nextHttp = httpUpload.takeIf { config.httpUploadEnabled && config.httpAutoBitrateEnabled }
        val nextRtmp = rtmpUpload.takeIf { config.rtmpAutoBitrateEnabled }
        if (httpSession != nextHttp?.sessionId) {
            httpSession = nextHttp?.sessionId
            http = nextHttp?.let { HttpAdaptiveBitrateController(maximum, config.httpMinVideoBitrate, audioBitrate) }
        }
        if (rtmpSession != nextRtmp?.sessionId) {
            rtmpSession = nextRtmp?.sessionId
            rtmp = nextRtmp?.let { RtmpAdaptiveBitrateController(maximum, config.rtmpMinVideoBitrate) }
        }
        nextHttp?.let { http?.sample(nowNs, it) }
        nextRtmp?.let { rtmp?.sample(nowNs, it) }
        target = minOf(http?.target ?: maximum, rtmp?.target ?: maximum)
        httpStats = http?.let { VideoAutoBitrateStats(target, it.minimum, maximum, it.adjustments, it.status, it.target) }
        rtmpStats = rtmp?.let { VideoAutoBitrateStats(target, it.minimum, maximum, it.adjustments, it.status, it.target) }
    }
}
