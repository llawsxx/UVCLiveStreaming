package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UsbAudioPipelineTest {
    private class Delay(override val delayFrames: Int) : PcmDsp {
        val samples = java.util.ArrayDeque<Byte>()
        init { repeat(delayFrames) { samples.add(0) } }
        override fun process(bytes: ByteArray) {
            for (offset in bytes.indices step 2) {
                samples.add(bytes[offset])
                bytes[offset] = samples.removeFirst()
            }
        }
        override fun close() = Unit
    }
    private fun pcm(vararg frames: Int) = frames.flatMap { listOf(it.toByte(), 0.toByte()) }.toByteArray()
    private fun finish(pipeline: UsbAudioPipeline) {
        pipeline.close()
        assertTrue(pipeline.awaitStopped(2000))
    }

    @Test fun lookaheadPreservesAllSamplesAndSourceTimestampsIncludingTail() {
        val output = Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val pipeline = UsbAudioPipeline(1000, 1, AudioDspSettings(enabled = true), { bytes, pts ->
            for (offset in bytes.indices step 2) output.add(bytes[offset].toInt() to (pts + offset / 2 * 1_000_000L))
        }, { fail(it) }, factory = { _, _, _ -> Delay(5) })
        pipeline.offer(pcm(1, 2, 3), 1_000_000_000)
        pipeline.offer(pcm(4, 5, 6), 1_003_000_000)
        pipeline.offer(pcm(7, 8), 1_006_000_000)
        finish(pipeline)
        assertEquals((1..8).map { it to (1_000_000_000L + (it - 1) * 1_000_000L) }, output.toList())
    }

    @Test fun bypassDoesNotModifyOrCopyPcm() {
        val input = pcm(1, 2, 3)
        var output: ByteArray? = null
        val pipeline = UsbAudioPipeline(48000, 1, AudioDspSettings(), { bytes, _ -> output = bytes }, { fail(it) },
            factory = { _, _, _ -> error("Bypass must not create DSP") })
        pipeline.offer(input, 0)
        finish(pipeline)
        assertSame(input, output)
        assertArrayEquals(pcm(1, 2, 3), output)
    }

    @Test fun disablingFlushesOldTailBeforeNewPcm() {
        val processed = CountDownLatch(1)
        val output = Collections.synchronizedList(mutableListOf<Int>())
        val pipeline = UsbAudioPipeline(1000, 1, AudioDspSettings(enabled = true), { bytes, _ ->
            for (offset in bytes.indices step 2) output.add(bytes[offset].toInt())
            processed.countDown()
        }, { fail(it) }, factory = { _, _, _ -> Delay(2) })
        pipeline.offer(pcm(1, 2, 3), 0)
        assertTrue(processed.await(2, TimeUnit.SECONDS))
        pipeline.updateSettings(AudioDspSettings())
        pipeline.offer(pcm(4, 5, 6), 3_000_000)
        finish(pipeline)
        assertEquals((1..6).toList(), output.toList())
    }

    @Test fun stalledDspCannotBlockUsbAndQueueIsBounded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = Collections.synchronizedList(mutableListOf<Int>())
        val creations = AtomicInteger()
        val pipeline = UsbAudioPipeline(48000, 1, AudioDspSettings(enabled = true), { bytes, _ ->
            output.add(bytes[0].toInt())
        }, { fail(it) }, factory = { _, _, _ ->
            creations.incrementAndGet()
            object : PcmDsp {
                override val delayFrames = 0
                override fun process(bytes: ByteArray) {
                    if (bytes[0].toInt() == 1) { entered.countDown(); release.await() }
                }
                override fun close() = Unit
            }
        })
        try {
            pipeline.offer(pcm(1), 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val offered = CountDownLatch(1)
            Thread { for (i in 2..50) pipeline.offer(pcm(i), i * 1_000_000L); offered.countDown() }.start()
            assertTrue(offered.await(2, TimeUnit.SECONDS))
            assertEquals(33, pipeline.droppedPackets.get().toInt())
            release.countDown()
            finish(pipeline)
            assertEquals(listOf(1) + (35..50).toList(), output.toList())
            assertEquals(2, creations.get()) // Reset history when a real input gap appears.
        } finally { release.countDown(); finish(pipeline) }
    }

    @Test fun initializationFailureStillSignalsStopped() {
        val error = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val pipeline = UsbAudioPipeline(48000, 1, AudioDspSettings(enabled = true), { _, _ -> fail() },
            { error.countDown() }, { stopped.countDown() }, { _, _, _ -> error("test failure") })
        pipeline.offer(pcm(1), 0)
        assertTrue(error.await(2, TimeUnit.SECONDS))
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        finish(pipeline)
    }

    @Test fun parameterUpdateKeepsProcessorAndRunsOnWorker() {
        val first = CountDownLatch(1)
        val creations = AtomicInteger()
        val updates = AtomicInteger()
        val pipeline = UsbAudioPipeline(48000, 1, AudioDspSettings(enabled = true), { _, _ -> first.countDown() },
            { fail(it) }, factory = { _, _, _ ->
                creations.incrementAndGet()
                object : PcmDsp {
                    override val delayFrames = 0
                    override fun process(bytes: ByteArray) = Unit
                    override fun close() = Unit
                    override fun updateSettings(settings: AudioDspSettings): Boolean {
                        assertEquals("usb-audio-dsp", Thread.currentThread().name)
                        assertEquals(-20f, settings.targetLufs)
                        updates.incrementAndGet()
                        return true
                    }
                }
            })
        pipeline.offer(pcm(1), 0)
        assertTrue(first.await(2, TimeUnit.SECONDS))
        pipeline.updateSettings(AudioDspSettings(enabled = true, targetLufs = -20f))
        pipeline.offer(pcm(2), 1_000_000)
        finish(pipeline)
        assertEquals(1, creations.get())
        assertEquals(1, updates.get())
    }
}
