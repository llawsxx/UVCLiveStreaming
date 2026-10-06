package com.llawsxx.uvclivestreaming.recording

import java.util.ArrayDeque

internal data class RtmpMediaPacket(
    val type: Int,
    val ptsUs: Long,
    val payload: ByteArray,
    val keyFrame: Boolean = false,
) {
    val timestampMs: Int get() = (ptsUs / 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** Access is serialized by RtmpStreamSink's lock. Each connection starts with fresh media. */
internal class RtmpMediaQueue(private val maxBytes: Long) {
    private val packets = ArrayDeque<RtmpMediaPacket>()
    private var queuedBytes = 0L
    private var generation = 0L
    private var connected = false
    private var originUs: Long? = null
    private var waitingForVideoKeyFrame = false
    private var keyFrameRequestPending = false

    // A producer retains this token while packaging a frame, so a reconnect can invalidate it.
    val currentGeneration: Long? get() = if (connected) generation else null
    val isEmpty: Boolean get() = packets.isEmpty()

    fun beginSession() {
        disconnect()
        connected = true
    }

    fun disconnect() {
        generation++
        connected = false
        packets.clear()
        queuedBytes = 0L
        originUs = null
        waitingForVideoKeyFrame = false
        keyFrameRequestPending = false
    }

    fun offer(packet: RtmpMediaPacket, producerGeneration: Long): Boolean {
        if (!connected || producerGeneration != generation) return false
        if (originUs == null) {
            if (packet.type != 9 || !packet.keyFrame) return false
            originUs = packet.ptsUs
        }
        val ptsUs = packet.ptsUs - checkNotNull(originUs)
        // Late audio can arrive after the keyframe but describe samples from before it.
        if (ptsUs < 0L) return false
        if (packet.type == 9 && waitingForVideoKeyFrame) {
            if (!packet.keyFrame) return false
            waitingForVideoKeyFrame = false
        }
        val queuedPacket = packet.copy(ptsUs = ptsUs)
        packets.addLast(queuedPacket)
        queuedBytes += packet.payload.size
        trimOverflow()
        return packets.peekLast() === queuedPacket
    }

    fun takeKeyFrameRequest(): Boolean = keyFrameRequestPending.also { keyFrameRequestPending = false }

    private fun trimOverflow() {
        while (queuedBytes > maxBytes) {
            val firstVideo = packets.firstOrNull { it.type == 9 }
            if (firstVideo == null) {
                // Audio has no video prediction dependencies; bound its backlog independently.
                queuedBytes -= packets.removeFirst().payload.size
                continue
            }
            if (firstVideo.keyFrame && firstVideo.payload.size > maxBytes &&
                packets.none { it.type == 9 && it !== firstVideo }) {
                // One large keyframe must be sendable even with a very small configured buffer.
                while (queuedBytes - firstVideo.payload.size > maxBytes) {
                    val audio = packets.iterator()
                    while (audio.hasNext()) {
                        val next = audio.next()
                        if (next.type == 8) {
                            queuedBytes -= next.payload.size
                            audio.remove()
                            break
                        }
                    }
                }
                return
            }
            val iterator = packets.iterator()
            var removedVideo = false
            var foundKeyFrame = false
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (next.type != 9) continue // Keep audio, including audio before the retained keyframe.
                if (removedVideo && next.keyFrame) {
                    foundKeyFrame = true
                    break
                }
                queuedBytes -= next.payload.size
                iterator.remove()
                removedVideo = true
            }
            if (!foundKeyFrame) {
                waitingForVideoKeyFrame = true
                keyFrameRequestPending = true
            }
        }
    }

    fun poll(): RtmpMediaPacket? = packets.pollFirst()?.also { queuedBytes -= it.payload.size }
}
