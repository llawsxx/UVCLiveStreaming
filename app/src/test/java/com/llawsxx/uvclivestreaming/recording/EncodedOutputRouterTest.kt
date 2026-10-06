package com.llawsxx.uvclivestreaming.recording

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class EncodedOutputRouterTest {
    private open class Output : EncodedOutput<String> {
        val formats = mutableListOf<String>()
        val samples = mutableListOf<EncodedSample>()
        var closed = false
        override fun setVideoFormat(format: String) { formats += format }
        override fun setAudioFormat(format: String) { formats += format }
        override fun write(sample: EncodedSample) { check(!closed); samples += sample }
        override fun close() { closed = true }
    }
    private fun video(pts: Long, key: Boolean = false) = EncodedSample(true, byteArrayOf(1, 2, 3), pts, keyFrame = key)
    private fun audio(pts: Long) = EncodedSample(false, byteArrayOf(4, 5), pts)
    private fun router() = EncodedOutputRouter<String> { _, error -> throw AssertionError(error) }.apply {
        setVideoFormat("H264 CSD"); setAudioFormat("AAC CSD")
    }

    @Test fun allOutputsInterleaveHeadsPreserveTrackOrderAndFlushTheirTails() {
        val router = EncodedOutputRouter<String>(muxingQueueSize = 3) { _, error -> throw AssertionError(error) }
        router.setVideoFormat("H264 CSD"); router.setAudioFormat("AAC CSD")
        val outputs = CaptureOutput.entries.associateWith { Output() }
        outputs.forEach { (type, output) -> router.attach(type, output, needsAudio = true) }
        val frames = listOf(video(1_000_000, true), audio(1_200_000), video(1_040_000), audio(1_010_000))
        frames.forEach(router::write)
        outputs.values.forEach { assertEquals(listOf(0L), it.samples.map { sample -> sample.ptsUs }) }
        router.close()
        outputs.values.forEach { output ->
            assertEquals(listOf(0L, 40_000L, 200_000L, 10_000L), output.samples.map { it.ptsUs })
            assertSame(frames[0].data, output.samples[0].data)
            assertSame(frames[2].data, output.samples[1].data)
            assertSame(frames[1].data, output.samples[2].data)
            assertSame(frames[3].data, output.samples[3].data)
            assertTrue(output.closed)
        }
    }

    @Test fun recordingStopsFlushOnlyItsQueueAndRestartStartsWithAFreshKeyframe() {
        val router = EncodedOutputRouter<String>(muxingQueueSize = 3) { _, error -> throw AssertionError(error) }
        router.setVideoFormat("H264 CSD"); router.setAudioFormat("AAC CSD")
        val file = Output(); val stream = Output(); val newFile = Output()
        router.attach(CaptureOutput.RECORDING, file, needsAudio = true)
        router.attach(CaptureOutput.RTMP, stream, needsAudio = true)
        router.write(video(1_000_000, true)); router.write(audio(1_020_000)); router.write(video(1_040_000))
        assertTrue(router.detach(CaptureOutput.RECORDING))
        assertEquals(listOf(0L, 20_000L, 40_000L), file.samples.map { it.ptsUs })
        assertTrue(stream.samples.isEmpty())
        router.attach(CaptureOutput.RECORDING, newFile, needsAudio = true)
        router.write(audio(1_060_000)); router.write(video(1_080_000))
        router.write(video(2_000_000, true)); router.write(audio(2_020_000))
        router.close()
        assertEquals(listOf(0L, 20_000L), newFile.samples.map { it.ptsUs })
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L, 80_000L, 1_000_000L, 1_020_000L),
            stream.samples.map { it.ptsUs })
    }

    @Test fun negativeAudioPtsAreDiscardedInsteadOfClampedToZero() {
        val router = EncodedOutputRouter<String>(muxingQueueSize = 3) { _, error -> throw AssertionError(error) }
        router.setVideoFormat("H264 CSD"); router.setAudioFormat("AAC CSD")
        val output = Output()
        router.attach(CaptureOutput.RTMP, output, needsAudio = true)
        router.write(video(0, true)); router.write(audio(-200_000)); router.write(audio(-100_000)); router.write(audio(20_000))
        router.close()
        assertEquals(listOf(0L, 20_000L), output.samples.map { it.ptsUs })
    }

    @Test fun cacheTooSmallStillWritesLateVideoWithoutDroppingFramesOrChangingTimestamps() {
        val router = EncodedOutputRouter<String>(muxingQueueSize = 1) { _, error ->
            throw AssertionError(error)
        }
        router.setVideoFormat("H264 CSD"); router.setAudioFormat("AAC CSD")
        val output = Output(); router.attach(CaptureOutput.RTMP, output, needsAudio = true)
        router.write(video(0, true)); router.write(audio(200_000)); router.write(audio(220_000))
        router.write(video(40_000)); router.write(video(80_000))
        router.write(video(240_000, true)); router.write(audio(260_000))
        router.close()
        assertEquals(listOf(0L, 200_000L, 40_000L, 80_000L, 220_000L, 240_000L, 260_000L),
            output.samples.map { it.ptsUs })
    }

    @Test fun recordingAndStreamingConsumeTheSameEncodedAudioAndVideoBytes() {
        router().use { router ->
            val recording = Output(); val rtmp = Output()
            router.attach(CaptureOutput.RECORDING, recording, needsAudio = true)
            router.attach(CaptureOutput.RTMP, rtmp, needsAudio = true)
            val firstVideo = video(10_000, true); val firstAudio = audio(10_500)
            router.write(firstVideo); router.write(firstAudio)
            assertEquals(listOf("H264 CSD", "AAC CSD"), recording.formats)
            assertEquals(recording.formats, rtmp.formats)
            for (i in 0..1) assertSame(recording.samples[i].data, rtmp.samples[i].data)
            assertSame(firstVideo.data, recording.samples[0].data)
            assertSame(firstAudio.data, recording.samples[1].data)
            assertEquals(listOf(0L, 500L), recording.samples.map { it.ptsUs })
        }
    }

    @Test fun addingRecordingMidStreamWaitsForKeyframeAndKeepsExistingStreamTimeline() {
        router().use { router ->
            val stream = Output(); val recording = Output()
            router.attach(CaptureOutput.RTMP, stream)
            router.write(video(10_000, true))
            router.attach(CaptureOutput.RECORDING, recording, needsAudio = true)
            router.write(audio(19_000)); router.write(video(19_500))
            assertTrue(recording.samples.isEmpty())
            val key = video(20_000, true)
            router.write(key)
            router.write(audio(19_999)) // Arrived late, but predates the new file.
            router.write(audio(20_500))
            assertEquals(listOf(0L, 500L), recording.samples.map { it.ptsUs })
            assertEquals(10_000L, stream.samples.first { it.data === key.data }.ptsUs)
            assertSame(stream.samples.first { it.data === key.data }.data, recording.samples[0].data)
        }
    }

    @Test fun addingHttpAndRtmpDuringRecordingUsesCachedFormatsWithoutRestartingRecording() {
        router().use { router ->
            val recording = Output(); val http = Output(); val rtmp = Output()
            router.attach(CaptureOutput.RECORDING, recording)
            router.write(video(5_000, true))
            router.attach(CaptureOutput.HTTP, http)
            router.attach(CaptureOutput.RTMP, rtmp)
            router.write(video(10_000)); router.write(video(15_000, true)); router.write(audio(16_000))
            assertEquals(4, recording.samples.size)
            assertEquals(listOf(0L, 1_000L), rtmp.samples.map { it.ptsUs })
            assertEquals(rtmp.samples, http.samples)
            assertEquals(listOf("H264 CSD", "AAC CSD"), http.formats)
            assertFalse(recording.closed)
        }
    }

    @Test fun stoppingAndRestartingRecordingDoesNotStopTheStreams() {
        router().use { router ->
            val first = Output(); val second = Output(); val stream = Output()
            router.attach(CaptureOutput.RECORDING, first); router.attach(CaptureOutput.HTTP, stream)
            router.write(video(1_000, true))
            assertTrue(router.detach(CaptureOutput.RECORDING))
            assertTrue(first.closed); assertFalse(stream.closed)
            router.write(video(2_000))
            router.attach(CaptureOutput.RECORDING, second)
            router.write(video(3_000)); router.write(video(4_000, true))
            assertEquals(1, first.samples.size)
            assertEquals(1, second.samples.size)
            assertEquals(0L, second.samples[0].ptsUs)
            assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), stream.samples.map { it.ptsUs })
            assertTrue(router.detach(CaptureOutput.HTTP))
            assertEquals(setOf(CaptureOutput.RECORDING), router.snapshot().keys)
            assertFalse(second.closed)
        }
    }

    @Test fun failedFileWritesCloseOnlyTheFileAndLeaveOtherOutputsReceiving() {
        val failures = mutableListOf<CaptureOutput>()
        EncodedOutputRouter<String> { type, _ -> failures += type }.use { router ->
            val file = object : Output() { override fun write(sample: EncodedSample) { throw IOException("Disk full") } }
            val rtmp = Output()
            router.setVideoFormat("H265 CSD")
            router.attach(CaptureOutput.RECORDING, file); router.attach(CaptureOutput.RTMP, rtmp)
            router.write(video(1_000, true)); router.write(video(2_000))
            assertTrue(file.closed); assertFalse(rtmp.closed)
            assertEquals(listOf(CaptureOutput.RECORDING), failures)
            assertEquals(2, rtmp.samples.size)
            assertEquals(setOf(CaptureOutput.RTMP), router.snapshot().keys)
        }
    }

    @Test fun newOutputsWaitForRequiredFormatsAndThenForANewKeyframe() {
        EncodedOutputRouter<String> { _, error -> throw AssertionError(error) }.use { router ->
            val output = Output()
            router.attach(CaptureOutput.RECORDING, output, needsAudio = true)
            router.write(video(0, true))
            router.setVideoFormat("video")
            router.write(video(1_000, true))
            router.setAudioFormat("audio")
            router.write(video(2_000))
            assertTrue(output.samples.isEmpty())
            router.write(video(3_000, true)); router.write(audio(3_500))
            assertEquals(listOf(0L, 500L), output.samples.map { it.ptsUs })
        }
    }

    @Test fun detachWaitsForInFlightWriteBeforeFinalizingAnOutput() {
        router().use { router ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val detachStarted = CountDownLatch(1)
            val output = object : Output() {
                override fun write(sample: EncodedSample) {
                    entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); super.write(sample)
                }
            }
            router.attach(CaptureOutput.RECORDING, output)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val writing = executor.submit { router.write(video(1_000, true)) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val detaching = executor.submit<Boolean> { detachStarted.countDown(); router.detach(CaptureOutput.RECORDING) }
                assertTrue(detachStarted.await(2, TimeUnit.SECONDS))
                assertFalse(detaching.isDone); assertFalse(output.closed)
                release.countDown()
                writing.get(2, TimeUnit.SECONDS); assertTrue(detaching.get(2, TimeUnit.SECONDS))
                assertTrue(output.closed)
                router.write(video(2_000))
                assertEquals(1, output.samples.size)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun shutdownClosesEveryOutputAndRejectsLateAttachmentsWithoutLeakingThem() {
        val router = router(); val file = Output(); val stream = Output()
        router.attach(CaptureOutput.RECORDING, file); router.attach(CaptureOutput.RTMP, stream)
        router.close()
        assertTrue(file.closed); assertTrue(stream.closed)
        assertTrue(router.snapshot().isEmpty())
        val late = Output()
        try { router.attach(CaptureOutput.HTTP, late); fail("Closed router should reject attachment") }
        catch (_: IllegalStateException) { assertTrue(late.closed) }
    }
}
