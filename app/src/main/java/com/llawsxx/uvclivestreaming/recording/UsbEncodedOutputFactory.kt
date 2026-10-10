package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Creates independent containers/transports for an existing USB encoder session. */
internal class UsbEncodedOutputFactory(
    private val config: RecordingConfig,
    private val store: RecordingOutputStore,
    private val hasAudio: Boolean,
    private val audioRate: Int,
    private val audioChannels: Int,
    private val onNotice: (String) -> Unit,
    private val onRequestKeyFrame: () -> Unit,
) {
    fun recording(container: ContainerFormat): EncodedOutput<MediaFormat> {
        val baseName = "USB_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}"
        if (container == ContainerFormat.MPEG_TS) return ts(baseName, record = true)
        val handle = store.create("${baseName}_001.mp4", "video/mp4")
        return try {
            MuxOutput(MediaMuxCoordinator(
                MediaMuxer(handle.descriptor().fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
                hasAudio,
            ) {}, handle = handle)
        } catch (error: Throwable) { handle.discard(); throw error }
    }

    fun http(): EncodedOutput<MediaFormat> = ts("HTTP", record = false)

    private fun ts(baseName: String, record: Boolean): EncodedOutput<MediaFormat> {
        val upload = if (!record && config.httpUploadEnabled) HttpTsUploadSink(
            config.httpUploadUrl,
            config.httpUploadCacheSeconds, onNotice,
        ) else null
        val server = if (record || upload != null) null else HttpTsRingBufferServer(
            port = config.httpStreamPort,
            maxBytes = ((config.videoBitrate.toLong() + if (hasAudio) config.audioBitrate else 0) / 8L) *
                config.httpBufferSeconds.coerceIn(1, 300),
        )
        val output = NativeTsOutput(if (record) store else null, baseName, true,
            if (record) config.segmentMinutes.coerceAtLeast(0) * 60_000L else 0L,
            httpServer = server, writeToFile = record, uploadSink = upload, onSegment = { _, _ -> })
        var muxer: NativeMpegTsMuxer? = null
        try {
            output.start()
            muxer = NativeMpegTsMuxer(config.videoCodec, hasAudio, audioRate, audioChannels)
            if (upload != null) onNotice("HTTP 分块上传：${config.httpUploadUrl}；服务端按配置延迟输出")
            return MuxOutput(NativeTsMuxCoordinator(muxer, output, hasAudio) {}, ts = output,
                recording = record)
        } catch (error: Throwable) {
            runCatching { muxer?.close() }; runCatching { output.close(publish = false) }
            throw error
        }
    }

    fun rtmp(url: String, bufferMs: Int = config.rtmpBufferMs,
             sendTimeoutSeconds: Int = config.rtmpSendTimeoutSeconds): EncodedOutput<MediaFormat> {
        require(url.startsWith("rtmp://", ignoreCase = true)) { "RTMP 地址必须以 rtmp:// 开头" }
        val streamConfig = config.copy(
            rtmpEnabled = true, rtmpUrl = url,
            rtmpBufferMs = bufferMs.coerceIn(100, 30_000),
            rtmpSendTimeoutSeconds = sendTimeoutSeconds.coerceIn(3, 30),
            mode = if (hasAudio) RecordingMode.AUDIO_VIDEO else RecordingMode.VIDEO,
        )
        return RtmpOutput(RtmpStreamSink(streamConfig, onNotice, onRequestKeyFrame).also { it.start() })
    }

    private class MuxOutput(
        private val mux: EncodedMuxCoordinator,
        private val handle: OutputHandle? = null,
        private val ts: NativeTsOutput? = null,
        private val recording: Boolean = true,
    ) : EncodedOutput<MediaFormat> {
        private var wroteSamples = false
        override val path: String? get() = if (recording) handle?.displayPath ?: ts?.currentPath else null
        override val segment: Int get() = if (recording) ts?.currentSegment ?: 1 else 0
        override val bytesStreamed: Long get() = ts?.bytesUploaded ?: ts?.bytesStreamed?.get() ?: 0L
        override val httpUploadStats: HttpUploadStats? get() = ts?.httpUploadStats
        override fun setVideoFormat(format: MediaFormat) = mux.setVideoFormat(format)
        override fun setAudioFormat(format: MediaFormat) = mux.setAudioFormat(format)
        override fun write(sample: EncodedSample) {
            val info = MediaCodec.BufferInfo().apply { set(0, sample.data.size, sample.ptsUs, sample.flags) }
            val buffer = ByteBuffer.wrap(sample.data)
            if (sample.video) mux.writeVideo(buffer, info) else mux.writeAudio(buffer, info)
            wroteSamples = true
        }
        override fun close() {
            try { mux.finish() } finally {
                ts?.close(publish = wroteSamples)
                if (wroteSamples) handle?.closeAndPublish() else handle?.discard()
            }
        }
    }

    private class RtmpOutput(private val sink: RtmpStreamSink) : EncodedOutput<MediaFormat> {
        override val bytesStreamed: Long get() = sink.bytesSent
        override val reconnectCount: Long get() = sink.reconnectCount
        override fun setVideoFormat(format: MediaFormat) = sink.setVideoFormat(format)
        override fun setAudioFormat(format: MediaFormat) = sink.setAudioFormat(format)
        override fun write(sample: EncodedSample) {
            if (sample.video) sink.writeVideo(sample.data, sample.ptsUs, sample.keyFrame)
            else sink.writeAudio(sample.data, sample.ptsUs)
        }
        override fun close() = sink.close()
    }
}
