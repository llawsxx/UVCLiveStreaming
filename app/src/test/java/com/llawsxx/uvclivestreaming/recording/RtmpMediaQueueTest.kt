package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class RtmpMediaQueueTest {
    @Test fun pressureUsesMonotonicArrivalTimeAndTracksRemovedBytes() {
        val queue = RtmpMediaQueue(1024)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        queue.offer(RtmpMediaPacket(9, 1_000_000, ByteArray(30), true, 100L), generation)
        queue.offer(RtmpMediaPacket(9, 1_040_000, ByteArray(20), false, 200L), generation)
        queue.offer(RtmpMediaPacket(8, 1_020_000, ByteArray(10), false, 300L), generation)
        assertEquals(60L, queue.queuedBytes)
        assertEquals(100L, queue.oldestQueuedNs)
        assertEquals(100L, queue.poll()!!.queuedAtNs)
        assertEquals(30L, queue.queuedBytes)
        assertEquals(200L, queue.oldestQueuedNs)
        queue.disconnect()
        assertEquals(0L, queue.queuedBytes)
        assertNull(queue.oldestQueuedNs)
        assertEquals(0L, queue.droppedPackets) // Reconnect flushing is a separate failure signal.
    }

    @Test fun overflowCountsDiscardedVideoAndDependentFramesWithoutCountingStartupGating() {
        val queue = RtmpMediaQueue(5)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        queue.offer(video(0), generation)
        assertEquals(0L, queue.droppedPackets)
        queue.offer(video(0, true), generation)
        queue.offer(video(40_000), generation)
        assertEquals(2L, queue.droppedPackets)
        assertEquals(0L, queue.queuedBytes)
        assertTrue(queue.takeKeyFrameRequest())
        queue.offer(video(80_000), generation)
        assertEquals(3L, queue.droppedPackets)
        queue.offer(video(120_000, true), generation)
        assertEquals(3L, queue.queuedBytes)
        assertEquals(3L, queue.droppedPackets)
    }

    @Test fun oversizedKeyframeAloneDoesNotSignalCongestion() {
        val queue = RtmpMediaQueue(1)
        queue.beginSession()
        assertTrue(queue.offer(video(0, true), checkNotNull(queue.currentGeneration)))
        assertEquals(3L, queue.queuedBytes)
        assertEquals(0L, queue.droppedPackets)
    }
    private fun video(ptsUs: Long, keyFrame: Boolean = false) =
        RtmpMediaPacket(9, ptsUs, byteArrayOf(1, 2, 3), keyFrame)
    private fun audio(ptsUs: Long) = RtmpMediaPacket(8, ptsUs, byteArrayOf(4, 5))

    @Test fun reconnectDiscardsBacklogAndStartsFromFreshKeyframe() {
        val queue = RtmpMediaQueue(1_024)
        queue.beginSession()
        val oldGeneration = checkNotNull(queue.currentGeneration)
        assertTrue(queue.offer(video(1_000_000, true), oldGeneration))
        assertTrue(queue.offer(audio(1_020_000), oldGeneration))
        assertTrue(queue.offer(video(1_040_000), oldGeneration))

        queue.disconnect()
        assertNull(queue.currentGeneration)
        assertNull(queue.poll())
        assertFalse(queue.offer(video(5_000_000, true), oldGeneration))
        assertFalse(queue.offer(audio(5_020_000), oldGeneration))

        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        assertFalse(queue.offer(audio(10_000_000), generation))
        assertFalse(queue.offer(video(10_020_000), generation))
        assertNull(queue.poll())
        val freshKeyframe = video(10_040_000, true)
        assertTrue(queue.offer(freshKeyframe, generation))
        val packet = checkNotNull(queue.poll())
        assertSame(freshKeyframe.payload, packet.payload)
        assertTrue(packet.keyFrame)
        assertEquals(0, packet.timestampMs)
        assertNull(queue.poll())
    }

    @Test fun reconnectedAudioAndVideoShareTheNewOriginAndLateAudioIsDropped() {
        val queue = RtmpMediaQueue(1_024)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        assertTrue(queue.offer(video(60_000_000, true), generation))
        // Less than one millisecond earlier must still be rejected, before timestamp rounding.
        assertFalse(queue.offer(audio(59_999_999), generation))
        assertTrue(queue.offer(audio(60_020_000), generation))
        assertTrue(queue.offer(video(60_040_000), generation))
        val packets = generateSequence { queue.poll() }.toList()
        assertEquals(listOf(9, 8, 9), packets.map { it.type })
        assertEquals(listOf(0, 20, 40), packets.map { it.timestampMs })
    }

    @Test fun framesBeingPackagedAcrossReconnectCannotEnterTheNewSession() {
        val queue = RtmpMediaQueue(1_024)
        queue.beginSession()
        val staleGeneration = checkNotNull(queue.currentGeneration)
        val inFlightKeyframe = video(1_000_000, true)
        queue.disconnect()
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        assertFalse(queue.offer(inFlightKeyframe, staleGeneration))
        assertFalse(queue.offer(audio(1_020_000), staleGeneration))
        assertFalse(queue.offer(video(2_000_000), generation))
        assertTrue(queue.offer(video(2_040_000, true), generation))
        assertEquals(0L, checkNotNull(queue.poll()).ptsUs)
        assertTrue(queue.isEmpty)
    }

    @Test fun everyReconnectRequiresItsOwnKeyframeAndResetsTimestamps() {
        val queue = RtmpMediaQueue(1_024)
        repeat(3) { session ->
            queue.beginSession()
            val generation = checkNotNull(queue.currentGeneration)
            val ptsUs = (session + 1) * 10_000_000L
            assertFalse(queue.offer(video(ptsUs), generation))
            assertFalse(queue.offer(audio(ptsUs), generation))
            assertTrue(queue.offer(video(ptsUs, true), generation))
            assertEquals(0, checkNotNull(queue.poll()).timestampMs)
            queue.disconnect()
        }
    }

    @Test fun overflowWithoutAnotherKeyframeDropsDependentVideoAndRequestsFreshKeyframe() {
        val queue = RtmpMediaQueue(6)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        queue.offer(video(1_000_000, true), generation)
        queue.offer(video(1_040_000), generation)
        assertFalse(queue.offer(video(1_080_000), generation))
        assertTrue(queue.isEmpty)
        assertTrue(queue.takeKeyFrameRequest())
        assertFalse(queue.takeKeyFrameRequest())
        assertFalse(queue.offer(video(1_120_000), generation))
        assertTrue(queue.offer(audio(1_140_000), generation))
        assertEquals(8, checkNotNull(queue.poll()).type)
        assertTrue(queue.offer(video(1_160_000, true), generation))
        assertTrue(queue.offer(video(1_200_000), generation))
        val resumed = generateSequence { queue.poll() }.toList()
        assertTrue(resumed.first().keyFrame)
        assertEquals(listOf(160, 200), resumed.map { it.timestampMs })
    }

    @Test fun overflowRetainsNextVideoKeyframeAndEarlierAudio() {
        val queue = RtmpMediaQueue(13)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        val earlyAudio = audio(1_020_000)
        val laterAudio = audio(1_080_000)
        queue.offer(video(1_000_000, true), generation)
        queue.offer(earlyAudio, generation)
        queue.offer(video(1_040_000), generation)
        queue.offer(laterAudio, generation)
        queue.offer(video(2_000_000, true), generation)
        queue.offer(video(2_040_000), generation) // Overflow trims video through the next keyframe.
        val remaining = generateSequence { queue.poll() }.toList()
        assertEquals(listOf(8, 8, 9, 9), remaining.map { it.type })
        assertEquals(listOf(20, 80, 1_000, 1_040), remaining.map { it.timestampMs })
        assertSame(earlyAudio.payload, remaining[0].payload)
        assertSame(laterAudio.payload, remaining[1].payload)
        assertTrue(remaining[2].keyFrame)
        assertFalse(queue.takeKeyFrameRequest())
        // Audio draining can arrive later with a timestamp earlier than the retained keyframe.
        assertTrue(queue.offer(audio(1_100_000), generation))
        assertEquals(100, checkNotNull(queue.poll()).timestampMs)
    }

    @Test fun audioCanTriggerOverflowWithoutBeingTrimmedWithTheVideo() {
        val queue = RtmpMediaQueue(6)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        queue.offer(video(1_000_000, true), generation)
        queue.offer(video(1_040_000), generation)
        assertTrue(queue.offer(audio(1_020_000), generation))
        val remaining = generateSequence { queue.poll() }.toList()
        assertEquals(listOf(8), remaining.map { it.type })
        assertEquals(20, remaining.single().timestampMs)
        assertTrue(queue.takeKeyFrameRequest())
        queue.offer(audio(1_060_000), generation)
        assertFalse(queue.takeKeyFrameRequest())
    }

    @Test fun repeatedOverflowSkipsWholeVideoGopsWithoutDiscardingEarlierAudio() {
        val queue = RtmpMediaQueue(14)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        queue.offer(video(1_000_000, true), generation)
        queue.offer(audio(1_020_000), generation)
        queue.offer(video(1_040_000), generation)
        queue.offer(video(2_000_000, true), generation)
        queue.offer(video(2_040_000), generation)
        queue.offer(video(3_000_000, true), generation)
        queue.offer(video(3_040_000), generation)
        queue.offer(video(3_080_000), generation)
        val remaining = generateSequence { queue.poll() }.toList()
        assertEquals(listOf(8, 9, 9, 9), remaining.map { it.type })
        assertEquals(listOf(20, 2_000, 2_040, 2_080), remaining.map { it.timestampMs })
        assertTrue(remaining[1].keyFrame)
        assertFalse(queue.takeKeyFrameRequest())
    }

    @Test fun audioOnlyBacklogStillDiscardsOldestAudioWhenCapacityIsExceeded() {
        val queue = RtmpMediaQueue(6)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        queue.offer(video(1_000_000, true), generation)
        queue.poll()
        for (offset in listOf(20_000, 40_000, 60_000, 80_000)) {
            queue.offer(audio(1_000_000L + offset), generation)
        }
        assertEquals(listOf(40, 60, 80), generateSequence { queue.poll() }.map { it.timestampMs }.toList())
        assertFalse(queue.takeKeyFrameRequest())
    }

    @Test fun keyframeLargerThanCapacityRemainsSendableAlongsideBoundedAudio() {
        val queue = RtmpMediaQueue(2)
        queue.beginSession()
        val generation = checkNotNull(queue.currentGeneration)
        assertTrue(queue.offer(video(1_000_000, true), generation))
        queue.offer(audio(1_020_000), generation)
        queue.offer(audio(1_040_000), generation)
        val remaining = generateSequence { queue.poll() }.toList()
        assertEquals(listOf(9, 8), remaining.map { it.type })
        assertTrue(remaining[0].keyFrame)
        assertEquals(listOf(0, 40), remaining.map { it.timestampMs })
        assertFalse(queue.takeKeyFrameRequest())
    }
}
