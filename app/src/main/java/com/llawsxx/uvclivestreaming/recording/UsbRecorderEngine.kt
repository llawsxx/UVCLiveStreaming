package com.llawsxx.uvclivestreaming.recording

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Captures video and PCM directly from the USB Host fd, without Camera2 or AudioRecord. */
@RequiresApi(Build.VERSION_CODES.O)
class UsbRecorderEngine(
    private val context: Context,
    private val config: RecordingConfig,
    private val outputStore: RecordingOutputStore,
    private val onStarted: (RecordingStats) -> Unit,
    private val onStats: (RecordingStats) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onOutputsEmpty: () -> Unit,
) : RecorderEngine, UsbCaptureCallback {
    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val setupDone = CountDownLatch(1)
    private val releaseDone = CountDownLatch(1)
    private data class VideoFrame(val bytes: ByteArray, val format: Int, val width: Int, val height: Int, val timestampNs: Long)
    private val videoQueue = ArrayBlockingQueue<VideoFrame>(config.usbVideoBufferFrames.coerceIn(1, 30))
    private val rawVideoConverter = RawVideoConverter()
    private val mjpegDecodePool = MjpegDecodePool(
        decoder = { bytes, format, width, height, destination ->
            format == 1 && NativeUsbCapture.nativeDecodeMjpegToI420(bytes, width, height, destination)
        },
        capacity = config.usbVideoBufferFrames.coerceIn(1, 30),
    )
    private val frameCount = AtomicLong()
    private val receivedVideoFrames = AtomicLong()
    private val renderedVideoFrames = AtomicLong()
    private val bytesWritten = AtomicLong()
    private val lastAudioLevelNs = AtomicLong()
    private val statsStarted = AtomicBoolean(false)
    @Volatile private var audioLevelDb = -60f
    private val audioQueue = ArrayBlockingQueue<Pair<ByteArray, Long>>(128)
    @Volatile private var preview: Surface? = null
    @Volatile private var previewEnabled = false
    @Volatile private var nativeHandle = 0L
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private val outputs = EncodedOutputRouter<MediaFormat>(::onOutputFailure)
    private val vuiRewriter = if (config.spsVuiRewriteEnabled) H26xVuiRewriter(config) else null
    @Volatile private var outputFactory: UsbEncodedOutputFactory? = null
    private val outputChanges = AtomicInteger()
    private val outputCommands = Executors.newSingleThreadExecutor { action ->
        Thread(action, "usb-output-control")
    }
    @Volatile private var videoFormatReady = false
    @Volatile private var audioFormatReady = false
    @Volatile private var streamRate = 0.0
    @Volatile private var recentFps: Double? = null
    private var videoThread: Thread? = null
    private var videoRenderThread: Thread? = null
    private var audioThread: Thread? = null
    private var statsThread: Thread? = null
    private var encoderInputSurface: Surface? = null
    private var startedAtNs = 0L
    private var audioChannels = 0
    private var audioRate = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private var audioCaptureEnabled = false
    private val videoTimestampSmoother = if (config.usbTimestampSmoothingEnabled)
        TimestampSmoother(videoSmoothingFrameRate(config.fps, config.usbTimestampSmoothingNtscEnabled),
            config.usbTimestampSmoothingMaxDeltaSeconds) else null
    private var audioTimestampSmoother: TimestampSmoother? = null
    private var audioOutputTimestampSmoother: TimestampSmoother? = null
    private val previewRevision = AtomicLong()

    override fun start(preview: Surface?, previewEnabled: Boolean, previewRotationDegrees: Int) {
        this.preview = preview
        this.previewEnabled = previewEnabled
        RecorderController.updateUsbAudioLevel(-60f)
        running.set(true)
        Thread({
            try {
                prepare()
            } catch (error: Throwable) {
                if (running.get()) onError("USB 摄像头启动失败：${error.message}")
            } finally {
                setupDone.countDown()
                if (!running.get()) releaseResources()
            }
        }, "usb-record-setup").start()
    }

    private fun prepare() {
        val manager = context.getSystemService(UsbManager::class.java)
        val deviceName = config.cameraId.removePrefix(USB_CAMERA_PREFIX)
        val device = requireNotNull(manager.deviceList[deviceName]) { "USB 摄像头已断开" }
        check(manager.hasPermission(device)) { "缺少 USB 摄像头访问权限" }
        usbConnection = requireNotNull(manager.openDevice(device)) { "无法打开 USB 摄像头" }
        nativeHandle = NativeUsbCapture.nativeOpen(
            checkNotNull(usbConnection).fileDescriptor,
            config.width, config.height, config.fps.toInt(), config.usbVideoInputFormat.nativeValue,
            config.hasAudio, config.usbAudioSampleRate,
        )
        check(nativeHandle != 0L) { "无法初始化 USB 摄像头" }
        val format = NativeUsbCapture.nativeFormat(nativeHandle)
        videoWidth = format[0]
        videoHeight = format[1]
        audioRate = format[2]
        audioChannels = format[3]
        audioCaptureEnabled = config.hasAudio && audioRate > 0 && audioChannels in 1..2
        if (audioCaptureEnabled && config.usbTimestampSmoothingEnabled) {
            audioTimestampSmoother = TimestampSmoother(audioRate.toDouble(), config.usbTimestampSmoothingMaxDeltaSeconds)
            audioOutputTimestampSmoother = TimestampSmoother(audioRate.toDouble(), config.usbTimestampSmoothingMaxDeltaSeconds)
        }
        if (videoWidth != config.width || videoHeight != config.height) {
            onNotice("USB 摄像头使用 ${videoWidth}×${videoHeight}，所选分辨率不可用")
        }
        if (config.hasAudio && !audioCaptureEnabled) onNotice("USB 摄像头未提供可用 UAC 麦克风路由，已跳过音频，视频仍可录制")

        val videoMime = if (config.videoCodec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
            else MediaFormat.MIMETYPE_VIDEO_AVC
        val videoFormat = MediaFormat.createVideoFormat(videoMime, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate.coerceAtLeast(100_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps.toInt().coerceIn(1, 240))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.videoKeyFrameIntervalSeconds.coerceIn(1, 30))
            if (config.videoMaxBFrames > 0) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, config.videoMaxBFrames.coerceIn(0, 4))
            }
            config.videoBitrateMode.mediaFormatValue?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
            applyEncoderColorSettings(config)
        }
        Log.i("UsbVideoDiagnostics", "Encoder color request: standard=${config.colorStandard.label} " +
            "transfer=${config.colorTransfer.encoderLabel()} range=${config.colorRange.label}")
        videoCodec = MediaCodec.createEncoderByType(videoMime).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = checkNotNull(videoCodec).createInputSurface()
        encoderInputSurface = inputSurface
        if (audioCaptureEnabled) {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, audioChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
        val factory = UsbEncodedOutputFactory(config, outputStore, audioCaptureEnabled,
            if (audioCaptureEnabled) audioRate else config.audioSampleRate,
            if (audioCaptureEnabled) audioChannels else config.audioChannelCount, onNotice)
        outputFactory = factory
        if (!config.httpServiceOnly) outputs.attach(CaptureOutput.RECORDING, factory.recording(config.container),
            needsAudio = audioCaptureEnabled)
        if (config.httpStreamEnabled) outputs.attach(CaptureOutput.HTTP, factory.http(), needsAudio = audioCaptureEnabled)
        if (config.rtmpEnabled) outputs.attach(CaptureOutput.RTMP, factory.rtmp(config.rtmpUrl),
            needsAudio = audioCaptureEnabled)
        check(outputs.snapshot().isNotEmpty()) { "未选择录像或串流输出" }
        if (!running.get()) return
        startedAtNs = System.nanoTime()
        videoCodec?.start()
        audioCodec?.start()
        startVideoDrain()
        if (audioCaptureEnabled) startAudioDrain()
        mjpegDecodePool.start()
        startVideoRender()
        NativeUsbCapture.nativeStart(nativeHandle, this)
        Thread({
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
            if (running.get() && frameCount.get() == 0L) {
                onError("USB 摄像头连接后 5 秒内没有输出可解码的视频帧")
            }
        }, "usb-video-watchdog").start()
    }

    private fun markStarted() {
        if (!running.get() || !videoFormatReady || (audioCaptureEnabled && !audioFormatReady) ||
            !statsStarted.compareAndSet(false, true)) return
        requestKeyFrame()
        onStarted(captureStats())
        statsThread = Thread({
            var lastStreamBytes = 0L
            var lastStreamNs = System.nanoTime()
            var lastVideoNs = lastStreamNs
            var lastReceived = receivedVideoFrames.get()
            var lastRendered = renderedVideoFrames.get()
            var lastEncoded = frameCount.get()
            while (running.get()) {
                val nowNs = System.nanoTime()
                val streamBytes = outputs.snapshot().values.sumOf { it.bytesStreamed }
                streamRate = if (streamBytes >= lastStreamBytes && nowNs > lastStreamNs)
                    (streamBytes - lastStreamBytes) * 8_000_000_000.0 / (nowNs - lastStreamNs) else 0.0
                lastStreamBytes = streamBytes
                lastStreamNs = nowNs
                if (nowNs - lastVideoNs >= 5_000_000_000L) {
                    val received = receivedVideoFrames.get()
                    val rendered = renderedVideoFrames.get()
                    val encoded = frameCount.get()
                    val seconds = (nowNs - lastVideoNs) / 1_000_000_000.0
                    recentFps = (encoded - lastEncoded) / seconds
                    Log.i("UsbVideoDiagnostics", String.format(Locale.US,
                        "Video fps: received=%.3f rendered=%.3f encoded=%.3f totals=%d/%d/%d MJPEG=%s rawBuffers=%s",
                        (received - lastReceived) / seconds, (rendered - lastRendered) / seconds,
                        (encoded - lastEncoded) / seconds, received, rendered, encoded,
                        mjpegDecodePool.diagnostics(), rawVideoConverter.diagnostics()))
                    lastVideoNs = nowNs
                    lastReceived = received
                    lastRendered = rendered
                    lastEncoded = encoded
                }
                onStats(captureStats())
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
            }
        }, "usb-record-stats").apply { start() }
    }

    override fun onUsbVideoFrame(bytes: ByteArray, format: Int, width: Int, height: Int, timestampNs: Long) {
        if (!running.get()) return
        receivedVideoFrames.incrementAndGet()
        val smoothedTimestampNs = videoTimestampSmoother?.smooth(timestampNs) ?: timestampNs
        if (format == 1) {
            mjpegDecodePool.offer(bytes, format, width, height, smoothedTimestampNs)
            return
        }
        val frame = VideoFrame(bytes, format, width, height, smoothedTimestampNs)
        if (!videoQueue.offer(frame)) {
            videoQueue.poll()
            videoQueue.offer(frame)
        }
    }

    private fun startVideoRender() {
        videoRenderThread = Thread({
            try {
                GpuVideoRenderer(checkNotNull(encoderInputSurface), config.usbYuvMatrix, config.usbSourceRange).use { gpu ->
                    while (running.get()) {
                        val decoded = mjpegDecodePool.poll(5)
                        var converted: RawVideoConverter.ConvertedFrame? = null
                        try {
                            val frame = if (decoded != null) {
                                GpuVideoFrame.fromDecoded(decoded) ?: continue
                            } else {
                                // Wait on the active format only; a 50-ms wait on
                                // an empty raw queue previously made MJPEG burst.
                                val raw = videoQueue.poll(5, TimeUnit.MILLISECONDS) ?: continue
                                converted = rawVideoConverter.convert(raw.bytes, raw.format, raw.width, raw.height, raw.timestampNs)
                                converted?.frame ?: continue
                            }
                            val target = GpuVideoRenderer.PreviewTarget(
                                preview.takeIf { previewEnabled }, previewRevision.get(),
                                RecorderController.previewLowFrameRate,
                            )
                            gpu.render(frame, target)
                            renderedVideoFrames.set(gpu.encodedFrameCount)
                        } finally {
                            converted?.close()
                            decoded?.close()
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Normal session shutdown.
            } catch (error: Throwable) {
                if (running.get()) onError("USB GPU 视频处理失败：${error.message}")
            } finally {
                rawVideoConverter.close()
            }
        }, "usb-video-render").apply { start() }
    }

    override fun onUsbAudioPcm(bytes: ByteArray, timestampNs: Long) {
        if (!running.get() || !audioCaptureEnabled) return
        val sampleFrames = bytes.size / (audioChannels * 2)
        if (sampleFrames == 0) return
        val smoothedTimestampNs = audioTimestampSmoother?.smooth(timestampNs, sampleFrames.toLong()) ?: timestampNs
        if (timestampNs - lastAudioLevelNs.get() >= 100_000_000L) {
            lastAudioLevelNs.set(timestampNs)
            audioLevelDb = usbPcmLevelDb(bytes)
            RecorderController.updateUsbAudioLevel(audioLevelDb)
        }
        if (!audioQueue.offer(bytes to smoothedTimestampNs)) {
            audioQueue.poll()
            audioQueue.offer(bytes to smoothedTimestampNs)
        }
    }

    private fun startVideoDrain() {
        val codec = checkNotNull(videoCodec)
        videoThread = Thread({
            val info = MediaCodec.BufferInfo()
            try {
                var ended = false
                while (!ended) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            Log.i("UsbVideoDiagnostics", "Encoder reported colors: " +
                                listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE)
                                    .joinToString { key -> "$key=${if (format.containsKey(key)) format.getInteger(key) else "unspecified"}" })
                            outputs.setVideoFormat(vuiRewriter?.rewriteFormat(format) ?: format)
                            videoFormatReady = true
                            markStarted()
                        }
                        index >= 0 -> {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val adjusted = MediaCodec.BufferInfo().apply {
                                    set(info.offset, info.size,
                                        (info.presentationTimeUs - startedAtNs / 1_000).coerceAtLeast(0), info.flags)
                                }
                                codec.getOutputBuffer(index)?.let {
                                    val encoded = copyBuffer(it, adjusted)
                                    val rewritten = vuiRewriter?.rewrite(encoded) ?: encoded
                                    outputs.write(EncodedSample(true, rewritten, adjusted.presentationTimeUs,
                                        adjusted.flags, adjusted.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                                }
                                frameCount.incrementAndGet()
                                bytesWritten.addAndGet(info.size.toLong())
                            }
                            ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) onError("USB 视频编码失败：${error.message}")
            }
        }, "usb-video-drain").apply { start() }
    }

    private fun startAudioDrain() {
        val codec = checkNotNull(audioCodec)
        audioThread = Thread({
            val info = MediaCodec.BufferInfo()
            var endedInput = false
            var endedOutput = false
            try {
                while (!endedOutput) {
                    val packet = if (running.get()) audioQueue.poll(10, TimeUnit.MILLISECONDS) else audioQueue.poll()
                    if (packet != null) {
                        var offset = 0
                        val bytes = packet.first
                        while (offset < bytes.size) {
                            val index = codec.dequeueInputBuffer(10_000)
                            if (index < 0) break
                            val buffer = codec.getInputBuffer(index) ?: continue
                            buffer.clear()
                            val length = minOf(buffer.remaining(), bytes.size - offset) / (audioChannels * 2) * (audioChannels * 2)
                            if (length <= 0) break
                            buffer.put(bytes, offset, length)
                            val sampleOffsetNs = offset.toLong() * 1_000_000_000L / (audioChannels * 2 * audioRate)
                            val ptsUs = ((packet.second + sampleOffsetNs - startedAtNs) / 1_000).coerceAtLeast(0)
                            codec.queueInputBuffer(index, 0, length, ptsUs, 0)
                            offset += length
                        }
                    } else if (!running.get() && !endedInput) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            codec.queueInputBuffer(index, 0, 0,
                                (System.nanoTime() - startedAtNs) / 1_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            endedInput = true
                        }
                    }
                    while (true) {
                        val index = codec.dequeueOutputBuffer(info, 0)
                        when {
                            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                outputs.setAudioFormat(codec.outputFormat)
                                audioFormatReady = true
                                markStarted()
                            }
                            index >= 0 -> {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    codec.getOutputBuffer(index)?.let {
                                        // PCM input batches are not AAC access units. Some encoders
                                        // propagate batch PTS, leaving 11/30-ms steps even when PCM
                                        // input was smoothed. AAC-LC output represents 1024 samples
                                        // per access unit; smooth that output timeline separately.
                                        val ptsUs = audioOutputTimestampSmoother?.smooth(
                                            info.presentationTimeUs * 1_000, 1_024L,
                                        )?.div(1_000) ?: info.presentationTimeUs
                                        outputs.write(EncodedSample(false, copyBuffer(it, info), ptsUs, info.flags))
                                    }
                                    bytesWritten.addAndGet(info.size.toLong())
                                }
                                endedOutput = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(index, false)
                            }
                            else -> break
                        }
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) onError("USB 音频编码失败：${error.message}")
            }
        }, "usb-audio-drain").apply { start() }
    }

    fun startRecording(container: ContainerFormat) = changeOutput(CaptureOutput.RECORDING, true) {
        it.recording(container)
    }
    fun stopRecording() = changeOutput(CaptureOutput.RECORDING, false)
    fun startHttp() = changeOutput(CaptureOutput.HTTP, true) { it.http() }
    fun stopHttp() = changeOutput(CaptureOutput.HTTP, false)
    fun startRtmp(url: String) = changeOutput(CaptureOutput.RTMP, true) { it.rtmp(url) }
    fun stopRtmp() = changeOutput(CaptureOutput.RTMP, false)
    fun hasActiveOutputs(): Boolean = outputChanges.get() > 0 || outputs.snapshot().isNotEmpty()

    private fun changeOutput(type: CaptureOutput, enable: Boolean,
                             create: ((UsbEncodedOutputFactory) -> EncodedOutput<MediaFormat>)? = null) {
        if (!running.get()) return
        outputChanges.incrementAndGet()
        publishStats()
        val command = Runnable {
            try {
                setupDone.await()
                if (!running.get()) return@Runnable
                if (enable) {
                    if (type !in outputs.snapshot()) {
                        val factory = checkNotNull(outputFactory)
                        outputs.attach(type, checkNotNull(create)(factory), needsAudio = audioCaptureEnabled)
                        requestKeyFrame()
                    }
                } else outputs.detach(type)
            } catch (error: Exception) {
                if (running.get()) onNotice("${outputLabel(type)}切换失败：${error.message}")
            } finally {
                outputChanges.decrementAndGet()
                publishStats()
                if (running.get() && outputChanges.get() == 0 && outputs.snapshot().isEmpty()) onOutputsEmpty()
            }
        }
        try { outputCommands.execute(command) } catch (_: java.util.concurrent.RejectedExecutionException) {
            outputChanges.decrementAndGet()
            publishStats()
        }
    }

    private fun requestKeyFrame() {
        runCatching {
            videoCodec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { if (running.get()) onNotice("已等待下一个视频关键帧") }
    }

    private fun onOutputFailure(type: CaptureOutput, error: Exception) {
        onNotice("${outputLabel(type)}已停止：${error.message}")
        publishStats()
        if (running.get() && outputChanges.get() == 0 && outputs.snapshot().isEmpty()) onOutputsEmpty()
    }

    private fun outputLabel(type: CaptureOutput): String = when (type) {
        CaptureOutput.RECORDING -> "录像"
        CaptureOutput.HTTP -> "HTTP 串流"
        CaptureOutput.RTMP -> "RTMP 推流"
    }

    private fun captureStats(): RecordingStats {
        val active = outputs.snapshot()
        val recording = active[CaptureOutput.RECORDING]
        val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000).coerceAtLeast(1)
        return RecordingStats(
            elapsedMs = elapsedMs,
            averageFps = frameCount.get() * 1_000.0 / elapsedMs,
            recentFps = recentFps,
            averageBitrateBitsPerSecond = bytesWritten.get() * 8_000.0 / elapsedMs,
            segment = recording?.segment ?: 0,
            outputPath = recording?.path,
            fileRecording = recording != null,
            httpStreaming = CaptureOutput.HTTP in active,
            rtmpStreaming = CaptureOutput.RTMP in active,
            outputChangePending = outputChanges.get() > 0,
            bytesStreamed = active.values.sumOf { it.bytesStreamed },
            streamBitrateBitsPerSecond = streamRate,
            audioLevelDb = audioLevelDb,
        )
    }

    private fun publishStats() {
        if (running.get() && statsStarted.get()) onStats(captureStats())
    }

    override fun stop(onComplete: () -> Unit) {
        running.set(false)
        Thread({
            try {
                setupDone.await()
                releaseResources()
            } finally {
                onComplete()
            }
        }, "usb-record-stop").start()
    }

    override fun forceRelease() {
        running.set(false)
        if (setupDone.count == 0L) releaseResources()
    }

    private fun releaseResources() {
        if (!released.compareAndSet(false, true)) {
            releaseDone.await()
            return
        }
        try {
        mjpegDecodePool.close()
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) runCatching { NativeUsbCapture.nativeClose(handle) }
        videoQueue.clear()
        rawVideoConverter.close()
        videoRenderThread?.interrupt()
        runCatching { videoRenderThread?.join() }
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { encoderInputSurface?.release() }
        runCatching { videoThread?.join(3_000) }
        runCatching { audioThread?.join(3_000) }
        outputCommands.shutdown()
        if (!runCatching { outputCommands.awaitTermination(3, TimeUnit.SECONDS) }.getOrDefault(false))
            outputCommands.shutdownNow()
        outputs.close()
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { usbConnection?.close() }
        RecorderController.updateUsbAudioLevel(-60f)
        statsThread?.interrupt()
        } finally {
            releaseDone.countDown()
        }
    }

    override fun updatePreview(surface: Surface?, enabled: Boolean, previewRotationDegrees: Int) {
        preview = surface
        previewEnabled = enabled
        previewRevision.incrementAndGet()
    }

    override fun switchCamera(cameraId: String) = Unit
    override fun updateCameraControls(updated: RecordingConfig) = Unit

    companion object {
        const val USB_CAMERA_PREFIX = "usb-host:"
    }

    private fun copyBuffer(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo): ByteArray =
        buffer.duplicate().apply {
            position(info.offset)
            limit(info.offset + info.size)
        }.let { duplicate -> ByteArray(duplicate.remaining()).also { duplicate.get(it) } }
}
