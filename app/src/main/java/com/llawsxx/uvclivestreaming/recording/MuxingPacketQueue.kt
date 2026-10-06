package com.llawsxx.uvclivestreaming.recording

import java.util.ArrayDeque

/** A bounded packet-count window for final encoder timestamps; used separately by each output. */
internal class MuxingPacketQueue(maxPackets: Int) {
    private data class Pending(val sample: EncodedSample, val sequence: Long)
    private val capacity = maxPackets.coerceIn(0, 1024)
    // Both tracks retain encoder order, including any PTS regressions.
    // Only the two heads are compared when choosing the next interleaved packet.
    private val video = ArrayDeque<Pending>()
    private val audio = ArrayDeque<Pending>()
    private var sequence = 0L
    val size: Int get() = video.size + audio.size

    fun offer(sample: EncodedSample) {
        val pending = Pending(sample, sequence++)
        if (sample.video) video.addLast(pending) else audio.addLast(pending)
    }

    fun poll(force: Boolean = false): EncodedSample? {
        if (!force && size <= capacity) return null
        val videoHead = video.peekFirst()
        val audioHead = audio.peekFirst()
        val next = when {
            videoHead == null -> audio.pollFirst()
            audioHead == null -> video.pollFirst()
            videoHead.sample.ptsUs < audioHead.sample.ptsUs ||
                (videoHead.sample.ptsUs == audioHead.sample.ptsUs && videoHead.sequence < audioHead.sequence) -> video.pollFirst()
            else -> audio.pollFirst()
        } ?: return null
        return next.sample
    }
}
