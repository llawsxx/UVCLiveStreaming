package com.llawsxx.uvclivestreaming.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.roundToInt

class MjpegEventQueueDeviceTest {
    private val colors = listOf(Color.BLACK, Color.WHITE, Color.RED, Color.GREEN,
        Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)

    private fun field(engine: UsbRecorderEngine, name: String): Any =
        UsbRecorderEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)!!

    @Test fun nativeDecodeAndEventWakePreserveRealJpegColors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".rawpipeline"))
        val results = JSONArray()
        for ((width, height) in listOf(1920 to 1080, 3840 to 2160)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            colors.forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect(index * width / 8f, 0f, (index + 1) * width / 8f, height.toFloat(), paint)
            }
            val jpeg = ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
            bitmap.recycle()
            assertEquals(255, jpeg[0].toInt() and 255)
            assertEquals(216, jpeg[1].toInt() and 255)
            val config = RecordingConfig(cameraId = TestCardSettings.DEVICE_ID, mode = RecordingMode.VIDEO,
                width = width, height = height, fps = 60.0, usbVideoBufferFrames = 2,
                usbTimestampSmoothingEnabled = false)
            val engine = UsbRecorderEngine(context, config, RecordingOutputStore(context, null),
                onStarted = {}, onStats = {}, onNotice = {}, onError = { error(it) }, onOutputsEmpty = {})
            val running = field(engine, "running") as AtomicBoolean
            val wake = field(engine, "videoWake") as QueueWakeSignal
            val pool = field(engine, "mjpegDecodePool") as MjpegDecodePool
            val captures = DirectVideoBufferPool(4)
            val permits = Semaphore(2)
            val warmFrames = 30
            val frames = 120
            val finished = CountDownLatch(warmFrames + frames)
            val failure = AtomicReference<Throwable?>()
            val latencies = mutableListOf<Double>()
            running.set(true)
            pool.start()
            val consumer = Thread({
                try {
                    var previousTimestamp = Long.MIN_VALUE
                    var received = 0
                    while (running.get()) {
                        val revision = wake.snapshot()
                        val frame = pool.poll(0)
                        if (frame == null) {
                            if (!wake.awaitChange(revision)) break
                            continue
                        }
                        frame.use {
                            val receivedNs = System.nanoTime()
                            assertTrue(frame.timestampNs > previousTimestamp)
                            previousTimestamp = frame.timestampNs
                            assertNotNull("Native JPEG decode failed", frame.yuv)
                            checkColors(frame)
                            if (received++ >= warmFrames) latencies.add((receivedNs - frame.timestampNs) / 1_000_000.0)
                            finished.countDown()
                            permits.release()
                        }
                    }
                } catch (error: Throwable) { failure.set(error) }
            }, "mjpeg-event-validation").apply { start() }
            try {
                val periodNs = 1_000_000_000L / 60
                val startNs = System.nanoTime()
                repeat(warmFrames + frames) { index ->
                    val deadline = startNs + index * periodNs
                    while (System.nanoTime() < deadline) LockSupport.parkNanos(deadline - System.nanoTime())
                    assertTrue("Decoder stalled", permits.tryAcquire(3, TimeUnit.SECONDS))
                    failure.get()?.let { throw AssertionError(it) }
                    val lease = checkNotNull(captures.acquire(jpeg.size))
                    lease.buffer.put(jpeg).flip()
                    engine.onUsbVideoFrame(CapturedVideoBuffer(lease.buffer) { lease.close() },
                        1, width, height, System.nanoTime())
                }
                assertTrue("MJPEG consumer stalled", finished.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError(it) }
                val diagnostics = pool.diagnostics()
                assertEquals(frames, latencies.size)
                assertEquals(0L, diagnostics.decodeFailures)
                assertEquals(0L, diagnostics.inputDrops)
                assertEquals(0L, diagnostics.outputSkippedSequences)
                val ordered = latencies.sorted()
                val result = JSONObject().put("width", width).put("height", height)
                    .put("jpeg_bytes", jpeg.size).put("measured_frames", frames)
                    .put("warmup_frames", warmFrames).put("paced_fps", 60)
                    .put("decode_failures", diagnostics.decodeFailures).put("input_drops", diagnostics.inputDrops)
                    .put("callback_to_decoded_mean_ms", ordered.average())
                    .put("callback_to_decoded_p95_ms", ordered[(ordered.size * 0.95).toInt()])
                    .put("average_native_decode_ms", diagnostics.averageDecodeMs)
                    .put("color_samples_passed", true).put("gpu_started", false).put("encoder_started", false)
                results.put(result)
                File(context.filesDir, "mjpeg-event.json").writeText(results.toString(2))
                println("MJPEG_EVENT $result")
            } finally {
                running.set(false)
                wake.close()
                consumer.interrupt(); consumer.join(3_000)
                assertFalse(consumer.isAlive)
                pool.close(); captures.close()
                (field(engine, "rawVideoConverter") as RawVideoConverter).close()
                (field(engine, "outputCommands") as ExecutorService).shutdownNow()
            }
        }
    }

    private fun checkColors(frame: MjpegDecodePool.DecodedFrame) {
        val buffer = checkNotNull(frame.yuv).buffer
        val chromaSize = frame.chromaWidth * frame.chromaHeight
        colors.forEachIndexed { index, color ->
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val expected = listOf(
                (0.299 * red + 0.587 * green + 0.114 * blue).roundToInt(),
                (128 - 0.168736 * red - 0.331264 * green + 0.5 * blue).roundToInt(),
                (128 + 0.5 * red - 0.418688 * green - 0.081312 * blue).roundToInt(),
            ).map { it.coerceIn(0, 255) }
            val horizontal = (2 * index + 1) * frame.width / 16
            val vertical = frame.height / 2
            val chromaOffset = vertical * frame.chromaHeight / frame.height * frame.chromaWidth +
                horizontal * frame.chromaWidth / frame.width
            val offsets = listOf(vertical * frame.width + horizontal,
                frame.width * frame.height + chromaOffset,
                frame.width * frame.height + chromaSize + chromaOffset)
            for (plane in offsets.indices) {
                val actual = buffer.get(offsets[plane]).toInt() and 255
                assertTrue("Color $index plane $plane: expected ${expected[plane]}, got $actual",
                    abs(actual - expected[plane]) <= 5)
            }
        }
    }
}
