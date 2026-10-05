package com.llawsxx.uvclivestreaming.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run only in an independently installed diagnostic package, without camera/microphone permissions. */
class TestCardCaptureDeviceTest {
    @Test fun virtualHttpCanAddRecordingAndContinueAfterRecordingStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Use the isolated diagnostic applicationId", context.packageName.endsWith(".layoutcheck"))
        val errors = CopyOnWriteArrayList<String>()
        val notices = CopyOnWriteArrayList<String>()
        val stats = AtomicReference<RecordingStats?>()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val config = RecordingConfig(cameraId = TestCardSettings.DEVICE_ID,
            testCard = TestCardSettings(TestCardPattern.MOTION, 1280, 720, 60.0),
            width = 1280, height = 720, fps = 60.0, mode = RecordingMode.VIDEO,
            videoBitrate = 2_000_000, httpStreamEnabled = true, httpServiceOnly = true,
            httpStreamPort = 13409)
        val engine = UsbRecorderEngine(context, config, RecordingOutputStore(context, null),
            onStarted = { stats.set(it); started.countDown() }, onStats = stats::set,
            onNotice = notices::add, onError = { errors.add(it); started.countDown() }, onOutputsEmpty = {})
        var connection: Socket? = null
        var reader: Thread? = null
        var recordingName: String? = null
        try {
            engine.start(null, false, 0)
            assertTrue("Startup timed out", started.await(15, TimeUnit.SECONDS))
            assertTrue("Startup errors $errors", errors.isEmpty())
            connection = Socket("127.0.0.1", 13409).apply { soTimeout = 3_000 }
            connection.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            val input = connection.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0 && header.length < 8192) { "Invalid HTTP response" }
                header.append(byte.toChar())
            }
            assertTrue(header.toString(), header.startsWith("HTTP/1.1 200"))
            reader = Thread {
                runCatching { File(context.filesDir, "test-card.ts").outputStream().use { output ->
                    input.use { stream -> stream.copyTo(output) }
                } }
            }.apply { start() }
            engine.startRecording(ContainerFormat.MP4)
            fun awaitState(check: (RecordingStats) -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 8_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    stats.get()?.takeIf(check)?.let { return }
                    Thread.sleep(20)
                }
                fail("Unexpected state ${stats.get()}; errors=$errors; notices=$notices")
            }
            awaitState { it.fileRecording && it.httpStreaming && !it.outputChangePending }
            recordingName = stats.get()!!.outputPath!!.substringAfterLast('/')
            Thread.sleep(3_000)
            assertNull("Virtual input must not report USB traffic", stats.get()!!.usbVideoReceiveBitsPerSecond)
            engine.stopRecording()
            awaitState { !it.fileRecording && it.httpStreaming && !it.outputChangePending }
            Thread.sleep(1_000)
            assertTrue("Virtual capture failed: $errors; notices=$notices", errors.isEmpty())
            println("Virtual shared encoder: ${stats.get()}; notices=$notices")
            engine.stop { stopped.countDown() }
            assertTrue(stopped.await(10, TimeUnit.SECONDS))
            connection.close()
            reader.join(4_000)
            assertTrue(File(context.filesDir, "test-card.ts").length() > 188 * 10)
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME}=?", arrayOf(recordingName), null)!!.use { cursor ->
                assertTrue("Recording was not published", cursor.moveToFirst())
                val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                try {
                    context.contentResolver.openInputStream(uri)!!.use { inputFile ->
                        File(context.filesDir, "test-card.mp4").outputStream().use { inputFile.copyTo(it) }
                    }
                    MediaExtractor().let { extractor ->
                        try {
                            extractor.setDataSource(context, uri, null)
                            assertEquals(1, extractor.trackCount)
                            val format = extractor.getTrackFormat(0)
                            assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH))
                            assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
                            extractor.selectTrack(0)
                            var count = 0
                            var last = -1L
                            while (extractor.sampleTime >= 0) {
                                assertTrue(extractor.sampleTime > last)
                                last = extractor.sampleTime
                                count++
                                extractor.advance()
                            }
                            assertTrue("Too few recorded frames: $count", count > 100)
                            println("MP4 verified: 1280x720, $count frames, last PTS=$last")
                        } finally { extractor.release() }
                    }
                } finally { context.contentResolver.delete(uri, null, null) }
            }
        } finally {
            connection?.close()
            engine.stop { stopped.countDown() }
            stopped.await(10, TimeUnit.SECONDS)
            reader?.join(4_000)
        }
    }
}
