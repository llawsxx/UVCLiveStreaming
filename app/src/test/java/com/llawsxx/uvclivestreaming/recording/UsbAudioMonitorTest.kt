package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class UsbAudioMonitorTest {
    private fun pcm(marker: Int) = byteArrayOf(marker.toByte(), 0, 0, 0)
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(2, TimeUnit.SECONDS))
    private fun waitWithoutInterrupt(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { }
    }
    private fun finish(monitor: UsbAudioMonitor) {
        monitor.close()
        assertTrue(monitor.awaitStopped(2_000))
    }

    @Test fun stalledPlaybackCannotBlockCaptureAndFullQueueKeepsNewestPcm() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = CountDownLatch(3)
        val markers = Collections.synchronizedList(mutableListOf<Int>())
        val monitor = UsbAudioMonitor(48_000, 2, { fail(it) }, queueCapacity = 2, clockNs = { 0 }) {
            object : AudioMonitorOutput {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    markers.add(bytes[0].toInt())
                    if (bytes[0].toInt() == 1) { entered.countDown(); waitWithoutInterrupt(release) }
                    written.countDown()
                    return length
                }
                override fun close() = Unit
            }
        }
        try {
            monitor.setEnabled(true)
            monitor.offer(pcm(1))
            await(entered)
            val offered = CountDownLatch(1)
            Thread {
                monitor.offer(pcm(2)); monitor.offer(pcm(3)); monitor.offer(pcm(4))
                offered.countDown()
            }.start()
            await(offered) // Playback is still stalled: no capture-side write/wait is allowed.
            assertEquals(listOf(1), markers.toList())
            release.countDown()
            await(written)
            assertEquals(listOf(1, 3, 4), markers.toList())
        } finally { release.countDown(); finish(monitor) }
    }

    @Test fun disablingAndReenablingFlushesOldPcmAndReleasesPlaybackOnWorker() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freshPlayed = CountDownLatch(1)
        val outputs = AtomicInteger()
        val markers = Collections.synchronizedList(mutableListOf<Int>())
        val closes = Collections.synchronizedList(mutableListOf<String>())
        val monitor = UsbAudioMonitor(48_000, 2, { fail(it) }, clockNs = { 0 }) {
            val number = outputs.incrementAndGet()
            object : AudioMonitorOutput {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    markers.add(bytes[0].toInt())
                    if (number == 1) { entered.countDown(); waitWithoutInterrupt(release) }
                    else freshPlayed.countDown()
                    return length
                }
                override fun close() { closes.add(Thread.currentThread().name) }
            }
        }
        try {
            monitor.offer(pcm(99)) // Disabled by default; never queues/opens playback.
            monitor.setEnabled(true)
            monitor.offer(pcm(1)); await(entered)
            monitor.offer(pcm(2))
            monitor.setEnabled(false)
            monitor.offer(pcm(3))
            monitor.setEnabled(true)
            monitor.offer(pcm(9))
            release.countDown()
            await(freshPlayed)
            assertEquals(listOf(1, 9), markers.toList())
            assertEquals(2, outputs.get())
            assertEquals(listOf("usb-audio-monitor"), closes.toList())
        } finally { release.countDown(); finish(monitor) }
    }

    @Test fun partialWritesKeepAllPcmBytesInOrder() {
        val played = CountDownLatch(2)
        val result = Collections.synchronizedList(mutableListOf<Byte>())
        val monitor = UsbAudioMonitor(48_000, 1, { fail(it) }, clockNs = { 0 }) {
            object : AudioMonitorOutput {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = minOf(2, length)
                    result.addAll(bytes.copyOfRange(offset, offset + count).toList())
                    played.countDown()
                    return count
                }
                override fun close() = Unit
            }
        }
        try {
            monitor.setEnabled(true)
            monitor.offer(byteArrayOf(1, 2, 3, 4)); await(played)
            assertEquals(listOf<Byte>(1, 2, 3, 4), result.toList())
        } finally { finish(monitor) }
    }

    @Test fun fullPlaybackBufferExpiresOldAudioAndThenPlaysFreshPcm() {
        val now = AtomicLong(0)
        val expired = CountDownLatch(1)
        val fresh = CountDownLatch(1)
        val monitor = UsbAudioMonitor(48_000, 2, { fail(it) }, clockNs = now::get) {
            object : AudioMonitorOutput {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (bytes[0].toInt() == 1) {
                        now.set(200_000_000)
                        expired.countDown()
                        return 0
                    }
                    assertEquals(9, bytes[0].toInt())
                    fresh.countDown()
                    return length
                }
                override fun close() = Unit
            }
        }
        try {
            monitor.setEnabled(true)
            monitor.offer(pcm(1)); await(expired)
            monitor.offer(pcm(9)); await(fresh)
        } finally { finish(monitor) }
    }

    @Test fun playbackFailureDoesNotStopPcmOffersAndCanRecoverAfterToggle() {
        val error = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val attempts = AtomicInteger()
        val monitor = UsbAudioMonitor(48_000, 2, { error.countDown() }, clockNs = { 0 }) {
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("playback unavailable")
            object : AudioMonitorOutput {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    recovered.countDown()
                    return length
                }
                override fun close() = Unit
            }
        }
        try {
            monitor.setEnabled(true)
            monitor.offer(pcm(1)); await(error)
            repeat(100) { monitor.offer(pcm(2)) }
            monitor.setEnabled(false)
            monitor.setEnabled(true)
            monitor.offer(pcm(3)); await(recovered)
            assertEquals(2, attempts.get())
        } finally { finish(monitor) }
    }
}
