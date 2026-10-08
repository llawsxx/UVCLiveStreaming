package com.llawsxx.uvclivestreaming.recording

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class SyntheticRawFrontendDeviceTest {
    private class Timing(val measured: Boolean) {
        var prepareStart = 0L
        var copiedAt = 0L
        var callbackStart = 0L
        var callbackEnd = 0L
        var convertStart = 0L
        var convertEnd = 0L
        var releasedAt = 0L
    }

    private fun field(engine: UsbRecorderEngine, name: String): Any =
        UsbRecorderEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)!!

    private fun summary(values: List<Double>): JSONObject {
        val ordered = values.sorted()
        return JSONObject().put("mean_ms", ordered.average())
            .put("p95_ms", ordered[(ordered.size * 0.95).toInt().coerceAtMost(ordered.lastIndex)])
    }

    @Test fun benchmarkBeforeGpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Isolated diagnostic only", context.packageName.endsWith(".rawpipeline"))
        val arguments = InstrumentationRegistry.getArguments()
        val formats = arguments.getString("formats", "2")!!.split(',').map(String::toInt)
        val seconds = arguments.getString("seconds", "3")!!.toInt()
        val burstFrames = arguments.getString("burstFrames", "160")!!.toInt()
        val results = JSONArray()
        val destination = File(context.filesDir, "rawfrontend.json")
        destination.delete()
        SyntheticRawPipelineDeviceTest().launchDisplay().use {
            File(context.filesDir, "rawfrontend-idle.json").writeText(benchmarkIdle().toString(2))
            for ((width, height) in listOf(1920 to 1080, 3840 to 2160)) {
                for (format in formats) {
                    val source = SyntheticRawVideo(format, width, height)
                    for (cpuRepack in if (width == 1920) listOf(true, false) else listOf(false, true)) {
                        for (paced in listOf(true, false)) {
                            val result = runCase(source, cpuRepack, paced, if (paced) seconds * 60 else burstFrames)
                            results.put(result)
                            destination.writeText(results.toString(2))
                            println("RAW_FRONTEND $result")
                        }
                    }
                }
            }
        }
    }

    private fun benchmarkIdle(): JSONArray {
        val results = JSONArray()
        for (format in listOf("RAW", "MJPEG")) {
            val wake = QueueWakeSignal()
            val pool = MjpegDecodePool(decoder = { _, _, _, _, _ -> false }, workerCount = 4,
                onOutputReady = wake::signal)
            val queue = ArrayBlockingQueue<Any>(2)
            val result = AtomicReference<JSONObject>()
            val failure = AtomicReference<Throwable?>()
            pool.start()
            val worker = Thread({
                try {
                    val startNs = System.nanoTime()
                    val cpuStartNs = Debug.threadCpuTimeNanos()
                    var iterations = 0
                    while (true) {
                        val revision = wake.snapshot()
                        assertNull(pool.poll(0))
                        assertNull(queue.poll())
                        iterations++
                        if (!wake.awaitChange(revision)) break
                    }
                    val cpuNs = Debug.threadCpuTimeNanos() - cpuStartNs
                    val wallNs = System.nanoTime() - startNs
                    val cpuPercent = cpuNs * 100.0 / wallNs
                    assertEquals("Idle consumer must remain blocked", 1, iterations)
                    assertTrue("Idle thread CPU too high: $cpuPercent", cpuPercent < 0.1)
                    result.set(JSONObject().put("policy", format).put("iterations", iterations)
                        .put("wall_ms", wallNs / 1_000_000.0).put("thread_cpu_ms", cpuNs / 1_000_000.0)
                        .put("single_core_cpu_percent", cpuPercent))
                } catch (error: Throwable) { failure.set(error) }
            }, "video-input-idle-check").apply { start() }
            try {
                Thread.sleep(10_000)
                wake.close()
                worker.join(3_000)
                assertFalse("Idle worker did not finish", worker.isAlive)
                failure.get()?.let { throw AssertionError("Idle wait failed", it) }
                results.put(checkNotNull(result.get()))
            } finally { wake.close(); worker.interrupt(); worker.join(3_000); pool.close() }
        }
        return results
    }

    private fun runCase(source: SyntheticRawVideo, cpuRepack: Boolean, paced: Boolean, frames: Int): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RecordingConfig(cameraId = TestCardSettings.DEVICE_ID, mode = RecordingMode.VIDEO,
            width = source.width, height = source.height, fps = 60.0,
            usbVideoBufferFrames = 2, usbTimestampSmoothingEnabled = false)
        val engine = UsbRecorderEngine(context, config, RecordingOutputStore(context, null),
            onStarted = {}, onStats = {}, onNotice = {}, onError = { error(it) }, onOutputsEmpty = {})
        val running = field(engine, "running") as AtomicBoolean
        @Suppress("UNCHECKED_CAST")
        val queue = field(engine, "videoQueue") as ArrayBlockingQueue<Any>
        val converter = field(engine, "rawVideoConverter") as RawVideoConverter
        val mjpeg = field(engine, "mjpegDecodePool") as MjpegDecodePool
        val wake = field(engine, "videoWake") as QueueWakeSignal
        val payloadField = UsbRecorderEngine::class.java.declaredClasses.first { it.simpleName == "VideoFrame" }
            .getDeclaredField("bytes").apply { isAccessible = true }
        val capturePool = DirectVideoBufferPool(4, 128L * 1024 * 1024)
        val repackPool = DirectVideoBufferPool(1)
        val permits = Semaphore(2)
        val warmFrames = 30
        val completed = CountDownLatch(warmFrames + frames)
        val records = ArrayList<Timing>(warmFrames + frames)
        val timings = java.util.concurrent.ConcurrentHashMap<CapturedVideoBuffer, Timing>()
        val failure = AtomicReference<Throwable?>()
        val periodNs = 1_000_000_000L / 60
        running.set(true)
        mjpeg.start()
        val consumer = Thread({
            try {
                while (running.get()) {
                    val revision = wake.snapshot()
                    check(mjpeg.poll(0) == null)
                    val queued = queue.poll()
                    if (queued == null) {
                        if (!wake.awaitChange(revision)) break
                        continue
                    }
                    val captured = payloadField.get(queued) as CapturedVideoBuffer
                    val timing = timings.remove(captured)!!
                    timing.convertStart = System.nanoTime()
                    try {
                        val converted = if (cpuRepack) {
                            val output = checkNotNull(repackPool.acquire(source.size))
                            try {
                                check(NativeUsbCapture.nativeConvertRawBufferToGpuBuffer(captured.buffer, captured.size,
                                    source.format, source.width, source.height, output.buffer))
                                val frame = GpuVideoFrame(null, source.width, source.height, timing.prepareStart,
                                    layout = when (source.format) {
                                        4 -> GpuVideoFrame.RGB
                                        9 -> GpuVideoFrame.BGR
                                        7 -> GpuVideoFrame.YUV10
                                        else -> GpuVideoFrame.I420
                                    }, fullRange = source.format == 4 || source.format == 9, directBuffer = output.buffer,
                                    chromaHeight = if (source.format == 2 || source.format == 3) source.height else source.height / 2)
                                RawVideoConverter.ConvertedFrame(frame, output)
                            } catch (error: Throwable) { output.close(); throw error }
                        } else {
                            checkNotNull(converter.convert(captured, source.format, source.width, source.height, timing.prepareStart))
                        }
                        timing.convertEnd = System.nanoTime()
                        converted.close()
                    } finally { captured.close() }
                }
            } catch (_: InterruptedException) {
            } catch (error: Throwable) { failure.set(error) }
        }, "raw-frontend-no-gpu").apply { start() }
        var measuredStart = 0L
        var measuredSourceEnd = 0L
        var sourceLateFrames = 0
        var sourceSkipped = 0
        try {
            val targetStart = System.nanoTime() + 30_000_000
            if (paced) measuredStart = targetStart + warmFrames * periodNs
            for (index in 0 until warmFrames + frames) {
                failure.get()?.let { throw AssertionError("Frontend consumer failed", it) }
                if (paced) {
                    val target = targetStart + index * periodNs
                    while (System.nanoTime() < target) LockSupport.parkNanos(target - System.nanoTime())
                    if (System.nanoTime() - target >= periodNs) {
                        if (index >= warmFrames) { sourceLateFrames++; sourceSkipped++ }
                        completed.countDown()
                        continue
                    }
                } else {
                    assertTrue("Frontend stalled: ${failure.get()}", permits.tryAcquire(5, TimeUnit.SECONDS))
                }
                if (!paced && index == warmFrames) measuredStart = System.nanoTime()
                val timing = Timing(index >= warmFrames)
                records.add(timing)
                timing.prepareStart = System.nanoTime()
                val lease = checkNotNull(capturePool.acquire(source.size))
                lease.buffer.put(source.template.duplicate()).flip()
                timing.copiedAt = System.nanoTime()
                val captured = CapturedVideoBuffer(lease.buffer) {
                    lease.close()
                    timing.releasedAt = System.nanoTime()
                    if (!paced) permits.release()
                    completed.countDown()
                }
                timings[captured] = timing
                timing.callbackStart = System.nanoTime()
                engine.onUsbVideoFrame(captured, source.format, source.width, source.height, timing.prepareStart)
                timing.callbackEnd = System.nanoTime()
            }
            measuredSourceEnd = if (paced) targetStart + (warmFrames + frames) * periodNs else System.nanoTime()
            assertTrue("Frontend drain timeout: ${failure.get()}", completed.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Frontend consumer failed", it) }
            assertEquals(0, capturePool.diagnostics().inUse)
            assertEquals(0, repackPool.diagnostics().inUse)
            assertEquals(0L, converter.diagnostics().allocations)
            val drops = (field(engine, "rawQueueDrops") as AtomicLong).get()
            val measured = records.filter { it.measured && it.convertEnd > 0 }
            if (!paced) {
                assertEquals("Burst queue drops=$drops", frames, measured.size)
                assertEquals(0L, drops)
            }
            assertTrue("No frontend frames processed", measured.isNotEmpty())
            fun times(duration: (Timing) -> Long) = measured.map { duration(it) / 1_000_000.0 }
            val measuredEnd = maxOf(measuredSourceEnd, measured.maxOf { it.releasedAt })
            return JSONObject().put("width", source.width).put("height", source.height).put("format", source.format)
                .put("cpu_repack", cpuRepack).put("mode", if (paced) "paced60" else "burst")
                .put("frames", frames).put("injected", records.count { it.measured }).put("processed", measured.size)
                .put("queue_drops", drops).put("measured_queue_drops", records.count { it.measured && it.convertEnd == 0L })
                .put("source_late_frames", sourceLateFrames).put("source_skipped", sourceSkipped)
                .put("gpu_started", false).put("encoder_started", false)
                .put("queue_wait", "event").put("mjpeg_poll_ms", 0)
                .put("copy", summary(times { it.copiedAt - it.prepareStart }))
                .put("callback", summary(times { it.callbackEnd - it.callbackStart }))
                .put("queue", summary(times { it.convertStart - it.callbackStart }))
                .put("conversion", summary(times { it.convertEnd - it.convertStart }))
                .put("frontend_total", summary(times { it.releasedAt - it.prepareStart }))
                .put("throughput_fps", measured.size * 1_000_000_000.0 / (measuredEnd - measuredStart))
        } finally {
            running.set(false)
            wake.close()
            consumer.interrupt(); consumer.join(3_000)
            assertFalse("Frontend consumer did not stop", consumer.isAlive)
            while (true) {
                val queued = queue.poll() ?: break
                (payloadField.get(queued) as CapturedVideoBuffer).close()
            }
            mjpeg.close(); converter.close(); capturePool.close(); repackPool.close()
            (field(engine, "outputCommands") as ExecutorService).shutdownNow()
        }
    }
}
