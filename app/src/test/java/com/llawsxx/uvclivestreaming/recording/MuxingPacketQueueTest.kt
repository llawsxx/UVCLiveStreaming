package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class MuxingPacketQueueTest {
    private fun video(pts: Long, key: Boolean = false) = EncodedSample(true, byteArrayOf(1), pts, keyFrame = key)
    private fun audio(pts: Long) = EncodedSample(false, byteArrayOf(2), pts)
    private fun finish(queue: MuxingPacketQueue) = generateSequence { queue.poll(force = true) }.toList()

    @Test fun holdsTheConfiguredCombinedPacketCountAndInterleavesTrackHeads() {
        val queue = MuxingPacketQueue(4)
        queue.offer(video(0, true)); queue.offer(audio(80)); queue.offer(audio(20)); queue.offer(video(40))
        assertNull(queue.poll())
        queue.offer(video(60))
        assertEquals(0L, checkNotNull(queue.poll()).ptsUs)
        assertEquals(4, queue.size)
        assertNull(queue.poll())
        assertEquals(listOf(40L, 60L, 80L, 20L), finish(queue).map { it.ptsUs })
    }

    @Test fun delayedAudioAlreadyQueuedDoesNotPassEarlierVideoArrivingLater() {
        val queue = MuxingPacketQueue(3)
        queue.offer(video(0, true)); queue.offer(audio(200)); queue.offer(video(40))
        queue.offer(video(80))
        assertEquals(0L, checkNotNull(queue.poll()).ptsUs)
        queue.offer(audio(220))
        assertEquals(40L, checkNotNull(queue.poll()).ptsUs)
        assertEquals(listOf(80L, 200L, 220L), finish(queue).map { it.ptsUs })
    }

    @Test fun advancedAudioCanBePlacedAheadOfVideoInsideTheWindow() {
        val queue = MuxingPacketQueue(3)
        queue.offer(video(0, true)); queue.offer(video(40)); queue.offer(video(80))
        queue.offer(audio(10))
        assertEquals(0L, checkNotNull(queue.poll()).ptsUs)
        queue.offer(audio(30))
        assertEquals(10L, checkNotNull(queue.poll()).ptsUs)
        assertEquals(listOf(30L, 40L, 80L), finish(queue).map { it.ptsUs })
    }

    @Test fun lateAudioOutsideTheWindowIsStillWrittenWithItsOriginalTimestamp() {
        val queue = MuxingPacketQueue(0)
        queue.offer(video(0, true)); queue.poll()
        queue.offer(video(40)); queue.poll()
        val late = audio(10)
        queue.offer(late)
        assertSame(late, queue.poll())
        val sound = audio(60)
        queue.offer(sound)
        assertSame(sound, queue.poll())
    }

    @Test fun lateVideoAndItsDependentFramesAreRetainedOutsideTheWindow() {
        val queue = MuxingPacketQueue(0)
        queue.offer(video(0, true)); queue.poll()
        queue.offer(audio(100)); queue.poll()
        val late = video(40)
        queue.offer(late)
        assertSame(late, queue.poll())
        queue.offer(video(120))
        assertEquals(120L, checkNotNull(queue.poll()).ptsUs)
        queue.offer(audio(130))
        assertEquals(130L, checkNotNull(queue.poll()).ptsUs)
        queue.offer(video(140, true))
        assertTrue(checkNotNull(queue.poll()).keyFrame)
    }

    @Test fun equalTimestampsKeepStableArrivalOrderAndSamplesAreNotCopied() {
        val queue = MuxingPacketQueue(2)
        val frames = listOf(video(0, true), audio(0), video(10))
        frames.forEach { queue.offer(it) }
        val output = listOf(checkNotNull(queue.poll())) + finish(queue)
        output.forEachIndexed { index, sample -> assertSame(frames[index], sample) }
    }

    @Test fun bFramesRetainDecodeOrderInsteadOfBeingSortedByPresentationTime() {
        val queue = MuxingPacketQueue(8)
        val frames = listOf(video(0, true), video(80), video(40), video(120))
        frames.forEach { queue.offer(it) }
        queue.offer(audio(100))
        assertEquals(frames, finish(queue).filter { it.video })
    }

    @Test fun bothTracksKeepOriginalOrderWithRegressingPtsDuringPollingAndFlush() {
        val queue = MuxingPacketQueue(4)
        val frames = listOf(video(0, true), audio(60), video(80), audio(20), video(40), audio(100))
        val output = mutableListOf<EncodedSample>()
        frames.forEach {
            queue.offer(it)
            generateSequence { queue.poll() }.forEach(output::add)
        }
        output += finish(queue)
        // A20 cannot pass A60, and V40 cannot pass V80.
        val expected = listOf(frames[0], frames[1], frames[3], frames[2], frames[4], frames[5])
        expected.forEachIndexed { index, sample -> assertSame(sample, output[index]) }
        assertEquals(frames.filter { it.video }, output.filter { it.video })
        assertEquals(frames.filter { !it.video }, output.filter { !it.video })
        assertEquals(listOf(0L, 60L, 20L, 80L, 40L, 100L), output.map { it.ptsUs })
        assertEquals(0, queue.size)
    }

    @Test fun equalHeadTimestampsKeepArrivalOrderWhenAudioArrivesFirst() {
        val queue = MuxingPacketQueue(4)
        val frames = listOf(audio(10), video(10), audio(20), video(20))
        frames.forEach(queue::offer)
        val output = finish(queue)
        frames.forEachIndexed { index, sample -> assertSame(sample, output[index]) }
    }

    @Test fun continuousCaptureNeverRetainsMoreThanTheConfiguredWindow() {
        val queue = MuxingPacketQueue(64)
        val output = mutableListOf<EncodedSample>()
        for (pts in 0L..10_000L) {
            queue.offer(if (pts % 2 == 0L) video(pts, pts == 0L) else audio(pts))
            generateSequence { queue.poll() }.forEach(output::add)
            assertTrue(queue.size <= 64)
        }
        output += finish(queue)
        assertEquals(10_001, output.size)
        assertTrue(output.zipWithNext().all { (a, b) -> a.ptsUs <= b.ptsUs })
    }
}
