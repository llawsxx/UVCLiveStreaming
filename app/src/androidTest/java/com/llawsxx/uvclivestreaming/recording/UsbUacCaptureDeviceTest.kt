package com.llawsxx.uvclivestreaming.recording

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Explicitly opt into a connected, independently authorized audio device in the diagnostic package. */
class UsbUacCaptureDeviceTest {
    @Test fun externalUacCapturesRawPrecisionAndFeedsVirtualVideoAacStream() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".layoutcheck"))
        val log = File(context.filesDir, "uac-device-results.txt")
        fun report(message: String) { log.appendText(message + "\n"); println(message) }
        log.writeText("")
        val manager = context.getSystemService(UsbManager::class.java)
        val devices = usbAudioInputDevices(manager)
        report("UAC input devices: $devices")
        val selected = devices.firstOrNull { choice -> manager.deviceList[choice.deviceName]?.let { device ->
            (0 until device.interfaceCount).none { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
        } == true }
        assertNotNull("Connect an external UAC-only audio device", selected)
        val inputDevice = checkNotNull(manager.deviceList[checkNotNull(selected).deviceName])
        if (!manager.hasPermission(inputDevice)) {
            report("Waiting for USB permission for ${selected.label}")
            manager.requestPermission(inputDevice, PendingIntent.getBroadcast(context, 6201,
                Intent("${context.packageName}.UAC_TEST_PERMISSION").setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT))
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (!manager.hasPermission(inputDevice) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        }
        assertTrue("USB permission was not granted", manager.hasPermission(inputDevice))
        for (bits in listOf(UsbAudioBitDepth.AUTO, UsbAudioBitDepth.PCM16, UsbAudioBitDepth.PCM24, UsbAudioBitDepth.PCM32)) {
            val capture = try { UsbUacCapture.open(context, selected, 0, bits) } catch (error: RuntimeException) {
                if (bits != UsbAudioBitDepth.AUTO && error.message?.contains("不支持所选") == true) {
                    report("${bits.label}: unsupported by this device"); continue
                }
                throw error
            }
            capture.use {
                val rawFrames = AtomicLong()
                val pcmFrames = AtomicLong()
                val nonzero = AtomicLong()
                val failures = CopyOnWriteArrayList<String>()
                val pipeline = UsbAudioPipeline(capture.sampleRate, capture.channels,
                    AudioDspSettings(enabled = true, loudnessEnabled = false), onPcm = { bytes, _ ->
                        pcmFrames.addAndGet((bytes.size / (capture.channels * 2)).toLong())
                    }, onError = failures::add)
                try {
                    capture.start(object : UsbCaptureCallback {
                        override fun onUsbVideoFrame(bytes: CapturedVideoBuffer, format: Int, width: Int, height: Int, timestampNs: Long) {
                            bytes.close()
                            failures.add("UAC-only capture emitted video")
                        }
                        override fun onUsbAudioPcm(bytes: ByteArray, timestampNs: Long) { failures.add("Raw PCM precision lost") }
                        override fun onUsbAudioPcmRaw(bytes: ByteArray, timestampNs: Long, sampleBytes: Int) {
                            if (sampleBytes != capture.sampleBytes) failures.add("Wrong input subslot width")
                            rawFrames.addAndGet((bytes.size / (sampleBytes * capture.channels)).toLong())
                            nonzero.addAndGet(bytes.count { byte -> byte != 0.toByte() }.toLong())
                            pipeline.offer(bytes, timestampNs, sampleBytes)
                        }
                    })
                    Thread.sleep(2_000)
                } finally {
                    capture.close()
                    pipeline.close()
                    assertTrue(pipeline.awaitStopped(5_000))
                }
                assertTrue("No UAC PCM received for $bits", rawFrames.get() > capture.sampleRate)
                assertEquals("DSP lost input frames", rawFrames.get(), pcmFrames.get())
                assertEquals(0L, pipeline.droppedPackets.get())
                assertTrue("Capture errors $failures", failures.isEmpty())
                report("${bits.label}: actual=${capture.bitDepth} bit/${capture.sampleBytes} bytes " +
                    "${capture.sampleRate}Hz ${capture.channels}ch, rawFrames=${rawFrames.get()} " +
                    "DSP output=${pcmFrames.get()}, nonzeroBytes=${nonzero.get()}")
            }
        }

        val errors = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val config = RecordingConfig(cameraId = TestCardSettings.DEVICE_ID,
            testCard = TestCardSettings(TestCardPattern.MOTION, 640, 360, 30.0), width = 640, height = 360, fps = 30.0,
            usbAudioDevice = selected, usbAudioBitDepth = UsbAudioBitDepth.AUTO,
            mode = RecordingMode.AUDIO_VIDEO, httpStreamEnabled = true, httpServiceOnly = true, httpStreamPort = 13411)
        val engine = UsbRecorderEngine(context, config, RecordingOutputStore(context, null),
            onStarted = { started.countDown() }, onStats = {}, onNotice = ::report,
            onError = { errors.add(it); started.countDown() }, onOutputsEmpty = {})
        var client: Socket? = null
        var receiver: Thread? = null
        try {
            engine.start(null, false, 0)
            assertTrue(started.await(15, TimeUnit.SECONDS))
            assertTrue("Engine startup errors $errors", errors.isEmpty())
            client = Socket("127.0.0.1", 13411).apply { soTimeout = 3_000 }
            client.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            val input = client.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0 && header.length < 8192)
                header.append(byte.toChar())
            }
            assertTrue(header.toString(), header.startsWith("HTTP/1.1 200"))
            receiver = Thread { runCatching {
                File(context.filesDir, "uac-test-video-audio.ts").outputStream().use { input.copyTo(it) }
            } }.apply { start() }
            Thread.sleep(4_000)
        } finally {
            engine.stop { stopped.countDown() }
            assertTrue(stopped.await(15, TimeUnit.SECONDS))
            client?.close()
            receiver?.join(4_000)
        }
        assertTrue("Engine errors $errors", errors.isEmpty())
        report("PASS external UAC + virtual video + AAC HTTP; bodyBytes=" + File(context.filesDir, "uac-test-video-audio.ts").length())
    }
}
