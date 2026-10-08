package com.llawsxx.uvclivestreaming.recording

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

class SyntheticRawPipelineDeviceTest {
    internal class DisplayActivity(private val activity: ComponentActivity) : AutoCloseable {
        fun onActivity(action: (ComponentActivity) -> Unit) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { action(activity) }
        }

        override fun close() = onActivity { it.finish() }
    }

    internal fun launchDisplay(): DisplayActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = "${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("am start -n $component")
        ).bufferedReader().use { response -> phase("Activity: shell ${response.readText().trim()}") }
        val resumed = AtomicReference<ComponentActivity?>()
        waitUntil {
            instrumentation.runOnMainSync {
                resumed.set(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull())
            }
            resumed.get() != null
        }
        return DisplayActivity(checkNotNull(resumed.get()))
    }

    private fun phase(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = "${SystemClock.elapsedRealtime()} $name\n"
        File(context.filesDir, "rawpipeline-phases.log").appendText(entry)
        Log.i("RawPipelinePhase", name)
        println("RAW_PIPELINE_PHASE $name")
    }

    @Test fun foregroundStartupSmoke() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".rawpipeline"))
        phase("smoke: launching Activity")
        launchDisplay().use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContentView(TextView(activity).apply { text = "Raw pipeline startup smoke" })
            }
            phase("smoke: Activity resumed")
            Thread.sleep(1_000)
        }
        phase("smoke: complete")
    }

    private fun field(engine: UsbRecorderEngine, name: String): Any =
        UsbRecorderEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)!!

    private fun invoke(engine: UsbRecorderEngine, name: String) {
        UsbRecorderEngine::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(engine)
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        error("Synthetic pipeline wait timed out")
    }

    @Test fun rawFramesTraverseProductionPipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Run only in the isolated diagnostic package", context.packageName.endsWith(".rawpipeline"))
        val arguments = InstrumentationRegistry.getArguments()
        val width = arguments.getString("width", "1920")!!.toInt()
        val height = arguments.getString("height", "1080")!!.toInt()
        val inputFormat = arguments.getString("format", "2")!!.toInt()
        val fps = arguments.getString("fps", "60")!!.toInt()
        val seconds = arguments.getString("seconds", "6")!!.toInt()
        val codec = VideoCodec.valueOf(arguments.getString("codec", "H264")!!)
        val lowPreview = arguments.getString("lowPreview", "false").toBoolean()
        val cpuRepack = arguments.getString("cpuRepack", "false").toBoolean()
        val yuvInput = arguments.getString("yuvInput", "false").toBoolean()
        val label = "${width}x$height-f$inputFormat-${codec.name}-${fps}fps-${if (lowPreview) "p5" else "p60"}-${seconds}s${if (cpuRepack) "-cpu" else ""}${if (yuvInput) "-yuv" else ""}"
        File(context.filesDir, "rawpipeline-phases.log").delete()
        File(context.filesDir, "$label.json").delete()
        phase("test: started $label")
        val source by lazy {
            phase("source: building template")
            SyntheticRawVideo(inputFormat, width, height).also { phase("source: template ready") }
        }
        val warmFrames = fps
        val requestedFrames = seconds * fps
        val postFrames = 90
        val jpeg by lazy { SyntheticMjpegVideo(File(context.filesDir, "synthetic-mjpeg-${width}x$height.bin"),
            width, height, warmFrames + requestedFrames + postFrames) }
        val expectedColors = arrayOf(intArrayOf(255, 255, 255), intArrayOf(255, 255, 0),
            intArrayOf(0, 255, 255), intArrayOf(0, 255, 0), intArrayOf(255, 0, 255),
            intArrayOf(255, 0, 0), intArrayOf(0, 0, 255), intArrayOf(0, 0, 0))
        val previewErrors = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val notices = CopyOnWriteArrayList<String>()
        val stats = AtomicReference<RecordingStats?>()
        val started = CountDownLatch(1)
        val releaseTimes = ConcurrentLinkedQueue<Double>()
        val prepareTimes = mutableListOf<Double>()
        val periodNs = 1_000_000_000L / fps
        val snapshotFile = File(context.filesDir, "$label-preview.png")
        val displayPreview = AtomicReference<SurfaceView>()
        val displayReady = CountDownLatch(1)
        phase("Activity: launching")
        val scenario = launchDisplay()
        phase("Activity: launched")
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            container.addView(TextView(activity).apply {
                text = "Raw video pipeline test: $label"
                setTextColor(Color.WHITE)
                textSize = 18f
            })
            val surface = SurfaceView(activity)
            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { displayReady.countDown() }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
            container.addView(surface, LinearLayout.LayoutParams(-1, activity.resources.displayMetrics.widthPixels * 9 / 16))
            displayPreview.set(surface)
            activity.setContentView(container)
        }
        assertTrue("Display surface unavailable", displayReady.await(10, TimeUnit.SECONDS))
        phase("Activity: surface ready")
        val config = RecordingConfig(cameraId = TestCardSettings.DEVICE_ID, mode = RecordingMode.VIDEO,
            width = width, height = height, fps = fps.toDouble(), videoCodec = codec,
            videoBitrate = if (width == 3840) 35_000_000 else 12_000_000,
            usbYuvMatrix = if (inputFormat == 1) UsbYuvMatrix.BT601 else UsbYuvMatrix.BT709,
            usbSourceRange = UsbSourceRange.AUTO, usbYuvEncoderInput = yuvInput,
            colorStandard = VideoColorStandard.BT709, colorTransfer = VideoColorTransfer.BT709,
            colorRange = VideoColorRange.LIMITED, usbTimestampSmoothingEnabled = false,
            usbVideoBufferFrames = 2, muxingQueueSize = 64, httpServiceOnly = true,
            httpStreamEnabled = true, httpStreamPort = 13419, httpBufferSeconds = 3)
        val engine = UsbRecorderEngine(context, config, RecordingOutputStore(context, null),
            onStarted = { stats.set(it); started.countDown() }, onStats = stats::set,
            onNotice = notices::add, onError = { errors.add(it); started.countDown() }, onOutputsEmpty = {})
        val pool = DirectVideoBufferPool(8, 256L * 1024 * 1024)
        UsbRecorderEngine::class.java.getDeclaredField("externalVideoSource").apply { isAccessible = true }.setBoolean(engine, true)
        engine.updateColorGrade(VideoColorGradeSettings())
        val oldLowPreview = RecorderController.previewLowFrameRate
        val readyFile = File(context.filesDir, "rawpipeline-ready")
        val clientFile = File(context.filesDir, "rawpipeline-client-ready")
        var recordingName: String? = null
        var sourceSkipped = 0
        var injected = 0
        var stopped = false
        fun counter(name: String) = (field(engine, name) as AtomicLong).get()
        fun produce(firstId: Int, count: Int, measured: Boolean): Long {
            val startNs = System.nanoTime() + 50_000_000L
            var index = 0
            while (index < count) {
                val targetNs = startNs + index * periodNs
                while (System.nanoTime() < targetNs) LockSupport.parkNanos(targetNs - System.nanoTime())
                val lateFrames = ((System.nanoTime() - targetNs) / periodNs).toInt()
                if (lateFrames > 0) {
                    val skip = minOf(lateFrames, count - index)
                    if (measured) sourceSkipped += skip
                    index += skip
                    if (index >= count) break
                }
                val timestampNs = startNs + index * periodNs
                val prepareStart = System.nanoTime()
                val lease = checkNotNull(pool.acquire(if (inputFormat == 1) jpeg.size else source.size))
                if (inputFormat == 1) lease.buffer.put(jpeg.frames[firstId + index]).flip()
                else {
                    lease.buffer.order(ByteOrder.LITTLE_ENDIAN).put(source.template.duplicate()).flip()
                    source.mark(lease.buffer, firstId + index)
                }
                val owned = CapturedVideoBuffer(lease.buffer) {
                    if (measured) releaseTimes.add((System.nanoTime() - timestampNs) / 1_000_000.0)
                    lease.close()
                }
                if (measured) { prepareTimes.add((System.nanoTime() - prepareStart) / 1_000_000.0); injected++ }
                engine.onUsbVideoFrame(owned, inputFormat, width, height, timestampNs)
                index++
            }
            val endNs = startNs + count * periodNs
            while (System.nanoTime() < endNs) LockSupport.parkNanos(endNs - System.nanoTime())
            return startNs
        }
        try {
            if (inputFormat == 1) jpeg.frames else {
                source.template
                RawVideoConverter().use { probe ->
                    probe.convert(CapturedVideoBuffer(source.template.duplicate()) {}, inputFormat, width, height, 1)!!.use {
                        assertEquals("Wrong diagnostic upload path", cpuRepack, probe.diagnostics().allocations > 0)
                    }
                }
            }
            phase("engine: starting")
            engine.start(null, false, 0)
            waitUntil {
                UsbRecorderEngine::class.java.getDeclaredField("videoRenderThread").apply { isAccessible = true }.get(engine) != null
            }
            produce(0, fps, false)
            assertTrue("Bootstrap timeout $errors", started.await(15, TimeUnit.SECONDS))
            phase("engine: started")
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals("Encoder fell back unexpectedly: $notices", yuvInput, engine.videoInputDiagnostics() != null)
            RecorderController.previewLowFrameRate = lowPreview
            engine.updatePreview(displayPreview.get().holder.surface, true, 0)
            phase("warmup: starting")
            produce(0, warmFrames, false)
            waitUntil { pool.diagnostics().inUse == 0 }
            waitUntil { counter("frameCount") >= counter("renderedVideoFrames") }
            phase("warmup: drained")
            Thread.sleep(250)
            @Suppress("UNCHECKED_CAST")
            val router = field(engine, "outputs") as EncodedOutputRouter<MediaFormat>
            router.detach(CaptureOutput.HTTP)
            val factory = field(engine, "outputFactory") as UsbEncodedOutputFactory
            router.attach(CaptureOutput.HTTP, factory.http())
            val recording = factory.recording(ContainerFormat.MP4)
            recordingName = recording.path!!.substringAfterLast('/')
            router.attach(CaptureOutput.RECORDING, recording)
            invoke(engine, "requestKeyFrame")
            clientFile.delete()
            readyFile.writeText(label)
            phase("HTTP: waiting for host")
            waitUntil(20_000) { clientFile.exists() }
            phase("HTTP: connected")
            val encodedBefore = counter("frameCount")
            val renderedBefore = counter("renderedVideoFrames")
            val dropsBefore = counter("rawQueueDrops")
            val skippedBefore = counter("videoTimestampSkips")
            val inputBefore = engine.videoInputDiagnostics()
            val outputBefore = engine.videoOutputDiagnostics()
            val measuredStart = produce(warmFrames, requestedFrames, true)
            phase("measurement: source complete")
            waitUntil { pool.diagnostics().inUse == 0 }
            if (inputFormat == 1) waitUntil {
                val decoded = (field(engine, "mjpegDecodePool") as MjpegDecodePool).diagnostics()
                decoded.inputQueued == 0 && decoded.outputQueued == 0 && decoded.outputBuffers.inUse == 0
            }
            phase("measurement: buffers released")
            waitUntil { counter("frameCount") >= counter("renderedVideoFrames") }
            val outputAfter = engine.videoOutputDiagnostics()
            val encoded = counter("frameCount") - encodedBefore
            val rendered = counter("renderedVideoFrames") - renderedBefore
            val queueDrops = counter("rawQueueDrops") - dropsBefore
            val timestampSkips = counter("videoTimestampSkips") - skippedBefore
            val drainedNs = System.nanoTime()
            val inputAfter = engine.videoInputDiagnostics()
            val decodeDiagnostics = (field(engine, "mjpegDecodePool") as MjpegDecodePool).diagnostics()
            produce(warmFrames + requestedFrames, postFrames, false)
            waitUntil { pool.diagnostics().inUse == 0 }
            phase("postroll: drained")
            Thread.sleep(100)
            val location = IntArray(2)
            val previewSize = IntArray(2)
            scenario.onActivity {
                displayPreview.get().getLocationOnScreen(location)
                previewSize[0] = displayPreview.get().width
                previewSize[1] = displayPreview.get().height
            }
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val previewBitmap = Bitmap.createBitmap(screenshot, location[0], location[1], previewSize[0], previewSize[1])
                try {
                    snapshotFile.outputStream().use { output -> previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
                    for (bar in 0 until 8) {
                        val color = previewBitmap.getPixel((bar * 2 + 1) * previewBitmap.width / 16, previewBitmap.height / 4)
                        val actual = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
                        val maximum = actual.indices.maxOf { component -> abs(actual[component] - expectedColors[bar][component]) }
                        if (maximum > 8) previewErrors.add("Display color bar=$bar error=$maximum")
                    }
                } finally { previewBitmap.recycle() }
            } finally { screenshot.recycle() }
            val done = CountDownLatch(1)
            phase("engine: stopping")
            engine.stop { done.countDown() }
            assertTrue("Stop timeout", done.await(15, TimeUnit.SECONDS))
            phase("engine: stopped")
            stopped = true
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, projection, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(recordingName), null)!!.use { cursor ->
                assertTrue("Recording not published: $recordingName", cursor.moveToFirst())
                val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                try {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        File(context.filesDir, "$label.mp4").outputStream().use { output -> input.copyTo(output) }
                    }
                } finally { context.contentResolver.delete(uri, null, null) }
            }
            val pts = mutableListOf<Long>()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(File(context.filesDir, "$label.mp4").absolutePath)
                assertEquals(1, extractor.trackCount)
                val outputFormat = extractor.getTrackFormat(0)
                assertEquals(width, outputFormat.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(height, outputFormat.getInteger(MediaFormat.KEY_HEIGHT))
                extractor.selectTrack(0)
                while (extractor.sampleTime >= 0) { pts.add(extractor.sampleTime); extractor.advance() }
            } finally { extractor.release() }
            fun average(values: Collection<Double>) = if (values.isEmpty()) 0.0 else values.average()
            fun p95(values: Collection<Double>): Double = values.sorted().let { if (it.isEmpty()) 0.0 else it[(it.size * 0.95).toInt().coerceAtMost(it.lastIndex)] }
            val summary = JSONObject().put("label", label).put("width", width).put("height", height)
                .put("format", inputFormat).put("codec", codec.name).put("fps", fps).put("seconds", seconds)
                .put("cpu_repack", cpuRepack)
                .put("yuv_input", yuvInput).put("release_scope", if (inputFormat == 1) "JPEG decode input lease" else "render completion")
                .put("mjpeg_decode_failures", decodeDiagnostics.decodeFailures)
                .put("mjpeg_input_drops", decodeDiagnostics.inputDrops)
                .put("mjpeg_output_skips", decodeDiagnostics.outputSkippedSequences)
                .put("mjpeg_decode_mean_ms", decodeDiagnostics.averageDecodeMs)
                .put("preview_low", lowPreview).put("requested_frames", requestedFrames).put("source_skipped", sourceSkipped)
                .put("injected_frames", injected).put("rendered_frames", rendered).put("encoded_frames", encoded)
                .put("queue_drops", queueDrops).put("timestamp_skips", timestampSkips).put("mp4_frames", pts.size)
                .put("pts_span_fps", if (pts.size > 1) (pts.size - 1) * 1_000_000.0 / (pts.last() - pts.first()) else 0.0)
                .put("wall_rendered_fps", rendered * 1_000_000_000.0 / (drainedNs - measuredStart))
                .put("prepare_mean_ms", average(prepareTimes)).put("prepare_p95_ms", p95(prepareTimes))
                .put("release_mean_ms", average(releaseTimes)).put("release_p95_ms", p95(releaseTimes))
                .put("encoder_name", outputAfter.codecName)
                .put("encoder_delivery_mean_ms", if (outputAfter.encoded == outputBefore.encoded) 0.0 else
                    (outputAfter.deliveryNs - outputBefore.deliveryNs) / 1_000_000.0 / (outputAfter.encoded - outputBefore.encoded))
                .put("encoder_delivery_scope", "source timestamp to codec output dequeue; includes decode, queueing and rendering")
                .put("preview_errors", previewErrors.size).put("errors", errors.joinToString("; "))
                .put("preview_backend", "display SurfaceView").put("preview_latency_measured", false)
                .put("notices", notices.joinToString("; ")).put("pool_allocations", pool.diagnostics().allocations)
            if (inputBefore != null && inputAfter != null) {
                val count = inputAfter.submitted - inputBefore.submitted
                fun measured(before: Double, after: Double) = if (count == 0L) 0.0 else
                    (after * inputAfter.submitted - before * inputBefore.submitted) / count
                summary.put("yuv_input_wait_mean_ms", measured(inputBefore.averageInputWaitMs, inputAfter.averageInputWaitMs))
                    .put("yuv_conversion_mean_ms", measured(inputBefore.averageConversionMs, inputAfter.averageConversionMs))
                    .put("yuv_queue_mean_ms", measured(inputBefore.averageQueueMs, inputAfter.averageQueueMs))
            }
            File(context.filesDir, "$label.json").writeText(summary.toString(2))
            println("RAW_PIPELINE $summary")
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(previewErrors.take(10).toString(), previewErrors.isEmpty())
            assertTrue("No encoded video", pts.size > fps)
            assertEquals("Capture buffer leaked", 0, pool.diagnostics().inUse)
            assertTrue("PTS did not increase", pts.zipWithNext().all { (first, second) -> second > first })
        } finally {
            if (!stopped) {
                val done = CountDownLatch(1)
                engine.stop { done.countDown() }
                done.await(15, TimeUnit.SECONDS)
            }
            pool.close()
            readyFile.delete(); clientFile.delete()
            RecorderController.previewLowFrameRate = oldLowPreview
            scenario.close()
        }
    }
}
