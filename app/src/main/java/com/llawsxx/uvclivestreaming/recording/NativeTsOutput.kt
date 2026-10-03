package com.llawsxx.uvclivestreaming.recording

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

internal class NativeTsOutput(
    private val outputStore: RecordingOutputStore?,
    private val baseName: String,
    private val hasVideo: Boolean,
    segmentMillis: Long,
    private val httpServer: HttpTsRingBufferServer? = null,
    private val writeToFile: Boolean = true,
    private val onSegment: (Int, String) -> Unit,
) : Closeable {
    private val segmentUs = segmentMillis.coerceAtLeast(0L) * 1_000L
    private var handle: OutputHandle? = null
    private var output: OutputStream? = null
    private var segmentIndex = 0
    private var segmentStartPtsUs = Long.MIN_VALUE
    private var closed = false
    val bytesStreamed = AtomicLong(0L)
    val bytesWritten = AtomicLong(0L)
    val currentPath: String? get() = handle?.displayPath
    val currentSegment: Int get() = segmentIndex

    fun start(): String {
        check(!closed)
        if (writeToFile) openNextSegment()
        httpServer?.start()
        return handle?.displayPath ?: httpServer?.endpoint ?: "HTTP MPEG-TS"
    }

    fun write(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        check(!closed) { "TS 输出已关闭" }
        if (segmentStartPtsUs == Long.MIN_VALUE) segmentStartPtsUs = ptsUs
        val safeBoundary = keyFrame || (!hasVideo && startsWithPat(data))
        if (writeToFile && safeBoundary && segmentUs > 0 && ptsUs - segmentStartPtsUs >= segmentUs) {
            publishCurrent()
            openNextSegment()
            segmentStartPtsUs = ptsUs
        }
        if (writeToFile) {
            checkNotNull(output).write(data)
            bytesWritten.addAndGet(data.size.toLong())
        }
        httpServer?.let {
            it.write(data, ptsUs, keyFrame)
            bytesStreamed.addAndGet(data.size.toLong())
        }
    }

    private fun openNextSegment() {
        val store = checkNotNull(outputStore) { "TS 文件输出未配置" }
        segmentIndex++
        val next = store.create("${baseName}_%03d.ts".format(segmentIndex), "video/mp2t")
        try {
            val stream = next.outputStream()
            handle = next
            output = stream
            onSegment(segmentIndex, next.displayPath)
        } catch (error: Throwable) {
            next.discard()
            throw error
        }
    }

    private fun startsWithPat(data: ByteArray): Boolean = data.size >= 188 &&
        data[0] == 0x47.toByte() && (data[1].toInt() and 0x1f) == 0 && data[2] == 0.toByte()

    private fun publishCurrent() {
        if (!writeToFile) return
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null
        handle?.closeAndPublish()
        handle = null
    }

    override fun close() = close(publish = true)

    fun close(publish: Boolean) {
        if (closed) return
        closed = true
        if (publish) publishCurrent() else {
            runCatching { output?.close() }; output = null
            handle?.discard(); handle = null
        }
        httpServer?.close()
    }

    private companion object {
    }
}
