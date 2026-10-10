package com.llawsxx.uvclivestreaming.recording

import java.io.Closeable

internal enum class CaptureOutput { RECORDING, HTTP, RTMP }

/** Owned encoder bytes. Every output consumes this same encoded payload without re-encoding. */
internal data class EncodedSample(
    val video: Boolean,
    val data: ByteArray,
    val ptsUs: Long,
    val flags: Int = 0,
    val keyFrame: Boolean = false,
)

internal interface EncodedOutput<F> : Closeable {
    val path: String? get() = null
    val segment: Int get() = 0
    val bytesStreamed: Long get() = 0L
    val reconnectCount: Long get() = 0L
    val httpUploadStats: HttpUploadStats? get() = null
    val rtmpUploadStats: RtmpUploadStats? get() = null
    fun setVideoFormat(format: F)
    fun setAudioFormat(format: F)
    fun write(sample: EncodedSample)
}

internal data class EncodedOutputInfo(val path: String?, val segment: Int, val bytesStreamed: Long,
                                      val reconnectCount: Long = 0L, val httpUploadStats: HttpUploadStats? = null,
                                      val rtmpUploadStats: RtmpUploadStats? = null)

/** Serializes output changes with both encoder drain threads, without owning capture or codecs. */
internal class EncodedOutputRouter<F>(
    private val muxingQueueSize: Int = 0,
    private val onFailure: (CaptureOutput, Exception) -> Unit,
) : Closeable {
    private class Entry<F>(val output: EncodedOutput<F>, val needsVideo: Boolean, val needsAudio: Boolean,
                           val queue: MuxingPacketQueue) {
        var originUs: Long? = null
    }
    private val lock = Any()
    private val entries = linkedMapOf<CaptureOutput, Entry<F>>()
    private var videoFormat: F? = null
    private var audioFormat: F? = null
    private var closed = false

    fun attach(type: CaptureOutput, output: EncodedOutput<F>, needsVideo: Boolean = true,
               needsAudio: Boolean = false) = synchronized(lock) {
        try {
            check(!closed)
            check(type !in entries) { "$type output already exists" }
            videoFormat?.let(output::setVideoFormat)
            audioFormat?.let(output::setAudioFormat)
            entries[type] = Entry(output, needsVideo, needsAudio,
                MuxingPacketQueue(muxingQueueSize))
        } catch (error: Exception) {
            runCatching { output.close() }
            throw error
        }
    }

    fun detach(type: CaptureOutput): Boolean {
        val entry = synchronized(lock) {
            val removed = entries.remove(type) ?: return false
            try {
                if (type == CaptureOutput.RECORDING) drain(removed, force = true)
            } catch (error: Exception) {
                runCatching { removed.output.close() }
                throw error
            }
            removed
        }
        // Removal waits for an in-flight write; later writes cannot reach the removed sink.
        entry.output.close()
        return true
    }

    fun setVideoFormat(format: F) = configure(true, format)
    fun setAudioFormat(format: F) = configure(false, format)

    private fun configure(video: Boolean, format: F) {
        visit(before = { if (video) videoFormat = format else audioFormat = format }) { entry ->
            if (video) entry.output.setVideoFormat(format) else entry.output.setAudioFormat(format)
        }
    }

    fun write(sample: EncodedSample) {
        visit { entry ->
            if ((entry.needsVideo && videoFormat == null) || (entry.needsAudio && audioFormat == null)) return@visit
            if (entry.originUs == null) {
                if (entry.needsVideo && (!sample.video || !sample.keyFrame)) return@visit
                entry.originUs = sample.ptsUs
            }
            val pts = sample.ptsUs - checkNotNull(entry.originUs)
            // Keep negative PCM/AAC timestamps intact until the output's common video origin is known.
            // Clamping them to zero would turn an audio advance into repeated zero timestamps.
            if (pts < 0) return@visit
            entry.queue.offer(sample.copy(ptsUs = pts))
            drain(entry)
        }
    }

    private fun drain(entry: Entry<F>, force: Boolean = false) {
        while (true) entry.output.write(entry.queue.poll(force) ?: break)
    }

    private fun visit(before: () -> Unit = {}, action: (Entry<F>) -> Unit) {
        val failures = mutableListOf<Pair<CaptureOutput, Exception>>()
        val failedOutputs = mutableListOf<EncodedOutput<F>>()
        synchronized(lock) {
            if (closed) return
            before()
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val (type, entry) = iterator.next()
                try { action(entry) } catch (error: Exception) {
                    iterator.remove()
                    failures += type to error
                    failedOutputs += entry.output
                }
            }
        }
        failedOutputs.forEach { runCatching { it.close() } }
        failures.forEach { (type, error) -> onFailure(type, error) }
    }

    fun snapshot(): Map<CaptureOutput, EncodedOutputInfo> = synchronized(lock) {
        entries.mapValues { (_, entry) ->
            EncodedOutputInfo(entry.output.path, entry.output.segment, entry.output.bytesStreamed,
                entry.output.reconnectCount, entry.output.httpUploadStats, entry.output.rtmpUploadStats)
        }
    }

    override fun close() {
        val outputs = synchronized(lock) {
            if (closed) return
            val httpEntry = entries[CaptureOutput.HTTP]
            visit { if (it !== httpEntry) drain(it, force = true) }
            closed = true
            entries.values.map { it.output }.also { entries.clear() }
        }
        outputs.forEach { runCatching { it.close() } }
    }
}
