package com.llawsxx.uvclivestreaming.recording

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.llawsxx.uvclivestreaming.UsbCameraActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runs in a foreground test activity. No PCM or AAC is written to storage or network. */
@RunWith(AndroidJUnit4::class)
class SystemAudioCaptureDeviceTest {
    @Test fun sourcesAndPreferredBuiltinMicFeedDspAndAacAndReleaseCleanly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Grant RECORD_AUDIO to the test target before running this test", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        val activity = instrumentation.startActivitySync(Intent(context, UsbCameraActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val devices = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            Log.i("SystemAudioCaptureTest", "Inputs: ${devices.map(SystemAudioDevice::from).joinToString { device -> device.label }}")
            val builtin = devices.firstOrNull { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let(SystemAudioDevice::from)
            assertNotNull("This hardware test requires a built-in microphone", builtin)
            for (settings in listOf(SystemAudioInputSettings(),
                SystemAudioInputSettings(device = builtin), SystemAudioInputSettings(source = AudioInputSource.CAMCORDER))) {
                val errors = CopyOnWriteArrayList<String>()
                val notices = CopyOnWriteArrayList<String>()
                val queue = ArrayBlockingQueue<Pair<ByteArray, Long>>(128)
                val offered = AtomicLong()
                val nonzeroBytes = AtomicLong()
                val capture = SystemAudioCapture.open(context, settings, 0)
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                var pipeline: UsbAudioPipeline? = null
                var codecStarted = false
                try {
                    val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, capture.sampleRate, capture.channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    }
                    codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    codecStarted = true
                    pipeline = UsbAudioPipeline(capture.sampleRate, capture.channels, AudioDspSettings(enabled = true),
                        onPcm = { bytes, timestamp -> assertTrue(queue.offer(bytes to timestamp)) }, onError = errors::add)
                    val activePipeline = pipeline
                    capture.start(onPcm = { bytes, timestamp ->
                        offered.incrementAndGet()
                        nonzeroBytes.addAndGet(bytes.count { byte -> byte.toInt() != 0 }.toLong())
                        activePipeline.offer(bytes, timestamp)
                    }, onError = errors::add, onNotice = notices::add)
                    val info = MediaCodec.BufferInfo()
                    val outputPts = mutableListOf<Long>()
                    var eos = false
                    fun drain(timeoutUs: Long) {
                        while (true) {
                            val index = codec.dequeueOutputBuffer(info, timeoutUs)
                            if (index >= 0) {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) outputPts.add(info.presentationTimeUs)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                                codec.releaseOutputBuffer(index, false)
                            } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
                        }
                    }
                    var firstPts: Long? = null
                    var lastPts = 0L
                    repeat(50) {
                        val packet = queue.poll(3, TimeUnit.SECONDS)
                        assertNotNull("No processed PCM; errors=$errors", packet)
                        val (bytes, pts) = checkNotNull(packet)
                        assertTrue("PCM must use the monotonic clock", kotlin.math.abs(System.nanoTime() - pts) < 3_000_000_000L)
                        if (firstPts == null) firstPts = pts
                        else assertTrue("PCM timestamps must increase", pts > lastPts)
                        lastPts = pts
                        val index = codec.dequeueInputBuffer(1_000_000)
                        assertTrue("AAC input unavailable", index >= 0)
                        checkNotNull(codec.getInputBuffer(index)).apply { clear(); put(bytes) }
                        codec.queueInputBuffer(index, 0, bytes.size, (pts - checkNotNull(firstPts)) / 1_000, 0)
                        drain(0)
                    }
                    capture.close()
                    activePipeline.close()
                    assertTrue(activePipeline.awaitStopped(3_000))
                    val completedOffers = offered.get()
                    SystemClock.sleep(30)
                    assertEquals("PCM callbacks must stop after close", completedOffers, offered.get())
                    val input = codec.dequeueInputBuffer(1_000_000)
                    assertTrue(input >= 0)
                    codec.queueInputBuffer(input, 0, 0, (lastPts - checkNotNull(firstPts)) / 1_000 + 10_000,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    val deadline = SystemClock.elapsedRealtime() + 3_000
                    while (!eos && SystemClock.elapsedRealtime() < deadline) drain(10_000)
                    assertTrue("AAC EOS was not drained", eos)
                    assertTrue("No encoded AAC frames", outputPts.isNotEmpty())
                    assertTrue(outputPts.zipWithNext().all { (a, b) -> b > a })
                    assertTrue("Audio errors: $errors", errors.isEmpty())
                    assertTrue("Actual input route was never reported", notices.isNotEmpty())
                    Log.i("SystemAudioCaptureTest", "PASS source=${settings.source} preferred=${settings.device?.id ?: "default"} " +
                        "rate=${capture.sampleRate} channels=${capture.channels} pcmPackets=${offered.get()} " +
                        "nonzeroBytes=${nonzeroBytes.get()} aacFrames=${outputPts.size} routes=$notices")
                } finally {
                    capture.close()
                    pipeline?.let { worker -> worker.close(); worker.awaitStopped(3_000) }
                    if (codecStarted) runCatching { codec.stop() }
                    codec.release()
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
